package com.phonerobot.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonerobot.app.ai.ChatMessage

/**
 * Centralized app state for the Phone Robot UI.
 *
 * Includes:
 * - Model status tracking
 * - Chat / AI conversation state
 * - Navigation state (Chat / Call tabs)
 * - Call state (reserved for future voice call module)
 * - Settings dialog state
 */
data class PhoneRobotUiState(
    // Model status
    val modelStatus: ModelStatus = ModelStatus.Idle,
    val modelLoadingProgress: Float = 0f,

    // Chat / AI conversation
    val messages: List<ChatMessage> = emptyList(),
    val currentInput: String = "",
    val isAiThinking: Boolean = false,

    // Navigation
    val currentDestination: PhoneRobotDestination = PhoneRobotDestination.CHAT,

    // Voice recording
    val isRecording: Boolean = false,

    // Call state (reserved for future voice call module)
    val isOnCall: Boolean = false,

    // USB connection
    val usbStatus: String = "Disconnected",

    // BLE connection
    val bleStatus: String = "Disconnected",
    val bleScanResults: List<Pair<String, String>> = emptyList(),  // (name, address)

    // Settings
    val showSettings: Boolean = false,
)

enum class ModelStatus {
    Idle, Loading, Ready, Error
}

/**
 * Mutable state holder for UI logic (ViewModel-like, but simple for v1).
 */
class PhoneRobotStateHolder {
    var uiState by mutableStateOf(PhoneRobotUiState())
        private set

    fun update(transform: (PhoneRobotUiState) -> PhoneRobotUiState) {
        uiState = transform(uiState)
    }
}
