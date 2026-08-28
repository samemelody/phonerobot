package com.phonerobot.app

import android.app.Application
import android.hardware.usb.UsbDevice
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.ai.FlexibleJavaScriptTool
import com.phonerobot.app.ai.GeneralTools
import com.phonerobot.app.ai.GemmaConfig
import com.phonerobot.app.audio.AudioProcessor
import com.phonerobot.app.audio.AudioRecorder
import com.phonerobot.app.audio.VoiceActivityDetector
import com.phonerobot.app.connection.ConnectionManager
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.McuTelemetry
import com.phonerobot.app.robot.QuickJSSandbox
import com.phonerobot.app.ui.PhoneRobotDestination
import com.phonerobot.app.ui.ModelStatus
import com.phonerobot.app.ui.PhoneRobotUiState
import com.phonerobot.app.ui.RobotModeStatus
import com.phonerobot.app.ui.SnackAction
import com.phonerobot.app.ui.SpeechSegment
import com.phonerobot.app.ui.UiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PhoneRobotViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PhoneRobotVM"

        /** Chat history is trimmed to the newest N messages so long sessions don't grow memory and recomposition cost forever. */
        private const val MAX_CHAT_MESSAGES = 200

        // Robot Mode audio config (16 kHz mono PCM — matches Gemma's audio encoder)
        private const val ROBOT_SAMPLE_RATE = 16000
        private const val ROBOT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ROBOT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val gemmaService get() = getApplication<PhoneRobotApplication>().gemmaService

    private val _state = MutableStateFlow(PhoneRobotUiState())
    val uiState: StateFlow<PhoneRobotUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    private var lastPrompt: String? = null

    // ── Robot Mode pipeline state (C6c: runs in-place on the ROBOT tab) ──

    // Speech segments queued for AI processing. Decouples the VAD read loop from
    // inference so audio keeps flowing while the model thinks; the sequential
    // consumer also serializes engine access (GemmaService has its own mutex too).
    private val pendingAudio = Channel<File>(Channel.UNLIMITED)
    private var robotAudioRecord: AudioRecord? = null

    @Volatile
    private var robotModeActive = false
    private var vadJob: Job? = null

    /** What a granted RECORD_AUDIO permission should trigger (set before the Activity launches the permission dialog). */
    enum class MicRequest { ChatRecording, RobotMode }
    private var pendingMicRequest: MicRequest? = null

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

    // ── Audio permission routing ─────────────────────────────────
    // The permission dialog is Activity-side (Activity Result API); the VM just
    // remembers what the granted permission should trigger.

    fun onAudioPermissionRequested(request: MicRequest) {
        pendingMicRequest = request
    }

    fun onAudioPermissionGranted() {
        when (pendingMicRequest) {
            MicRequest.ChatRecording -> startRecording()
            MicRequest.RobotMode -> startRobotMode()
            null -> Unit
        }
        pendingMicRequest = null
    }

    fun onAudioPermissionDenied() {
        when (pendingMicRequest) {
            MicRequest.ChatRecording ->
                postSystemMessage("Microphone permission is required for voice input.")
            MicRequest.RobotMode ->
                _state.update { it.copy(robotModeStatus = RobotModeStatus.PermissionDenied) }
            null -> Unit
        }
        pendingMicRequest = null
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

    fun setDestination(destination: PhoneRobotDestination) {
        // Leaving the ROBOT tab (e.g. tapping Chat) exits continuous listening —
        // the mic is freed for chat voice messages again
        if (destination != PhoneRobotDestination.ROBOT_MODE) stopRobotMode()
        _state.update { it.copy(currentDestination = destination) }
    }

    // ── Text chat ────────────────────────────────────────────────

    fun sendMessage() {
        val input = _state.value.currentInput.trim()
        if (input.isBlank()) return

        if (!gemmaService.isReady) {
            val elapsed = _state.value.modelLoadingElapsedSec
            postSystemMessage(
                if (_state.value.modelStatus == ModelStatus.Loading && elapsed > 0) {
                    "Model is still loading (${elapsed}s) — your message wasn't sent. Try again shortly."
                } else {
                    "Model is not ready yet — your message wasn't sent. Try again shortly."
                }
            )
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
            postSystemMessage("Model is not ready yet — voice message wasn't sent. Try again shortly.")
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

    // ── Robot Mode: continuous VAD listening (C6c) ───────────────

    fun startRobotMode() {
        if (robotModeActive) return
        if (!gemmaService.isReady) {
            Log.w(TAG, "startRobotMode: model not ready")
            _state.update { it.copy(robotModeStatus = RobotModeStatus.ModelNotReady) }
            return
        }

        robotModeActive = true
        _state.update {
            it.copy(
                robotModeRunning = true,
                robotModeStatus = RobotModeStatus.Listening,
                lastSpeech = null,
            )
        }
        vadJob = viewModelScope.launch(Dispatchers.IO) { runVadLoop() }
        viewModelScope.launch(Dispatchers.IO) {
            // Sequential consumer: processes queued speech segments one at a time
            for (audioFile in pendingAudio) {
                postUserMessage("[Voice command]")
                processRobotAudio(audioFile)
                audioFile.delete()
                if (robotModeActive) {
                    _state.update { it.copy(robotModeStatus = RobotModeStatus.Listening) }
                }
            }
        }
    }

    fun stopRobotMode() {
        if (!robotModeActive) return
        robotModeActive = false
        vadJob?.cancel()
        releaseRobotAudioRecord()
        drainPendingAudio()
        _state.update {
            it.copy(robotModeRunning = false, robotModeStatus = RobotModeStatus.Stopped)
        }
    }

    private suspend fun runVadLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            ROBOT_SAMPLE_RATE, ROBOT_CHANNEL_CONFIG, ROBOT_AUDIO_FORMAT
        ) * 2

        val vad = VoiceActivityDetector(
            sampleRate = ROBOT_SAMPLE_RATE,
            energyThreshold = 0.01f,
            speechStartFrames = 2,
            speechEndFrames = 15
        )

        // Raw PCM accumulation for the current speech segment (no per-byte boxing)
        var recordingBuffer = ByteArrayOutputStream()
        var isRecordingSpeech = false

        try {
            robotAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                ROBOT_SAMPLE_RATE,
                ROBOT_CHANNEL_CONFIG,
                ROBOT_AUDIO_FORMAT,
                bufferSize
            )
            robotAudioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)

            while (robotModeActive) {
                val read = robotAudioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue
                val chunk = buffer.copyOfRange(0, read)
                val isSpeech = vad.processAudio(chunk)

                if (isSpeech && !isRecordingSpeech) {
                    // Speech started → start buffering
                    isRecordingSpeech = true
                    recordingBuffer.reset()
                    recordingBuffer.write(chunk)
                    _state.update { it.copy(robotModeStatus = RobotModeStatus.RecordingSpeech) }
                } else if (isSpeech && isRecordingSpeech) {
                    // Continuing speech → keep buffering
                    recordingBuffer.write(chunk)
                } else if (!isSpeech && isRecordingSpeech) {
                    // Speech ended → save to WAV and queue for AI processing
                    isRecordingSpeech = false
                    val audioFile = saveRobotBufferToWav(recordingBuffer.toByteArray())
                    recordingBuffer.reset()
                    if (audioFile != null) {
                        _state.update {
                            it.copy(
                                lastSpeech = SpeechSegment(audioFile.name, audioFile.length().toInt()),
                                robotModeStatus = RobotModeStatus.Processing,
                            )
                        }
                        // Handed off to the processing queue instead of awaited,
                        // so this read loop keeps consuming audio during inference
                        pendingAudio.trySend(audioFile)
                    }
                }
            }
        } catch (e: Exception) {
            if (robotModeActive) {
                Log.e(TAG, "Error in VAD recording", e)
                _state.update { it.copy(robotModeStatus = RobotModeStatus.Error(e.message ?: "unknown")) }
            }
        } finally {
            releaseRobotAudioRecord()
        }
    }

    /** PCM buffer → WAV file in cacheDir, or null if the segment is too small. */
    private fun saveRobotBufferToWav(buffer: ByteArray): File? {
        if (buffer.size < 44) return null  // Too small

        return try {
            val file = File.createTempFile("robot_mode_", ".wav", getApplication<Application>().cacheDir)
            FileOutputStream(file).use { fos ->
                writeRobotWavHeader(fos, buffer.size)
                fos.write(buffer)
                fos.flush()
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving WAV", e)
            null
        }
    }

    private fun writeRobotWavHeader(out: FileOutputStream, dataSize: Int) {
        val totalDataLen = dataSize + 36
        val byteRate = ROBOT_SAMPLE_RATE * 1 * 16 / 8

        val header = ByteArray(44)
        // RIFF
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // Chunk size
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        // Subchunk1 size (16 for PCM)
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        // Audio format (1 = PCM)
        header[20] = 1; header[21] = 0
        // Channels
        header[22] = 1; header[23] = 0
        // Sample rate
        header[24] = (ROBOT_SAMPLE_RATE and 0xff).toByte()
        header[25] = ((ROBOT_SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((ROBOT_SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((ROBOT_SAMPLE_RATE shr 24) and 0xff).toByte()
        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // Block align
        header[32] = (1 * 16 / 8).toByte(); header[33] = 0
        // Bits per sample
        header[34] = 16; header[35] = 0
        // data
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        // Subchunk2 size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        out.write(header)
    }

    private suspend fun processRobotAudio(audioFile: File) {
        try {
            val result = gemmaService.generateFromAudio(audioFile)
            postAssistantMessage(result.text)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing robot-mode audio with AI", e)
            postSystemMessage("⚠ Voice command failed: ${e.message}")
        }
    }

    private fun releaseRobotAudioRecord() {
        try {
            robotAudioRecord?.stop()
            robotAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing robot-mode AudioRecord", e)
        }
        robotAudioRecord = null
    }

    /** Deletes speech segments still queued but never processed. */
    private fun drainPendingAudio() {
        while (true) {
            val file = pendingAudio.tryReceive().getOrNull() ?: break
            file.delete()
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
        _state.update { it.copy(modelStatus = ModelStatus.Loading, modelLoadingElapsedSec = 0) }

        // Elapsed-seconds ticker: LiteRT-LM's Engine.initialize() exposes no progress
        // callback (verified against litertlm-android 0.16.1 API), so the honest
        // feedback we can give is a live elapsed counter
        val ticker = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                _state.update { it.copy(modelLoadingElapsedSec = it.modelLoadingElapsedSec + 1) }
            }
        }

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
                    ticker.cancel()
                    _state.update { it.copy(modelStatus = ModelStatus.Ready) }
                    postSystemMessage("Gemma 4 ready! Tell me what robot you're controlling.")
                } else {
                    Log.e(TAG, "loadModel: initialize() returned false")
                    ticker.cancel()
                    _state.update { it.copy(modelStatus = ModelStatus.Error) }
                    postSystemMessage("Failed to load model — check that the .litertlm file is in the models folder.")
                    showSnackbar("Model failed to load", SnackAction.RetryModelLoad)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel: model loading failed", e)
                ticker.cancel()
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
        stopRobotMode()
        drainPendingAudio()
        pendingAudio.close()
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
