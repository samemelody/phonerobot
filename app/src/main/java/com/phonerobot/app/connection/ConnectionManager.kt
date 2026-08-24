package com.phonerobot.app.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.phonerobot.app.robot.BleRobotChannel
import com.phonerobot.app.robot.McuTelemetry
import com.phonerobot.app.robot.RobotChannel
import com.phonerobot.app.robot.RobotCommand
import com.phonerobot.app.robot.ToyCarProtocol
import com.phonerobot.app.robot.UsbRobotChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns both robot channels (USB + BLE), the heartbeat loop and MCU telemetry parsing.
 * Created inside PhoneRobotViewModel, so connections survive Activity recreation.
 */
class ConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val postEvent: (String) -> Unit,
) {

    companion object {
        private const val TAG = "ConnectionManager"
        private const val HEARTBEAT_INTERVAL_MS = 500L
    }

    private val appContext = context.applicationContext

    val usbChannel = UsbRobotChannel(appContext)
    val bleChannel = BleRobotChannel(appContext)

    /** Channel currently used for sending (prefer BLE) */
    val activeChannel: RobotChannel
        get() = if (bleChannel.isConnected()) bleChannel else usbChannel

    // ── Observable connection state ──────────────────────────────

    private val _telemetry = MutableStateFlow(McuTelemetry())
    val telemetry: StateFlow<McuTelemetry> = _telemetry.asStateFlow()

    private val _usbStatus = MutableStateFlow("Disconnected")
    val usbStatus: StateFlow<String> = _usbStatus.asStateFlow()

    private val _bleStatus = MutableStateFlow("Disconnected")
    val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

    private val _bleScanResults = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val bleScanResults: StateFlow<List<Pair<String, String>>> = _bleScanResults.asStateFlow()

    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Int = 0

    init {
        usbChannel.registerUsbReceiver()

        usbChannel.onPermissionResult = { device, granted ->
            if (granted) {
                val connected = usbChannel.connect(device)
                _usbStatus.value = if (connected) "Connected" else "Connect failed"
                if (connected) {
                    postEvent("USB device connected: ${device.deviceName}")
                    markConnected()
                    startHeartbeat()
                }
            } else {
                _usbStatus.value = "Permission denied"
            }
        }
        usbChannel.onDisconnected = {
            _usbStatus.value = "Disconnected"
            postEvent("USB disconnected")
            markDisconnected()
            stopHeartbeat()
        }

        bleChannel.onScanResult = { name, address ->
            Log.i(TAG, "scan result: name=$name addr=$address")
            _bleScanResults.update { current ->
                if (current.any { it.second == address }) current else current + Pair(name, address)
            }
        }
        bleChannel.onScanFinished = {
            _bleStatus.value = if (_bleScanResults.value.isEmpty()) "Disconnected"
            else "${_bleScanResults.value.size} device(s) found"
        }
        bleChannel.onConnected = { name ->
            _bleStatus.value = "Connected"
            postEvent("BLE connected: $name")
            markConnected()
            startHeartbeat()
        }
        bleChannel.onConnectFailed = { reason ->
            _bleStatus.value = "Failed"
            postEvent("BLE connection failed: $reason")
        }
        bleChannel.onDisconnected = {
            _bleStatus.value = "Disconnected"
            postEvent("BLE disconnected")
            markDisconnected()
            stopHeartbeat()
        }

        val onMcuData: (ByteArray) -> Unit = { data -> handleMcuData(data) }
        usbChannel.onDataReceived = onMcuData
        bleChannel.onDataReceived = onMcuData
    }

    // ── Operations ───────────────────────────────────────────────

    /** Auto-connect USB if already plugged in. Returns true if connected. */
    fun tryConnectFirstAvailableUsb(): Boolean {
        val connected = usbChannel.connectFirstAvailable()
        _usbStatus.value = if (connected) "Connected" else "Disconnected"
        if (connected) {
            markConnected()
            startHeartbeat()
        }
        return connected
    }

    fun connectKnownUsbDevice() {
        val drivers = usbChannel.listAvailableDevices()
        if (drivers.isEmpty()) {
            postEvent("No USB serial device found.")
            return
        }
        val driver = drivers[0]
        val device = driver.device
        if (usbChannel.hasPermission(device)) {
            val connected = usbChannel.connect(driver)
            _usbStatus.value = if (connected) "Connected" else "Connect failed"
            if (connected) {
                postEvent("USB connected: ${device.deviceName}")
                markConnected()
                startHeartbeat()
            }
        } else {
            _usbStatus.value = "Requesting permission..."
            usbChannel.requestPermission(device)
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        usbChannel.requestPermission(device)
    }

    fun isUsbConnected(): Boolean = usbChannel.isConnected()

    /** Launch a BLE scan. Permission / bluetooth / location checks must be done by the caller. */
    fun startBleScan() {
        _bleStatus.value = "Scanning..."
        _bleScanResults.value = emptyList()
        postEvent("Scanning for BLE devices...")
        bleChannel.startScanUnfiltered(10_000L, autoConnectOnMatch = true)
    }

    fun connectBle(address: String) {
        bleChannel.stopScan()
        _bleStatus.value = "Connecting..."
        postEvent("Connecting to BLE device...")
        bleChannel.connect(address)
    }

    fun disconnectBle() {
        stopHeartbeat()
        bleChannel.disconnect()
        _bleStatus.value = "Disconnected"
        _bleScanResults.value = emptyList()
        postEvent("BLE disconnected")
        markDisconnected()
    }

    fun shutdown() {
        stopHeartbeat()
        bleChannel.stopScan()
        bleChannel.disconnect()
        usbChannel.disconnect()
        usbChannel.unregisterUsbReceiver()
    }

    // ── Heartbeat ────────────────────────────────────────────────

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatSeq = 0
        heartbeatJob = scope.launch {
            Log.i(TAG, "Heartbeat started (${HEARTBEAT_INTERVAL_MS}ms interval)")
            while (isActive && activeChannel.isConnected()) {
                activeChannel.send(RobotCommand.RawData(ToyCarProtocol.buildHeartbeat(heartbeatSeq++)))
                delay(HEARTBEAT_INTERVAL_MS)
            }
            Log.i(TAG, "Heartbeat stopped")
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Telemetry parsing (shared by USB and BLE) ────────────────

    private fun handleMcuData(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.i(TAG, "<<< MCU RAW [${data.size}B]: $hex")

        val parsed = ToyCarProtocol.parseNotification(data)
        if (!parsed.valid) {
            Log.w(TAG, "<<< MCU PARSE FAIL: ${parsed.error}")
            return
        }

        Log.i(TAG, "<<< MCU PARSED: ${parsed.summary}")

        when (parsed.cmdName) {
            "STATUS" -> {
                _telemetry.update {
                    it.copy(
                        connected = true,
                        batteryPct = parsed.data["battery"] as? Int ?: it.batteryPct,
                        moving = parsed.data["moving"] as? Boolean ?: false,
                        fault = parsed.data["fault"] as? Boolean ?: false,
                        errorName = parsed.data["errorName"] as? String,
                        lastFrameSummary = "[${parsed.cmdName}] ${parsed.summary}",
                        lastUpdateMs = System.currentTimeMillis(),
                    )
                }
                val errorName = parsed.data["errorName"] as? String ?: ""
                if (errorName.isNotEmpty() && errorName != "Normal") {
                    postEvent("⚠ MCU fault: $errorName — ${parsed.summary}")
                }
            }
            "HB_ACK" -> {
                _telemetry.update {
                    it.copy(
                        connected = true,
                        batteryPct = parsed.data["battery"] as? Int ?: it.batteryPct,
                        moving = parsed.data["moving"] as? Boolean ?: it.moving,
                        fault = parsed.data["fault"] as? Boolean ?: it.fault,
                        lastFrameSummary = "[${parsed.cmdName}] ${parsed.summary}",
                        lastUpdateMs = System.currentTimeMillis(),
                    )
                }
            }
            "CMD_DONE" -> {
                val resultName = parsed.data["resultName"] as? String ?: ""
                if (resultName != "Success") {
                    postEvent("⚠ CMD failed: $resultName")
                }
                _telemetry.update {
                    it.copy(lastFrameSummary = "[${parsed.cmdName}] ${parsed.summary}", lastUpdateMs = System.currentTimeMillis())
                }
            }
        }
    }

    private fun markConnected() {
        _telemetry.update { it.copy(connected = true) }
    }

    private fun markDisconnected() {
        _telemetry.value = McuTelemetry(connected = false)
    }
}
