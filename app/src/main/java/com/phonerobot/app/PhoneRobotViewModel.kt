package com.phonerobot.app

import android.app.Application
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.ai.FlexibleJavaScriptTool
import com.phonerobot.app.ai.GeneralTools
import com.phonerobot.app.ai.GemmaConfig
import com.phonerobot.app.audio.AudioProcessor
import com.phonerobot.app.audio.AudioRecorder
import com.phonerobot.app.connection.ConnectionManager
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.McuTelemetry
import com.phonerobot.app.robot.QuickJSSandbox
import com.phonerobot.app.ui.PhoneRobotDestination
import com.phonerobot.app.ui.ModelStatus
import com.phonerobot.app.ui.PhoneRobotUiState
import com.phonerobot.app.ui.SnackAction
import com.phonerobot.app.ui.UiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PhoneRobotViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PhoneRobotVM"

        /** Chat history is trimmed to the newest N messages so long sessions don't grow memory and recomposition cost forever. */
        private const val MAX_CHAT_MESSAGES = 200
    }

    private val gemmaService get() = getApplication<PhoneRobotApplication>().gemmaService

    private val _state = MutableStateFlow(PhoneRobotUiState())
    val uiState: StateFlow<PhoneRobotUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    private var lastPrompt: String? = null

    private fun showSnackbar(text: String, action: SnackAction? = null) {
        _effects.tryEmit(UiEffect.ShowSnackbar(text, action))
    }

    fun onSnackAction(action: SnackAction?) {
        when (action) {
            SnackAction.RetryModelLoad -> loadModel()
            SnackAction.RetryInference -> retryLastInference()
            null -> Unit
        }
    }

    val scriptManager: JsScriptManager = JsScriptManager(getApplication()).apply { initializeStorage() }

    val connection: ConnectionManager = ConnectionManager(
        context = getApplication(),
        scope = viewModelScope,
        postEvent = ::postSystemMessage,
    )

    val jsSandbox: QuickJSSandbox = QuickJSSandbox(
        channelProvider = { connection.activeChannel },
        scriptManager = scriptManager,
        enableDetailedLogs = true,
    )

    private val audioRecorder: AudioRecorder = AudioRecorder(getApplication())

    init {
        jsSandbox.initialize()

        viewModelScope.launch {
            combine(
                connection.telemetry,
                connection.usbStatus,
                connection.bleStatus,
                connection.bleScanResults,
            ) { mcu, usb, ble, results ->
                MergedConnection(mcu, usb, ble, results)
            }.collect { m ->
                _state.update {
                    it.copy(mcu = m.mcu, usbStatus = m.usbStatus, bleStatus = m.bleStatus, bleScanResults = m.scanResults)
                }
            }
        }

        if (connection.tryConnectFirstAvailableUsb()) {
            Log.i(TAG, "USB device auto-connected on startup")
        }

        loadModel()
    }

    // ── UI events ────────────────────────────────────────────────

    fun setInput(text: String) = _state.update { it.copy(currentInput = text) }

    fun setDestination(destination: PhoneRobotDestination) = _state.update { it.copy(currentDestination = destination) }

    // ── Text chat ────────────────────────────────────────────────

    fun sendMessage() {
        val input = _state.value.currentInput.trim()
        if (input.isBlank()) return

        if (!gemmaService.isReady) {
            postAssistantMessage("Model is not ready yet. Please wait...")
            return
        }

        val statusPrefix = _state.value.mcu.promptSummary()
        val prompt = if (statusPrefix.isNotEmpty()) "$statusPrefix\n$input" else input

        postUserMessage(input)
        _state.update { it.copy(currentInput = "", isAiThinking = true) }
        lastPrompt = prompt
        runInference(prompt)
    }

    private fun retryLastInference() {
        val prompt = lastPrompt ?: return
        if (!gemmaService.isReady) {
            showSnackbar("Model not ready", SnackAction.RetryInference)
            return
        }
        postUserMessage("(retry)")
        _state.update { it.copy(isAiThinking = true) }
        runInference(prompt)
    }

    private fun runInference(prompt: String) {
        viewModelScope.launch {
            try {
                val result = gemmaService.generate(prompt)
                Log.i(TAG, "Inference complete -> '${result.text.take(100)}' (${result.latencyMs}ms)")
                if (result.text.startsWith("Error:")) {
                    postSystemMessage("⚠ Inference failed — ${result.text.removePrefix("Error:").trim()}")
                    showSnackbar("Inference failed", SnackAction.RetryInference)
                } else {
                    postAssistantMessage(result.text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                postSystemMessage("⚠ Inference failed: ${e.message}")
                showSnackbar("Inference failed", SnackAction.RetryInference)
            } finally {
                _state.update { it.copy(isAiThinking = false) }
            }
        }
    }

    // ── Voice recording ──────────────────────────────────────────

    fun startRecording() {
        val file = audioRecorder.startRecording()
        if (file == null) {
            postSystemMessage("Failed to start recording.")
            return
        }
        _state.update { it.copy(isRecording = true) }
        postSystemMessage("Recording... (max 20s, tap mic to stop)")

        audioRecorder.onRecordingFinished = { recordedFile ->
            viewModelScope.launch(Dispatchers.Main) {
                _state.update { it.copy(isRecording = false) }
                if (recordedFile != null) {
                    sendAudioToModel(recordedFile)
                } else {
                    postSystemMessage("Recording too short, please try again.")
                }
            }
        }
    }

    fun stopRecordingAndSend() {
        audioRecorder.onRecordingFinished = null
        val file = audioRecorder.stopRecording()
        _state.update { it.copy(isRecording = false) }

        if (file != null && AudioProcessor.isValidAudioFile(file)) {
            sendAudioToModel(file)
        } else {
            postSystemMessage("Recording too short, please try again.")
        }
    }

    private fun sendAudioToModel(audioFile: File) {
        if (!gemmaService.isReady) {
            postAssistantMessage("Model is not ready yet. Please wait...")
            return
        }

        postUserMessage("[Voice message]")
        _state.update { it.copy(isAiThinking = true) }

        viewModelScope.launch {
            try {
                val result = gemmaService.generateFromAudio(audioFile)
                Log.i(TAG, "Audio inference complete -> '${result.text.take(100)}' (${result.latencyMs}ms)")
                postAssistantMessage(result.text)
            } catch (e: Exception) {
                Log.e(TAG, "Audio inference error", e)
                postAssistantMessage("Error: ${e.message}")
            } finally {
                _state.update { it.copy(isAiThinking = false) }
                audioFile.delete()
            }
        }
    }

    // ── Connection wrappers ──────────────────────────────────────

    fun connectUsbDevice() = connection.connectKnownUsbDevice()

    fun onUsbDeviceAttached(device: UsbDevice) {
        if (!connection.isUsbConnected()) {
            postSystemMessage("USB detected: ${device.deviceName}")
            connection.requestUsbPermission(device)
        }
    }

    fun startBleScan() = connection.startBleScan()

    fun connectBle(address: String) = connection.connectBle(address)

    fun disconnectBle() = connection.disconnectBle()

    // ── Model loading ────────────────────────────────────────────

    private fun loadModel() {
        Log.i(TAG, "loadModel: starting model loading with tools")
        _state.update { it.copy(modelStatus = ModelStatus.Loading) }

        viewModelScope.launch {
            try {
                val flexibleJsTool = FlexibleJavaScriptTool(jsSandbox, scriptManager) { toolName, summary, body ->
                    postToolMessage(toolName, summary, body)
                }
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
                    _state.update { it.copy(modelStatus = ModelStatus.Ready) }
                    postSystemMessage("Gemma 4 ready! Tell me what robot you're controlling.")
                } else {
                    Log.e(TAG, "loadModel: initialize() returned false")
                    _state.update { it.copy(modelStatus = ModelStatus.Error) }
                    postSystemMessage("Failed to load model — check that the .litertlm file is in the models folder.")
                    showSnackbar("Model failed to load", SnackAction.RetryModelLoad)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel: model loading failed", e)
                _state.update { it.copy(modelStatus = ModelStatus.Error) }
                postSystemMessage("Failed to load model: ${e.message}")
                showSnackbar("Model failed to load", SnackAction.RetryModelLoad)
            }
        }
    }

    // ── Messaging helpers ────────────────────────────────────────

    private fun appendMessage(message: ChatMessage) {
        _state.update {
            it.copy(messages = (it.messages + message).takeLast(MAX_CHAT_MESSAGES))
        }
    }

    fun postSystemMessage(text: String) {
        appendMessage(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun postUserMessage(text: String) {
        appendMessage(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun postAssistantMessage(text: String) {
        appendMessage(ChatMessage(ChatMessage.Role.ASSISTANT, text))
    }

    private fun postToolMessage(toolName: String, summary: String, body: String) {
        val content = if (body.isBlank()) summary else "$summary\n\n$body"
        appendMessage(ChatMessage(ChatMessage.Role.TOOL, content, toolName = toolName))
    }

    // ── Teardown ─────────────────────────────────────────────────

    override fun onCleared() {
        Log.i(TAG, "onCleared: releasing resources")
        audioRecorder.release()
        jsSandbox.cleanup()
        connection.shutdown()
        super.onCleared()
    }

    private data class MergedConnection(
        val mcu: McuTelemetry,
        val usbStatus: String,
        val bleStatus: String,
        val scanResults: List<Pair<String, String>>,
    )
}
