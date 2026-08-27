package com.phonerobot.app.robot

import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.*
import java.io.InputStreamReader
import java.util.concurrent.Executors

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
    private val channelProvider: () -> RobotChannel,
    private val scriptManager: JsScriptManager,
    private val enableDetailedLogs: Boolean = true,
) {
    companion object {
        private const val TAG = "QuickJSSandbox"
        private const val MAX_SCRIPT_LENGTH = 8192
        private const val SCRIPT_TIMEOUT_MS = 5_000L
        private const val INSTRUCTION_CHECK_INTERVAL = 10_000

        /**
         * Abort a script when the JVM heap drops below this watermark.
         * Rhino has no per-script memory quota, but the instruction observer fires
         * regularly during interpreted execution — checking free heap there bounds
         * allocation-heavy scripts (e.g. a loop pushing into an array) before they
         * OOM the app, same abort path as the time budget.
         */
        private const val MIN_FREE_HEAP_BYTES = 32L * 1024 * 1024
    }

    /** Get the current active channel (may change between BLE/USB at runtime) */
    private val channel: RobotChannel get() = channelProvider()

    /**
     * Dedicated single thread for ALL Rhino operations.
     * Rhino contexts and shared scope are not thread-safe; serializing every
     * sandbox operation onto one thread eliminates the cross-thread races.
     */
    private val jsDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "RhinoJS") }
        .asCoroutineDispatcher()

    /** Run a block on the dedicated JS thread and block the caller until done. */
    private fun <T> runOnJsThread(block: () -> T): T =
        runBlocking { withContext(jsDispatcher) { block() } }

    /**
     * Factory producing hardened Rhino contexts:
     * - instruction observer aborts scripts running past SCRIPT_TIMEOUT_MS
     *   (works because optimizationLevel = -1 forces the interpreter)
     * - same observer aborts when free heap falls below MIN_FREE_HEAP_BYTES,
     *   bounding allocation-heavy scripts that would otherwise OOM the app
     * - ClassShutter denies ALL Java class access from JS; only injected host
     *   objects (console, protocol, Uint8Array polyfill) are reachable
     * scriptStartMs is written before each evaluation on the single JS thread.
     */
    private val jsContextFactory = object : ContextFactory() {
        @Volatile
        var scriptStartMs: Long = 0L

        override fun makeContext(): Context =
            object : Context() {
                override fun observeInstructionCount(instructionCount: Int) {
                    val startMs = scriptStartMs
                    if (startMs == 0L) return
                    val elapsedMs = System.currentTimeMillis() - startMs
                    if (elapsedMs > SCRIPT_TIMEOUT_MS) {
                        throw EvaluatorException(
                            "Script execution timed out after ${elapsedMs}ms",
                            "<sandbox>",
                            0,
                        )
                    }
                    // "How much can still be allocated before hitting maxMemory" —
                    // unlike freeMemory(), this is not depressed right after GC or
                    // before the heap commits more pages
                    val runtime = Runtime.getRuntime()
                    val allocatable = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    if (allocatable < MIN_FREE_HEAP_BYTES) {
                        throw EvaluatorException(
                            "Script aborted: free heap low (${allocatable / (1024 * 1024)}MB)",
                            "<sandbox>",
                            0,
                        )
                    }
                }
            }.also { cx ->
                cx.optimizationLevel = -1
                cx.setInstructionObserverThreshold(INSTRUCTION_CHECK_INTERVAL)
                cx.setClassShutter { false }
            }
    }

    // Rhino context and scope
    private var context: Context? = null
    private var scope: Scriptable? = null
    private var protocolObject: Scriptable? = null

    // State
    private var activeProtocol: String? = null

    // ── Lifecycle ─────────────────────────────────────────────

    fun initialize(): Boolean = runOnJsThread { initializeInternal() }

    private fun initializeInternal(): Boolean {
        return try {
            context = jsContextFactory.enterContext()
            context?.optimizationLevel = -1 // -1 for Android compatibility
            scope = context?.initStandardObjects()

            // Add console object for logging
            addConsoleObject()

            // Add Uint8Array polyfill (Rhino doesn't have typed arrays)
            addUint8ArrayPolyfill()

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

    /**
     * Add Uint8Array polyfill to Rhino scope.
     * Rhino doesn't support ES6 typed arrays, so we provide a JS-based polyfill
     * that creates a NativeArray-like object compatible with our processExecutionResult().
     */
    private fun addUint8ArrayPolyfill() {
        val polyfill = """
            var Uint8Array = (function() {
                function Uint8Array(arr) {
                    if (typeof arr === 'number') {
                        this.length = arr;
                        for (var i = 0; i < arr; i++) this[i] = 0;
                    } else if (arr && typeof arr.length === 'number') {
                        this.length = arr.length;
                        for (var i = 0; i < arr.length; i++) this[i] = arr[i] & 0xFF;
                    } else {
                        this.length = 0;
                    }
                }
                Uint8Array.prototype.set = function(arr, offset) {
                    offset = offset || 0;
                    for (var i = 0; i < arr.length; i++) {
                        this[offset + i] = arr[i] & 0xFF;
                    }
                };
                Uint8Array.prototype.slice = function(start, end) {
                    start = start || 0;
                    end = end || this.length;
                    var result = [];
                    for (var i = start; i < end; i++) result.push(this[i]);
                    return new Uint8Array(result);
                };
                return Uint8Array;
            })();
        """.trimIndent()

        try {
            val cx = Context.getCurrentContext()
            cx.evaluateString(scope, polyfill, "Uint8Array_polyfill", 1, null)
            Log.i(TAG, "Added Uint8Array polyfill to Rhino scope")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Uint8Array polyfill", e)
        }
    }

    fun cleanup() = runOnJsThread { cleanupInternal() }

    private fun cleanupInternal() {
        activeProtocol = null
        protocolObject = null

        try {
            Context.exit()
        } catch (e: Exception) {
            Log.w(TAG, "Error exiting Rhino context", e)
        }

        context = null
        scope = null
        jsDispatcher.close()

        Log.i(TAG, "Sandbox cleaned up")
    }

    fun isReady(): Boolean = context != null && scope != null

    // ── Protocol Loading ──────────────────────────────────────

    /**
     * Load a protocol template into the sandbox.
     * Executes the JS file in Rhino to get the protocol object.
     */
    fun loadProtocol(filename: String): String = runOnJsThread { loadProtocolInternal(filename) }

    private fun loadProtocolInternal(filename: String): String {
        if (!isReady()) {
            return "Error: Sandbox not initialized"
        }

        val script = scriptManager.loadProtocolScript(filename)
        if (script == null) {
            val err = "Protocol '$filename' not found"
            Log.e(TAG, err)
            return "Error: $err"
        }

        // Rhino Context is thread-local — must enter for current thread
        val threadContext = jsContextFactory.enterContext()
        threadContext.optimizationLevel = -1

        return try {
            // Execute the protocol JS to define the 'protocol' object
            jsContextFactory.scriptStartMs = System.currentTimeMillis()
            threadContext.evaluateString(scope, script, filename, 1, null)
            jsContextFactory.scriptStartMs = 0L

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
        } finally {
            jsContextFactory.scriptStartMs = 0L
            Context.exit()
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
    fun executeScript(javascriptCode: String): Any = runOnJsThread { executeScriptInternal(javascriptCode) }

    private fun executeScriptInternal(javascriptCode: String): Any {
        Log.i(TAG, "=== executeScript START ===")
        Log.i(TAG, "Sandbox ready: ${isReady()}, Active protocol: $activeProtocol")
        Log.i(TAG, "Input JS (${javascriptCode.length} chars): $javascriptCode")

        if (!isReady()) {
            val err = "Error: Sandbox not initialized — call initialize() first"
            Log.e(TAG, err)
            return err
        }

        if (javascriptCode.length > MAX_SCRIPT_LENGTH) {
            val err = "Error: Script too long (${javascriptCode.length} chars, max $MAX_SCRIPT_LENGTH)"
            Log.e(TAG, err)
            return err
        }

        // Check protocol is loaded if code references 'protocol'
        if (javascriptCode.contains("protocol") && protocolObject == null) {
            val err = "Error: JS code references 'protocol' but no protocol is loaded. Call loadProtocol() first."
            Log.e(TAG, err)
            Log.e(TAG, "Current scope has these top-level vars: ${scope?.ids?.joinToString(", ")}")
            return err
        }

        // Sanitize AI-generated JS: fix common mistakes
        var code = javascriptCode

        // Fix invalid hex literals like "50x32D8" → "0x32D8" (AI often prepends digits before 0x)
        val invalidHexRegex = Regex("""\b(\d+)(x[0-9A-Fa-f]{2,})\b""")
        if (invalidHexRegex.containsMatchIn(code)) {
            val fixed = invalidHexRegex.replace(code) { match ->
                val hexPart = match.groupValues[2]  // e.g. "x32D8"
                Log.w(TAG, "Auto-fix: invalid hex literal '${match.value}' → '0${hexPart}'")
                "0${hexPart}"  // e.g. "0x32D8"
            }
            Log.i(TAG, "Sanitized JS hex literals: '$code' → '$fixed'")
            code = fixed
        }

        // Wrap in IIFE if code uses 'return' (Rhino only allows return inside functions)
        val wrappedCode = if (code.trimStart().startsWith("return")) {
            Log.d(TAG, "Wrapping JS in IIFE (code starts with 'return')")
            "(function() { $code })()"
        } else {
            code
        }

        // Rhino Context is thread-local, so we need to enter a context for this thread
        val threadContext = jsContextFactory.enterContext()
        threadContext.optimizationLevel = -1  // -1 for Android compatibility

        return try {
            jsContextFactory.scriptStartMs = System.currentTimeMillis()
            // Use the thread-local context to evaluate the script
            val result = threadContext.evaluateString(
                scope,
                wrappedCode,
                "AI_Generated_Script",
                1,
                null
            )
            jsContextFactory.scriptStartMs = 0L

            Log.i(TAG, "Execution OK — result type: ${result?.javaClass?.simpleName}")
            Log.d(TAG, "Result value: ${result.toString().take(200)}")

            // Process the result
            val processed = processExecutionResult(result)
            Log.i(TAG, "Processed result type: ${processed.javaClass.simpleName}")
            Log.i(TAG, "=== executeScript END (success) ===")
            processed

        } catch (e: org.mozilla.javascript.EcmaError) {
            Log.e(TAG, "=== executeScript END (EcmaError) ===")
            Log.e(TAG, "Rhino EcmaError: ${e.javaClass.simpleName} — ${e.message}")
            Log.e(TAG, "Source: ${e.sourceName}:${e.lineNumber}:${e.columnNumber}")
            Log.e(TAG, "Failed JS code: $javascriptCode")

            // Log what's available in scope for debugging
            val scopeVars = scope?.ids?.filterIsInstance<String>()?.joinToString(", ")
            Log.e(TAG, "Scope variables: $scopeVars")
            Log.e(TAG, "Protocol object present: ${protocolObject != null}")

            "Error: ${e.message} (at ${e.sourceName}:${e.lineNumber})"
        } catch (e: EvaluatorException) {
            jsContextFactory.scriptStartMs = 0L
            val message = e.message ?: ""
            if (message.startsWith("Script execution timed out")) {
                Log.e(TAG, "=== executeScript END (TIMEOUT after ${SCRIPT_TIMEOUT_MS}ms budget) ===")
                Log.e(TAG, "Timed-out JS code: $javascriptCode")
                "Error: $message"
            } else if (message.startsWith("Script aborted:")) {
                Log.e(TAG, "=== executeScript END (MEMORY_LIMIT — free heap below ${MIN_FREE_HEAP_BYTES / (1024 * 1024)}MB) ===")
                Log.e(TAG, "Aborted JS code: $javascriptCode")
                "Error: $message"
            } else {
                Log.e(TAG, "=== executeScript END (EvaluatorException) ===")
                Log.e(TAG, "Rhino EvaluatorException: ${e.message}")
                Log.e(TAG, "Source: ${e.sourceName}:${e.lineNumber}:${e.columnNumber}")
                Log.e(TAG, "Failed JS code: $javascriptCode")

                "Error: Syntax error — ${e.message} (at ${e.sourceName}:${e.lineNumber})"
            }
        } catch (e: Exception) {
            jsContextFactory.scriptStartMs = 0L
            Log.e(TAG, "=== executeScript END (Exception) ===")
            Log.e(TAG, "Execution failed: ${e.javaClass.simpleName} — ${e.message}", e)
            Log.e(TAG, "Failed JS code: $javascriptCode")

            "Error: ${e.javaClass.simpleName} — ${e.message}"
        } finally {
            jsContextFactory.scriptStartMs = 0L
            // Always exit the context to avoid thread-local leaks
            Context.exit()
        }
    }

    /**
     * Process the result of JS execution.
     * If result is a Uint8Array (NativeUint8Array or polyfill), convert to ByteArray.
     */
    private fun processExecutionResult(result: Any?): Any {
        if (result == null || result == Undefined.instance) {
            // No return value is OK for console.log() etc.
            return "Script executed successfully (no return value)"
        }

        // Check if result is a Uint8Array — either Rhino's native or our polyfill
        val isNativeUint8Array = result is NativeArray || result.javaClass.simpleName == "NativeUint8Array"
        val isPolyfillUint8Array = result is Scriptable && !isNativeUint8Array && isUint8ArrayPolyfill(result)

        if (isNativeUint8Array || isPolyfillUint8Array) {
            val byteArray = convertUint8ArrayToByteArray(result)
            Log.i(TAG, "Got Uint8Array result: ${byteArray.size} bytes (source: ${if (isNativeUint8Array) "native" else "polyfill"})")

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
     * Check if a Rhino Scriptable is our Uint8Array polyfill instance.
     * The polyfill creates objects with numeric indices and a 'length' property,
     * and the constructor name is "Uint8Array".
     */
    private fun isUint8ArrayPolyfill(obj: Scriptable): Boolean {
        return try {
            // Check if it has a 'length' property that is a number
            val lengthProp = ScriptableObject.getProperty(obj, "length")
            if (lengthProp !is Number) return false

            val length = lengthProp.toInt()
            if (length <= 0) return false

            // Check if it has numeric indices (at least index 0)
            val firstElem = ScriptableObject.getProperty(obj, 0)
            if (firstElem == Scriptable.NOT_FOUND) return false

            // Check constructor name via toString or className
            val className = obj.javaClass.simpleName
            if (className == "Uint8Array") return true

            // For our polyfill, check if it looks like a byte array (all numeric indices are small ints)
            if (length > 0 && length <= 256) {
                val firstVal = when (firstElem) {
                    is Number -> firstElem.toInt()
                    else -> return false
                }
                // Byte values should be 0-255
                firstVal in 0..255
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
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
    fun executeSavedScript(scriptName: String): Any = runOnJsThread {
        val content = scriptManager.loadScript(scriptName)
        if (content != null) {
            executeScriptInternal(content)
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

    fun getSandboxStatus(): String = runOnJsThread {
        buildString {
            append("Sandbox: ${if (isReady()) "Ready (Rhino)" else "Not initialized"}\n")
            append("Protocol: ${activeProtocol ?: "None loaded"}\n")
            append("Channel: ${if (channel.isConnected()) "Connected" else "Disconnected"}")
        }
    }
}
