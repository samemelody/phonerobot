package com.phonerobot.app.robot

import android.util.Log
import org.mozilla.javascript.*
import java.io.InputStreamReader

/**
 * JavaScript Sandbox for robot protocol execution using Rhino.
 *
 * Workflow:
 * 1. loadProtocol(filename) - Load JS protocol file into Rhino
 * 2. executeScript(jsCode) - Execute AI-generated JS code
 * 3. JS calls protocol.pack*() functions → returns Uint8Array
 * 4. Binary result is automatically sent via RobotChannel (BT/USB)
 */
class QuickJSSandbox(
    private val channel: RobotChannel,
    private val scriptManager: JsScriptManager,
    private val enableDetailedLogs: Boolean = true,
) {
    companion object {
        private const val TAG = "QuickJSSandbox"
        private const val MAX_SCRIPT_LENGTH = 8192
    }

    // Rhino context and scope
    private var context: Context? = null
    private var scope: Scriptable? = null
    private var protocolObject: Scriptable? = null

    // State
    private var activeProtocol: String? = null

    // ── Lifecycle ─────────────────────────────────────────────

    fun initialize(): Boolean {
        return try {
            context = Context.enter()
            context?.optimizationLevel = -1 // -1 for Android compatibility
            scope = context?.initStandardObjects()

            // Add console object for logging
            addConsoleObject()

            Log.i(TAG, "Rhino sandbox initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sandbox init failed", e)
            false
        }
    }

    /**
     * Add a console object to the Rhino scope
     * Supports: console.log(), console.error(), console.warn()
     */
    private fun addConsoleObject() {
        val consoleObj = Context.getCurrentContext().newObject(scope)

        // console.log() function
        val logFunction = object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any? {
                val message = args?.joinToString(" ") { 
                    if (it is NativeJavaObject) it.unwrap().toString() else it.toString() 
                } ?: ""
                Log.i("JS_Console", message)
                return Undefined.instance
            }
        }

        // console.error() function
        val errorFunction = object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any? {
                val message = args?.joinToString(" ") { 
                    if (it is NativeJavaObject) it.unwrap().toString() else it.toString() 
                } ?: ""
                Log.e("JS_Console", message)
                return Undefined.instance
            }
        }

        // console.warn() function
        val warnFunction = object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any? {
                val message = args?.joinToString(" ") { 
                    if (it is NativeJavaObject) it.unwrap().toString() else it.toString() 
                } ?: ""
                Log.w("JS_Console", message)
                return Undefined.instance
            }
        }

        ScriptableObject.putProperty(consoleObj, "log", logFunction)
        ScriptableObject.putProperty(consoleObj, "error", errorFunction)
        ScriptableObject.putProperty(consoleObj, "warn", warnFunction)

        scope?.put("console", scope, consoleObj)
        Log.d(TAG, "Added console object to Rhino scope")
    }

    fun cleanup() {
        activeProtocol = null
        protocolObject = null

        try {
            Context.exit()
        } catch (e: Exception) {
            Log.w(TAG, "Error exiting Rhino context", e)
        }

        context = null
        scope = null

        Log.i(TAG, "Sandbox cleaned up")
    }

    fun isReady(): Boolean = context != null && scope != null

    // ── Protocol Loading ──────────────────────────────────────

    /**
     * Load a protocol template into the sandbox.
     * Executes the JS file in Rhino to get the protocol object.
     */
    fun loadProtocol(filename: String): String {
        if (!isReady()) {
            return "Error: Sandbox not initialized"
        }

        val script = scriptManager.loadProtocolScript(filename)
        if (script == null) {
            val err = "Protocol '$filename' not found"
            Log.e(TAG, err)
            return "Error: $err"
        }

        return try {
            // Execute the protocol JS to define the 'protocol' object
            context?.evaluateString(scope, script, filename, 1, null)

            // Get the protocol object from the scope
            val protocol = scope?.get("protocol", scope!!)
            if (protocol == null || protocol == Scriptable.NOT_FOUND) {
                "Error: 'protocol' object not defined in $filename"
            } else {
                protocolObject = protocol as Scriptable

                activeProtocol = filename

                // Get list of functions from the protocol object
                val functions = getProtocolFunctionNames()
                val funcList = functions.joinToString("\n") { "  - protocol.$it()" }

                Log.i(TAG, "Loaded protocol: $filename with ${functions.size} functions")
                "Loaded: $filename\nAvailable commands:\n$funcList"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading protocol: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Get list of function names from the protocol object
     */
    private fun getProtocolFunctionNames(): List<String> {
        val functions = mutableListOf<String>()
        val ids = protocolObject?.ids

        if (ids is Array<*>) {
            ids.forEach { id ->
                if (id is String) {
                    functions.add(id)
                }
            }
        }

        return functions.sorted()
    }

    /** Get the currently active protocol filename */
    fun getActiveProtocol(): String? = activeProtocol

    // ── Script Execution ──────────────────────────────────────

    /**
     * Execute JavaScript code in the sandbox.
     * The JS code should call protocol.pack*() functions.
     *
     * @param javascriptCode AI-generated JS code
     * @return Execution result description (String) or ByteArray for binary data
     */
    fun executeScript(javascriptCode: String): Any {
        if (!isReady()) {
            return "Error: Sandbox not initialized"
        }

        if (javascriptCode.length > MAX_SCRIPT_LENGTH) {
            return "Error: Script too long (${javascriptCode.length} chars)"
        }

        // Rhino Context is thread-local, so we need to enter a context for this thread
        val threadContext = Context.enter()
        threadContext.optimizationLevel = -1  // -1 for Android compatibility

        return try {
            // Use the thread-local context to evaluate the script
            val result = threadContext.evaluateString(
                scope,
                javascriptCode,
                "AI_Generated_Script",
                1,
                null
            )

            Log.i(TAG, "Executed JS: ${javascriptCode.take(80)}...")
            Log.d(TAG, "Result type: ${result?.javaClass?.simpleName}")

            // Process the result
            processExecutionResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "Execution failed: ${e.message}", e)
            "Error: ${e.message}"
        } finally {
            // Always exit the context to avoid thread-local leaks
            Context.exit()
        }
    }

    /**
     * Process the result of JS execution.
     * If result is Uint8Array (NativeUint8Array), convert to ByteArray.
     */
    private fun processExecutionResult(result: Any?): Any {
        if (result == null || result == Undefined.instance) {
            // No return value is OK for console.log() etc.
            return "Script executed successfully (no return value)"
        }

        // Check if result is a Uint8Array (Rhino's NativeUint8Array)
        if (result is NativeArray || result.javaClass.simpleName == "NativeUint8Array") {
            val byteArray = convertUint8ArrayToByteArray(result)
            Log.i(TAG, "Got Uint8Array result: ${byteArray.size} bytes")

            // Auto-send via channel
            if (byteArray.isNotEmpty()) {
                sendViaChannel(byteArray)
            }

            return byteArray
        }

        // If result is a number, string, etc.
        return result.toString()
    }

    /**
     * Convert Rhino's NativeUint8Array to Kotlin ByteArray
     */
    private fun convertUint8ArrayToByteArray(uint8Array: Any): ByteArray {
        return try {
            // Rhino's NativeUint8Array can be accessed via Scriptable
            val scriptable = uint8Array as Scriptable
            val length = ScriptableObject.getProperty(scriptable, "length") as Number
            val size = length.toInt()

            val bytes = ByteArray(size)
            for (i in 0 until size) {
                val value = ScriptableObject.getProperty(scriptable, i)
                bytes[i] = when (value) {
                    is Number -> value.toByte()
                    else -> 0
                }
            }
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Uint8Array", e)
            byteArrayOf()
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

    // ── Channel Send ──────────────────────────────────────────

    /**
     * Send binary protocol data through the robot channel (BT/USB).
     */
    private fun sendViaChannel(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }

        // Always log the rawdata
        Log.i(TAG, ">>> RAWDATA: [${data.size}B] $hex")

        if (!channel.isConnected()) {
            Log.w(TAG, ">>> RAWDATA: Channel NOT connected - data NOT sent to robot (but logged above)")
            return
        }

        try {
            val command = RobotCommand.RawData(data)
            val success = kotlinx.coroutines.runBlocking { channel.send(command) }
            if (success) {
                Log.i(TAG, ">>> RAWDATA: Sent ${data.size}B via channel OK")
            } else {
                Log.e(TAG, ">>> RAWDATA: Channel send FAILED")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> RAWDATA: Channel send exception", e)
        }
    }

    // ── Status ────────────────────────────────────────────────

    fun getSandboxStatus(): String {
        return buildString {
            append("Sandbox: ${if (isReady()) "Ready (Rhino)" else "Not initialized"}\n")
            append("Protocol: ${activeProtocol ?: "None loaded"}\n")
            append("Channel: ${if (channel.isConnected()) "Connected" else "Disconnected"}")
        }
    }

    // ── Testing ────────────────────────────────────────

    /**
     * Test the sandbox with a simple command.
     * Call this to verify the sandbox is working correctly.
     *
     * @return Test results as a formatted string
     */
    fun testSandbox(): String {
        val results = StringBuilder()
        results.append("=== JS Sandbox Test ===\n\n")

        Log.i(TAG, "========== JS SANDBOX TEST START ==========")

        // Test 1: Initialize
        results.append("1. Initializing sandbox...\n")
        val initOk = initialize()
        results.append("   Result: ${if (initOk) "✓ OK" else "✗ FAILED"}\n\n")

        Log.i(TAG, "Test 1 - Initialize: ${if (initOk) "OK" else "FAILED"}")

        if (!initOk) {
            results.append("Cannot continue - sandbox init failed")
            Log.e(TAG, "Test FAILED: Sandbox init failed")
            Log.i(TAG, "========== JS SANDBOX TEST END ==========")
            return results.toString()
        }

        // Test 2: Load protocol
        results.append("2. Loading protocol: rover_protocol.js...\n")
        val loadResult = loadProtocol("rover_protocol.js")
        results.append("   Result: $loadResult\n\n")

        Log.i(TAG, "Test 2 - Load protocol: $loadResult")

        if (activeProtocol == null) {
            results.append("Cannot continue - protocol load failed")
            cleanup()
            Log.e(TAG, "Test FAILED: Protocol load failed")
            Log.i(TAG, "========== JS SANDBOX TEST END ==========")
            return results.toString()
        }

        // Test 3: Execute a simple command
        results.append("3. Executing: protocol.packStopRequest()...\n")
        val execResult = executeScript("return protocol.packStopRequest();")
        results.append("   Result: $execResult\n")
        results.append("   Type: ${execResult.javaClass.simpleName}\n\n")

        Log.i(TAG, "Test 3 - Execute stop: result type = ${execResult.javaClass.simpleName}")
        if (execResult is ByteArray) {
            val hex = execResult.joinToString(" ") { "%02X".format(it) }
            results.append("   ✓ Got ByteArray (${execResult.size} bytes)\n")
            results.append("   Hex: $hex\n\n")
            Log.i(TAG, "Test 3 - Got ${execResult.size} bytes: $hex")
        }

        // Test 4: Execute drive command
        results.append("4. Executing: protocol.packDriveRequest(100, 0, 50)...\n")
        val driveResult = executeScript("return protocol.packDriveRequest(100, 0, 50);")
        results.append("   Result: $driveResult\n")
        results.append("   Type: ${driveResult.javaClass.simpleName}\n\n")

        Log.i(TAG, "Test 4 - Execute drive: result type = ${driveResult.javaClass.simpleName}")
        if (driveResult is ByteArray) {
            val hex = driveResult.joinToString(" ") { "%02X".format(it) }
            results.append("   ✓ Got ByteArray (${driveResult.size} bytes)\n")
            results.append("   Hex: $hex\n\n")
            Log.i(TAG, "Test 4 - Got ${driveResult.size} bytes: $hex")
        }

        // Cleanup
        cleanup()
        results.append("5. Cleanup complete\n")
        results.append("\n=== Test Complete ===\n")

        Log.i(TAG, "Test 5 - Cleanup complete")
        Log.i(TAG, "========== JS SANDBOX TEST END ==========")

        return results.toString()
    }
}
