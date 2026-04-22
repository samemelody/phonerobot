package com.phonerobot.app.robot

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary protocol handler for robot commands.
 * Converts high-level robot commands to binary packets with COBS framing for STM32 transmission.
 * 
 * <p>Packet format (before COBS encoding):
 * [1 byte: command type] [N bytes: payload]</p>
 * 
 * <p>Command types:</p>
 * <ul>
 *   <li>0x01 = DRIVE  — payload: [1 byte direction] [1 byte speed]</li>
 *   <li>0x02 = STEER  — payload: [2 bytes angle (big-endian uint16)]</li>
 *   <li>0x03 = STOP   — no payload</li>
 * </ul>
 */
class RobotProtocol {
    companion object {
        private const val TAG = "RobotProtocol"
        private const val DEBUG = true  // Enable detailed logging

        // Command type identifiers
        private const val CMD_DRIVE: Byte = 0x01
        private const val CMD_STEER: Byte = 0x02
        private const val CMD_STOP: Byte = 0x03
    }

    /**
     * Encode a drive command.
     * @param direction 1 for forward, -1 for backward, 0 for stop
     * @param speed Speed from 0-255
     * @return COBS-encoded byte array ready for transmission
     */
    fun encodeDriveCommand(direction: Int, speed: Int): ByteArray {
        val normalizedDir = when {
            direction > 0 -> 1
            direction < 0 -> -1
            else -> 0
        }
        val normalizedSpeed = speed.coerceIn(0, 255)

        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeByte(CMD_DRIVE.toInt())
        dos.writeByte(normalizedDir)
        dos.writeByte(normalizedSpeed)

        return encodeCommand(payload.toByteArray())
    }

    /**
     * Encode a steering command.
     * @param angle Angle in degrees (0-180)
     * @return COBS-encoded byte array ready for transmission
     */
    fun encodeSteerCommand(angle: Int): ByteArray {
        val normalizedAngle = angle.coerceIn(0, 180)

        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeByte(CMD_STEER.toInt())
        dos.writeShort(normalizedAngle)

        return encodeCommand(payload.toByteArray())
    }

    /**
     * Encode a stop command.
     * @return COBS-encoded byte array ready for transmission
     */
    fun encodeStopCommand(): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeByte(CMD_STOP.toInt())

        return encodeCommand(payload.toByteArray())
    }

    /**
     * Apply COBS framing to an encoded command.
     */
    private fun encodeCommand(data: ByteArray): ByteArray {
        try {
            val cobsBytes = CobsCodec.encodeSimple(data)
            Log.d(TAG, "Encoded command: ${data.size}B payload → ${cobsBytes.size}B COBS frame")
            return cobsBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode command", e)
            throw e
        }
    }

    /**
     * Decode received COBS-encoded data back to a parsed command.
     * @param data Received byte array (should end with 0x00)
     * @return Parsed command result, or null on failure
     */
    fun decodeCommand(data: ByteArray): DecodedCommand? {
        try {
            val decoded = CobsCodec.decode(data)
            val dis = DataInputStream(ByteArrayInputStream(decoded))

            val cmdType = dis.readByte()
            return when (cmdType) {
                CMD_DRIVE -> DecodedCommand.Drive(
                    direction = dis.readByte().toInt(),
                    speed = dis.readByte().toInt() and 0xFF
                )
                CMD_STEER -> DecodedCommand.Steer(
                    angle = dis.readShort().toInt()
                )
                CMD_STOP -> DecodedCommand.Stop
                else -> {
                    Log.w(TAG, "Unknown command type: 0x${String.format("%02X", cmdType)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode command", e)
            return null
        }
    }

    /**
     * Validate and normalize drive parameters.
     */
    fun validateDriveParams(direction: Int, speed: Int): Pair<Int, Int> {
        val normalizedDirection = when {
            direction > 0 -> 1
            direction < 0 -> -1
            else -> 0
        }
        val normalizedSpeed = speed.coerceIn(0, 255)
        return Pair(normalizedDirection, normalizedSpeed)
    }

    /**
     * Validate steering angle.
     */
    fun validateSteerAngle(angle: Int): Int {
        return angle.coerceIn(0, 180)
    }
}

/**
 * Parsed result of decoding a binary robot command.
 */
sealed class DecodedCommand {
    data class Drive(val direction: Int, val speed: Int) : DecodedCommand()
    data class Steer(val angle: Int) : DecodedCommand()
    data object Stop : DecodedCommand()
}
