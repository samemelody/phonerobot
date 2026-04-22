// Toy Car Protocol Core Functions
// This script provides the protocol functions that can be called by the AI assistant

const protocol = {
    /**
     * Pack forward movement request
     * @param {number} speed - Speed percentage (0-100)
     * @param {number} time - Duration in milliseconds (10-60000)
     * @returns {Uint8Array} Binary command data
     */
    packForwardRequest: function(speed, time) {
        console.log(`Protocol: Forward request - speed=${speed}%, time=${time}ms`);
        
        // Validate parameters
        if (speed < 0 || speed > 100) throw new Error("Speed must be 0-100");
        if (time < 10 || time > 60000) throw new Error("Time must be 10-60000ms");
        
        // Convert to binary format (simplified example)
        const buffer = new ArrayBuffer(6);
        const view = new DataView(buffer);
        view.setUint8(0, 0x01); // Forward command
        view.setUint8(1, speed);
        view.setUint16(2, time & 0xFFFF, true); // Little endian
        view.setUint16(4, (time >> 16) & 0xFFFF, true);
        
        return new Uint8Array(buffer);
    },
    
    /**
     * Pack stop movement request
     * @param {number} brakeTime - Brake time in milliseconds (0-5000, 0 for immediate)
     * @returns {Uint8Array} Binary command data
     */
    packStopRequest: function(brakeTime = 0) {
        console.log(`Protocol: Stop request - brakeTime=${brakeTime}ms`);
        
        if (brakeTime < 0 || brakeTime > 5000) throw new Error("Brake time must be 0-5000ms");
        
        const buffer = new ArrayBuffer(3);
        const view = new DataView(buffer);
        view.setUint8(0, 0x02); // Stop command
        view.setUint16(1, brakeTime, true);
        
        return new Uint8Array(buffer);
    },
    
    /**
     * Pack turn movement request
     * @param {number} speed - Turning speed percentage (0-100)
     * @param {number} angle - Turn angle in degrees (-90 to 90, positive for right)
     * @returns {Uint8Array} Binary command data
     */
    packTurnRequest: function(speed, angle) {
        console.log(`Protocol: Turn request - speed=${speed}%, angle=${angle}°`);
        
        if (speed < 0 || speed > 100) throw new Error("Speed must be 0-100");
        if (angle < -90 || angle > 90) throw new Error("Angle must be -90 to 90 degrees");
        
        const buffer = new ArrayBuffer(4);
        const view = new DataView(buffer);
        view.setUint8(0, 0x03); // Turn command
        view.setUint8(1, speed);
        view.setInt16(2, angle, true);
        
        return new Uint8Array(buffer);
    },
    
    /**
     * Pack get speed status request
     * @returns {Uint8Array} Binary command data
     */
    packGetSpeedRequest: function() {
        console.log("Protocol: Get speed request");
        
        const buffer = new ArrayBuffer(1);
        const view = new DataView(buffer);
        view.setUint8(0, 0x04); // Get speed command
        
        return new Uint8Array(buffer);
    }
};

// Export for use in QuickJS sandbox
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { protocol };
}