package com.phonerobot.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.phonerobot.app.ai.GemmaService
import com.phonerobot.app.audio.VoiceActivityDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream

/**
 * Robot Mode Activity - Continuous listening with VAD.
 * Uses single AudioRecord → VAD detects speech → records to WAV → sends to AI.
 */
class RobotModeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RobotModeActivity"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "Audio permission granted")
            startRobotMode()
        } else {
            Log.e(TAG, "Audio permission denied")
            updateStatus(getString(R.string.robot_mode_permission_denied))
        }
    }

    // Get GemmaService from Application (singleton - model loaded only once)
    private val gemmaService: GemmaService
        get() = (application as PhoneRobotApplication).gemmaService

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Single AudioRecord instance (shared between VAD and recording)
    private var audioRecord: AudioRecord? = null
    private var isActive = false

    // Recording buffer (accumulates audio during speech)
    private var recordingBuffer = mutableListOf<Byte>()
    private var isRecordingSpeech = false

    // Speech segments queued for AI processing. Decouples the VAD read loop from
    // inference (which takes seconds) so audio keeps flowing while the model thinks;
    // the consumer also serializes inference calls (single engine, no concurrency).
    private val pendingAudio = Channel<File>(Channel.UNLIMITED)

    // UI elements
    private lateinit var statusText: android.widget.TextView
    private lateinit var lastCommandText: android.widget.TextView
    private lateinit var aiResponseText: android.widget.TextView
    private lateinit var startButton: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_robot_mode)

        // Initialize UI
        statusText = findViewById(R.id.status_text)
        lastCommandText = findViewById(R.id.last_command_text)
        aiResponseText = findViewById(R.id.ai_response_text)
        startButton = findViewById(R.id.start_button)

        startButton.setOnClickListener {
            if (isActive) {
                stopRobotMode()
            } else {
                startRobotMode()
            }
        }

        checkPermissions()
    }

    private fun startRobotMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus(getString(R.string.robot_mode_permission_required))
            return
        }

        if (!gemmaService.isReady) {
            updateStatus(getString(R.string.robot_mode_model_not_ready))
            return
        }

        isActive = true
        updateStatus(getString(R.string.robot_mode_listening))
        startButton.text = getString(R.string.robot_mode_btn_stop)

        // Sequential consumer: processes queued speech segments one at a time
        scope.launch(Dispatchers.IO) {
            for (audioFile in pendingAudio) {
                processAudioWithAI(audioFile)
                audioFile.delete()
                withContext(Dispatchers.Main) {
                    updateStatus(getString(R.string.robot_mode_listening))
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            startVadRecording()
        }
    }

    private fun stopRobotMode() {
        isActive = false
        isRecordingSpeech = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        updateStatus(getString(R.string.robot_mode_stopped))
        startButton.text = getString(R.string.robot_mode_btn_start)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startVadRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * 2

        val vad = VoiceActivityDetector(
            sampleRate = SAMPLE_RATE,
            energyThreshold = 0.01f,
            speechStartFrames = 2,
            speechEndFrames = 15
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            audioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)
            recordingBuffer.clear()

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val isSpeech = vad.processAudio(buffer.copyOfRange(0, read))

                    if (isSpeech && !isRecordingSpeech) {
                        // Speech started → start buffering
                        isRecordingSpeech = true
                        recordingBuffer.clear()
                        recordingBuffer.addAll(buffer.copyOfRange(0, read).toList())
                        withContext(Dispatchers.Main) {
                            updateStatus(getString(R.string.robot_mode_recording_speech))
                        }
                    } else if (isSpeech && isRecordingSpeech) {
                        // Continuing speech → keep buffering
                        recordingBuffer.addAll(buffer.copyOfRange(0, read).toList())
                    } else if (!isSpeech && isRecordingSpeech) {
                        // Speech ended -> save to WAV and queue for AI processing
                        isRecordingSpeech = false
                        val audioFile = saveBufferToWav(recordingBuffer)
                        recordingBuffer.clear()

                        withContext(Dispatchers.Main) {
                            if (audioFile != null) {
                                lastCommandText.text = getString(
                                    R.string.robot_mode_speech_file,
                                    audioFile.name,
                                    audioFile.length()
                                )
                                updateStatus(getString(R.string.robot_mode_processing))
                            }
                        }

                        if (audioFile != null) {
                            // Handed off to the processing queue instead of awaited,
                            // so this read loop keeps consuming audio during inference
                            pendingAudio.trySend(audioFile)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in VAD recording", e)
            withContext(Dispatchers.Main) {
                updateStatus(getString(R.string.robot_mode_error, e.message ?: ""))
            }
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }
            audioRecord = null
        }
    }

    private fun saveBufferToWav(buffer: List<Byte>): File? {
        if (buffer.size < 44) return null  // Too small

        return try {
            val file = File.createTempFile("robot_mode_", ".wav", cacheDir)
            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, buffer.size)
                fos.write(buffer.toByteArray())
                fos.flush()
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving WAV", e)
            null
        }
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        val totalDataLen = dataSize + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8

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
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
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

    private suspend fun processAudioWithAI(audioFile: File) {
        try {
            val result = gemmaService.generateFromAudio(audioFile)
            withContext(Dispatchers.Main) {
                aiResponseText.text = "AI: ${result.text}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio with AI", e)
            withContext(Dispatchers.Main) {
                aiResponseText.text = getString(R.string.robot_mode_error, e.message ?: "")
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRobotMode()
        scope.cancel()
    }
}
