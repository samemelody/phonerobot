package com.phonerobot.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Audio recorder for capturing microphone input as WAV.
 * 16kHz mono 16-bit PCM, max 20 seconds, auto-stops when limit reached.
 */
class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var recordingStartTime: Long = 0

    var onRecordingFinished: ((File?) -> Unit)? = null

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
        const val MAX_DURATION_MS = 20_000L
    }

    /** Start recording. Returns the output file or null on failure. */
    fun startRecording(): File? {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return null
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size from AudioRecord")
                return null
            }
            val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return null
            }

            outputFile = File.createTempFile("audio_record_", ".wav", context.cacheDir)

            audioRecord?.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            recordingThread = thread(start = true, name = "AudioRecorder") {
                writeAudioData(bufferSize)
            }

            Log.i(TAG, "Recording started: ${outputFile?.absolutePath}")
            return outputFile

        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            return null
        }
    }

    /** Stop recording and return the WAV file. */
    fun stopRecording(): File? {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress")
            return null
        }

        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) { /* already stopped */ }
        audioRecord?.release()
        audioRecord = null

        recordingThread?.join(1500)
        recordingThread = null

        val file = outputFile
        Log.i(TAG, "Recording stopped: ${file?.absolutePath} (${getCurrentDuration()}ms)")
        return file
    }

    fun isRecording(): Boolean = isRecording

    fun getCurrentDuration(): Long {
        return if (isRecording) System.currentTimeMillis() - recordingStartTime else 0
    }

    /** Release all resources. Call in Activity.onDestroy. */
    fun release() {
        stopRecording()
        outputFile = null
        onRecordingFinished = null
    }

    // -- Internal --

    private fun writeAudioData(bufferSize: Int) {
        val data = ByteArray(bufferSize)

        try {
            FileOutputStream(outputFile).use { fos ->
                // Write placeholder WAV header (will be updated after recording)
                writeWavHeader(fos, 0, SAMPLE_RATE, 1, 16)

                var totalBytesWritten = 0

                while (isRecording) {
                    // Auto-stop at 20s limit
                    if (System.currentTimeMillis() - recordingStartTime >= MAX_DURATION_MS) {
                        Log.i(TAG, "Max duration reached, auto-stopping")
                        isRecording = false
                        break
                    }

                    val read = audioRecord?.read(data, 0, bufferSize) ?: break
                    if (read > 0) {
                        fos.write(data, 0, read)
                        totalBytesWritten += read
                    }
                }

                fos.flush()
                updateWavHeader(outputFile!!, totalBytesWritten)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing audio data", e)
        }

        // Clean up AudioRecord on this thread (in case auto-stop triggered)
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null

        val file = if (totalBytesWritten(outputFile) > 44) outputFile else null
        onRecordingFinished?.invoke(file)
    }

    private fun totalBytesWritten(file: File?): Int {
        if (file == null || !file.exists()) return 0
        // File size minus 44-byte WAV header
        return (file.length() - 44).toInt().coerceAtLeast(0)
    }

    private fun writeWavHeader(
        out: FileOutputStream, dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int
    ) {
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

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
        header[22] = channels.toByte(); header[23] = 0
        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // Block align
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        // Bits per sample
        header[34] = bitsPerSample.toByte(); header[35] = 0
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

    private fun updateWavHeader(file: File, dataSize: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalDataLen = dataSize + 36
                raf.seek(4)
                raf.write(totalDataLen and 0xff)
                raf.write((totalDataLen shr 8) and 0xff)
                raf.write((totalDataLen shr 16) and 0xff)
                raf.write((totalDataLen shr 24) and 0xff)
                raf.seek(40)
                raf.write(dataSize and 0xff)
                raf.write((dataSize shr 8) and 0xff)
                raf.write((dataSize shr 16) and 0xff)
                raf.write((dataSize shr 24) and 0xff)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error updating WAV header", e)
        }
    }
}
