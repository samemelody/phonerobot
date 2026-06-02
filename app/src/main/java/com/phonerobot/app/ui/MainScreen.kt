package com.phonerobot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonerobot.app.ai.ChatMessage
import com.phonerobot.app.RobotModeActivity

/**
 * Navigation destinations for the bottom nav bar.
 */
enum class PhoneRobotDestination(val icon: ImageVector, val label: String) {
    CHAT(Icons.Default.Chat, "Chat"),
    ROBOT_MODE(Icons.Default.Mic, "Robot"),
}

/**
 * Main screen with bottom navigation bar (Chat / Call tabs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: PhoneRobotUiState,
    currentDestination: PhoneRobotDestination,
    onDestinationChanged: (PhoneRobotDestination) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onVoiceClicked: () -> Unit,
    onCallClick: () -> Unit,
    onConnectUsb: () -> Unit = {},
    onScanBle: () -> Unit = {},
    onConnectBle: (String) -> Unit = {},
    onDisconnectBle: () -> Unit = {},
) {
    Scaffold(
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
            // -- Status Bar --
            ModelStatusBar(state.modelStatus)

            Spacer(Modifier.height(8.dp))

            // -- BLE Status Bar (primary) --
            BleStatusBar(
                bleStatus = state.bleStatus,
                scanResults = state.bleScanResults,
                onScan = onScanBle,
                onConnect = onConnectBle,
                onDisconnect = onDisconnectBle,
            )

            Spacer(Modifier.height(12.dp))

            // -- Content based on selected tab --
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
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                selected = currentDestination == destination,
                onClick = { onDestinationChanged(destination) }
            )
        }
    }
}

// ==================== Status Bar ====================

/**
 * Shows model loading / ready / error status.
 */
@Composable
private fun ModelStatusBar(status: ModelStatus) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                ModelStatus.Ready -> Color(0xFFE8F5E9)
                ModelStatus.Loading -> Color(0xFFFFF3E0)
                ModelStatus.Error -> Color(0xFFFFEBEE)
                else -> Color(0xFFF5F5F5)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (status == ModelStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                ModelStatus.Ready -> Color(0xFF4CAF50)
                                ModelStatus.Loading -> Color(0xFFFF9800)
                                ModelStatus.Error -> Color(0xFFF44336)
                                else -> Color(0xFF9E9E9E)
                            }
                        )
                )
            }

            Text(
                text = when (status) {
                    ModelStatus.Idle -> "Model not loaded"
                    ModelStatus.Loading -> "Loading Gemma 4..."
                    ModelStatus.Ready -> "Gemma 4 Ready"
                    ModelStatus.Error -> "Model Error - check logs"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp),
                color = when (status) {
                    ModelStatus.Error -> Color(0xFFD32F2F)
                    else -> Color.DarkGray
                }
            )
        }
    }
}

// ==================== BLE Status Bar ====================

/**
 * Shows BLE connection status, scan button, and discovered devices.
 */
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

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> Color(0xFFE3F2FD)
                bleStatus.contains("fail", ignoreCase = true) -> Color(0xFFFFEBEE)
                isScanning -> Color(0xFFFFF3E0)
                scanResults.isNotEmpty() -> Color(0xFFE8F5E9)
                else -> Color(0xFFF5F5F5)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFFF9800)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "BLE",
                        tint = when {
                            isConnected -> Color(0xFF1976D2)
                            else -> Color.Gray
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = when {
                        isScanning -> "BLE: Scanning..."
                        isConnected -> "BLE: Connected"
                        scanResults.isNotEmpty() -> "BLE: ${scanResults.size} device(s) found"
                        else -> "BLE: $bleStatus"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                    color = when {
                        isConnected -> Color(0xFF1565C0)
                        bleStatus.contains("fail", ignoreCase = true) -> Color(0xFFD32F2F)
                        else -> Color.DarkGray
                    }
                )
            }

            Row {
                // Show device list button (when results available and not connected)
                if (scanResults.isNotEmpty() && !isConnected && !isScanning) {
                    Box {
                        TextButton(onClick = { showDeviceList = true }) {
                            Text("Select", fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = showDeviceList,
                            onDismissRequest = { showDeviceList = false }
                        ) {
                            scanResults.forEach { (name, address) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                            Text(address, fontSize = 11.sp, color = Color.Gray)
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

                // Disconnect button (when connected)
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935)
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 4.dp
                        )
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Disconnect",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Disconnect", fontSize = 12.sp, color = Color.White)
                    }
                }

                // Scan button (when not connected)
                if (!isConnected) {
                    Button(
                        onClick = onScan,
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2)
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 4.dp
                        )
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Scan",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Scan", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// ==================== USB Status Bar ====================

/**
 * Shows USB connection status and connect button.
 */
@Composable
private fun UsbStatusBar(usbStatus: String, onConnect: () -> Unit) {
    val isConnected = usbStatus.equals("Connected", ignoreCase = true)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> Color(0xFFE8F5E9)
                usbStatus.contains("fail", ignoreCase = true) ||
                    usbStatus.contains("denied", ignoreCase = true) -> Color(0xFFFFEBEE)
                else -> Color(0xFFF5F5F5)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = "USB",
                    tint = when {
                        isConnected -> Color(0xFF4CAF50)
                        else -> Color.Gray
                    },
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "USB: $usbStatus",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                    color = when {
                        isConnected -> Color(0xFF2E7D32)
                        usbStatus.contains("fail", ignoreCase = true) ||
                            usbStatus.contains("denied", ignoreCase = true) -> Color(0xFFD32F2F)
                        else -> Color.DarkGray
                    }
                )
            }

            if (!isConnected) {
                TextButton(onClick = onConnect) {
                    Text("Connect", fontSize = 13.sp)
                }
            }
        }
    }
}

// ==================== Chat Panel ====================

/**
 * Chat conversation + input bar.
 */
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Message list
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier.weight(1f)
            ) {
                item {
                    if (isThinking) {
                        ChatBubble(
                            role = ChatMessage.Role.ASSISTANT,
                            text = "Thinking...",
                            isTyping = true
                        )
                    }
                }
                items(messages.reversed()) { msg ->
                    ChatBubble(role = msg.role, text = msg.content)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Input row
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text("Say something...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    singleLine = false
                )

                IconButton(onClick = { onVoice() }) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Voice",
                        tint = if (isRecording) Color(0xFFF44336) else Color(0xFF1976D2)
                    )
                }

                IconButton(
                    onClick = { onSend() },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) Color(0xFF1976D2) else Color.Gray
                    )
                }
            }
        }
    }
}

// ==================== Robot Mode Panel ====================

/**
 * Robot Mode panel — continuous listening mode.
 * Launches RobotModeActivity for voice command processing.
 */
@Composable
private fun RobotModePanel(
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isActive by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "Robot Mode",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2196F3)
            )

            Spacer(Modifier.height(32.dp))

            // Robot mode button
            IconButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(context, RobotModeActivity::class.java)
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        Color(0xFF2196F3),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Start Robot Mode",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Tap to start continuous listening",
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Robot Mode: Continuous listening for voice commands.\nAI processes speech and controls robot.",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ==================== Chat Bubble ====================

/**
 * A single chat bubble.
 */
@Composable
private fun ChatBubble(role: ChatMessage.Role, text: String, isTyping: Boolean = false) {
    val isUser = role == ChatMessage.Role.USER
    Row(
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Color(0xFFBBDEFB) else Color(0xFFE8F5E9))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isTyping) {
                Row {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = text, fontSize = 15.sp, color = Color.Gray)
                }
            } else {
                Text(text = text, fontSize = 15.sp)
            }
        }
    }
}
