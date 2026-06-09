package com.phonerobot.app.ai

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.QuickJSSandbox
import org.mozilla.javascript.Context

/**
 * AI tool for robot protocol control.
 *
 * IMPROVED TOOL DESCRIPTIONS: Based on API_REFERENCE.md analysis:
 * - Tool descriptions must be ACTION-ORIENTED (tell AI WHEN to call)
 * - Parameter descriptions must be SPECIFIC (tell AI WHAT values to pass)
 * - Use ALL CAPS for mandatory actions to grab AI attention
 */
class FlexibleJavaScriptTool(
    private val jsSandbox: QuickJSSandbox,
    private val scriptManager: JsScriptManager,
) : ToolSet {

    @Tool(description = "MANDATORY FIRST CALL: Call this IMMEDIATELY when user mentions any robot, vehicle, drone, arm, or mechanical device. Returns available commands. Available filenames: 'rover_protocol.js' (default for UGV/rover), 'toy_car_protocol_core.js' (car), 'drone_protocol.js' (UAV), 'robot_arm_protocol.js' (arm), 'bipedal_robot_protocol.js' (humanoid). DEFAULT to 'rover_protocol.js' if unsure.")
    fun listProtocols(): String {
        val templates = scriptManager.listProtocolTemplates()
        val installed = scriptManager.listInstalledProtocols()

        // Find user-created protocols (not in the original templates)
        val templateNames = templates.keys
        val userProtocols = installed.filter { it !in templateNames }

        val sb = StringBuilder()
        if (templates.isNotEmpty()) {
            sb.append("Built-in protocols:\n")
            templates.entries.forEach { (file, desc) ->
                sb.append("  $file — $desc\n")
            }
        }
        if (userProtocols.isNotEmpty()) {
            sb.append("User-created protocols:\n")
            userProtocols.forEach { file ->
                sb.append("  $file\n")
            }
        }
        return if (sb.isEmpty()) "No protocols available" else sb.toString().trimEnd()
    }

    @Tool(description = "Call IMMEDIATELY when user gives a robot command (move, turn, stop, grab, rotate, drive, forward, backward, left, right). Loads protocol and returns available functions. DO NOT ask questions - just load the protocol and execute.")
    fun loadProtocol(
        @ToolParam(description = "Protocol filename. DEFAULT: 'rover_protocol.js' for most robots. Others: 'toy_car_protocol_core.js', 'drone_protocol.js', 'robot_arm_protocol.js', 'bipedal_robot_protocol.js'. If user says 'car' use 'toy_car_protocol_core.js', if 'drone' use 'drone_protocol.js'") filename: String
    ): String {
        return jsSandbox.loadProtocol(filename)
    }

    @Tool(description = "Execute JavaScript to generate and send robot command. Call IMMEDIATELY for ANY robot command (move, turn, stop, etc). The JS code MUST use protocol.packXxxRequest() functions. Binary result is AUTO-SENT to robot via USB/Bluetooth. DO NOT ask for confirmation - execute directly.")
    fun executeJavaScript(
        @ToolParam(description = "JavaScript code using protocol functions. Examples: 'return protocol.packDriveRequest(200, 0, 100);' for forward movement, 'return protocol.packRotateRequest(-90, 100);' for left turn, 'return protocol.packStopRequest();' for stop. Check loaded protocol for exact function names and parameters.") jsCode: String
    ): String {
        Log.i("FlexibleJS", "=== executeJavaScript START ===")
        Log.i("FlexibleJS", "Input JS code (${jsCode.length} chars): $jsCode")
        Log.i("FlexibleJS", "Sandbox ready: ${jsSandbox.isReady()}, Active protocol: ${jsSandbox.getActiveProtocol()}")

        if (!jsSandbox.isReady()) {
            val err = "✗ Sandbox not initialized — cannot execute JS. Try calling loadProtocol() first."
            Log.e("FlexibleJS", err)
            return err
        }

        if (jsSandbox.getActiveProtocol() == null) {
            val warn = "⚠ No protocol loaded — JS may fail if it references 'protocol' object. Call loadProtocol() first."
            Log.w("FlexibleJS", warn)
        }

        val result = jsSandbox.executeScript(jsCode)

        Log.i("FlexibleJS", "Result type: ${result?.javaClass?.simpleName}")
        Log.i("FlexibleJS", "Result value: ${result.toString().take(200)}")

        return when (result) {
            is ByteArray -> {
                val hex = result.joinToString(" ") { "%02X".format(it) }
                Log.i("FlexibleJS", "✓ ByteArray result: ${result.size} bytes [$hex]")
                "✓ Sent ${result.size} bytes to robot: [$hex]"
            }
            is String -> {
                if (result.startsWith("Error:")) {
                    Log.e("FlexibleJS", "✗ Execution FAILED: $result")
                    Log.e("FlexibleJS", "Failed JS code was: $jsCode")
                    "✗ $result\nHint: Call loadProtocol() first, then use readProtocol() to check available function names."
                } else {
                    Log.i("FlexibleJS", "Result: $result")
                    result
                }
            }
            else -> {
                Log.i("FlexibleJS", "Result: $result")
                result.toString()
            }
        }
    }

    @Tool(description = "ONLY call this if executeJavaScript() FAILS or returns unexpected results. Reads protocol script to debug function names, parameters, or byte packing issues. DO NOT call this for normal operation.")
    fun readProtocol(
        @ToolParam(description = "Protocol filename to read and debug") filename: String
    ): String {
        return scriptManager.loadProtocolScript(filename)
            ?: "Protocol '$filename' not found. Call listProtocols() first."
    }

    @Tool(description = "ONLY call this if you need to FIX a broken protocol. Writes a corrected version (original is preserved). After writing, you MUST call loadProtocol() with the new filename. DO NOT call this for normal operation.")
    fun writeProtocol(
        @ToolParam(description = "Filename for the fixed protocol, e.g. 'fixed_rover.js'. Choose a descriptive name that indicates what was fixed.") filename: String,
        @ToolParam(description = "Complete protocol script in JavaScript. Must define 'protocol' object with pack* functions using Uint8Array or DataView for binary packing. Include the FULL script, not just the fixed function. Use readProtocol() first to see the current script. IMPORTANT: Use 'var' not 'const'/'let' (Rhino ES5 only).") content: String
    ): String {
        // Validate that the content looks like a protocol script
        if (!content.contains("protocol") || !content.contains("pack")) {
            return "Error: Content does not appear to be a valid protocol script. " +
                "It must define a 'protocol' object with pack* functions."
        }

        // Check for ES6 syntax that Rhino doesn't support
        val es6Issues = mutableListOf<String>()
        if (content.contains(Regex("""\bconst\b"""))) es6Issues.add("'const' (use 'var')")
        if (content.contains(Regex("""\blet\b"""))) es6Issues.add("'let' (use 'var')")
        if (content.contains("=>")) es6Issues.add("arrow functions (use function(){})")
        if (content.contains("`")) es6Issues.add("template literals (use string concatenation)")
        if (es6Issues.isNotEmpty()) {
            return "Error: Script uses ES6 syntax not supported by Rhino: ${es6Issues.joinToString(", ")}. " +
                "Please rewrite using ES5 syntax only."
        }

        // Validate JS syntax with Rhino
        try {
            val cx = Context.enter()
            cx.optimizationLevel = -1
            try {
                cx.compileString(content, filename, 1, null)
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            return "Error: JavaScript syntax error — ${e.message}. Fix the syntax and try again."
        }

        // Ensure filename ends with .js
        val normalizedName = if (filename.endsWith(".js")) filename else "${filename}.js"

        val saved = scriptManager.saveProtocolScript(normalizedName, content)
        return if (saved) {
            "Protocol '$normalizedName' saved successfully (${content.length} chars). " +
                "Call loadProtocol('$normalizedName') to use it."
        } else {
            "Error: Failed to save protocol '$normalizedName'. File may already exist — use a different filename or call deleteProtocol() first."
        }
    }

    @Tool(description = "Delete a user-created protocol file. Use this to remove broken or unwanted protocols. CANNOT delete built-in templates. After deleting, you can re-create with writeProtocol() if needed.")
    fun deleteProtocol(
        @ToolParam(description = "Protocol filename to delete, e.g. 'toy_car_protocol_fixed.js'. Only user-created files can be deleted.") filename: String
    ): String {
        val normalizedName = if (filename.endsWith(".js")) filename else "${filename}.js"
        val deleted = scriptManager.deleteProtocol(normalizedName)
        return if (deleted) {
            "Deleted protocol '$normalizedName'. You can now recreate it if needed."
        } else {
            "Failed to delete '$normalizedName'. It may be a built-in template (cannot delete) or file not found."
        }
    }

    @Tool(description = "List user-saved JavaScript scripts. RARELY NEEDED - only call if user explicitly asks about saved scripts.")
    fun listScripts(): String {
        val scripts = scriptManager.listScripts()
        return if (scripts.isEmpty()) "No saved scripts" else "Scripts: ${scripts.joinToString(", ")}"
    }

    @Tool(description = "Test JavaScript code execution. Call this when user says 'test js' or wants to test/execute custom JavaScript code. Saves the JS code to a file and executes it. Returns execution result.")
    fun testJavaScript(
        @ToolParam(description = "JavaScript code to test. Can be any valid JavaScript code - calculations, logic, or any JS syntax. The code will be saved and executed in the sandbox environment.") jsCode: String
    ): String {
        return try {
            // Save JS code to file
            val scriptFile = scriptManager.saveScript(jsCode, "test_${System.currentTimeMillis()}")
            Log.i("FlexibleJavaScriptTool", "Saved test JS to: ${scriptFile.name}")

            // Log the JS code being tested
            Log.i("FlexibleJavaScriptTool", "Testing JS code: ${jsCode.take(100)}...")

            // Execute the JavaScript code
            val result = jsSandbox.executeScript(jsCode)

            // Log the result
            Log.i("FlexibleJavaScriptTool", "JS execution result: $result")

            // Return the result
            when (result) {
                is ByteArray -> {
                    val hex = result.joinToString(" ") { "%02X".format(it) }
                    "✓ Executed successfully!\nSaved to: ${scriptFile.name}\nResult: ${result.size} bytes: [$hex]"
                }
                else -> "✓ Executed successfully!\nSaved to: ${scriptFile.name}\nResult: ${result.toString()}"
            }
        } catch (e: Exception) {
            Log.e("FlexibleJavaScriptTool", "JS test error", e)
            "✗ Execution error: ${e.message}"
        }
    }
}
