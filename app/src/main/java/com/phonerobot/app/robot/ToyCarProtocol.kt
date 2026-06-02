package com.phonerobot.app.robot

/**
 * Toy Car BLE Protocol — Kotlin-side frame builder & parser.
 *
 * Mirror of toy_car_protocol_core.js for use in Kotlin code
 * (heartbeat, response parsing) without going through the JS sandbox.
 *
 * Frame: SYNC(0xA5) | LEN | CMD | PAYLOAD(0~6B) | CRC8
 * CRC8:  poly=0x07  init=0x00  (CRC-8/ROHC)
 */
object ToyCarProtocol {

    // ── Frame constants ────────────────────────────────────────

    const val SYNC_BYTE: Byte = 0xA5.toByte()

    // Downstream (Phone → MCU)
    const val CMD_MOVE:      Byte = 0x10
    const val CMD_TURN:      Byte = 0x20
    const val CMD_ARC:       Byte = 0x30
    const val CMD_STOP:      Byte = 0x40
    const val CMD_HEARTBEAT: Byte = 0x50

    // Upstream (MCU → Phone)
    const val STA_STATUS:   Byte = 0x81.toByte()
    const val STA_HB_ACK:   Byte = 0x82.toByte()
    const val STA_CMD_DONE: Byte = 0x83.toByte()

    // ── CRC8 (CRC-8/ROHC: poly=0x07, init=0x00) ───────────────

    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0)
                    ((crc shl 1) xor 0x07) and 0xFF
                else
                    (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    fun crc8(data: List<Byte>): Int = crc8(data.toByteArray())

    // ── Frame building ─────────────────────────────────────────

    /**
     * Build a complete binary frame: SYNC | LEN | CMD | payload | CRC8
     */
    fun buildFrame(cmd: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val pLen = payload.size

        // Build CRC input: SYNC + LEN + CMD + payload
        val crcInput = ByteArray(3 + pLen)
        crcInput[0] = SYNC_BYTE
        crcInput[1] = pLen.toByte()
        crcInput[2] = cmd
        System.arraycopy(payload, 0, crcInput, 3, pLen)

        val crc = crc8(crcInput)

        // Build full frame
        val frame = ByteArray(crcInput.size + 1)
        System.arraycopy(crcInput, 0, frame, 0, crcInput.size)
        frame[frame.size - 1] = crc.toByte()

        return frame
    }

    /**
     * Build a HEARTBEAT frame: A5 01 50 [seq] [CRC]
     */
    fun buildHeartbeat(seq: Int): ByteArray {
        val payload = byteArrayOf((seq and 0xFF).toByte())
        return buildFrame(CMD_HEARTBEAT, payload)
    }

    // ── Frame parsing ──────────────────────────────────────────

    /**
     * Parsed notification result.
     */
    data class ParsedFrame(
        val valid: Boolean,
        val error: String? = null,
        val cmd: Byte = 0,
        val cmdName: String = "",
        val summary: String = "",
        val data: Map<String, Any> = emptyMap(),
    )

    /**
     * Parse a raw byte array received from MCU via USB.
     * Validates SYNC byte, length, and CRC8, then auto-detects status type.
     */
    fun parseNotification(raw: ByteArray): ParsedFrame {
        // Minimum frame: SYNC + LEN + CMD + CRC = 4 bytes
        if (raw.size < 4) {
            return ParsedFrame(valid = false, error = "Frame too short (${raw.size}B < 4B)")
        }

        val sync = raw[0]
        if (sync != SYNC_BYTE) {
            return ParsedFrame(
                valid = false,
                error = "Invalid SYNC: 0x${"%02X".format(sync)}, expected 0xA5"
            )
        }

        val pLen = raw[1].toInt() and 0xFF
        val expectedLen = 3 + pLen + 1  // SYNC + LEN + CMD + payload + CRC
        if (raw.size < expectedLen) {
            return ParsedFrame(
                valid = false,
                error = "Frame too short: got ${raw.size}B, need at least ${expectedLen}B"
            )
        }

        val cmd = raw[2]
        val payload = raw.copyOfRange(3, 3 + pLen)
        val crc = raw[3 + pLen].toInt() and 0xFF

        // Verify CRC
        val crcInput = raw.copyOfRange(0, 3 + pLen)
        val calcCrc = crc8(crcInput)
        if (calcCrc != crc) {
            return ParsedFrame(
                valid = false,
                error = "CRC mismatch: calc=0x${"%02X".format(calcCrc)} got=0x${"%02X".format(crc)}"
            )
        }

        // Parse based on command code
        return when (cmd.toInt() and 0xFF) {
            0x81 -> parseStatus(payload)
            0x82 -> parseHbAck(payload)
            0x83 -> parseCmdDone(payload)
            else -> ParsedFrame(
                valid = true,
                cmd = cmd,
                cmdName = "UNKNOWN_0x${"%02X".format(cmd)}",
                summary = "Unknown cmd: 0x${"%02X".format(cmd)}",
                data = mapOf("rawPayload" to payload)
            )
        }
    }

    // ── Status parsers ─────────────────────────────────────────

    private fun parseStatus(payload: ByteArray): ParsedFrame {
        val battery = payload[0].toInt() and 0xFF
        val state   = payload[1].toInt() and 0xFF
        val error   = payload[2].toInt() and 0xFF
        val cmdEcho = payload[3].toInt() and 0xFF

        val moving  = (state and 0x01) != 0
        val fault   = (state and 0x02) != 0
        val timedOp = (state and 0x04) != 0

        val errorName = when (error) {
            0x00 -> "Normal"
            0x01 -> "MotorStall"
            0x02 -> "BatteryLow"
            0x03 -> "Timeout"
            else -> "Unknown($error)"
        }

        val parts = mutableListOf<String>()
        parts.add("Battery=${battery}%")
        if (moving) parts.add("MOVING")
        if (fault) parts.add("FAULT!")
        if (timedOp) parts.add("TimedOp")
        if (error != 0) parts.add(errorName)
        parts.add("CurrentCmd=0x${"%02X".format(cmdEcho)}")

        return ParsedFrame(
            valid = true,
            cmd = STA_STATUS,
            cmdName = "STATUS",
            summary = "STATUS: ${parts.joinToString(" | ")}",
            data = mapOf(
                "battery" to battery,
                "moving" to moving,
                "fault" to fault,
                "timedOp" to timedOp,
                "errorCode" to error,
                "errorName" to errorName,
                "currentCmd" to cmdEcho
            )
        )
    }

    private fun parseHbAck(payload: ByteArray): ParsedFrame {
        val seq     = payload[0].toInt() and 0xFF
        val battery = payload[1].toInt() and 0xFF
        val state   = payload[2].toInt() and 0xFF

        val moving  = (state and 0x01) != 0
        val fault   = (state and 0x02) != 0
        val timedOp = (state and 0x04) != 0

        val parts = mutableListOf<String>()
        parts.add("seq=$seq")
        parts.add("Battery=${battery}%")
        if (moving) parts.add("MOVING")
        if (fault) parts.add("FAULT!")

        return ParsedFrame(
            valid = true,
            cmd = STA_HB_ACK,
            cmdName = "HB_ACK",
            summary = "HB_ACK: ${parts.joinToString(" | ")}",
            data = mapOf(
                "seq" to seq,
                "battery" to battery,
                "moving" to moving,
                "fault" to fault,
                "timedOp" to timedOp
            )
        )
    }

    private fun parseCmdDone(payload: ByteArray): ParsedFrame {
        val cmdEcho = payload[0].toInt() and 0xFF
        val result  = payload[1].toInt() and 0xFF

        val resultName = when (result) {
            0x00 -> "Success"
            0x01 -> "Interrupted"
            0xFF.toInt() -> "Aborted"
            else -> "Unknown($result)"
        }

        return ParsedFrame(
            valid = true,
            cmd = STA_CMD_DONE,
            cmdName = "CMD_DONE",
            summary = "CMD_DONE: 0x${"%02X".format(cmdEcho)} → $resultName",
            data = mapOf(
                "cmdEcho" to cmdEcho,
                "result" to result,
                "resultName" to resultName
            )
        )
    }
}
