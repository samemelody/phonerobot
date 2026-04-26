// Bipedal Robot Protocol - Humanoid / Biped Walking Robot
// Servo-based biped with balance IMU and foot pressure sensors
// Binary format: [1B cmd][payload] with COBS framing

const protocol = {

    // ── Locomotion ────────────────────────────────────────────

    /**
     * Walk forward or backward
     * @param {number} steps    - Number of steps (1-20, 0=stop)
     * @param {number} speed    - Walking speed (1=slow, 2=normal, 3=fast)
     * @param {number} stepLength - Step length in cm (5-30)
     * @returns {Uint8Array}
     */
    packWalkRequest: function(steps, speed, stepLength) {
        // Handle default parameters (Rhino doesn't support ES6 default params)
        if (speed === undefined) speed = 2;
        if (stepLength === undefined) stepLength = 15;
        if (steps < 0 || steps > 20) throw new Error("steps must be 0..20");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        if (stepLength < 5 || stepLength > 30) throw new Error("stepLength must be 5..30cm");
        const buf = new ArrayBuffer(4);
        const v = new DataView(buf);
        v.setUint8(0, 0x80);  // CMD_WALK
        v.setUint8(1, steps);
        v.setUint8(2, speed);
        v.setUint8(3, stepLength);
        return new Uint8Array(buf);
    },

    /**
     * Turn in place
     * @param {number} angle - Turn angle (-180..180, positive=right, negative=left)
     * @param {number} speed - Turn speed (1-3)
     * @returns {Uint8Array}
     */
    packTurnRequest: function(angle, speed) {
        // Handle default parameter (Rhino doesn't support ES6 default params)
        if (speed === undefined) speed = 2;
        if (angle < -180 || angle > 180) throw new Error("angle must be -180..180");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(4);
        const v = new DataView(buf);
        v.setUint8(0, 0x81);  // CMD_TURN
        v.setInt16(1, angle, true);
        v.setUint8(3, speed);
        return new Uint8Array(buf);
    },

    /**
     * Stop walking and stabilize balance
     * @returns {Uint8Array}
     */
    packStopRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x82);  // CMD_STOP
        return new Uint8Array(buf);
    },

    /**
     * Sidestep (lateral movement)
     * @param {number} direction - 1=right, -1=left
     * @param {number} steps     - Number of sidesteps (1-10)
     * @returns {Uint8Array}
     */
    packSidestepRequest: function(direction, steps) {
        if (direction !== 1 && direction !== -1) throw new Error("direction must be 1 or -1");
        if (steps < 1 || steps > 10) throw new Error("steps must be 1..10");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x83);  // CMD_SIDESTEP
        v.setInt8(1, direction);
        v.setUint8(2, steps);
        return new Uint8Array(buf);
    },

    // ── Posture / Balance ─────────────────────────────────────

    /**
     * Stand up from sitting/crouching position
     * @returns {Uint8Array}
     */
    packStandUpRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x84);  // CMD_STAND_UP
        return new Uint8Array(buf);
    },

    /**
     * Sit down (controlled descent)
     * @returns {Uint8Array}
     */
    packSitDownRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x85);  // CMD_SIT_DOWN
        return new Uint8Array(buf);
    },

    /**
     * Shift body center of mass (for balance adjustment)
     * @param {number} x - Lateral shift in mm (-50..50, positive=right)
     * @param {number} y - Forward/backward shift in mm (-30..30)
     * @returns {Uint8Array}
     */
    packBodyShiftRequest: function(x, y) {
        if (x < -50 || x > 50) throw new Error("x must be -50..50mm");
        if (y < -30 || y > 30) throw new Error("y must be -30..30mm");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x86);  // CMD_BODY_SHIFT
        v.setInt16(1, Math.round(x * 10), true);
        v.setInt16(3, Math.round(y * 10), true);
        return new Uint8Array(buf);
    },

    // ── Gestures ──────────────────────────────────────────────

    /**
     * Wave hand gesture
     * @param {number} hand    - 0=left, 1=right
     * @param {number} pattern - 0=simple wave, 1=enthusiastic, 2=royal
     * @returns {Uint8Array}
     */
    packWaveRequest: function(hand, pattern) {
        // Handle default parameters (Rhino doesn't support ES6 default params)
        if (hand === undefined) hand = 1;
        if (pattern === undefined) pattern = 0;
        if (hand !== 0 && hand !== 1) throw new Error("hand must be 0 or 1");
        if (pattern < 0 || pattern > 2) throw new Error("pattern must be 0..2");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x90);  // CMD_WAVE
        v.setUint8(1, hand);
        v.setUint8(2, pattern);
        return new Uint8Array(buf);
    },

    /**
     * Bow gesture
     * @param {number} angle - Bow angle in degrees (15-45)
     * @returns {Uint8Array}
     */
    packBowRequest: function(angle) {
        // Handle default parameter (Rhino doesn't support ES6 default params)
        if (angle === undefined) angle = 30;
        if (angle < 15 || angle > 45) throw new Error("angle must be 15..45");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x91);  // CMD_BOW
        v.setUint8(1, angle);
        return new Uint8Array(buf);
    },

    /**
     * Shake head gesture (yes/no)
     * @param {number} gesture - 0=nod (yes), 1=shake (no)
     * @param {number} count   - Number of repetitions (1-5)
     * @returns {Uint8Array}
     */
    packHeadGestureRequest: function(gesture, count) {
        // Handle default parameter (Rhino doesn't support ES6 default params)
        if (count === undefined) count = 1;
        if (gesture !== 0 && gesture !== 1) throw new Error("gesture must be 0 or 1");
        if (count < 1 || count > 5) throw new Error("count must be 1..5");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x92);  // CMD_HEAD_GESTURE
        v.setUint8(1, gesture);
        v.setUint8(2, count);
        return new Uint8Array(buf);
    },

    /**
     * Raise arm(s) in victory/celebration pose
     * @param {number} arms - 0=left only, 1=right only, 2=both arms
     * @returns {Uint8Array}
     */
    packCelebrateRequest: function(arms) {
        // Handle default parameter (Rhino doesn't support ES6 default params)
        if (arms === undefined) arms = 2;
        if (arms < 0 || arms > 2) throw new Error("arms must be 0..2");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x93);  // CMD_CELEBRATE
        v.setUint8(1, arms);
        return new Uint8Array(buf);
    },

    // ── Emergency ─────────────────────────────────────────────

    /**
     * Emergency stop - freeze all servos immediately
     * @returns {Uint8Array}
     */
    packEmergencyStopRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x8F);  // CMD_ESTOP
        return new Uint8Array(buf);
    },

    /**
     * Relax all servos (go limp - for safety after estop)
     * @returns {Uint8Array}
     */
    packRelaxRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x8E);  // CMD_RELAX
        return new Uint8Array(buf);
    },

    // ── Telemetry ─────────────────────────────────────────────

    /**
     * Request IMU and foot pressure sensor data
     * @returns {Uint8Array}
     */
    packBalanceStatusRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x87);  // CMD_BALANCE_STATUS
        return new Uint8Array(buf);
    },

    /**
     * Request all servo positions
     * @returns {Uint8Array}
     */
    packServoStatusRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x88);  // CMD_SERVO_STATUS
        return new Uint8Array(buf);
    }
};

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol };
}
