// Robot Arm Protocol - 6-DOF Articulated Manipulator
// Servo-based or stepper-based robot arm with gripper
// Binary format: [1B cmd][payload] with COBS framing

const protocol = {

    // ── Joint Control ─────────────────────────────────────────

    /**
     * Move a single joint to target angle
     * @param {number} jointId - Joint index (1=base, 2=shoulder, 3=elbow, 4=wrist_pitch, 5=wrist_roll, 6=gripper)
     * @param {number} angle   - Target angle in degrees (range depends on joint)
     * @param {number} speed   - Movement speed (1=slow, 2=medium, 3=fast)
     * @returns {Uint8Array}
     */
    packJointMoveRequest: function(jointId, angle, speed = 2) {
        if (jointId < 1 || jointId > 6) throw new Error("jointId must be 1..6");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x70);  // CMD_JOINT_MOVE
        v.setUint8(1, jointId);
        v.setInt16(2, Math.round(angle * 10), true);  // 0.1 deg resolution
        v.setUint8(4, speed);
        return new Uint8Array(buf);
    },

    /**
     * Move all joints simultaneously (multi-joint coordinated move)
     * @param {number[]} angles - Array of 6 angles [base, shoulder, elbow, wrist_pitch, wrist_roll, gripper]
     * @param {number} speed    - Movement speed (1-3)
     * @returns {Uint8Array}
     */
    packMultiJointMoveRequest: function(angles, speed = 2) {
        if (!Array.isArray(angles) || angles.length !== 6) throw new Error("angles must be array of 6");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(14);
        const v = new DataView(buf);
        v.setUint8(0, 0x71);  // CMD_MULTI_JOINT_MOVE
        for (var i = 0; i < 6; i++) {
            v.setInt16(1 + i * 2, Math.round(angles[i] * 10), true);
        }
        v.setUint8(13, speed);
        return new Uint8Array(buf);
    },

    /**
     * Stop all joint movement immediately
     * @returns {Uint8Array}
     */
    packJointStopRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x72);  // CMD_JOINT_STOP
        return new Uint8Array(buf);
    },

    // ── Gripper ───────────────────────────────────────────────

    /**
     * Control gripper open/close
     * @param {number} position - 0=fully closed, 100=fully open
     * @param {number} force    - Grip force (1=light, 2=medium, 3=strong)
     * @returns {Uint8Array}
     */
    packGripperRequest: function(position, force = 2) {
        if (position < 0 || position > 100) throw new Error("position must be 0..100");
        if (force < 1 || force > 3) throw new Error("force must be 1..3");
        const buf = new ArrayBuffer(3);
        const v = new DataView(buf);
        v.setUint8(0, 0x73);  // CMD_GRIPPER
        v.setUint8(1, position);
        v.setUint8(2, force);
        return new Uint8Array(buf);
    },

    // ── Cartesian Control ─────────────────────────────────────

    /**
     * Move end-effector to XYZ position in workspace
     * @param {number} x     - X position in mm (-300..300)
     * @param {number} y     - Y position in mm (-300..300)
     * @param {number} z     - Z position in mm (0..400)
     * @param {number} speed - Movement speed (1-3)
     * @returns {Uint8Array}
     */
    packCartesianMoveRequest: function(x, y, z, speed = 2) {
        if (x < -300 || x > 300) throw new Error("x must be -300..300mm");
        if (y < -300 || y > 300) throw new Error("y must be -300..300mm");
        if (z < 0 || z > 400) throw new Error("z must be 0..400mm");
        if (speed < 1 || speed > 3) throw new Error("speed must be 1..3");
        const buf = new ArrayBuffer(8);
        const v = new DataView(buf);
        v.setUint8(0, 0x74);  // CMD_CARTESIAN_MOVE
        v.setInt16(1, Math.round(x * 10), true);   // 0.1mm resolution
        v.setInt16(3, Math.round(y * 10), true);
        v.setInt16(5, Math.round(z * 10), true);
        v.setUint8(7, speed);
        return new Uint8Array(buf);
    },

    /**
     * Set end-effector orientation (roll, pitch, yaw)
     * @param {number} roll  - Roll in degrees (-180..180)
     * @param {number} pitch - Pitch in degrees (-90..90)
     * @param {number} yaw   - Yaw in degrees (-180..180)
     * @returns {Uint8Array}
     */
    packOrientationRequest: function(roll, pitch, yaw) {
        if (roll < -180 || roll > 180) throw new Error("roll must be -180..180");
        if (pitch < -90 || pitch > 90) throw new Error("pitch must be -90..90");
        if (yaw < -180 || yaw > 180) throw new Error("yaw must be -180..180");
        const buf = new ArrayBuffer(7);
        const v = new DataView(buf);
        v.setUint8(0, 0x75);  // CMD_ORIENTATION
        v.setInt16(1, Math.round(roll * 10), true);
        v.setInt16(3, Math.round(pitch * 10), true);
        v.setInt16(5, Math.round(yaw * 10), true);
        return new Uint8Array(buf);
    },

    // ── Preset Poses ──────────────────────────────────────────

    /**
     * Move to home (zero) position - all joints at 0 degrees
     * @returns {Uint8Array}
     */
    packHomeRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x76);  // CMD_HOME
        return new Uint8Array(buf);
    },

    /**
     * Move to preset named pose
     * @param {number} poseId - Pose ID (0=pick, 1=place, 2=scan, 3=drop, 4=rest)
     * @returns {Uint8Array}
     */
    packPresetPoseRequest: function(poseId) {
        if (poseId < 0 || poseId > 4) throw new Error("poseId must be 0..4");
        const buf = new ArrayBuffer(2);
        const v = new DataView(buf);
        v.setUint8(0, 0x77);  // CMD_PRESET_POSE
        v.setUint8(1, poseId);
        return new Uint8Array(buf);
    },

    // ── Teach / Playback ──────────────────────────────────────

    /**
     * Save current joint positions as a waypoint in a program
     * @param {number} programId - Program slot (0-9)
     * @param {number} stepIndex - Step index within program (0-255)
     * @param {number} dwellMs   - Dwell time at this waypoint in ms
     * @returns {Uint8Array}
     */
    packTeachWaypointRequest: function(programId, stepIndex, dwellMs) {
        if (programId < 0 || programId > 9) throw new Error("programId must be 0..9");
        const buf = new ArrayBuffer(5);
        const v = new DataView(buf);
        v.setUint8(0, 0x78);  // CMD_TEACH_WAYPOINT
        v.setUint8(1, programId);
        v.setUint8(2, stepIndex);
        v.setUint16(3, dwellMs, true);
        return new Uint8Array(buf);
    },

    /**
     * Run a stored program (playback taught sequence)
     * @param {number} programId - Program slot (0-9)
     * @param {number} loops     - Number of repetitions (0=infinite)
     * @returns {Uint8Array}
     */
    packRunProgramRequest: function(programId, loops) {
        if (programId < 0 || programId > 9) throw new Error("programId must be 0..9");
        const buf = new ArrayBuffer(4);
        const v = new DataView(buf);
        v.setUint8(0, 0x79);  // CMD_RUN_PROGRAM
        v.setUint8(1, programId);
        v.setUint16(2, loops, true);
        return new Uint8Array(buf);
    },

    // ── Telemetry ─────────────────────────────────────────────

    /**
     * Request current joint angles and end-effector position
     * @returns {Uint8Array}
     */
    packStatusRequest: function() {
        const buf = new ArrayBuffer(1);
        const v = new DataView(buf);
        v.setUint8(0, 0x7F);  // CMD_STATUS
        return new Uint8Array(buf);
    }
};

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol };
}
