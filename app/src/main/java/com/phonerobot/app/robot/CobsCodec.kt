package com.phonerobot.app.robot

import android.util.Log

/**
 * Consistent Overhead Byte Stuffing (COBS) codec for framing serial data.
 * Encodes data to avoid zero bytes, uses 0x00 as frame delimiter.
 */
object CobsCodec {
    private const val TAG = "CobsCodec"
    private const val DEBUG = false  // Enable for detailed COBS logging

    /**
     * Encode data using COBS (Consistent Overhead Byte Stuffing).
     * @param data Input byte array
     * @return Encoded byte array with no zero bytes, ending with 0x00
     */
    fun encode(data: ByteArray): ByteArray {
        if (DEBUG) Log.d(TAG, "encode: input size=${data.size} bytes")
        
        if (data.isEmpty()) {
            if (DEBUG) Log.d(TAG, "encode: empty input, returning minimal frame")
            return byteArrayOf(0x01, 0x00)
        }

        val result = mutableListOf<Byte>()
        var segmentStart = 0
        var currentIndex = 0

        while (currentIndex < data.size) {
            if (data[currentIndex] == 0.toByte()) {
                // Found zero byte, write segment length and copy data
                val segmentLength = currentIndex - segmentStart + 1
                if (DEBUG) Log.v(TAG, "encode: zero at $currentIndex, segment length=$segmentLength")
                result.add(segmentLength.toByte())
                // Copy segment data (excluding the zero byte)
                for (i in segmentStart until currentIndex) {
                    result.add(data[i])
                }
                segmentStart = currentIndex + 1
            }
            currentIndex++
        }

        // Handle final segment
        val finalSegmentLength = currentIndex - segmentStart
        if (finalSegmentLength > 0) {
            result.add((finalSegmentLength + 1).toByte())
            // Copy remaining data
            for (i in segmentStart until currentIndex) {
                result.add(data[i])
            }
        } else {
            result.add(0x01)
        }

        // Add terminating zero
        result.add(0x00)
        
        val output = result.map { it.toByte() }.toByteArray()
        if (DEBUG) Log.d(TAG, "encode: ${data.size}B input → ${output.size}B COBS frame")
        return output
    }

    /**
     * Decode COBS-encoded data.
     * @param encoded Input byte array (should end with 0x00)
     * @return Decoded byte array
     */
    fun decode(encoded: ByteArray): ByteArray {
        if (DEBUG) Log.d(TAG, "decode: input size=${encoded.size} bytes")
        
        if (encoded.isEmpty() || encoded.last() != 0.toByte()) {
            val error = "COBS encoded data must end with 0x00"
            Log.e(TAG, "decode: $error")
            throw IllegalArgumentException(error)
        }

        val result = mutableListOf<Byte>()
        var index = 0

        while (index < encoded.size - 1) { // Skip last 0x00
            val blockLength = encoded[index].toInt() and 0xFF
            if (blockLength == 0) {
                val error = "Invalid COBS encoding: zero block length"
                Log.e(TAG, "decode: $error")
                throw IllegalArgumentException(error)
            }

            index++
            if (blockLength > 1) {
                // Copy data bytes
                val copyLength = blockLength - 1
                if (index + copyLength > encoded.size - 1) {
                    val error = "Invalid COBS encoding: insufficient data"
                    Log.e(TAG, "decode: $error")
                    throw IllegalArgumentException(error)
                }
                for (i in 0 until copyLength) {
                    result.add(encoded[index + i])
                }
                index += copyLength
            }

            // Add zero byte between blocks (except for last block)
            if (index < encoded.size - 1) {
                result.add(0.toByte())
            }
        }

        val output = result.map { it.toByte() }.toByteArray()
        if (DEBUG) Log.d(TAG, "decode: ${encoded.size}B encoded → ${output.size}B decoded")
        return output
    }

    /**
     * Simple encode without building intermediate lists.
     */
    fun encodeSimple(data: ByteArray): ByteArray {
        val output = ByteArray(data.size + data.size / 254 + 2) // Worst case size
        var outputPos = 0
        var code = 1
        var dataPos = 0

        while (dataPos < data.size) {
            if (data[dataPos] == 0.toByte()) {
                output[outputPos++] = code.toByte()
                code = 1
            } else {
                output[outputPos++] = data[dataPos]
                code++
                if (code == 0xFF) {
                    output[outputPos++] = code.toByte()
                    code = 1
                }
            }
            dataPos++
        }

        output[outputPos++] = code.toByte()
        output[outputPos++] = 0

        return output.copyOf(outputPos)
    }
}