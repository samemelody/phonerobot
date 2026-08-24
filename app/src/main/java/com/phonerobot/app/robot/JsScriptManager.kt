package com.phonerobot.app.robot

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages JavaScript scripts stored in external storage.
 * Provides CRUD operations for script files and protocol template management.
 */
class JsScriptManager(private val context: Context) {
    companion object {
        private const val TAG = "JsScriptManager"
        private const val SCRIPTS_DIR = "PhoneRobot/scripts"
        private const val PROTOCOL_DIR = "PhoneRobot/protocol"

        /** All protocol template assets bundled with the app */
        val PROTOCOL_TEMPLATES = listOf(
            "toy_car_protocol_core.js" to "Toy Car - basic RC car control (packMove/packTurn/packArc/packStop)"
        )
    }

    /**
     * Get the external storage directory for scripts
     * Falls back to internal storage if external is not available
     */
    private fun getScriptsDir(): File {
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, SCRIPTS_DIR)
        } else {
            File(context.filesDir, SCRIPTS_DIR)
        }
    }

    /**
     * Get the external storage directory for protocols
     */
    private fun getProtocolDir(): File {
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, PROTOCOL_DIR)
        } else {
            File(context.filesDir, PROTOCOL_DIR)
        }
    }

    /**
     * Initialize storage directories and copy protocol templates from assets.
     * Always overwrites built-in templates to keep them in sync with app updates.
     */
    fun initializeStorage() {
        val scriptsDir = getScriptsDir()
        val protocolDir = getProtocolDir()

        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        if (!protocolDir.exists()) protocolDir.mkdirs()

        // Log the storage locations
        Log.i(TAG, "Scripts directory: ${scriptsDir.absolutePath}")
        Log.i(TAG, "Protocol directory: ${protocolDir.absolutePath}")

        // Always copy/overwrite built-in protocol templates to keep them in sync
        PROTOCOL_TEMPLATES.forEach { (assetName, _) ->
            val destFile = File(protocolDir, assetName)
            copyAssetToFile(assetName, destFile)
            Log.i(TAG, "Synced protocol template: $assetName")
        }
    }

    // ── Script CRUD ───────────────────────────────────────────

    /**
     * Save JS script to external storage
     * @param content JS code content
     * @param name Optional script name, will generate timestamp-based name if null
     * @return File path of saved script
     */
    fun saveScript(content: String, name: String? = null): File {
        val scriptsDir = getScriptsDir()
        val fileName = name ?: generateScriptFileName("script")
        val scriptFile = File(scriptsDir, "${fileName}.js")

        Log.i(TAG, "Saving script to: ${scriptFile.absolutePath}")

        FileOutputStream(scriptFile).use { fos ->
            fos.write(content.toByteArray())
        }

        return scriptFile
    }

    /**
     * Load JS script content
     * @param fileName Script file name (without .js extension)
     * @return Script content as string, or null if not found
     */
    fun loadScript(fileName: String): String? {
        val scriptFile = File(getScriptsDir(), "${fileName}.js")
        return if (scriptFile.exists()) {
            FileInputStream(scriptFile).use { fis -> String(fis.readBytes()) }
        } else {
            null
        }
    }

    /**
     * Get list of all saved scripts
     * @return List of script file names (without .js extension)
     */
    fun listScripts(): List<String> {
        val scriptsDir = getScriptsDir()
        return if (scriptsDir.exists()) {
            scriptsDir.listFiles { file -> file.extension == "js" }
                ?.map { it.nameWithoutExtension } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Delete script file
     * @param fileName Script file name (without .js extension)
     * @return True if successful
     */
    fun deleteScript(fileName: String): Boolean {
        val scriptFile = File(getScriptsDir(), "${fileName}.js")
        return scriptFile.delete()
    }

    // ── Protocol Templates ────────────────────────────────────

    /**
     * Load a specific protocol template by asset filename.
     * @param protocolFileName e.g. "rover_protocol.js"
     * @return Protocol JS content, or null if not found
     */
    fun loadProtocolScript(protocolFileName: String = "toy_car_protocol_core.js"): String? {
        val protocolFile = File(getProtocolDir(), protocolFileName)
        return if (protocolFile.exists()) {
            Log.d(TAG, "Loading protocol from: ${protocolFile.absolutePath}")
            FileInputStream(protocolFile).use { fis -> String(fis.readBytes()) }
        } else {
            // Fallback: try reading directly from assets
            try {
                context.assets.open(protocolFileName).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                Log.e(TAG, "Protocol template not found: $protocolFileName", e)
                null
            }
        }
    }

    /**
     * List all available protocol templates with descriptions.
     * @return Map of filename → description
     */
    fun listProtocolTemplates(): Map<String, String> {
        return PROTOCOL_TEMPLATES.toMap()
    }

    /**
     * Get list of installed protocol template filenames.
     * @return List of protocol filenames (e.g. ["rover_protocol.js", "drone_protocol.js"])
     */
    fun listInstalledProtocols(): List<String> {
        val protocolDir = getProtocolDir()
        return if (protocolDir.exists()) {
            protocolDir.listFiles { file -> file.extension == "js" }
                ?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Save a new protocol script to the protocol directory.
     * Rejects if the file already exists (to prevent overwriting originals).
     * @param protocolFileName e.g. "my_fixed_car.js"
     * @param content Full JS script content
     * @return true if saved successfully, false if file exists or write failed
     */
    fun saveProtocolScript(protocolFileName: String, content: String): Boolean {
        val protocolDir = getProtocolDir()
        if (!protocolDir.exists()) protocolDir.mkdirs()
        val protocolFile = File(protocolDir, protocolFileName)

        if (protocolFile.exists()) {
            Log.w(TAG, "Protocol file already exists, will not overwrite: $protocolFileName")
            return false
        }

        Log.i(TAG, "Saving protocol to: ${protocolFile.absolutePath}")

        return try {
            FileOutputStream(protocolFile).use { fos ->
                fos.write(content.toByteArray())
            }
            Log.i(TAG, "Saved new protocol: $protocolFileName (${content.length} chars)")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save protocol: $protocolFileName", e)
            false
        }
    }

    /**
     * Delete a user-created protocol file from external storage.
     * Cannot delete built-in template protocols (will be re-copied on next init).
     * @param protocolFileName e.g. "toy_car_protocol_fixed.js"
     * @return true if deleted, false if not found or is a built-in template
     */
    fun deleteProtocol(protocolFileName: String): Boolean {
        // Protect built-in templates
        if (PROTOCOL_TEMPLATES.any { it.first == protocolFileName }) {
            Log.w(TAG, "Cannot delete built-in protocol: $protocolFileName")
            return false
        }

        val protocolFile = File(getProtocolDir(), protocolFileName)
        return if (protocolFile.exists()) {
            val deleted = protocolFile.delete()
            if (deleted) {
                Log.i(TAG, "Deleted protocol: $protocolFileName")
            } else {
                Log.w(TAG, "Failed to delete protocol: $protocolFileName")
            }
            deleted
        } else {
            Log.w(TAG, "Protocol not found: $protocolFileName")
            false
        }
    }

    // ── Internal helpers ──────────────────────────────────────

    private fun generateScriptFileName(prefix: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$timestamp"
    }

    private fun copyAssetToFile(assetName: String, destFile: File) {
        try {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while (inputStream.read(buffer).also { length = it } > 0) {
                        outputStream.write(buffer, 0, length)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetName", e)
        }
    }
}
