// Rover Protocol - Unmanned Ground Vehicle (UGV)
// 4-wheel or tracked rover with sensor payload support
// Binary format: [1B cmd][payload] with COBS framing

const protocol = {
    // ── Movement ──────────────────────────────────────────────

    /**
     * Drive rover with independent left/right track speeds
     * @param {number} leftSpeed  - Left track speed (-100 to 100, negative=reverse)
     * @param {number} rightSpeed - Right track speed (-100 to 100, negative=reverse)
     * @returns {Uint8Array}
     */
    packDriveRequest: function(leftSpeed, rightSpeed) {
        if (leftSpeed < -100 || leftSpeed > 100) throw new Error("leftSpeed must be -100..100");
        if (rightSpeed < -100 || rightSpeed > 100) throw new Error("rightSpeed must be -100..100");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x10);  // CMD_DRIVE
        v.setInt8(1, leftSpeed);
        v.setInt8(2, rightSpeed);
        v.setUint16(3, 0, true);  // duration (0 = continuous)
        return new Uint8Array(buf);
    },

    /**
     * Drive rover forward/backward for a timed duration
     * @param {number} speed    - Speed 0-100 (always forward direction)
     * @param {number} duration - Duration in ms (0=continuous, max 60000)
     * @returns {Uint8Array}
     */
    packTimedDriveRequest: function(speed, duration) {
        if (speed < 0 || speed > 100) throw new Error("speed must be 0..100");
        if (duration < 0 || duration > 60000) throw new Error("duration must be 0..60000ms");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x11);  // CMD_TIMED_DRIVE
        v.setUint8(1, speed);
        v.setUint16(2, duration & 0xFFFF, true);
        v.setUint8(4, (duration >> 16) & 0xFF);
        return new Uint8Array(buf);
    },

    /**
     * Rotate rover in place (pivot turn)
     * @param {number} speed     - Rotation speed 0-100
     * @param {number} angle     - Degrees (-360..360, positive=CW, negative=CCW)
     * @returns {Uint8Array}
     */
    packRotateRequest: function(speed, angle) {
        if (speed < 0 || speed > 100) throw new Error("speed must be 0..100");
        if (angle < -360 || angle > 360) throw new Error("angle must be -360..360");
        const buf = new ArrayBuffer(4);
        const v = new DataView(buf);
        v.setUint8(0, 0x12);  // CMD_ROTATE
        v.setUint8(1, speed);
        v.setInt16(2, angle, true);
        return new Uint8Array(buf);
    },

    /**
     * Navigate to GPS waypoint (autonomous)
     * @param {number} lat       - Latitude (float, degrees)
     * @param {number} lng       - Longitude (float, degrees)
     * @param {number} tolerance - Arrival tolerance in meters (1-50)
     * @returns {Uint8Array}
     */
    packGotoWaypointRequest: function(lat, lng, tolerance) {
        if (tolerance < 1 || tolerance > 50) throw new Error("tolerance must be 1..50m");
        const buf = new ArrayBuffer(11);
        const v = new DataView(buf);
        v.setUint8(0, 0x13);  // CMD_GOTO_WAYPOINT
        v.setFloat32(1, lat, true);
        v.setFloat32(5, lng, true);
        v.setUint8(9, tolerance);
        v.setUint8(10, 0);    // reserved
        return new Uint8Array(buf);
    },

    // ── Emergency ─────────────────────────────────────────────

    /**
     * Emergency stop - halt all motors immediately
     * @returns {Uint8Array}
     */
    packEstopRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x1F);  // CMD_ESTOP
        return new Uint8Array(buf);
    },

    // ── Sensor / Telemetry ────────────────────────────────────

    /**
     * Request current telemetry snapshot (GPS, IMU, battery)
     * @returns {Uint8Array}
     */
    packTelemetryRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x20);  // CMD_TELEMETRY
        return new Uint8Array(buf);
    },

    /**
     * Set camera pan/tilt
     * @param {number} pan  - Pan angle (-180..180 degrees)
     * @param {number} tilt - Tilt angle (-90..90 degrees)
     * @returns {Uint8Array}
     */
    packCameraPanTiltRequest: function(pan, tilt) {
        if (pan < -180 || pan > 180) throw new Error("pan must be -180..180");
        if (tilt < -90 || tilt > 90) throw new Error("tilt must be -90..90");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x21);  // CMD_CAMERA_PAN_TILT
        v.setInt16(1, pan * 10, true);   // 0.1 deg resolution
        v.setInt16(3, tilt * 10, true);
        return new Uint8Array(buf);
    },

    /**
     * Request a photo capture from onboard camera
     * @param {number} resolution - 0=QVGA, 1=VGA, 2=1080p
     * @returns {Uint8Array}
     */
    packPhotoCaptureRequest: function(resolution = 1) {
        if (resolution < 0 || resolution > 2) throw new Error("resolution must be 0..2");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x22);  // CMD_PHOTO
        v.setUint8(1, resolution);
        return new Uint8Array(buf);
    },

    // ── Mission ───────────────────────────────────────────────

    /**
     * Start autonomous patrol along pre-loaded waypoint list
     * @param {number} patrolId - Patrol route ID (0-255)
     * @param {number} loops    - Number of loops (0=infinite until estop)
     * @returns {Uint8Array}
     */
    packPatrolStartRequest: function(patrolId, loops) {
        if (patrolId < 0 || patrolId > 255) throw new Error("patrolId must be 0..255");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x30);  // CMD_PATROL_START
        v.setUint8(1, patrolId);
        v.setUint8(2, loops);
        return new Uint8Array(buf);
    },

    /**
     * Return to home/base station (RTL - Return To Launch)
     * @returns {Uint8Array}
     */
    packReturnToHomeRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x31);  // CMD_RTL
        return new Uint8Array(buf);
    }
};

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol };
}
