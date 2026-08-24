package com.phonerobot.app.robot

/**
 * Live telemetry from the MCU, updated from STATUS / HB_ACK frames.
 * Replaces the old non-thread-safe `mcuStatus: String` field.
 */
data class McuTelemetry(
    val connected: Boolean = false,
    val batteryPct: Int? = null,
    val moving: Boolean = false,
    val fault: Boolean = false,
    val errorName: String? = null,
    val lastFrameSummary: String = "",
    val lastUpdateMs: Long = 0L,
) {
    /** Human-readable one-line status for AI prompt injection */
    fun promptSummary(): String =
        if (!connected || lastFrameSummary.isEmpty()) ""
        else "[Current robot status: $lastFrameSummary]"
}
