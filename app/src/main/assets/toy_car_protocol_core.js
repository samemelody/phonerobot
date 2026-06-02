// ============================================================
// Toy Car BLE Protocol — Binary Frame Pack/Unpack (Rhino Compatible)
// Version: v1.0 (2026-05-20)
// Based on: protocol.md
//
// Frame: SYNC(0xA5) | LEN | CMD | PAYLOAD(0~6B) | CRC8
// CRC8:  polynomial=0x07  init=0x00  (CRC-8/ROHC)
//
// Commands (Phone → MCU):
//   0x10 MOVE      – straight line  (dir + speed + duration)
//   0x20 TURN      – in-place turn  (dir + angle + speed)
//   0x30 ARC       – curved move    (dir + turnDir + speed + radius)
//   0x40 STOP      – immediate stop (no payload)
//   0x50 HEARTBEAT – keepalive      (seq)
//
// Status (MCU → Phone):
//   0x81 STATUS    – battery + state + error + cmdEcho
//   0x82 HB_ACK    – seq + battery + state
//   0x83 CMD_DONE  – cmdEcho + result
// ============================================================

var protocol = {};

// ── Constants ──────────────────────────────────────────────────

protocol.SYNC_BYTE      = 0xA5;

protocol.CMD_MOVE       = 0x10;
protocol.CMD_TURN       = 0x20;
protocol.CMD_ARC        = 0x30;
protocol.CMD_STOP       = 0x40;
protocol.CMD_HEARTBEAT  = 0x50;

protocol.STA_STATUS     = 0x81;
protocol.STA_HB_ACK     = 0x82;
protocol.STA_CMD_DONE   = 0x83;

protocol.DIR_FORWARD    = 0x01;
protocol.DIR_BACKWARD   = 0x02;
protocol.TURN_LEFT      = 0x01;
protocol.TURN_RIGHT     = 0x02;

// ── CRC8 (CRC-8/ROHC: poly=0x07, init=0x00) ──────────────────

/**
 * Calculate CRC8 checksum over a byte array.
 * @param {number[]|Uint8Array} data – array of bytes
 * @returns {number} 8-bit CRC value (0–255)
 */
protocol.crc8 = function(data) {
    var crc = 0x00;
    for (var i = 0; i < data.length; i++) {
        crc ^= data[i];
        for (var j = 0; j < 8; j++) {
            if (crc & 0x80) {
                crc = ((crc << 1) ^ 0x07) & 0xFF;
            } else {
                crc = (crc << 1) & 0xFF;
            }
        }
    }
    return crc;
};

// ── Core Frame Assembly ───────────────────────────────────────

/**
 * Build a complete binary frame.
 * @param {number} cmd    – command/status code (0x10–0x83)
 * @param {number[]} payload – payload bytes (length 0–6)
 * @returns {Uint8Array} full frame: SYNC + LEN + CMD + payload + CRC8
 */
protocol.packFrame = function(cmd, payload) {
    // Default to empty payload
    if (payload === undefined) payload = [];

    var pLen = payload.length;

    // Build CRC input: SYNC | LEN | CMD | PAYLOAD
    var crcInput = [];
    crcInput.push(this.SYNC_BYTE);   // 0: SYNC
    crcInput.push(pLen);             // 1: LEN
    crcInput.push(cmd);              // 2: CMD
    for (var i = 0; i < pLen; i++) {
        crcInput.push(payload[i]);   // 3.. : PAYLOAD
    }

    var crc = this.crc8(crcInput);

    // Build output frame
    var frame = crcInput.slice();    // shallow copy (SYNC+LEN+CMD+payload)
    frame.push(crc);                 // CRC8

    return new Uint8Array(frame);
};

// ── Core Frame Parsing ────────────────────────────────────────

/**
 * Parse a received raw-frame byte array.
 * @param {Uint8Array} frame – raw BLE notification bytes
 * @returns {object} { valid, sync, len, cmd, payload:Uint8Array, crc, calcCrc }
 */
protocol.unpackFrame = function(frame) {
    // Minimum sensible frame: SYNC + LEN + CMD + CRC = 4 bytes
    if (frame.length < 4) {
        return { valid: false, error: 'Frame too short (min 4 bytes)' };
    }

    var sync = frame[0];
    if (sync !== this.SYNC_BYTE) {
        return {
            valid: false,
            error: 'Invalid sync byte: 0x' + sync.toString(16) + ', expected 0xA5'
        };
    }

    var pLen = frame[1];
    var expectedLen = 3 + pLen + 1; // SYNC(1) + LEN(1) + CMD(1) + payload(pLen) + CRC(1)
    if (frame.length < expectedLen) {
        return {
            valid: false,
            error: 'Frame length mismatch: got ' + frame.length +
                   ', expected at least ' + expectedLen
        };
    }

    var cmd     = frame[2];
    var payload = frame.slice(3, 3 + pLen);
    var crc     = frame[3 + pLen];

    // Verify CRC over SYNC…payload
    var crcInput = [];
    for (var i = 0; i < 3 + pLen; i++) { crcInput.push(frame[i]); }
    var calcCrc = this.crc8(crcInput);

    return {
        valid:   (calcCrc === crc),
        sync:    sync,
        len:     pLen,
        cmd:     cmd,
        payload: payload,
        crc:     crc,
        calcCrc: calcCrc
    };
};

// ── Command: MOVE (0x10) — Straight-line movement ─────────────

/**
 * Pack MOVE command.
 * @param {number} direction  – 0x01=forward, 0x02=backward
 * @param {number} speed      – 0–100 (percentage)
 * @param {number} durationMs – 0–65535 ms (0 = continuous until next cmd)
 * @returns {Uint8Array}
 */
protocol.packMove = function(direction, speed, durationMs) {
    // Null-safe defaults
    if (durationMs === undefined || durationMs === null) durationMs = 0;

    var payload = [
        direction & 0xFF,
        speed & 0xFF,
        durationMs & 0xFF,          // u16 low byte
        (durationMs >> 8) & 0xFF     // u16 high byte
    ];

    console.log(
        'Packing MOVE  | dir=' + (direction === 0x01 ? 'FWD' : 'BWD') +
        ' speed=' + speed + '% dur=' + durationMs + 'ms'
    );

    return this.packFrame(this.CMD_MOVE, payload);
};

// ── Command: TURN (0x20) — In-place rotation ──────────────────

/**
 * Pack TURN command.
 * @param {number} direction – 0x01=left, 0x02=right
 * @param {number} angle     – 1–180 degrees
 * @param {number} speed     – 1–100 (percentage)
 * @returns {Uint8Array}
 */
protocol.packTurn = function(direction, angle, speed) {
    var payload = [
        direction & 0xFF,
        angle & 0xFF,
        speed & 0xFF
    ];

    console.log(
        'Packing TURN  | dir=' + (direction === 0x01 ? 'LEFT' : 'RIGHT') +
        ' angle=' + angle + '° speed=' + speed + '%'
    );

    return this.packFrame(this.CMD_TURN, payload);
};

// ── Command: ARC (0x30) — Curved movement ─────────────────────

/**
 * Pack ARC command.
 * @param {number} direction – 0x01=forward, 0x02=backward
 * @param {number} turnDir   – 0x01=left arc, 0x02=right arc
 * @param {number} speed     – 1–100 (percentage)
 * @param {number} radiusCm  – 1–65535 cm (turn radius)
 * @returns {Uint8Array}
 */
protocol.packArc = function(direction, turnDir, speed, radiusCm) {
    var payload = [
        direction & 0xFF,
        turnDir & 0xFF,
        speed & 0xFF,
        radiusCm & 0xFF,          // u16 low byte
        (radiusCm >> 8) & 0xFF     // u16 high byte
    ];

    console.log(
        'Packing ARC   | dir=' + (direction === 0x01 ? 'FWD' : 'BWD') +
        ' turn=' + (turnDir === 0x01 ? 'LEFT' : 'RIGHT') +
        ' speed=' + speed + '% radius=' + radiusCm + 'cm'
    );

    return this.packFrame(this.CMD_ARC, payload);
};

// ── Command: STOP (0x40) — Immediate stop ─────────────────────

/**
 * Pack STOP command (no payload, highest priority).
 * @returns {Uint8Array}
 */
protocol.packStop = function() {
    console.log('Packing STOP');
    return this.packFrame(this.CMD_STOP, []);
};

// ── Command: HEARTBEAT (0x50) — Keepalive ─────────────────────

/**
 * Pack HEARTBEAT command.
 * @param {number} seq – sequence number 0–255 (should auto-increment)
 * @returns {Uint8Array}
 */
protocol.packHeartbeat = function(seq) {
    var payload = [seq & 0xFF];

    console.log('Packing HEARTBEAT | seq=' + seq);

    return this.packFrame(this.CMD_HEARTBEAT, payload);
};

// ── Status: STATUS (0x81) — Parse ─────────────────────────────

/**
 * Parse STATUS payload (battery + state + error + cmdEcho).
 * @param {Uint8Array} payload – 4 bytes from unpackFrame()
 * @returns {object}
 */
protocol.parseStatus = function(payload) {
    var battery  = payload[0];
    var state    = payload[1];
    var error    = payload[2];
    var cmdEcho  = payload[3];

    var errorNames = {
        0x00: 'Normal',
        0x01: 'MotorStall',
        0x02: 'BatteryLow',
        0x03: 'Timeout'
    };

    return {
        battery:    battery,
        moving:     (state & 0x01) !== 0,
        fault:      (state & 0x02) !== 0,
        timedOp:    (state & 0x04) !== 0,
        stateRaw:   state,
        errorCode:  error,
        errorName:  errorNames[error] || 'Unknown_0x' + error.toString(16),
        currentCmd: cmdEcho
    };
};

// ── Status: HB_ACK (0x82) — Parse ─────────────────────────────

/**
 * Parse heart-beat ACK payload (seq + battery + state).
 * @param {Uint8Array} payload – 3 bytes from unpackFrame()
 * @returns {object}
 */
protocol.parseHbAck = function(payload) {
    return {
        seq:      payload[0],
        battery:  payload[1],
        moving:   (payload[2] & 0x01) !== 0,
        fault:    (payload[2] & 0x02) !== 0,
        timedOp:  (payload[2] & 0x04) !== 0,
        stateRaw: payload[2]
    };
};

// ── Status: CMD_DONE (0x83) — Parse ───────────────────────────

/**
 * Parse command-done payload (cmdEcho + result).
 * @param {Uint8Array} payload – 2 bytes from unpackFrame()
 * @returns {object}
 */
protocol.parseCmdDone = function(payload) {
    var resultNames = {
        0x00: 'Success',
        0x01: 'Interrupted',
        0xFF: 'Aborted'
    };
    var result = payload[1];

    return {
        cmdEcho:    payload[0],
        result:     result,
        resultName: resultNames[result] || 'Unknown_0x' + result.toString(16)
    };
};

// ── Convenience: Auto-detect & parse any notification ─────────

/**
 * Parse a raw BLE notification frame end-to-end:
 *   1) verify frame integrity & CRC
 *   2) auto-detect status type
 *   3) return structured object
 *
 * @param {Uint8Array} rawFrame – bytes received from BLE Notify Char
 * @returns {object} { valid, cmd, cmdName, data }
 */
protocol.parseNotification = function(rawFrame) {
    var frame = this.unpackFrame(rawFrame);
    if (!frame.valid) return frame;  // returns { valid:false, error:... }

    var result = { valid: true, cmd: frame.cmd, cmdName: '', data: null };

    switch (frame.cmd) {
        case this.STA_STATUS:
            result.cmdName = 'STATUS';
            result.data    = this.parseStatus(frame.payload);
            break;
        case this.STA_HB_ACK:
            result.cmdName = 'HB_ACK';
            result.data    = this.parseHbAck(frame.payload);
            break;
        case this.STA_CMD_DONE:
            result.cmdName = 'CMD_DONE';
            result.data    = this.parseCmdDone(frame.payload);
            break;
        default:
            result.cmdName = 'UNKNOWN_0x' + frame.cmd.toString(16);
            result.data    = { rawPayload: frame.payload };
            break;
    }

    return result;
};

// ── Export for module loaders ──────────────────────────────────
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol: protocol };
}
