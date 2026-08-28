package com.phonerobot.app.ui

import androidx.compose.runtime.Immutable

/** A captured speech segment queued for AI processing. */
@Immutable
data class SpeechSegment(
    val fileName: String,
    val bytes: Int,
)

/** Robot Mode lifecycle status; resolved to string resources in the Compose layer. */
sealed interface RobotModeStatus {
    data object Ready : RobotModeStatus
    data object Listening : RobotModeStatus
    data object RecordingSpeech : RobotModeStatus
    data object Processing : RobotModeStatus
    data object Stopped : RobotModeStatus
    data object ModelNotReady : RobotModeStatus
    data object PermissionRequired : RobotModeStatus
    data object PermissionDenied : RobotModeStatus
    data class Error(val message: String) : RobotModeStatus
}
