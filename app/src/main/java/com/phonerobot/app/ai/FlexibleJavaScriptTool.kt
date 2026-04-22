package com.phonerobot.app.ai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.phonerobot.app.robot.JsScriptManager
import com.phonerobot.app.robot.QuickJSSandbox

/**
 * AI tool for robot protocol control.
 *
 * Workflow:
 * 1. AI calls loadProtocol(filename) to load the right robot protocol template
 * 2. AI calls executeJavaScript(code) to generate protocol binary data
 * 3. Binary data is auto-sent via Bluetooth/USB channel
 *
 * Example conversation:
 *   User: "You are driving a toy car"
 *   AI:  → loadProtocol("toy_car_protocol_core.js")
 *   User: "Go forward"
 *   AI:  → executeJavaScript("return protocol.packForwardRequest(50, 1000);")
 *   User: "Stop"
 *   AI:  → executeJavaScript("return protocol.packStopRequest();")
 */
class FlexibleJavaScriptTool(
    private val jsSandbox: QuickJSSandbox,
    private val scriptManager: JsScriptManager,
) : ToolSet {

    @Tool(description = "List available robot protocol templates and any user-created protocol scripts. Call this first to see which robot types are supported and their filenames.")
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

    @Tool(description = "Load a robot protocol template into the sandbox. This must be called before executing any robot commands. Returns the list of available protocol functions and their parameters.")
    fun loadProtocol(
        @ToolParam(description = "Protocol filename from listProtocols, e.g. 'toy_car_protocol_core.js', 'rover_protocol.js', 'drone_protocol.js', 'robot_arm_protocol.js', 'bipedal_robot_protocol.js'") filename: String
    ): String {
        return jsSandbox.loadProtocol(filename)
    }

    @Tool(description = "Execute JavaScript code to generate and send a robot command. Use protocol.packXxxRequest() functions from the loaded protocol. The binary result is automatically sent via Bluetooth/USB. Returns the execution result.")
    fun executeJavaScript(
        @ToolParam(description = "JavaScript code using protocol functions, e.g. 'return protocol.packForwardRequest(50, 1000);' or 'return protocol.packStopRequest();'") jsCode: String
    ): String {
        val result = jsSandbox.executeScript(jsCode)
        return when (result) {
            is ByteArray -> {
                val hex = result.joinToString(" ") { "%02X".format(it) }
                "Sent ${result.size} bytes via channel: [$hex]"
            }
            else -> result.toString()
        }
    }

    @Tool(description = "Read the full protocol script to understand all available commands, parameters, and valid ranges. Use this if you need details about a specific function.")
    fun readProtocol(
        @ToolParam(description = "Protocol filename") filename: String
    ): String {
        return scriptManager.loadProtocolScript(filename)
            ?: "Protocol '$filename' not found. Call listProtocols() first."
    }

    @Tool(description = "Write a new protocol script to the protocol directory. Use this to create a modified or fixed version of an existing protocol. The original is preserved — both versions can be tested. After writing, call loadProtocol() with the new filename to use it. The script must follow the same pack*Request + DataView pattern.")
    fun writeProtocol(
        @ToolParam(description = "Filename for the new protocol script, e.g. 'my_fixed_car.js'. Choose a descriptive name.") filename: String,
        @ToolParam(description = "Complete protocol script content in JavaScript. Must define a 'protocol' object with pack*Request functions using DataView for binary packing.") content: String
    ): String {
        // Validate that the content looks like a protocol script
        if (!content.contains("pack") || !content.contains("Request") || !content.contains("DataView")) {
            return "Error: Content does not appear to be a valid protocol script. " +
                "It must contain pack*Request functions with DataView binary packing."
        }

        // Ensure filename ends with .js
        val normalizedName = if (filename.endsWith(".js")) filename else "${filename}.js"

        val saved = scriptManager.saveProtocolScript(normalizedName, content)
        return if (saved) {
            "Protocol '$normalizedName' saved successfully (${content.length} chars). " +
                "Call loadProtocol('$normalizedName') to load it into the sandbox."
        } else {
            "Error: Failed to save protocol '$normalizedName'. File may already exist — use a different filename."
        }
    }

    @Tool(description = "List user-saved JavaScript scripts")
    fun listScripts(): String {
        val scripts = scriptManager.listScripts()
        return if (scripts.isEmpty()) "No saved scripts" else "Scripts: ${scripts.joinToString(", ")}"
    }
}
