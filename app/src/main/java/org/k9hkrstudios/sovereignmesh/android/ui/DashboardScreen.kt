/*
 * Sovereign Mesh (Android)
 * Copyright (C) 2025 Sovereign Mesh Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.k9hkrstudios.sovereignmesh.android.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.k9hkrstudios.sovereignmesh.android.database.Channel
import org.k9hkrstudios.sovereignmesh.android.database.Message
import org.k9hkrstudios.sovereignmesh.android.hardware.ble.BleConnectionState
import org.k9hkrstudios.sovereignmesh.android.hardware.usb.UsbConnectionState
import org.k9hkrstudios.sovereignmesh.android.ui.theme.CryptoGreen
import org.k9hkrstudios.sovereignmesh.android.ui.theme.CryptoTeal
import org.k9hkrstudios.sovereignmesh.android.ui.theme.StealthSurface
import org.k9hkrstudios.sovereignmesh.android.ui.theme.TacticalAmber
import org.k9hkrstudios.sovereignmesh.android.ui.theme.TacticalRed
import org.k9hkrstudios.sovereignmesh.android.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DashboardScreen is the primary user interface for managing hardware connections,
 * selecting communication channels, and viewing decrypted mesh traffic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SovereignViewModel,
    modifier: Modifier = Modifier
) {
    val usbState by viewModel.usbConnectionState.collectAsState()
    val bleState by viewModel.bleConnectionState.collectAsState()
    val bleErrorMessage by viewModel.bleErrorMessage.collectAsState()
    val bleDevices by viewModel.discoveredBleDevices.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val activeChannel by viewModel.activeChannel.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    val myNodeId by viewModel.myNodeId.collectAsState()

    var showAddChannelDialog by remember { mutableStateOf(false) }
    var textMessage by remember { mutableStateOf("") }

    var stegoBackupFilePath by remember { mutableStateOf<String?>(null) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var extractedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ConnectionControlsCard(
                    usbState = usbState,
                    bleState = bleState,
                    bleDevices = bleDevices,
                    bleErrorMessage = bleErrorMessage,
                    onConnectUsb = { viewModel.autoConnectUsb() },
                    onDisconnectUsb = { viewModel.disconnectUsb() },
                    onStartBleScan = { viewModel.startBleScan() },
                    onStopBleScan = { viewModel.stopBleScan() },
                    onConnectBle = { viewModel.connectBle(it) },
                    onDisconnectBle = { viewModel.disconnectBle() }
                )
            }

            item {
                Spacer(modifier = Modifier.height(2.dp))
                ChannelSelectorPanel(
                    channels = channels,
                    activeChannel = activeChannel,
                    unreadCounts = unreadCounts,
                    onSelectChannel = { viewModel.selectChannel(it) },
                    onAddChannelClick = { showAddChannelDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁 DECRYPTED MESSAGES LOG",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CryptoTeal,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "🗑️ CLEAR LOGS",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { viewModel.clearAllMessages() }
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        )
                        Text(
                            text = "📂 EXTRACT STEGO",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = CryptoGreen,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { showExtractDialog = true }
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO LOCAL RECORDS FOUND\n(Verify connection and channel keys)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }
                }
            } else {
                items(messages) { msg ->
                    MessageItem(
                        message = msg,
                        myNodeId = myNodeId,
                        onSpeakClick = { viewModel.speak(msg.payloadDecrypted ?: "") },
                        onStegoBackupClick = {
                            val filePath = viewModel.saveStegoBackup(msg.payloadDecrypted ?: "")
                            if (filePath != null) {
                                stegoBackupFilePath = filePath
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textMessage,
                onValueChange = { textMessage = it },
                placeholder = { Text("Enter private transmission...", fontFamily = FontFamily.Monospace) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CryptoGreen,
                    unfocusedBorderColor = CryptoTeal,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (textMessage.isNotBlank()) {
                        viewModel.sendMessage(textMessage)
                        textMessage = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CryptoGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Text("SEND", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }

    if (showAddChannelDialog) {
        AddChannelDialog(
            onDismiss = { showAddChannelDialog = false },
            onConfirm = { name, useAes256 ->
                viewModel.addChannel(name, useAes256)
                showAddChannelDialog = false
            }
        )
    }

    if (stegoBackupFilePath != null) {
        AlertDialog(
            onDismissRequest = { stegoBackupFilePath = null },
            title = {
                Text(
                    "STEGANOGRAPHIC BACKUP",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Decrypted payload embedded using LSB rotation (Red, Green, Blue) into a secure local PNG carrier image.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Saved path:\n${stegoBackupFilePath}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = CryptoTeal
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { stegoBackupFilePath = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CryptoGreen, contentColor = Color.Black)
                ) {
                    Text("OK", fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = StealthSurface
        )
    }

    if (showExtractDialog) {
        val backups = remember { viewModel.listStegoBackups() }
        AlertDialog(
            onDismissRequest = { showExtractDialog = false },
            title = {
                Text(
                    "EXTRACT FROM CARRIER",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    if (backups.isEmpty()) {
                        Text(
                            "No steganographic backups (.png files) found in local app storage.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    } else {
                        Text(
                            "Select a local carrier file to extract hidden message bytes:",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            items(backups) { file ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val secret = viewModel.extractStegoBackup(file)
                                            extractedMessage = secret ?: "[Extraction Failed: Invalid/Empty Payload]"
                                            showExtractDialog = false
                                        }
                                ) {
                                    Text(
                                        text = file.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = CryptoTeal,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExtractDialog = false }) {
                    Text("CLOSE", color = TacticalRed, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = StealthSurface
        )
    }

    if (extractedMessage != null) {
        AlertDialog(
            onDismissRequest = { extractedMessage = null },
            title = {
                Text(
                    "EXTRACTED SECRET DATA",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = extractedMessage!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = { extractedMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CryptoGreen, contentColor = Color.Black)
                ) {
                    Text("OK", fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = StealthSurface
        )
    }
}

/**
 * Card containing controls for initiating and monitoring USB-OTG and BLE connections.
 */
@Composable
fun ConnectionControlsCard(
    usbState: UsbConnectionState,
    bleState: BleConnectionState,
    bleDevices: List<BluetoothDevice>,
    bleErrorMessage: String?,
    onConnectUsb: () -> Unit,
    onDisconnectUsb: () -> Unit,
    onStartBleScan: () -> Unit,
    onStopBleScan: () -> Unit,
    onConnectBle: (BluetoothDevice) -> Unit,
    onDisconnectBle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "⚡ HARDWARE CONNECTIVITY CONTROL",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CryptoGreen,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            // USB Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔌 USB-OTG:", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(6.dp))
                    val statusText = when (usbState) {
                        UsbConnectionState.CONNECTED -> "CONNECTED"
                        UsbConnectionState.CONNECTING -> "CONNECTING"
                        UsbConnectionState.ERROR -> "ERROR"
                        UsbConnectionState.DISCONNECTED -> "DISCONNECTED"
                    }
                    val statusColor = when (usbState) {
                        UsbConnectionState.CONNECTED -> CryptoGreen
                        UsbConnectionState.CONNECTING -> TacticalAmber
                        UsbConnectionState.ERROR -> TacticalRed
                        UsbConnectionState.DISCONNECTED -> TextMuted
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (usbState == UsbConnectionState.CONNECTED) {
                    Button(
                        onClick = onDisconnectUsb,
                        colors = ButtonDefaults.buttonColors(containerColor = TacticalRed),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("RELEASE", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    Button(
                        onClick = onConnectUsb,
                        colors = ButtonDefaults.buttonColors(containerColor = CryptoTeal),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("CONNECT", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // BLE Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📡 BLE LINK:", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(6.6.dp))
                    val statusText = when (bleState) {
                        BleConnectionState.CONNECTED -> "CONNECTED"
                        BleConnectionState.SCANNING -> "SCANNING"
                        BleConnectionState.CONNECTING -> "CONNECTING"
                        BleConnectionState.ERROR -> "ERROR"
                        BleConnectionState.DISCONNECTED -> "DISCONNECTED"
                    }
                    val statusColor = when (bleState) {
                        BleConnectionState.CONNECTED -> CryptoGreen
                        BleConnectionState.SCANNING -> TacticalAmber
                        BleConnectionState.CONNECTING -> TacticalAmber
                        BleConnectionState.ERROR -> TacticalRed
                        BleConnectionState.DISCONNECTED -> TextMuted
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row {
                    if (bleState == BleConnectionState.SCANNING) {
                        Button(
                            onClick = onStopBleScan,
                            colors = ButtonDefaults.buttonColors(containerColor = TacticalRed),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("STOP SCAN", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else if (bleState == BleConnectionState.CONNECTED) {
                        Button(
                            onClick = onDisconnectBle,
                            colors = ButtonDefaults.buttonColors(containerColor = TacticalRed),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("DISCONNECT", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Button(
                            onClick = onStartBleScan,
                            colors = ButtonDefaults.buttonColors(containerColor = CryptoTeal),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("SCAN", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (bleState == BleConnectionState.ERROR && bleErrorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠️ $bleErrorMessage",
                    color = TacticalRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Discovered BLE Devices dropdown
            if (bleState == BleConnectionState.SCANNING && bleDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "TARGET NODES FOUND:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TacticalAmber,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    bleDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConnectBle(device) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            @SuppressLint("MissingPermission")
                            val name = device.name ?: "Unknown Node"
                            Text(
                                text = "• $name (${device.address})",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "CONNECT",
                                fontSize = 11.sp,
                                color = CryptoGreen,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Panel for selecting active communication channels and creating new ones.
 */
@Composable
fun ChannelSelectorPanel(
    channels: List<Channel>,
    activeChannel: Channel?,
    unreadCounts: Map<String, Int>,
    onSelectChannel: (Channel) -> Unit,
    onAddChannelClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔑 CRYPTOGRAPHIC CHANNELS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = CryptoGreen,
                    fontFamily = FontFamily.Monospace
                )
                Button(
                    onClick = onAddChannelClick,
                    colors = ButtonDefaults.buttonColors(containerColor = CryptoTeal),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("+ ADD", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            channels.forEach { channel ->
                val isActive = channel == activeChannel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelectChannel(channel) }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "# ${channel.name}",
                            color = if (isActive) CryptoGreen else Color.White,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        val unreadCount = unreadCounts[channel.id] ?: 0
                        if (unreadCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(TacticalRed, shape = RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 9.sp,
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    )
                                )
                            }
                        }
                    }
                    
                    val keyDesc = if (channel.psk.size == 32) "AES-256" else "AES-128"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isActive) CryptoGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = keyDesc,
                            fontSize = 10.sp,
                            color = if (isActive) CryptoGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual message bubble displaying decrypted payload and metadata.
 */
@Composable
fun MessageItem(
    message: Message,
    myNodeId: Int,
    onSpeakClick: () -> Unit,
    onStegoBackupClick: () -> Unit
) {
    val isMyMessage = message.senderId == myNodeId
    val alignment = if (isMyMessage) Alignment.End else Alignment.Start
    val bubbleColor = if (isMyMessage) CryptoTeal.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
    val badgeColor = if (message.payloadDecrypted?.startsWith("[Decryption") == true) TacticalRed else CryptoGreen

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val senderLabel = if (isMyMessage) "Me (0x${Integer.toHexString(message.senderId)})" else "Node 0x${Integer.toHexString(message.senderId)}"
            Text(
                text = senderLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CryptoGreen,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Text(
                text = sdf.format(Date(message.timestamp * 1000)),
                fontSize = 10.sp,
                color = TextMuted,
                fontFamily = FontFamily.Monospace
            )
        }
        
        Spacer(modifier = Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp,
                        bottomStart = if (isMyMessage) 8.dp else 0.dp,
                        bottomEnd = if (isMyMessage) 0.dp else 8.dp
                    )
                )
                .background(bubbleColor)
                .padding(10.dp)
        ) {
            Column {
                Text(
                    text = message.payloadDecrypted ?: "[NO PAYLOAD]",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(badgeColor.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = message.encryptionType,
                                fontSize = 8.sp,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PKG ID: 0x${Integer.toHexString(message.packetId)}",
                            fontSize = 8.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Speak and Stego local actions
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔊 READ",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CryptoGreen,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { onSpeakClick() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "🖼️ STEGO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CryptoTeal,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { onStegoBackupClick() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for generating a new symmetric encryption channel.
 */
@Composable
fun AddChannelDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, useAes256: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var useAes256 by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "GENERATE SYMMETRIC CHANNEL",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel Name", fontFamily = FontFamily.Monospace) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useAes256,
                        onCheckedChange = { useAes256 = it },
                        colors = CheckboxDefaults.colors(checkedColor = CryptoGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "AES-256 Encryption (Highly Secure)",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "Unchecked creates standard AES-128 keys.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, useAes256)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CryptoGreen, contentColor = Color.Black)
            ) {
                Text("GENERATE", fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TacticalRed, fontFamily = FontFamily.Monospace)
            }
        },
        containerColor = StealthSurface
    )
}
