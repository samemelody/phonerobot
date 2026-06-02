package com.phonerobot.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.ai.GemmaConfig
import com.phonerobot.app.ai.GemmaService
import com.phonerobot.app.ai.FlexibleJavaScriptTool
import com.phonerobot.app.ai.GeneralTools
import com.phonerobot.app.audio.AudioProcessor
import com.phonerobot.app.audio.AudioRecorder
import com.phonerobot.app.robot.BleRobotChannel
import com.phonerobot.app.ui.MainScreen
import com.phonerobot.app.ui.ModelStatus
import com.phonerobot.app.ui.PhoneRobotDestination
import com.phonerobot.app.ui.PhoneRobotStateHolder
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.QuickJSSandbox
import com.phonerobot.app.robot.RobotChannel
import com.phonerobot.app.robot.RobotCommand
import com.phonerobot.app.robot.ToyCarProtocol
import com.phonerobot.app.robot.UsbRobotChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var gemmaService: GemmaService
    private lateinit var state: PhoneRobotStateHolder
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var scriptManager: JsScriptManager
    private lateinit var jsSandbox: QuickJSSandbox
    private lateinit var usbChannel: UsbRobotChannel
    private lateinit var bleChannel: BleRobotChannel

    // Heartbeat & MCU status
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Int = 0
    private var mcuStatus: String = "MCU: not connected"

    /** Returns whichever channel is currently connected (prefer BLE) */
    private val activeChannel: RobotChannel
        get() = if (bleChannel.isConnected()) bleChannel else usbChannel

    private val TAG = "MainActivity"

    // Permission request launchers
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted")
            startRecording()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied")
            addMessage(ChatMessage.Role.SYSTEM, "Microphone permission is required for voice input.")
        }
    }

    // BLE permissions launcher (Android 12+)
    private val requestBlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "BLE permissions granted")
            startBleScan()
        } else {
            // Show exactly which permission was denied
            val denied = permissions.filter { !it.value }.keys
            Log.e(TAG, "BLE permissions DENIED: $denied")
            addMessage(ChatMessage.Role.SYSTEM, "BLE scan needs: ${denied.joinToString(", ")}. Grant in Settings > Apps > PhoneRobot > Permissions.")
            // Open app settings so user can grant
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open app settings", e)
            }
        }
    }

    // Bluetooth enable launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled, starting scan")
            startBleScan()
        } else {
            addMessage(ChatMessage.Role.SYSTEM, "Bluetooth must be enabled to scan for devices.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing PhoneRobot app")

        state = PhoneRobotStateHolder()
        audioRecorder = AudioRecorder(applicationContext)
        scriptManager = JsScriptManager(applicationContext)
        scriptManager.initializeStorage()

        // ── USB serial channel (secondary) ──────────────────────
        usbChannel = UsbRobotChannel(applicationContext)
        usbChannel.registerUsbReceiver()
        usbChannel.onPermissionResult = { device, granted ->
            if (granted) {
                val connected = usbChannel.connect(device)
                state.update { it.copy(usbStatus = if (connected) "Connected" else "Connect failed") }
                if (connected) {
                    addMessage(ChatMessage.Role.SYSTEM, "USB device connected: ${device.deviceName}")
                    mcuStatus = "MCU: connected, awaiting first response..."
                    startHeartbeat()
                }
            } else {
                state.update { it.copy(usbStatus = "Permission denied") }
            }
        }
        usbChannel.onDisconnected = {
            state.update { it.copy(usbStatus = "Disconnected") }
            addMessage(ChatMessage.Role.SYSTEM, "USB disconnected")
            mcuStatus = "MCU: disconnected"
            stopHeartbeat()
        }

        // ── BLE serial channel (primary) ─────────────────────
        bleChannel = BleRobotChannel(applicationContext)
        bleChannel.onScanResult = { name, address ->
            Log.i(TAG, "████ MainActivity scan result: name=$name addr=$address ████")
            state.update { it.copy(bleScanResults = it.bleScanResults + Pair(name, address)) }
        }
        bleChannel.onScanFinished = {
            Log.i(TAG, "████ BLE scan finished — devices found: ${state.uiState.bleScanResults.size} ████")
            state.update { current ->
                if (current.bleScanResults.isEmpty()) {
                    current.copy(bleStatus = "Disconnected")
                } else {
                    current.copy(bleStatus = "${current.bleScanResults.size} device(s) found")
                }
            }
        }
        bleChannel.onConnected = { name ->
            state.update { it.copy(bleStatus = "Connected") }
            addMessage(ChatMessage.Role.SYSTEM, "BLE connected: $name")
            mcuStatus = "MCU: connected, awaiting first response..."
            startHeartbeat()
        }
        bleChannel.onConnectFailed = { reason ->
            state.update { it.copy(bleStatus = "Failed") }
            addMessage(ChatMessage.Role.SYSTEM, "BLE connection failed: $reason")
        }
        bleChannel.onDisconnected = {
            state.update { it.copy(bleStatus = "Disconnected") }
            addMessage(ChatMessage.Role.SYSTEM, "BLE disconnected")
            mcuStatus = "MCU: disconnected"
            stopHeartbeat()
        }

        // ── MCU response handler (shared by both USB and BLE) ──
        val onMcuData: (ByteArray) -> Unit = { data ->
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "<<< MCU RAW [${data.size}B]: $hex")

            val parsed = ToyCarProtocol.parseNotification(data)
            if (parsed.valid) {
                Log.i(TAG, "<<< MCU PARSED: ${parsed.summary}")
                mcuStatus = "[${parsed.cmdName}] ${parsed.summary}"

                val errorName = parsed.data["errorName"] as? String ?: ""
                val resultName = parsed.data["resultName"] as? String ?: ""
                if (parsed.cmdName == "STATUS" && errorName.isNotEmpty() && errorName != "Normal") {
                    addMessage(ChatMessage.Role.SYSTEM, "⚠ MCU fault: $errorName — ${parsed.summary}")
                }
                if (parsed.cmdName == "CMD_DONE" && resultName != "Success") {
                    addMessage(ChatMessage.Role.SYSTEM, "⚠ CMD failed: $resultName")
                }
            } else {
                Log.w(TAG, "<<< MCU PARSE FAIL: ${parsed.error}")
            }
        }
        usbChannel.onDataReceived = onMcuData
        bleChannel.onDataReceived = onMcuData

        // ── Auto-connect USB if already plugged in ────────────
        val usbAlreadyConnected = usbChannel.connectFirstAvailable()
        state.update { it.copy(usbStatus = if (usbAlreadyConnected) "Connected" else "Disconnected") }
        if (usbAlreadyConnected) {
            mcuStatus = "MCU: connected, awaiting first response..."
            startHeartbeat()
        }

        // ── JS Sandbox (uses activeChannel: prefers BLE, falls back to USB) ──
        jsSandbox = QuickJSSandbox(
            channel = activeChannel,
            scriptManager = scriptManager,
            enableDetailedLogs = true,
        )
        jsSandbox.initialize()

        // ── UI ────────────────────────────────────────────────
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        state = state.uiState,
                        currentDestination = state.uiState.currentDestination,
                        onDestinationChanged = { dest ->
                            state.update { it.copy(currentDestination = dest) }
                        },
                        onInputChanged = { text ->
                            state.update { it.copy(currentInput = text) }
                        },
                        onSendClicked = {
                            handleSendMessage()
                        },
                        onVoiceClicked = {
                            handleVoiceClicked()
                        },
                        onCallClick = {
                            val currentlyOnCall = state.uiState.isOnCall
                            state.update { it.copy(isOnCall = !currentlyOnCall) }
                        },
                        onConnectUsb = {
                            connectUsbDevice()
                        },
                        onScanBle = {
                            startBleScan()
                        },
                        onConnectBle = { address ->
                            connectBleDevice(address)
                        },
                        onDisconnectBle = {
                            disconnectBle()
                        },
                    )
                }
            }
        }

        loadModel()
    }

    // -- Voice recording --

    private fun handleVoiceClicked() {
        if (state.uiState.isRecording) {
            stopRecordingAndSend()
        } else {
            if (checkAudioPermission()) {
                startRecording()
            }
        }
    }

    private fun checkAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun startRecording() {
        val file = audioRecorder.startRecording()
        if (file != null) {
            state.update { it.copy(isRecording = true) }
            addMessage(ChatMessage.Role.SYSTEM, "Recording... (max 20s, tap mic to stop)")

            audioRecorder.onRecordingFinished = { recordedFile ->
                runOnUiThread {
                    state.update { it.copy(isRecording = false) }
                    if (recordedFile != null) {
                        sendAudioToModel(recordedFile)
                    } else {
                        addMessage(ChatMessage.Role.SYSTEM, "Recording too short, please try again.")
                    }
                }
            }
        } else {
            addMessage(ChatMessage.Role.SYSTEM, "Failed to start recording.")
        }
    }

    private fun stopRecordingAndSend() {
        audioRecorder.onRecordingFinished = null
        val file = audioRecorder.stopRecording()
        state.update { it.copy(isRecording = false) }

        if (file != null && AudioProcessor.isValidAudioFile(file)) {
            sendAudioToModel(file)
        } else {
            addMessage(ChatMessage.Role.SYSTEM, "Recording too short, please try again.")
        }
    }

    private fun sendAudioToModel(audioFile: File) {
        if (!::gemmaService.isInitialized || !gemmaService.isReady) {
            addMessage(ChatMessage.Role.ASSISTANT, "Model is not ready yet. Please wait...")
            return
        }

        addMessage(ChatMessage.Role.USER, "[Voice message]")
        state.update { it.copy(isAiThinking = true) }

        lifecycleScope.launch {
            try {
                val result = gemmaService.generateFromAudio(audioFile)
                Log.i(TAG, "Audio inference complete -> '${result.text.take(100)}' (${result.latencyMs}ms)")
                addMessage(ChatMessage.Role.ASSISTANT, result.text)
            } catch (e: Exception) {
                Log.e(TAG, "Audio inference error", e)
                addMessage(ChatMessage.Role.ASSISTANT, "Error: ${e.message}")
            } finally {
                state.update { it.copy(isAiThinking = false) }
                audioFile.delete()
            }
        }
    }

    // -- Text chat --

    private fun loadModel() {
        Log.i(TAG, "loadModel: starting model loading with tools")
        state.update { it.copy(modelStatus = ModelStatus.Loading) }

        // Use the singleton from PhoneRobotApplication
        gemmaService = (application as PhoneRobotApplication).gemmaService

        lifecycleScope.launch {
            try {
                val flexibleJsTool = FlexibleJavaScriptTool(jsSandbox, scriptManager)
                val generalTools = GeneralTools()

                val success = gemmaService.initialize(
                    config = GemmaConfig(
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                    ),
                    toolSets = listOf(flexibleJsTool, generalTools),
                )

                if (success) {
                    Log.i(TAG, "loadModel: model loaded successfully")
                    state.update { it.copy(modelStatus = ModelStatus.Ready) }
                    addMessage(ChatMessage.Role.SYSTEM, "Gemma 4 ready! Tell me what robot you're controlling.")
                } else {
                    Log.e(TAG, "loadModel: initialize() returned false")
                    state.update { it.copy(modelStatus = ModelStatus.Error) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel: model loading failed", e)
                state.update { it.copy(modelStatus = ModelStatus.Error) }
                addMessage(ChatMessage.Role.SYSTEM, "Failed to load model: ${e.message}")
            }
        }
    }

    private fun handleSendMessage() {
        val input = state.uiState.currentInput.trim()
        if (input.isBlank()) return

        // Check for test command
        if (input.equals("test sandbox", ignoreCase = true)) {
            testSandbox()
            return
        }

        if (!::gemmaService.isInitialized || !gemmaService.isReady) {
            addMessage(ChatMessage.Role.ASSISTANT, "Model is not ready yet. Please wait...")
            return
        }

        // Prepend MCU status to prompt so AI knows current robot state
        val promptWithStatus = if (mcuStatus != "MCU: not connected" && mcuStatus != "MCU: disconnected") {
            "[Current robot status: $mcuStatus]\n$input"
        } else {
            input
        }

        addMessage(ChatMessage.Role.USER, input)
        state.update { it.copy(currentInput = "", isAiThinking = true) }

        lifecycleScope.launch {
            try {
                val result = gemmaService.generate(promptWithStatus)
                Log.i(TAG, "Inference complete -> '${result.text.take(100)}' (${result.latencyMs}ms)")
                addMessage(ChatMessage.Role.ASSISTANT, result.text)
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                addMessage(ChatMessage.Role.ASSISTANT, "Error: ${e.message}")
            } finally {
                state.update { it.copy(isAiThinking = false) }
            }
        }
    }

    /**
     * Test the JS sandbox and display results
     */
    private fun testSandbox() {
        state.update { it.copy(currentInput = "", isAiThinking = true) }
        addMessage(ChatMessage.Role.USER, "[Test JS Sandbox]")

        lifecycleScope.launch {
            try {
                val results = jsSandbox.testSandbox()
                Log.i(TAG, "=== SANDBOX TEST RESULTS ===")
                Log.i(TAG, results)
                Log.i(TAG, "=== END TEST RESULTS ===")

                addMessage(ChatMessage.Role.SYSTEM, "Sandbox test complete. Check logcat for details.")
                addMessage(ChatMessage.Role.ASSISTANT, results)
            } catch (e: Exception) {
                Log.e(TAG, "Sandbox test failed", e)
                addMessage(ChatMessage.Role.ASSISTANT, "Test failed: ${e.message}")
            } finally {
                state.update { it.copy(isAiThinking = false) }
            }
        }
    }

    private fun addMessage(role: ChatMessage.Role, text: String) {
        state.update {
            it.copy(messages = it.messages + ChatMessage(role, text))
        }
    }

    // ── USB connect ────────────────────────────────────────────

    fun connectUsbDevice() {
        val drivers = usbChannel.listAvailableDevices()
        if (drivers.isEmpty()) {
            addMessage(ChatMessage.Role.SYSTEM, "No USB serial device found.")
            return
        }
        val device = drivers[0].device
        if (usbChannel.hasPermission(device)) {
            val connected = usbChannel.connect(drivers[0])
            state.update { it.copy(usbStatus = if (connected) "Connected" else "Connect failed") }
            if (connected) {
                addMessage(ChatMessage.Role.SYSTEM, "USB connected: ${device.deviceName}")
                mcuStatus = "MCU: connected, awaiting first response..."
                startHeartbeat()
            }
        } else {
            state.update { it.copy(usbStatus = "Requesting permission...") }
            usbChannel.requestPermission(device)
        }
    }

    // ── BLE scan + connect ─────────────────────────────────────

    private fun startBleScan() {
        // Check Bluetooth enabled
        if (!bleChannel.isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth not enabled, requesting...")
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
            return
        }

        // Check location services enabled (required for BLE scan on Android)
        val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            Log.w(TAG, "Location services disabled — BLE scan requires location on Android")
            addMessage(ChatMessage.Role.SYSTEM, "Please enable Location services (required for BLE scanning)")
            // Open location settings
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Check permissions — LOCATION is required for BLE on ALL Android versions
        val missingPermissions = mutableListOf<String>()

        // Bluetooth permissions by SDK level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                missingPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                missingPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED)
                missingPermissions.add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED)
                missingPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Location: only required for BLE scanning on Android 11 and below
        // On Android 12+, BLUETOOTH_SCAN with neverForLocation flag replaces it
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: $missingPermissions, requesting...")
            requestBlePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        Log.i(TAG, "████ All checks passed — launching BLE scan ████")
        state.update { it.copy(bleStatus = "Scanning...", bleScanResults = emptyList()) }
        addMessage(ChatMessage.Role.SYSTEM, "Scanning for BLE devices...")

        // Auto-connect to known devices (MLT-BT05, HMSoft, etc.)
        bleChannel.startScanUnfiltered(10_000L, autoConnectOnMatch = true)
    }

    fun connectBleDevice(address: String) {
        bleChannel.stopScan()
        state.update { it.copy(bleStatus = "Connecting...") }
        addMessage(ChatMessage.Role.SYSTEM, "Connecting to BLE device...")
        bleChannel.connect(address)
    }

    private fun disconnectBle() {
        stopHeartbeat()
        bleChannel.disconnect()
        state.update { it.copy(bleStatus = "Disconnected", bleScanResults = emptyList()) }
        addMessage(ChatMessage.Role.SYSTEM, "BLE disconnected")
        mcuStatus = "MCU: disconnected"
    }

    // ── Heartbeat (500ms interval, sent via active channel) ────

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatSeq = 0
        heartbeatJob = lifecycleScope.launch {
            Log.i(TAG, "Heartbeat started (500ms interval)")
            while (isActive && activeChannel.isConnected()) {
                val frame = ToyCarProtocol.buildHeartbeat(heartbeatSeq++)
                activeChannel.send(RobotCommand.RawData(frame))
                delay(500L)
            }
            Log.i(TAG, "Heartbeat stopped")
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Lifecycle overrides ────────────────────────────────────

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            }
            if (device != null && !usbChannel.isConnected()) {
                addMessage(ChatMessage.Role.SYSTEM, "USB detected: ${device.deviceName}")
                usbChannel.requestPermission(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: releasing resources")
        stopHeartbeat()
        bleChannel.stopScan()
        bleChannel.disconnect()
        audioRecorder.release()
        usbChannel.disconnect()
        usbChannel.unregisterUsbReceiver()
        jsSandbox.cleanup()
        if (::gemmaService.isInitialized) {
            gemmaService.close()
        }
    }
}
