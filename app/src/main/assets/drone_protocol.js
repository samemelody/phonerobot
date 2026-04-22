// Drone Protocol - Multirotor UAV (Quadcopter/Hexacopter)
// Flight controller with GPS, barometer, and IMU
// Binary format: [1B cmd][payload] with COBS framing

const protocol = {

    // ── Flight Control ────────────────────────────────────────

    /**
     * Arm the motors (required before takeoff)
     * @param {number} mode - 0=standby arming, 1=auto-arm on takeoff
     * @returns {Uint8Array}
     */
    packArmRequest: function(mode = 0) {
        if (mode < 0 || mode > 1) throw new Error("mode must be 0 or 1");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x40);  // CMD_ARM
        v.setUint8(1, mode);
        return new Uint8Array(buf);
    },

    /**
     * Disarm motors (only when landed)
     * @returns {Uint8Array}
     */
    packDisarmRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x41);  // CMD_DISARM
        return new Uint8Array(buf);
    },

    /**
     * Take off to specified altitude
     * @param {number} altitude - Target altitude in meters (1-100)
     * @returns {Uint8Array}
     */
    packTakeoffRequest: function(altitude) {
        if (altitude < 1 || altitude > 100) throw new Error("altitude must be 1..100m");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x42);  // CMD_TAKEOFF
        v.setUint16(1, Math.round(altitude * 100), true);  // cm resolution
        return new Uint8Array(buf);
    },

    /**
     * Land at current position
     * @param {number} speed - Descent speed (1=slow, 2=normal, 3=fast)
     * @returns {Uint8Array}
     */
    packLandRequest: function(speed = 2) {
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x43);  // CMD_LAND
        v.setUint8(1, speed);
        return new Uint8Array(buf);
    },

    // ── Attitude / Movement ───────────────────────────────────

    /**
     * Go to GPS coordinate at specified altitude
     * @param {number} lat       - Latitude (degrees)
     * @param {number} lng       - Longitude (degrees)
     * @param {number} altitude  - Target altitude in meters (1-100)
     * @param {number} speed     - Flight speed in m/s (1-15)
     * @returns {Uint8Array}
     */
    packGotoRequest: function(lat, lng, altitude, speed) {
        if (altitude < 1 || altitude > 100) throw new Error("altitude must be 1..100m");
        if (speed < 1 || speed > 15) throw new Error("speed must be 1..15 m/s");
        const buf = new ArrayBuffer(15);
        const v = new DataView(buf);
        v.setUint8(0, 0x44);  // CMD_GOTO
        v.setFloat32(1, lat, true);
        v.setFloat32(5, lng, true);
        v.setUint16(9, Math.round(altitude * 100), true);
        v.setUint8(13, speed);
        v.setUint8(14, 0);    // reserved
        return new Uint8Array(buf);
    },

    /**
     * Change altitude (ascend or descend in-place)
     * @param {number} altitude - Target altitude in meters (1-100)
     * @returns {Uint8Array}
     */
    packSetAltitudeRequest: function(altitude) {
        if (altitude < 1 || altitude > 100) throw new Error("altitude must be 1..100m");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x45);  // CMD_SET_ALTITUDE
        v.setUint16(1, Math.round(altitude * 100), true);
        return new Uint8Array(buf);
    },

    /**
     * Yaw (rotate heading) in place
     * @param {number} angle   - Degrees (-360..360, positive=CW)
     * @param {number} speed   - Rotation speed (1=slow, 2=medium, 3=fast)
     * @returns {Uint8Array}
     */
    packYawRequest: function(angle, speed = 2) {
        if (angle < -360 || angle > 360) throw new Error("angle must be -360..360");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(4);
        const v = new DataView(buf);
        v.setUint8(0, 0x46);  // CMD_YAW
        v.setInt16(1, angle, true);
        v.setUint8(3, speed);
        return new Uint8Array(buf);
    },

    /**
     * Manual velocity control (for joystick-like control)
     * @param {number} vx    - Forward/backward velocity m/s (-5..5)
     * @param {number} vy    - Left/right velocity m/s (-5..5)
     * @param {number} vz    - Up/down velocity m/s (-3..3)
     * @param {number} yawRate - Yaw rate deg/s (-90..90)
     * @returns {Uint8Array}
     */
    packVelocityRequest: function(vx, vy, vz, yawRate) {
        if (vx < -5 || vx > 5) throw new Error("vx must be -5..5 m/s");
        if (vy < -5 || vy > 5) throw new Error("vy must be -5..5 m/s");
        if (vz < -3 || vz > 3) throw new Error("vz must be -3..3 m/s");
        if (yawRate < -90 || yawRate > 90) throw new Error("yawRate must be -90..90 deg/s");
        const buf = new ArrayBuffer(9);
        const v = new DataView(buf);
        v.setUint8(0, 0x47);  // CMD_VELOCITY
        v.setInt16(1, Math.round(vx * 100), true);
        v.setInt16(3, Math.round(vy * 100), true);
        v.setInt16(5, Math.round(vz * 100), true);
        v.setInt8(7, yawRate);
        v.setUint8(8, 0);    // reserved
        return new Uint8Array(buf);
    },

    // ── Emergency ─────────────────────────────────────────────

    /**
     * Emergency stop - kill motors immediately (will crash!)
     * Only use in extreme emergency.
     * @returns {Uint8Array}
     */
    packKillSwitchRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x4F);  // CMD_KILL_SWITCH
        return new Uint8Array(buf);
    },

    /**
     * Return to launch point and land (RTL)
     * @returns {Uint8Array}
     */
    packRtlRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x4E);  // CMD_RTL
        return new Uint8Array(buf);
    },

    // ── Camera / Payload ──────────────────────────────────────

    /**
     * Control gimbal (camera stabilization)
     * @param {number} pitch - Pitch angle (-90..30 degrees)
     * @param {number} yaw   - Yaw angle (-180..180 degrees)
     * @returns {Uint8Array}
     */
    packGimbalRequest: function(pitch, yaw) {
        if (pitch < -90 || pitch > 30) throw new Error("pitch must be -90..30");
        if (yaw < -180 || yaw > 180) throw new Error("yaw must be -180..180");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x50);  // CMD_GIMBAL
        v.setInt16(1, pitch * 10, true);
        v.setInt16(3, yaw * 10, true);
        return new Uint8Array(buf);
    },

    /**
     * Take a photo
     * @returns {Uint8Array}
     */
    packPhotoRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x51);  // CMD_PHOTO
        return new Uint8Array(buf);
    },

    /**
     * Start/stop video recording
     * @param {number} action - 0=stop recording, 1=start recording
     * @returns {Uint8Array}
     */
    packVideoRequest: function(action) {
        if (action !== 0 && action !== 1) throw new Error("action must be 0 or 1");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x52);  // CMD_VIDEO
        v.setUint8(1, action);
        return new Uint8Array(buf);
    },

    // ── Telemetry ─────────────────────────────────────────────

    /**
     * Request full telemetry (GPS, altitude, battery, attitude)
     * @returns {Uint8Array}
     */
    packTelemetryRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x60);  // CMD_TELEMETRY
        return new Uint8Array(buf);
    }
};

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol };
}
