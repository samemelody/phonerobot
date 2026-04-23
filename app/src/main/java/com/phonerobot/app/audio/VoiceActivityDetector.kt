package com.phonerobot.app.audio

import android.media.AudioRecord
import kotlin.math.sqrt

/**
 * Simple Voice Activity Detector (VAD) using energy-based detection.
 * Detects when speech starts and ends based on audio energy levels.
 *
 * Usage:
 *   val vad = VoiceActivityDetector()
 *   vad.processAudio(audioData) { isSpeech ->
 *       if (isSpeech) "Speech detected" else "Silence"
 *   }
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val frameSizeMs: Int = 20,  // 20ms frames
    private val energyThreshold: Float = 0.01f,  // RMS threshold (0.0-1.0)
    private val speechStartFrames: Int = 2,  // Frames of speech to confirm start
    private val speechEndFrames: Int = 15,  // Frames of silence to confirm end
) {
    private val frameSizeSamples = sampleRate * frameSizeMs / 1000
    private var speechFrameCount = 0
    private var silenceFrameCount = 0
    private var isInSpeech = false

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onVadStateChange: ((VadState) -> Unit)? = null

    enum class VadState {
        LISTENING,    // Waiting for speech
        SPEECH,        // Speech detected
        SILENCE        // Silence after speech
    }

    /**
     * Process audio data and detect speech/silence.
     * @param audioData PCM 16-bit audio data
     * @return true if currently in speech state
     */
    fun processAudio(audioData: ByteArray): Boolean {
        val energy = calculateRms(audioData)

        if (energy > energyThreshold) {
            // Speech detected
            speechFrameCount++
            silenceFrameCount = 0

            if (!isInSpeech && speechFrameCount >= speechStartFrames) {
                isInSpeech = true
                speechFrameCount = 0
                onSpeechStart?.invoke()
                onVadStateChange?.invoke(VadState.SPEECH)
            }
        } else {
            // Silence detected
            silenceFrameCount++
            speechFrameCount = 0

            if (isInSpeech && silenceFrameCount >= speechEndFrames) {
                isInSpeech = false
                silenceFrameCount = 0
                onSpeechEnd?.invoke()
                onVadStateChange?.invoke(VadState.SILENCE)
            }
        }

        if (!isInSpeech && speechFrameCount == 0 && silenceFrameCount == 0) {
            onVadStateChange?.invoke(VadState.LISTENING)
        }

        return isInSpeech
    }

    /**
     * Calculate RMS (Root Mean Square) energy of audio data.
     */
    private fun calculateRms(audioData: ByteArray): Float {
        var sum = 0.0
        val sampleCount = audioData.size / 2  // 16-bit = 2 bytes per sample

        for (i in 0 until sampleCount) {
            val sample = ((audioData[i * 2 + 1].toInt() shl 8) or (audioData[i * 2].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }

        val rms = sqrt(sum / sampleCount)
        return (rms / 32768.0).toFloat()  // Normalize to 0.0-1.0
    }

    /**
     * Reset VAD state.
     */
    fun reset() {
        speechFrameCount = 0
        silenceFrameCount = 0
        isInSpeech = false
    }
}
