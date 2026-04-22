package com.phonerobot.app.robot

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * QuickJS JavaScript Sandbox for robot protocol execution.
 *
 * Workflow:
 * 1. loadProtocol(filename) - AI tells sandbox which robot protocol to use
 * 2. executeScript(jsCode) - AI generates JS calling protocol.pack*() functions
 * 3. Binary result is automatically sent via RobotChannel (BT/USB)
 *
 * The sandbox simulates JS execution by parsing protocol.pack*() calls
 * and returning the binary data that the JS would have produced.
 * In a future version, a real QuickJS runtime will replace this.
 */
class QuickJSSandbox(
    private val channel: RobotChannel,
    private val scriptManager: JsScriptManager,
    private val enableDetailedLogs: Boolean = true,
) {
    companion object {
        private const val TAG = "QuickJSSandbox"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val MAX_SCRIPT_LENGTH = 8192
    }

    // State
    private val isInitialized = AtomicBoolean(false)
    private var executionCount = 0

    /** Currently loaded protocol template filename, or null if none */
    private var activeProtocol: String? = null

    /** Parsed function definitions from the loaded protocol script */
    private val protocolFunctions = mutableMapOf<String, ProtocolFunctionDef>()

    // ── Lifecycle ─────────────────────────────────────────────

    fun initialize(): Boolean {
        return try {
            isInitialized.set(true)
            Log.i(TAG, "Sandbox initialized - ready to load protocol")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sandbox init failed", e)
            false
        }
    }

    fun cleanup() {
        activeProtocol = null
        protocolFunctions.clear()
        isInitialized.set(false)
        executionCount = 0
        Log.i(TAG, "Sandbox cleaned up")
    }

    fun isReady(): Boolean = isInitialized.get()

    // ── Protocol Loading ──────────────────────────────────────

    /**
     * Load a protocol template into the sandbox.
     * Parses the JS file to discover available protocol.pack*() functions.
     *
     * @param filename Asset filename (e.g. "rover_protocol.js")
     * @return Description of loaded protocol and its functions
     */
    fun loadProtocol(filename: String): String {
        val script = scriptManager.loadProtocolScript(filename)
        if (script == null) {
            val err = "Protocol '$filename' not found"
            Log.e(TAG, err)
            return "Error: $err"
        }

        activeProtocol = filename
        protocolFunctions.clear()

        // Parse function definitions from JS: packXxxRequest: function(param1, param2)
        val funcRegex = Regex(
            """pack(\w+)Request\s*:\s*function\s*\(([^)]*)\)"""
        )
        funcRegex.findAll(script).forEach { match ->
            val name = "pack${match.groupValues[1]}Request"
            val params = match.groupValues[2]
                .split(",")
                .map { it.trim().removeSuffix(" = 0").removeSuffix("=0").trim() }
                .filter { it.isNotEmpty() }
            protocolFunctions[name] = ProtocolFunctionDef(name, params)
        }

        // Also extract JSDoc descriptions
        val descRegex = Regex(
            """/\*\*\s*\n\s*\*\s*([^\n]+)\n\s*\*[^*]*pack(\w+)Request"""
        )
        descRegex.findAll(script).forEach { match ->
            val desc = match.groupValues[1].trim()
            val name = "pack${match.groupValues[2]}Request"
            protocolFunctions[name]?.description = desc
        }

        val funcList = protocolFunctions.values.joinToString("\n") { f ->
            "  - protocol.${f.name}(${f.params.joinToString(", ")})" +
                (if (f.description.isNotEmpty()) " — ${f.description}" else "")
        }

        Log.i(TAG, "Loaded protocol: $filename with ${protocolFunctions.size} functions")
        return "Loaded: $filename\nAvailable commands:\n$funcList"
    }

    /** Get the currently active protocol filename */
    fun getActiveProtocol(): String? = activeProtocol

    /** Get list of available protocol function names */
    fun getProtocolFunctions(): List<String> = protocolFunctions.keys.toList()

    // ── Script Execution ──────────────────────────────────────

    /**
     * Execute JavaScript code in the sandbox.
     * If the result is binary data (ByteArray), it is automatically sent
     * through the RobotChannel (Bluetooth/USB).
     *
     * @param javascriptCode AI-generated JS code
     * @return Execution result description (String)
     */
    fun executeScript(javascriptCode: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Any {
        if (!isInitialized.get()) {
            return "Error: Sandbox not initialized"
        }

        if (javascriptCode.length > MAX_SCRIPT_LENGTH) {
            return "Error: Script too long (${javascriptCode.length} chars)"
        }

        if (activeProtocol == null) {
            return "Error: No protocol loaded. Call loadProtocol() first."
        }

        executionCount++
        val execId = executionCount

        if (enableDetailedLogs) {
            Log.i(TAG, "Exec #$execId: ${javascriptCode.take(80)}...")
        }

        return try {
            val result = executeJavaScriptInternal(javascriptCode, execId)

            // If binary data was produced, auto-send via channel
            if (result is ByteArray && result.isNotEmpty()) {
                sendViaChannel(result, execId)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Exec #$execId failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Execute a saved script by name.
     */
    fun executeSavedScript(scriptName: String): Any {
        val content = scriptManager.loadScript(scriptName)
        return if (content != null) {
            executeScript(content)
        } else {
            "Error: Script '$scriptName' not found"
        }
    }

    // ── Internal Execution ────────────────────────────────────

    /**
     * Simulated JS execution: parses protocol.pack*() calls from the code
     * and generates binary data matching what the JS would produce.
     *
     * This works by:
     * 1. Finding protocol.packXxxRequest(...) calls in the JS code
     * 2. Extracting the numeric arguments
     * 3. Building a binary packet: [1B cmd][params as per protocol convention]
     *
     * When real QuickJS is integrated, this will be replaced with actual
     * JS evaluation that returns Uint8Array from the protocol object.
     */
    private fun executeJavaScriptInternal(code: String, execId: Int): Any {
        // Find the first protocol.pack*() call in the code
        val callRegex = Regex("""protocol\.(pack\w+Request)\s*\(([^)]*)\)""")
        val match = callRegex.find(code)

        if (match == null) {
            Log.w(TAG, "Exec #$execId: No protocol.pack*() call found in code")
            return "Error: No protocol command found. Use protocol.packXxxRequest() syntax."
        }

        val funcName = match.groupValues[1]
        val argsStr = match.groupValues[2]

        // Verify function exists in loaded protocol
        val funcDef = protocolFunctions[funcName]
        if (funcDef == null) {
            val available = protocolFunctions.keys.joinToString(", ")
            return "Error: Unknown function '$funcName'. Available: $available"
        }

        // Parse arguments
        val args = parseArguments(argsStr)
        if (args.size < funcDef.params.size) {
            // Some params may have defaults; allow fewer args
            Log.d(TAG, "Exec #$execId: $funcName called with ${args.size} args (expects ${funcDef.params.size})")
        }

        // Generate binary data from the call
        val binaryData = generateBinaryPacket(funcName, args)

        if (enableDetailedLogs) {
            val hex = binaryData.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "Exec #$execId: $funcName(${args.joinToString(", ")}) → ${binaryData.size}B [$hex]")
        }

        return binaryData
    }

    /**
     * Parse comma-separated numeric arguments from JS code.
     * Handles: integers, negative numbers, floats, simple expressions like "angle * 10"
     */
    private fun parseArguments(argsStr: String): List<Double> {
        if (argsStr.isBlank()) return emptyList()

        return argsStr.split(",").mapNotNull { arg ->
            val trimmed = arg.trim()
            // Try to evaluate simple numeric expressions
            // Strip Math.round() wrapper if present
            val cleaned = trimmed
                .replace(Regex("""Math\.round\(([^)]+)\)""")) { it.groupValues[1] }
                .replace(Regex("""\s*\*\s*\d+"""), "")  // strip "* 10" resolution scaling

            cleaned.toDoubleOrNull()
        }
    }

    /**
     * Generate binary packet from a protocol function call.
     * Uses the JS protocol template convention:
     * - First byte: command ID from the JS (setUint8(0, 0xNN))
     * - Remaining bytes: parameters packed per the JS DataView calls
     *
     * Since we don't have a real JS runtime, we reconstruct the binary
     * by re-reading the JS protocol script and matching the pack function.
     */
    private fun generateBinaryPacket(funcName: String, args: List<Double>): ByteArray {
        val script = scriptManager.loadProtocolScript(activeProtocol!!)
            ?: return byteArrayOf()

        // Extract the command byte (0xNN) from setUint8(0, 0xNN)
        val cmdByteRegex = Regex(
            """pack\w+Request[^}]*?setUint8\(0,\s*(0x[0-9A-Fa-f]+|\d+)"""
        )
        val cmdMatch = cmdByteRegex.find(script)
        // We need to find the right one for this specific function
        // Look for the command byte within the specific function body

        // Simpler approach: find the function body, then extract the cmd byte
        val funcBodyRegex = Regex(
            """$funcName\s*:\s*function[^{]*\{([\s\S]*?)\n\s*\},"""
        )
        val funcBodyMatch = funcBodyRegex.find(script)
        val funcBody = funcBodyMatch?.groupValues?.get(1) ?: ""

        // Extract command byte
        val cmdRegex = Regex("""setUint8\(0,\s*(0x[0-9A-Fa-f]+|\d+)\)""")
        val cmdMatch2 = cmdRegex.find(funcBody)
        val cmdByte = if (cmdMatch2 != null) {
            val raw = cmdMatch2.groupValues[1]
            if (raw.startsWith("0x", ignoreCase = true)) raw.removePrefix("0x").removePrefix("0X").toInt(16).toByte()
            else raw.toInt().toByte()
        } else {
            0x00.toByte()
        }

        // Now build the packet by mimicking what the JS DataView writes
        // We'll collect all setUint8/setInt8/setUint16/setInt16/setFloat32 calls
        val writes = mutableListOf<ByteWrite>()
        val writeRegex = Regex(
            """(setUint8|setInt8|setUint16|setInt16|setFloat32)\((\d+),\s*([^)]+)\)"""
        )
        writeRegex.findAll(funcBody).forEach { wMatch ->
            val method = wMatch.groupValues[1]
            val offset = wMatch.groupValues[2].toInt()
            val valueExpr = wMatch.groupValues[3].trim()
            writes.add(ByteWrite(method, offset, valueExpr))
        }

        // Sort by offset and build the byte array
        if (writes.isEmpty()) return byteArrayOf(cmdByte)

        val maxOffset = writes.maxOf { w ->
            w.offset + when (w.method) {
                "setUint8", "setInt8" -> 1
                "setUint16", "setInt16" -> 2
                "setFloat32" -> 4
                else -> 1
            }
        }

        val buffer = ByteArray(maxOffset)
        writes.forEach { w ->
            val value = evaluateValueExpr(w.valueExpr, args)
            when (w.method) {
                "setUint8" -> buffer[w.offset] = value.toInt().toByte()
                "setInt8" -> buffer[w.offset] = value.toInt().toByte()
                "setUint16" -> {
                    val v = value.toInt()
                    buffer[w.offset] = (v and 0xFF).toByte()
                    buffer[w.offset + 1] = ((v shr 8) and 0xFF).toByte()
                }
                "setInt16" -> {
                    val v = value.toInt()
                    buffer[w.offset] = (v and 0xFF).toByte()
                    buffer[w.offset + 1] = ((v shr 8) and 0xFF).toByte()
                }
                "setFloat32" -> {
                    val bits = java.lang.Float.floatToIntBits(value.toFloat())
                    buffer[w.offset] = (bits and 0xFF).toByte()
                    buffer[w.offset + 1] = ((bits shr 8) and 0xFF).toByte()
                    buffer[w.offset + 2] = ((bits shr 16) and 0xFF).toByte()
                    buffer[w.offset + 3] = ((bits shr 24) and 0xFF).toByte()
                }
            }
        }

        return buffer
    }

    /**
     * Evaluate a value expression from the JS protocol script.
     * Replaces parameter references with actual arg values and computes the result.
     */
    private fun evaluateValueExpr(expr: String, args: List<Double>): Double {
        var result = expr

        // Replace common JS expressions
        // "speed" → args[0], "angle" → args[0] or args[1], etc.
        val paramNames = listOf(
            "speed", "leftSpeed", "rightSpeed", "angle", "time", "duration",
            "altitude", "lat", "lng", "tolerance", "patrolId", "loops",
            "brakeTime", "mode", "resolution", "patrolId", "vx", "vy", "vz",
            "yawRate", "roll", "pitch", "yaw", "action", "steps", "stepLength",
            "direction", "count", "hand", "pattern", "arms", "poseId", "programId",
            "stepIndex", "dwellMs", "jointId", "position", "force", "x", "y", "z",
            "pan", "tilt"
        )

        // Map parameter names to argument indices
        var argIdx = 0
        paramNames.forEach { name ->
            if (result.contains(name)) {
                val value = if (argIdx < args.size) args[argIdx] else 0.0
                result = result.replace(name, value.toString())
                argIdx++
            }
        }

        // Handle Math.round(xxx * 10)
        result = result.replace(Regex("""Math\.round\(([^)]+)\)""")) { match ->
            // Simple eval of the inner expression
            try {
                Math.round(evaluateSimpleExpr(match.groupValues[1])).toString()
            } catch (e: Exception) { "0" }
        }

        // Handle bit shifts: (xxx & 0xFFFF), (xxx >> 16)
        result = result.replace(Regex("""\(([^)]+)\s*&\s*0xFFFF\)""")) { match ->
            try { (evaluateSimpleExpr(match.groupValues[1]).toInt() and 0xFFFF).toString() }
            catch (e: Exception) { "0" }
        }
        result = result.replace(Regex("""\(([^)]+)\s*>>\s*(\d+)\)""")) { match ->
            try { (evaluateSimpleExpr(match.groupValues[1]).toInt() shr match.groupValues[2].toInt()).toString() }
            catch (e: Exception) { "0" }
        }

        // Try to evaluate the final expression
        return try {
            evaluateSimpleExpr(result)
        } catch (e: Exception) {
            0.0
        }
    }

    /** Evaluate simple arithmetic expressions (numbers, +, -, *, /) */
    private fun evaluateSimpleExpr(expr: String): Double {
        val cleaned = expr.trim()
            .replace(Regex("""0x([0-9A-Fa-f]+)""")) { it.groupValues[1].toInt(16).toString() }

        // Very simple: if it's just a number, return it
        return cleaned.trim().toDoubleOrNull() ?: 0.0
    }

    // ── Channel Send ──────────────────────────────────────────

    /**
     * Send binary protocol data through the robot channel (BT/USB).
     * Always logs the rawdata hex regardless of connection status.
     */
    private fun sendViaChannel(data: ByteArray, execId: Int) {
        val hex = data.joinToString(" ") { "%02X".format(it) }

        // Always log the rawdata — useful for testing without hardware
        Log.i(TAG, ">>> RAWDATA #$execId: [${data.size}B] $hex")

        if (!channel.isConnected()) {
            Log.w(TAG, ">>> RAWDATA #$execId: Channel NOT connected - data NOT sent to robot (but logged above)")
            return
        }

        Thread {
            try {
                val command = RobotCommand.RawData(data)
                val success = runBlocking { channel.send(command) }
                if (success) {
                    Log.i(TAG, ">>> RAWDATA #$execId: Sent ${data.size}B via channel OK")
                } else {
                    Log.e(TAG, ">>> RAWDATA #$execId: Channel send FAILED")
                }
            } catch (e: Exception) {
                Log.e(TAG, ">>> RAWDATA #$execId: Channel send exception", e)
            }
        }.start()
    }

    // ── Status ────────────────────────────────────────────────

    fun getSandboxStatus(): String {
        return buildString {
            append("Sandbox: ${if (isInitialized.get()) "Ready" else "Not initialized"}\n")
            append("Protocol: ${activeProtocol ?: "None loaded"}\n")
            append("Functions: ${protocolFunctions.size}\n")
            append("Executions: $executionCount\n")
            append("Channel: ${if (channel.isConnected()) "Connected" else "Disconnected"}")
        }
    }
}

// ── Data classes ──────────────────────────────────────────────

/** Parsed protocol function definition */
data class ProtocolFunctionDef(
    val name: String,
    val params: List<String>,
    var description: String = ""
)

/** Represents a DataView write operation from the JS protocol script */
data class ByteWrite(
    val method: String,  // setUint8, setInt16, etc.
    val offset: Int,
    val valueExpr: String
)
