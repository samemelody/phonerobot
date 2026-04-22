package com.phonerobot.app

import android.Manifest
import android.content.pm.PackageManager
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
import com.phonerobot.app.audio.AudioProcessor
import com.phonerobot.app.audio.AudioRecorder
import com.phonerobot.app.ui.MainScreen
import com.phonerobot.app.ui.ModelStatus
import com.phonerobot.app.ui.PhoneRobotDestination
import com.phonerobot.app.ui.PhoneRobotStateHolder
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.QuickJSSandbox
import com.phonerobot.app.robot.UsbRobotChannel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var gemmaService: GemmaService
    private lateinit var state: PhoneRobotStateHolder
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var scriptManager: JsScriptManager
    private lateinit var jsSandbox: QuickJSSandbox
    private lateinit var usbChannel: UsbRobotChannel

    private val TAG = "MainActivity"

    // Permission request launcher
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing PhoneRobot app")

        state = PhoneRobotStateHolder()
        audioRecorder = AudioRecorder(applicationContext)
        scriptManager = JsScriptManager(applicationContext)
        scriptManager.initializeStorage()

        // USB serial channel for MCU communication
        usbChannel = UsbRobotChannel(applicationContext)
        usbChannel.registerUsbReceiver()
        usbChannel.onPermissionResult = { device, granted ->
            if (granted) {
                val connected = usbChannel.connect(device)
                state.update { it.copy(usbStatus = if (connected) "Connected" else "Connect failed") }
                if (connected) {
                    addMessage(ChatMessage.Role.SYSTEM, "USB device connected: ${device.deviceName}")
                }
            } else {
                state.update { it.copy(usbStatus = "Permission denied") }
            }
        }
        usbChannel.onDisconnected = {
            state.update { it.copy(usbStatus = "Disconnected") }
            addMessage(ChatMessage.Role.SYSTEM, "USB device disconnected")
        }
        usbChannel.onDataReceived = { data ->
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "MCU response: [$hex]")
        }

        // Try auto-connect if a USB device is already attached
        val alreadyConnected = usbChannel.connectFirstAvailable()
        state.update { it.copy(usbStatus = if (alreadyConnected) "Connected" else "Disconnected") }

        jsSandbox = QuickJSSandbox(
            channel = usbChannel,
            scriptManager = scriptManager,
            enableDetailedLogs = true,
        )
        jsSandbox.initialize()

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

        gemmaService = GemmaService(applicationContext)

        lifecycleScope.launch {
            try {
                val flexibleJsTool = FlexibleJavaScriptTool(jsSandbox, scriptManager)

                val success = gemmaService.initialize(
                    config = GemmaConfig(
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                    ),
                    toolSets = listOf(flexibleJsTool),
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

        if (!::gemmaService.isInitialized || !gemmaService.isReady) {
            addMessage(ChatMessage.Role.ASSISTANT, "Model is not ready yet. Please wait...")
            return
        }

        addMessage(ChatMessage.Role.USER, input)
        state.update { it.copy(currentInput = "", isAiThinking = true) }

        lifecycleScope.launch {
            try {
                val result = gemmaService.generate(input)
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

    private fun addMessage(role: ChatMessage.Role, text: String) {
        state.update {
            it.copy(messages = it.messages + ChatMessage(role, text))
        }
    }

    fun connectUsbDevice() {
        val drivers = usbChannel.listAvailableDevices()
        if (drivers.isEmpty()) {
            addMessage(ChatMessage.Role.SYSTEM, "No USB serial device found. Connect a device and try again.")
            return
        }
        val device = drivers[0].device
        if (usbChannel.hasPermission(device)) {
            val connected = usbChannel.connect(drivers[0])
            state.update { it.copy(usbStatus = if (connected) "Connected" else "Connect failed") }
            if (connected) {
                addMessage(ChatMessage.Role.SYSTEM, "USB device connected: ${device.deviceName}")
            }
        } else {
            state.update { it.copy(usbStatus = "Requesting permission...") }
            usbChannel.requestPermission(device)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Handle USB device attached while activity is running
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            }
            if (device != null && !usbChannel.isConnected()) {
                addMessage(ChatMessage.Role.SYSTEM, "USB device detected: ${device.deviceName}")
                usbChannel.requestPermission(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: releasing resources")
        audioRecorder.release()
        usbChannel.disconnect()
        usbChannel.unregisterUsbReceiver()
        jsSandbox.cleanup()
        if (::gemmaService.isInitialized) {
            gemmaService.close()
        }
    }
}
