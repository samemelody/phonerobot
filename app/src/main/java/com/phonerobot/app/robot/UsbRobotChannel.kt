package com.phonerobot.app.robot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * USB OTG serial channel for communicating with MCU (e.g. STM32F103).
 *
 * Uses usb-serial-for-android library which supports CDC-ACM, CH340, CP210x,
 * FTDI, and other common USB-serial chips.
 *
 * Usage:
 *   val channel = UsbRobotChannel(context)
 *   channel.connect(device)   // or channel.connectFirstAvailable()
 *   // ... channel.send(RawData(bytes)) ...
 *   channel.disconnect()
 */
class UsbRobotChannel(private val context: Context) : RobotChannel {

    companion object {
        private const val TAG = "UsbRobotChannel"
        private const val ACTION_USB_PERMISSION = "com.phonerobot.app.USB_PERMISSION"
        private const val DEFAULT_BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 100
    }

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private var serialPort: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    private var _connected = false

    /** Callback for incoming data from the MCU */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    /** Callback for USB permission result */
    var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    /** Callback for unexpected disconnection */
    var onDisconnected: (() -> Unit)? = null

    override fun isConnected(): Boolean = _connected && serialPort?.isOpen == true

    /**
     * List all available USB serial devices.
     * @return List of UsbSerialDriver instances
     */
    fun listAvailableDevices(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    /**
     * Check if we have permission to access a USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request USB permission from the user.
     * Result is delivered via onPermissionResult callback.
     */
    fun requestPermission(device: UsbDevice) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Connect to a specific USB serial driver.
     * @param driver The USB serial driver to connect to
     * @param baudRate Serial baud rate (default 115200)
     * @return true if connected successfully
     */
    fun connect(driver: UsbSerialDriver, baudRate: Int = DEFAULT_BAUD_RATE): Boolean {
        return try {
            val device = driver.device
            if (!usbManager.hasPermission(device)) {
                Log.w(TAG, "No USB permission for device: ${device.deviceName}")
                return false
            }

            // Open connection
            val conn = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Failed to open USB device: ${device.deviceName}")
                return false
            }
            connection = conn

            // Open port (use first port)
            val port = driver.ports[0]
            port.open(conn)
            port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true  // Required for some MCU boards
            port.rts = true
            serialPort = port
            _connected = true

            // Start reading incoming data
            startIoManager()

            Log.i(TAG, "Connected to ${device.deviceName} at $baudRate baud")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            disconnect()
            false
        }
    }

    /**
     * Connect to the first available USB serial device.
     * @param baudRate Serial baud rate (default 115200)
     * @return true if connected successfully
     */
    fun connectFirstAvailable(baudRate: Int = DEFAULT_BAUD_RATE): Boolean {
        val drivers = listAvailableDevices()
        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            return false
        }
        val driver = drivers[0]
        Log.i(TAG, "Found USB device: ${driver.device.deviceName} (${driver.javaClass.simpleName})")
        return connect(driver, baudRate)
    }

    /**
     * Connect to a specific UsbDevice (found via USB_DEVICE_ATTACHED intent).
     * @param device The USB device from the intent
     * @param baudRate Serial baud rate (default 115200)
     * @return true if connected successfully
     */
    fun connect(device: UsbDevice, baudRate: Int = DEFAULT_BAUD_RATE): Boolean {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            Log.e(TAG, "No serial driver found for device: ${device.deviceName}")
            return false
        }
        return connect(driver, baudRate)
    }

    /**
     * Disconnect from the USB device and release resources.
     */
    fun disconnect() {
        try {
            ioManager?.stop()
            ioManager = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping IO manager: ${e.message}")
        }

        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serial port: ${e.message}")
        }
        serialPort = null

        try {
            connection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB connection: ${e.message}")
        }
        connection = null

        _connected = false
        Log.i(TAG, "Disconnected")
    }

    /**
     * Send a command to the MCU via USB serial.
     * For RawData commands, sends the raw bytes directly.
     * For other commands, sends the text representation + newline.
     */
    override suspend fun send(command: RobotCommand): Boolean {
        return withContext(Dispatchers.IO) {
            val port = serialPort
            if (port == null || !port.isOpen) {
                Log.w(TAG, "Cannot send — not connected")
                return@withContext false
            }

            val bytes: ByteArray = when (command) {
                is RobotCommand.RawData -> command.data
                else -> (command.raw + "\n").toByteArray(Charsets.UTF_8)
            }

            try {
                port.write(bytes, WRITE_TIMEOUT_MS)
                val hex = bytes.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, ">>> USB SENT [${bytes.size}B]: $hex")
                true
            } catch (e: IOException) {
                Log.e(TAG, "USB write failed: ${e.message}", e)
                _connected = false
                onDisconnected?.invoke()
                false
            }
        }
    }

    /**
     * Register a BroadcastReceiver for USB permission results.
     * Call this in Activity.onResume() or onCreate().
     */
    fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.registerReceiver(
                usbReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * Unregister the USB BroadcastReceiver.
     * Call this in Activity.onPause() or onDestroy().
     */
    fun unregisterUsbReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering USB receiver: ${e.message}")
        }
    }

    private fun startIoManager() {
        val port = serialPort ?: return
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val hex = data.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "<<< USB RECV [${data.size}B]: $hex")
                onDataReceived?.invoke(data)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "USB read error: ${e.message}", e)
                _connected = false
                onDisconnected?.invoke()
            }
        })
        ioManager!!.start()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        Log.i(TAG, "USB permission for ${device.deviceName}: $granted")
                        onPermissionResult?.invoke(device, granted)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB device detached: ${device?.deviceName}")
                    disconnect()
                    onDisconnected?.invoke()
                }
            }
        }
    }
}
