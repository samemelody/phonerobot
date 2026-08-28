package com.phonerobot.app.ui

import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.robot.McuTelemetry

/**
 * Centralized app state for the Phone Robot UI.
 */
data class PhoneRobotUiState(
    // Model status
    val modelStatus: ModelStatus = ModelStatus.Idle,
    val modelLoadingElapsedSec: Int = 0,

    // Chat / AI conversation
    val messages: List<ChatMessage> = emptyList(),
    val currentInput: String = "",
    val isAiThinking: Boolean = false,

    // Navigation
    val currentDestination: PhoneRobotDestination = PhoneRobotDestination.CHAT,

    // Voice recording
    val isRecording: Boolean = false,

    // Robot Mode (continuous VAD listening; runs only while ROBOT tab is selected)
    val robotModeRunning: Boolean = false,
    val robotModeStatus: RobotModeStatus = RobotModeStatus.Ready,
    val lastSpeech: SpeechSegment? = null,

    // USB connection
    val usbStatus: String = "Disconnected",

    // BLE connection
    val bleStatus: String = "Disconnected",
    val bleScanResults: List<Pair<String, String>> = emptyList(),

    // Live MCU telemetry (battery / motion / faults)
    val mcu: McuTelemetry = McuTelemetry(),
)

enum class ModelStatus {
    Idle, Loading, Ready, Error
}
