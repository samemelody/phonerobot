package com.phonerobot.app.robot

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

/**
 * BLE serial channel for communicating with MLT-BT05 (or similar BLE UART modules).
 *
 * MLT-BT05 default UUIDs:
 *   Service: 0000FFE0-0000-1000-8000-00805F9B34FB
 *   Characteristic: 0000FFE1-0000-1000-8000-00805F9B34FB
 *   (READ | WRITE | WRITE_NO_RESPONSE | NOTIFY)
 *
 * Usage:
 *   val channel = BleRobotChannel(context)
 *   channel.startScan { name, address -> ... }   // show in UI
 *   channel.connect(address)                      // connect to selected device
 *   channel.send(RobotCommand.RawData(bytes))     // send data
 *   channel.disconnect()                          // clean up
 */
class BleRobotChannel(private val context: Context) : RobotChannel {

    companion object {
        private const val TAG = "BleRobotChannel"

        // MLT-BT05 / HM-10 / HC-08 common UUIDs
        val SERVICE_UUID: UUID       = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID          = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Device name filter (auto-connect candidate)
        val AUTO_CONNECT_NAMES = setOf("MLT-BT05", "BT05", "HMSoft", "HC-08", "JDY-")
    }

    // ── BLE stack ───────────────────────────────────────────────

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // Connection state
    @Volatile private var _connected = false
    private var reconnectAddress: String? = null
    private var mtu = 23  // BLE default, upgraded during connection

    // Scanning
    private val bluetoothLeScanner get() = bluetoothAdapter?.bluetoothLeScanner
    private var isScanning = false

    // ── Callbacks ───────────────────────────────────────────────

    /** Scan result: (deviceName, macAddress) */
    var onScanResult: ((String, String) -> Unit)? = null

    /** Scan finished */
    var onScanFinished: (() -> Unit)? = null

    /** Incoming data from MCU (via BLE notification) */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    /** Device connected successfully */
    var onConnected: ((String) -> Unit)? = null

    /** Device disconnected */
    var onDisconnected: (() -> Unit)? = null

    /** Connection failed */
    var onConnectFailed: ((String) -> Unit)? = null

    // ── RobotChannel interface ──────────────────────────────────

    override fun isConnected(): Boolean = _connected && bluetoothGatt != null

    /**
     * Send binary data to the MCU via BLE write.
     * Handles chunking for payloads larger than MTU-3.
     */
    override suspend fun send(command: RobotCommand): Boolean {
        return withContext(Dispatchers.IO) {
            val bytes: ByteArray = when (command) {
                is RobotCommand.RawData -> command.data
                else -> (command.raw + "\n").toByteArray(Charsets.UTF_8)
            }

            val gatt = bluetoothGatt
            val char = writeCharacteristic
            if (gatt == null || char == null) {
                Log.w(TAG, "Cannot send — not connected")
                return@withContext false
            }

            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, ">>> BLE SEND [${bytes.size}B]: $hex")

            try {
                // Request MTU upgrade if needed (for packets > 20 bytes)
                val maxChunk = mtu - 3
                if (bytes.size > maxChunk && maxChunk < 512) {
                    requestMtu(minOf(bytes.size + 3, 512))
                }

                // Chunked write for large payloads
                val effectiveMax = mtu - 3
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + effectiveMax, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)

                    char.value = chunk
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                    val success = gatt.writeCharacteristic(char)
                    if (!success) {
                        Log.e(TAG, "BLE write failed at offset $offset")
                        return@withContext false
                    }

                    // Small delay between chunks (BLE is async)
                    if (offset + effectiveMax < bytes.size) {
                        delay(20L)
                    }
                    offset = end
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "BLE write error: ${e.message}", e)
                false
            }
        }
    }

    // ── Scanning ────────────────────────────────────────────────

    /**
     * Start scanning for BLE serial modules.
     * Results are delivered via onScanResult callback.
     *
     * @param durationMs Scan duration (default 10 seconds)
     */
    fun startScan(durationMs: Long = 10_000L) {
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available (adapter off or no BLE support)")
            onScanFinished?.invoke()
            return
        }

        if (isScanning) {
            stopScan()
        }

        isScanning = true
        Log.i(TAG, "Starting BLE scan (${durationMs}ms)")

        // Filter for BLE serial modules by service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
            isScanning = false
            onScanFinished?.invoke()
            return
        }

        // Auto-stop after duration
        CoroutineScope(Dispatchers.IO).launch {
            delay(durationMs)
            if (isScanning) {
                stopScan()
            }
        }
    }

    /**
     * Start scanning without UUID filter (catches devices that don't advertise service UUID).
     * Automatically stops and connects when a device matching AUTO_CONNECT_NAMES is found.
     *
     * @param durationMs Scan timeout (default 10 seconds). Scan may end sooner if target found.
     * @param autoConnectOnMatch If true, auto-connect to first matching device from AUTO_CONNECT_NAMES.
     */
    fun startScanUnfiltered(durationMs: Long = 10_000L, autoConnectOnMatch: Boolean = true) {
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Scanner not available — adapter=${bluetoothAdapter}, enabled=${bluetoothAdapter?.isEnabled}")
            onScanFinished?.invoke()
            return
        }

        if (isScanning) stopScan()
        isScanning = true
        Log.i(TAG, "Starting unfiltered BLE scan (${durationMs}ms, autoConnect=$autoConnectOnMatch)")

        // Use SCAN_MODE_LOW_LATENCY for best results; fall back to legacy on some OEMs
        val settings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()
        } else {
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        }

        _autoConnectOnMatch = autoConnectOnMatch

        try {
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            Log.i(TAG, "BLE scan started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
            isScanning = false
            onScanFinished?.invoke()
            return
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan start failed: ${e.message}", e)
            isScanning = false
            onScanFinished?.invoke()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(durationMs)
            if (isScanning) stopScan()
        }
    }

    /** Whether to auto-connect when a known device name is found during scan */
    private var _autoConnectOnMatch: Boolean = true

    fun stopScan() {
        if (!isScanning) return
        isScanning = false

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Error stopping scan", e)
        }

        Log.i(TAG, "BLE scan stopped")
        onScanFinished?.invoke()
    }

    fun isScanning(): Boolean = isScanning

    // ── Connection ──────────────────────────────────────────────

    /**
     * Connect to a BLE device by MAC address.
     * @param address MAC address (from scan result)
     * @param autoReconnect Whether to auto-reconnect on disconnect
     */
    fun connect(address: String, autoReconnect: Boolean = true) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth not available")
            onConnectFailed?.invoke("Bluetooth not available")
            return
        }

        // Disconnect any existing connection first
        disconnect()

        reconnectAddress = if (autoReconnect) address else null
        Log.i(TAG, "Connecting to BLE device: $address")

        val device: BluetoothDevice
        try {
            device = adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid address: $address", e)
            onConnectFailed?.invoke("Invalid address: $address")
            return
        }

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE connect permission denied", e)
            onConnectFailed?.invoke("Permission denied")
        }
    }

    /**
     * Connect to the first device matching a name prefix from AUTO_CONNECT_NAMES.
     */
    fun connectFirstAvailable(durationMs: Long = 10_000L) {
        Log.i(TAG, "Auto-connect: scanning for known devices...")

        onScanResult = { name, address ->
            if (this::class.members.any { it.name == "reconnectAddress" }) {
                // Check if any auto-connect name matches
                val matches = AUTO_CONNECT_NAMES.any { name.contains(it, ignoreCase = true) }
                if (matches) {
                    Log.i(TAG, "Auto-connect match: $name ($address)")
                    stopScan()
                    connect(address)
                }
            }
        }

        startScanUnfiltered(durationMs)
    }

    fun disconnect() {
        reconnectAddress = null
        _connected = false

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT: ${e.message}")
        }

        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        mtu = 23
        Log.i(TAG, "Disconnected")
    }

    // ── MTU negotiation ─────────────────────────────────────────

    private suspend fun requestMtu(targetMtu: Int): Int = withTimeoutOrNull(5_000L) {
        suspendCancellableCoroutine { cont ->
            val gatt = bluetoothGatt
            if (gatt == null) {
                cont.resumeWith(Result.success(mtu))
                return@suspendCancellableCoroutine
            }

            val originalCallback = gattCallback
            val tempCallback = object : BluetoothGattCallback() {
                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    gattCallback = originalCallback
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        this@BleRobotChannel.mtu = mtu
                        Log.i(TAG, "MTU negotiated: $mtu")
                    }
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(this@BleRobotChannel.mtu))
                    }
                }
            }

            cont.invokeOnCancellation {
                gattCallback = originalCallback
            }

            try {
                gattCallback = tempCallback
                gatt.requestMtu(targetMtu)
            } catch (e: SecurityException) {
                if (cont.isActive) {
                    cont.resumeWith(Result.success(mtu))
                }
            }
        }
    } ?: mtu

    // ── Helper ──────────────────────────────────────────────────

    /**
     * Check if Bluetooth is enabled on the device.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Get the Bluetooth adapter for enabling (handled by system intent).
     * Note: the `bluetoothAdapter` property already exposes this — use isBluetoothEnabled() instead.
     */

    // ── Internal callbacks ──────────────────────────────────────

    /**
     * Public setter for GATT callback (needed for MTU hacks).
     * Default is the primary gattCallback.
     */
    private var gattCallback: BluetoothGattCallback = createGattCallback()

    private fun createGattCallback() = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection state change error: status=$status")
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services...")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "discoverServices permission denied", e)
                        onConnectFailed?.invoke("Permission denied")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    _connected = false
                    writeCharacteristic = null
                    notifyCharacteristic = null

                    onDisconnected?.invoke()

                    // Auto-reconnect
                    val addr = reconnectAddress
                    if (addr != null && status != BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Auto-reconnecting to $addr in 1s...")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(1_000L)
                            connect(addr)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                onConnectFailed?.invoke("Service discovery failed")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service $SERVICE_UUID not found")
                onConnectFailed?.invoke("BLE serial service not found")
                return
            }

            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic $CHARACTERISTIC_UUID not found")
                onConnectFailed?.invoke("BLE serial characteristic not found")
                return
            }

            // Check supported properties
            val props = characteristic.properties
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                           (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0

            Log.i(TAG, "Characteristic found — write=$canWrite, notify=$canNotify, props=0x${"%02X".format(props)}")

            // Enable notifications
            if (canNotify) {
                try {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.i(TAG, "Notifications enabled")
                    } else {
                        Log.w(TAG, "CCCD descriptor not found — notifications may not work")
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Enable notification permission denied", e)
                }
            }

            writeCharacteristic = characteristic
            notifyCharacteristic = characteristic
            _connected = true

            val deviceName = gatt.device.name ?: gatt.device.address
            Log.i(TAG, "BLE connected to: $deviceName")
            onConnected?.invoke(deviceName)

            // Request larger MTU for protocol frames (up to 10 bytes per frame)
            try {
                gatt.requestMtu(64)
            } catch (e: SecurityException) {
                Log.w(TAG, "MTU request permission denied", e)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "<<< BLE RECV [${data.size}B]: $hex")
            onDataReceived?.invoke(data)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleRobotChannel.mtu = mtu
                Log.i(TAG, "MTU set to $mtu (effective data: ${mtu - 3}B)")
            } else {
                Log.w(TAG, "MTU change failed: status=$status")
            }
        }
    }

    // ── Scan callback ───────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "(no name)"
            val address = device.address
            val rssi = result.rssi

            // Use Log.i (not Log.d) to ensure visibility in all logcat filters
            Log.i(TAG, "███ BLE DEVICE FOUND: name=\"$name\" addr=$address rssi=$rssi callbackType=$callbackType")

            // Auto-connect if device name matches known modules
            if (_autoConnectOnMatch && isScanning) {
                val matches = AUTO_CONNECT_NAMES.any { name.contains(it, ignoreCase = true) }
                if (matches) {
                    Log.i(TAG, "Auto-connect match: \"$name\" ($address) — stopping scan and connecting")
                    CoroutineScope(Dispatchers.IO).launch {
                        stopScan()
                        connect(address)
                    }
                    return
                }
            }

            onScanResult?.invoke(name, address)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.i(TAG, "███ BLE BATCH RESULTS: ${results.size} devices")
            for (r in results) {
                val d = r.device
                Log.i(TAG, "   name=\"${d.name ?: "(no name)"}\" addr=${d.address} rssi=${r.rssi}")
                onScanResult?.invoke(d.name ?: "(no name)", d.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "███ BLE SCAN FAILED! errorCode=$errorCode")
            isScanning = false
            onScanFinished?.invoke()
        }
    }
}
