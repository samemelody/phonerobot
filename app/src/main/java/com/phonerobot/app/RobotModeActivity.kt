package com.phonerobot.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.phonerobot.app.audio.VoiceActivityDetector
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Robot Mode Activity - Continuous listening mode.
 * Listens for speech, detects commands, sends to AI.
 */
class RobotModeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RobotModeActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var isRobotModeActive = false

    // UI elements
    private lateinit var statusText: android.widget.TextView
    private lateinit var lastCommandText: android.widget.TextView
    private lateinit var aiResponseText: android.widget.TextView
    private lateinit var startButton: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_robot_mode) // You'll need to create this layout

        statusText = findViewById(R.id.status_text)
        lastCommandText = findViewById(R.id.last_command_text)
        aiResponseText = findViewById(R.id.ai_response_text)
        startButton = findViewById(R.id.start_button)

        startButton.setOnClickListener {
            if (isRobotModeActive) {
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
            updateStatus("Permission required")
            return
        }

        isRobotModeActive = true
        updateStatus("Listening...")
        startButton.text = "Stop"

        scope.launch(Dispatchers.IO) {
            startContinuousListening()
        }
    }

    private fun stopRobotMode() {
        isRobotModeActive = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        updateStatus("Stopped")
        startButton.text = "Start"
    }

    private suspend fun startContinuousListening() {
        val vad = VoiceActivityDetector()
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()

            val buffer = ByteArray(bufferSize)
            var isRecordingCommand = false
            val commandAudio = mutableListOf<Byte>()

            while (isRobotModeActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val isSpeech = vad.processAudio(buffer.copyOfRange(0, read))

                    if (isSpeech && !isRecordingCommand) {
                        // Speech started
                        isRecordingCommand = true
                        commandAudio.clear()
                        commandAudio.addAll(buffer.copyOfRange(0, read).toList())
                        withContext(Dispatchers.Main) {
                            updateStatus("Recording command...")
                        }
                    } else if (isSpeech && isRecordingCommand) {
                        // Continuing speech
                        commandAudio.addAll(buffer.copyOfRange(0, read).toList())
                    } else if (!isSpeech && isRecordingCommand) {
                        // Speech ended
                        isRecordingCommand = false
                        val commandBytes = commandAudio.toByteArray()
                        
                        withContext(Dispatchers.Main) {
                            lastCommandText.text = "Voice command detected (${commandBytes.size} bytes)"
                            updateStatus("Processing...")
                        }
                        
                        processWithAI("Voice command detected")
                        commandAudio.clear()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in continuous listening", e)
            withContext(Dispatchers.Main) {
                updateStatus("Error: ${e.message}")
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private suspend fun processWithAI(command: String) {
        // For now, just simulate AI processing
        delay(500) // Simulate processing time
        
        withContext(Dispatchers.Main) {
            aiResponseText.text = "Command received: $command"
            updateStatus("Listening...")
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Audio permission granted")
                startRobotMode()
            } else {
                Log.e(TAG, "Audio permission denied")
                updateStatus("Permission denied")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRobotMode()
        scope.cancel()
    }
}
