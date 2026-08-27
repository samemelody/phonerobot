package com.phonerobot.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonerobot.app.R
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.RobotModeActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Navigation destinations for the bottom nav bar.
 */
enum class PhoneRobotDestination(val icon: ImageVector, val labelRes: Int) {
    CHAT(Icons.AutoMirrored.Filled.Chat, R.string.nav_chat),
    ROBOT_MODE(Icons.Default.Mic, R.string.nav_robot),
}

// USB hardware currently unusable (pin issue) — flip to true once fixed
private const val SHOW_USB_STATUS = false

private enum class StatusLevel { Neutral, Info, Progress, Success, Error }

/**
 * Main screen with bottom navigation bar (Chat / Robot tabs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: PhoneRobotUiState,
    snackbarHostState: SnackbarHostState,
    onDestinationChanged: (PhoneRobotDestination) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onVoiceClicked: () -> Unit,
    onConnectUsb: () -> Unit = {},
    onScanBle: () -> Unit = {},
    onConnectBle: (String) -> Unit = {},
    onDisconnectBle: () -> Unit = {},
) {
    val currentDestination = state.currentDestination
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            PhoneRobotNavBar(
                currentDestination = currentDestination,
                onDestinationChanged = onDestinationChanged,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            ModelStatusBar(state.modelStatus, state.modelLoadingElapsedSec)

            if (SHOW_USB_STATUS) {
                Spacer(Modifier.height(8.dp))
                UsbStatusBar(
                    usbStatus = state.usbStatus,
                    onConnect = onConnectUsb,
                )
            }

            Spacer(Modifier.height(8.dp))

            BleStatusBar(
                bleStatus = state.bleStatus,
                scanResults = state.bleScanResults,
                onScan = onScanBle,
                onConnect = onConnectBle,
                onDisconnect = onDisconnectBle,
            )

            Spacer(Modifier.height(12.dp))

            when (currentDestination) {
                PhoneRobotDestination.CHAT -> {
                    ChatPanel(
                        messages = state.messages,
                        isThinking = state.isAiThinking,
                        isRecording = state.isRecording,
                        inputText = state.currentInput,
                        onInputChange = onInputChanged,
                        onSend = onSendClicked,
                        onVoice = onVoiceClicked,
                        modifier = Modifier.weight(1f),
                    )
                }
                PhoneRobotDestination.ROBOT_MODE -> {
                    RobotModePanel(
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ==================== Navigation Bar ====================

@Composable
private fun PhoneRobotNavBar(
    currentDestination: PhoneRobotDestination,
    onDestinationChanged: (PhoneRobotDestination) -> Unit,
) {
    NavigationBar {
        PhoneRobotDestination.entries.forEach { destination ->
            NavigationBarItem(
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) },
                selected = currentDestination == destination,
                onClick = { onDestinationChanged(destination) }
            )
        }
    }
}

// ==================== Status Cards ====================

@Composable
private fun StatusCard(
    level: StatusLevel,
    text: String,
    leading: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val status = PhoneRobotThemeDefaults.statusColors
    val (container, content) = when (level) {
        StatusLevel.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
        StatusLevel.Info -> scheme.primaryContainer to scheme.onPrimaryContainer
        StatusLevel.Progress -> status.warningContainer to status.onWarningContainer
        StatusLevel.Success -> status.successContainer to status.onSuccessContainer
        StatusLevel.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            leading()
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
    }
}

@Composable
private fun LoadingIndicator() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
}

@Composable
private fun ModelStatusBar(status: ModelStatus, loadingElapsedSec: Int = 0) {
    val level = when (status) {
        ModelStatus.Ready -> StatusLevel.Success
        ModelStatus.Loading -> StatusLevel.Progress
        ModelStatus.Error -> StatusLevel.Error
        ModelStatus.Idle -> StatusLevel.Neutral
    }
    val text = when (status) {
        ModelStatus.Idle -> stringResource(R.string.model_status_idle)
        ModelStatus.Loading -> stringResource(R.string.model_status_loading, loadingElapsedSec)
        ModelStatus.Ready -> stringResource(R.string.model_status_ready)
        ModelStatus.Error -> stringResource(R.string.model_status_error)
    }
    val icon = when (status) {
        ModelStatus.Ready -> Icons.Default.CheckCircle
        ModelStatus.Error -> Icons.Default.Error
        else -> Icons.Default.RadioButtonUnchecked
    }
    StatusCard(
        level = level,
        text = text,
        leading = {
            if (status == ModelStatus.Loading) {
                LoadingIndicator()
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    )
}

@Composable
private fun BleStatusBar(
    bleStatus: String,
    scanResults: List<Pair<String, String>>,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit = {},
) {
    val isConnected = bleStatus.equals("Connected", ignoreCase = true)
    val isScanning = bleStatus.equals("Scanning...", ignoreCase = true)
    var showDeviceList by remember { mutableStateOf(false) }

    val level = when {
        isConnected -> StatusLevel.Success
        bleStatus.contains("fail", ignoreCase = true) -> StatusLevel.Error
        isScanning -> StatusLevel.Progress
        scanResults.isNotEmpty() -> StatusLevel.Info
        else -> StatusLevel.Neutral
    }
    val text = when {
        isScanning -> stringResource(R.string.ble_scanning)
        isConnected -> stringResource(R.string.ble_connected)
        scanResults.isNotEmpty() -> stringResource(R.string.ble_devices_found, scanResults.size)
        else -> stringResource(R.string.ble_status_value, bleStatus)
    }

    StatusCard(
        level = level,
        text = text,
        leading = {
            if (isScanning) {
                LoadingIndicator()
            } else {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        if (scanResults.isNotEmpty() && !isConnected && !isScanning) {
            Box {
                TextButton(onClick = { showDeviceList = true }) {
                    Text(stringResource(R.string.btn_select))
                }
                DropdownMenu(
                    expanded = showDeviceList,
                    onDismissRequest = { showDeviceList = false }
                ) {
                    scanResults.forEach { (name, address) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, fontWeight = FontWeight.Medium)
                                    Text(
                                        address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                showDeviceList = false
                                onConnect(address)
                            }
                        )
                    }
                }
            }
        }

        if (isConnected) {
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 6.dp
                )
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.cd_disconnect),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_disconnect))
            }
        } else {
            Button(
                onClick = onScan,
                enabled = !isScanning,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 6.dp
                )
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.cd_scan),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_scan))
            }
        }
    }
}

@Composable
private fun UsbStatusBar(usbStatus: String, onConnect: () -> Unit) {
    val isConnected = usbStatus.equals("Connected", ignoreCase = true)
    val hasProblem = usbStatus.contains("fail", ignoreCase = true) ||
        usbStatus.contains("denied", ignoreCase = true)

    StatusCard(
        level = when {
            isConnected -> StatusLevel.Success
            hasProblem -> StatusLevel.Error
            else -> StatusLevel.Neutral
        },
        text = stringResource(R.string.usb_status_value, usbStatus),
        leading = { Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp)) }
    ) {
        if (!isConnected) {
            TextButton(onClick = onConnect) {
                Text(stringResource(R.string.btn_connect))
            }
        }
    }
}

// ==================== Chat Panel ====================

@Composable
private fun ChatPanel(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    isRecording: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleMaxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier.weight(1f)
            ) {
                item {
                    if (isThinking) {
                        ChatBubble(
                            message = ChatMessage(
                                ChatMessage.Role.ASSISTANT,
                                stringResource(R.string.ai_thinking)
                            ),
                            isTyping = true,
                            maxWidth = bubbleMaxWidth
                        )
                    }
                }
                items(
                    messages.reversed(),
                    key = { msg -> msg.timestampMs * 31L + msg.content.hashCode() }
                ) { msg ->
                    ChatBubble(message = msg, maxWidth = bubbleMaxWidth)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text(stringResource(R.string.input_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    singleLine = false
                )

                IconButton(onClick = { onVoice() }) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (isRecording) R.string.cd_stop_recording else R.string.cd_voice
                        ),
                        tint = if (isRecording) scheme.error else scheme.primary
                    )
                }

                IconButton(
                    onClick = { onSend() },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.cd_send),
                        tint = if (inputText.isNotBlank()) scheme.primary else scheme.outline
                    )
                }
            }
        }
    }
}

// ==================== Robot Mode Panel ====================

@Composable
private fun RobotModePanel(
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.robot_mode_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(32.dp))

            IconButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(context, RobotModeActivity::class.java)
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_start_robot_mode),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.robot_mode_start_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.robot_mode_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==================== Chat Bubble ====================

private data class ContentSegment(val isCode: Boolean, val text: String)

private fun parseContentSegments(text: String): List<ContentSegment> {
    if (!text.contains("```")) return listOf(ContentSegment(false, text))

    val segments = mutableListOf<ContentSegment>()
    var remaining = text
    while (true) {
        val start = remaining.indexOf("```")
        if (start < 0) {
            if (remaining.isNotEmpty()) segments.add(ContentSegment(false, remaining))
            break
        }
        val before = remaining.substring(0, start)
        if (before.isNotEmpty()) segments.add(ContentSegment(false, before))
        val lineBreak = remaining.indexOf('\n', start)
        val codeStart = if (lineBreak in (start + 1)..remaining.length) lineBreak + 1 else start + 3
        val end = remaining.indexOf("```", codeStart)
        if (end < 0) {
            segments.add(ContentSegment(true, remaining.substring(codeStart)))
            break
        }
        segments.add(ContentSegment(true, remaining.substring(codeStart, end).trimEnd('\n')))
        remaining = remaining.substring(end + 3)
    }
    return segments
}

@Composable
private fun TimestampText(timestampMs: Long) {
    val time = remember(timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
    Text(
        text = time,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    maxWidth: Dp,
    isTyping: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    if (message.role == ChatMessage.Role.SYSTEM && !isTyping) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        return
    }

    if (message.role == ChatMessage.Role.TOOL && !isTyping) {
        ToolCard(message = message, maxWidth = maxWidth)
        return
    }

    val isUser = message.role == ChatMessage.Role.USER
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    }
    val (bg, fg) = if (isUser) {
        scheme.primaryContainer to scheme.onPrimaryContainer
    } else {
        scheme.surfaceVariant to scheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(bg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { clipboard.setText(AnnotatedString(message.content)) }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isTyping) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(color = fg)
                    Spacer(Modifier.width(8.dp))
                    Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = fg)
                }
            } else {
                val segments = remember(message.content) { parseContentSegments(message.content) }
                Column {
                    segments.forEach { segment ->
                        if (segment.isCode) {
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.inverseSurface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = segment.text.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = scheme.inverseOnSurface
                                )
                            }
                        } else {
                            Text(
                                text = segment.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = fg
                            )
                        }
                    }
                }
            }
        }
        TimestampText(message.timestampMs)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(message: ChatMessage, maxWidth: Dp) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message.timestampMs) { mutableStateOf(false) }

    val separator = message.content.indexOf("\n\n")
    val summary = if (separator > 0) message.content.substring(0, separator) else message.content
    val body = if (separator > 0) message.content.substring(separator + 2) else ""

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            tonalElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { clipboard.setText(AnnotatedString(message.content)) }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = message.toolName ?: "tool",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = summary.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (expanded && body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.inverseSurface)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.inverseOnSurface
                        )
                    }
                }
            }
        }
        TimestampText(message.timestampMs)
    }
}

@Composable
private fun TypingDots(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val transition = rememberInfiniteTransition(label = "typing_dot_$index")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}
