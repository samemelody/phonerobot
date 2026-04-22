package com.phonerobot.app.audio

import android.util.Log
import com.google.ai.edge.litertlm.Content
import java.io.File

/**
 * Audio processor for preparing recorded audio for LiteRT-LM inference.
 */
object AudioProcessor {

    private const val TAG = "AudioProcessor"

    /** Create a Content.AudioFile from a WAV file, suitable for LiteRT-LM. */
    fun prepareAudioContent(audioFile: File): Content.AudioFile {
        Log.i(TAG, "Preparing audio content: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
        return Content.AudioFile(audioFile.absolutePath)
    }

    /** Basic validation: must be a non-empty WAV file under 10MB. */
    fun isValidAudioFile(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        val ext = file.extension.lowercase()
        return (ext == "wav") && file.length() > 44 && file.length() < 10 * 1024 * 1024
    }
}
