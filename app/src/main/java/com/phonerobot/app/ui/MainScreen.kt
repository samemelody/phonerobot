package com.phonerobot.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonerobot.app.R
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.RobotModeActivity

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
            ModelStatusBar(state.modelStatus)

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
private fun ModelStatusBar(status: ModelStatus) {
    val level = when (status) {
        ModelStatus.Ready -> StatusLevel.Success
        ModelStatus.Loading -> StatusLevel.Progress
        ModelStatus.Error -> StatusLevel.Error
        ModelStatus.Idle -> StatusLevel.Neutral
    }
    val text = when (status) {
        ModelStatus.Idle -> stringResource(R.string.model_status_idle)
        ModelStatus.Loading -> stringResource(R.string.model_status_loading)
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
                            role = ChatMessage.Role.ASSISTANT,
                            text = stringResource(R.string.ai_thinking),
                            isTyping = true,
                            maxWidth = bubbleMaxWidth
                        )
                    }
                }
                items(messages.reversed()) { msg ->
                    ChatBubble(role = msg.role, text = msg.content, maxWidth = bubbleMaxWidth)
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

@Composable
private fun ChatBubble(
    role: ChatMessage.Role,
    text: String,
    maxWidth: Dp,
    isTyping: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme

    if (role == ChatMessage.Role.SYSTEM && !isTyping) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        return
    }

    val isUser = role == ChatMessage.Role.USER
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

    Row(
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isTyping) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = fg
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = fg)
                }
            } else {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = fg)
            }
        }
    }
}
