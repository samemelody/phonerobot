package com.phonerobot.app

import android.util.Log
import com.phonerobot.app.robot.*

/**
 * Test utility for the JS sandbox protocol workflow.
 * Tests: loadProtocol → executeScript → binary output → channel send
 */
object JsSandboxTest {
    private const val TAG = "JsSandboxTest"

    fun runMiniTest(context: android.content.Context): String {
        Log.i(TAG, "Starting sandbox workflow test")

        val scriptManager = JsScriptManager(context)
        scriptManager.initializeStorage()
        val channel = MockRobotChannel()
        val sandbox = QuickJSSandbox(
            channel = channel,
            scriptManager = scriptManager,
            enableDetailedLogs = true,
        )

        sandbox.initialize()

        val results = StringBuilder("=== Sandbox Workflow Test ===\n\n")

        // Test 1: List available protocols
        val templates = scriptManager.listProtocolTemplates()
        results.appendLine("1. Available protocols: ${templates.keys.joinToString(", ")}")

        // Test 2: Load toy car protocol
        val loadResult = sandbox.loadProtocol("toy_car_protocol_core.js")
        results.appendLine("2. Load toy car protocol:\n$loadResult\n")

        // Test 3: Execute forward command
        val fwdResult = sandbox.executeScript("return protocol.packForwardRequest(50, 1000);")
        results.appendLine("3. Forward(50, 1000): ${formatResult(fwdResult)}")

        // Test 4: Execute stop command
        val stopResult = sandbox.executeScript("return protocol.packStopRequest();")
        results.appendLine("4. Stop(): ${formatResult(stopResult)}")

        // Test 5: Execute turn command
        val turnResult = sandbox.executeScript("return protocol.packTurnRequest(30, -45);")
        results.appendLine("5. Turn(30, -45): ${formatResult(turnResult)}")

        // Test 6: Load rover protocol
        val roverLoad = sandbox.loadProtocol("rover_protocol.js")
        results.appendLine("6. Load rover protocol:\n${roverLoad.take(200)}...\n")

        // Test 7: Execute rover drive
        val roverDrive = sandbox.executeScript("return protocol.packDriveRequest(80, 60);")
        results.appendLine("7. Rover drive(80, 60): ${formatResult(roverDrive)}")

        // Test 8: Sandbox status
        results.appendLine("\n8. Status: ${sandbox.getSandboxStatus()}")

        sandbox.cleanup()
        Log.i(TAG, "Sandbox test completed")
        return results.toString()
    }

    private fun formatResult(result: Any): String {
        return when (result) {
            is ByteArray -> "${result.size}B [${result.joinToString(" ") { "%02X".format(it) }}]"
            else -> result.toString()
        }
    }
}
