package com.phonerobot.app.robot

import android.util.Log

/**
 * Supported robot motion commands.
 */
sealed class RobotCommand(val raw: String) {
    /** Move forward N centimeters */
    data class MoveForward(val distanceCm: Int) : RobotCommand("MOVE_FWD $distanceCm")

    /** Move backward N centimeters */
    data class MoveBackward(val distanceCm: Int) : RobotCommand("MOVE_BACK $distanceCm")

    /** Turn left N degrees */
    data class TurnLeft(val degrees: Int) : RobotCommand("TURN_LEFT $degrees")

    /** Turn right N degrees */
    data class TurnRight(val degrees: Int) : RobotCommand("TURN_RIGHT $degrees")

    /** Emergency stop all motors */
    data object Stop : RobotCommand("STOP")

    /** Unknown / unparseable command */
    data class Unknown(val text: String) : RobotCommand(text)
    
    /**
     * Raw data command for protocol-encoded bytes from JS sandbox.
     */
    data class RawData(val data: ByteArray) : RobotCommand("RAW_DATA[${data.size} bytes]") {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawData) return false
            return data.contentEquals(other.data)
        }
        
        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }
}

/**
 * Parses AI-generated text into a typed RobotCommand.
 *
 * Expected formats from model:
 *   "MOVE_FWD 100"
 *   "TURN_LEFT 90"
 *   "STOP"
 *   etc.
 */
object CommandParser {

    private const val TAG = "CommandParser"

    fun parse(aiOutput: String): RobotCommand {
        val trimmed = aiOutput.trim()

        // Regex patterns for each command type
        val patterns = mapOf<Regex, (MatchResult) -> RobotCommand>(
            Regex("""MOVE_FWD\s+(\d+)""") to { m ->
                RobotCommand.MoveForward(m.groupValues[1].toInt())
            },
            Regex("""MOVE_BACK\s+(\d+)""") to { m ->
                RobotCommand.MoveBackward(m.groupValues[1].toInt())
            },
            Regex("""TURN_LEFT\s+(\d+)""") to { m ->
                RobotCommand.TurnLeft(m.groupValues[1].toInt())
            },
            Regex("""TURN_RIGHT\s+(\d+)""") to { m ->
                RobotCommand.TurnRight(m.groupValues[1].toInt())
            },
            Regex("""STOP""", RegexOption.IGNORE_CASE) to {
                RobotCommand.Stop
            }
        )

        for ((pattern, factory) in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val cmd = factory(match)
                Log.d(TAG, "Parsed: '$trimmed' → ${cmd::class.simpleName}")
                return cmd
            }
        }

        Log.w(TAG, "Could not parse command: '$trimmed'")
        return RobotCommand.Unknown(trimmed)
    }
}

/**
 * Interface for sending commands to the physical robot.
 * Implementations: UsbChannel, BluetoothChannel, MockChannel.
 */
interface RobotChannel {
    suspend fun send(command: RobotCommand): Boolean
    fun isConnected(): Boolean
}

/**
 * Mock channel for development without real hardware.
 * Logs commands instead of transmitting them.
 */
class MockRobotChannel : RobotChannel {
    private var _connected = true
    override fun isConnected() = _connected

    override suspend fun send(command: RobotCommand): Boolean {
        Log.i("MockChannel", "🤖 [MOCK] Sending: ${command.raw}")
        // Simulate small transmission delay
        kotlinx.coroutines.delay(50L)
        return true
    }

    fun disconnect() { _connected = false }
}
