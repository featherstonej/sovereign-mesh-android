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

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.k9hkrstudios.sovereignmesh.android.crypto.MeshCryptoEngine
import org.k9hkrstudios.sovereignmesh.android.database.Channel
import org.k9hkrstudios.sovereignmesh.android.database.MeshDatabaseHelper
import org.k9hkrstudios.sovereignmesh.android.database.Message
import org.k9hkrstudios.sovereignmesh.android.database.SignalLog
import org.k9hkrstudios.sovereignmesh.android.hardware.MeshHardwareService
import org.k9hkrstudios.sovereignmesh.android.hardware.ble.BleConnectionState
import org.k9hkrstudios.sovereignmesh.android.hardware.usb.UsbConnectionState
import org.k9hkrstudios.sovereignmesh.android.util.stego.StegoEngine
import org.k9hkrstudios.sovereignmesh.android.util.tts.MeshTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.Portnums

/**
 * SovereignViewModel is the primary state container for the UI layer.
 *
 * It bridges the [MeshHardwareService] with the Jetpack Compose UI, handles
 * message encryption/decryption, database persistence, and tactical utilities
 * like Steganography and Text-to-Speech.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SovereignViewModel(
    private val context: Context,
    private val databaseHelper: MeshDatabaseHelper
) : ViewModel() {

    private val _service = MutableStateFlow<MeshHardwareService?>(null)
    /** The currently active [MeshHardwareService] instance. */
    val service: StateFlow<MeshHardwareService?> = _service.asStateFlow()

    // Expose flows from background service
    /** Observed connection state for the USB-OTG interface. */
    val usbConnectionState = _service.flatMapLatest { service ->
        service?.usbConnectionState ?: flowOf(UsbConnectionState.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsbConnectionState.DISCONNECTED)

    /** Observed connection state for the Bluetooth LE interface. */
    val bleConnectionState = _service.flatMapLatest { service ->
        service?.bleConnectionState ?: flowOf(BleConnectionState.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BleConnectionState.DISCONNECTED)

    /** Observed error messages from the BLE subsystem. */
    val bleErrorMessage = _service.flatMapLatest { service ->
        service?.bleErrorMessage ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Observed list of nearby Bluetooth devices. */
    val discoveredBleDevices = _service.flatMapLatest { service ->
        service?.discoveredBleDevices ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Database cached items
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    /** The list of messages for the currently active channel. */
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    /** All active communication channels stored in the database. */
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow<Channel?>(null)
    /** The channel currently selected by the user for communication. */
    val activeChannel: StateFlow<Channel?> = _activeChannel.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** A map of channel IDs to their respective unread message counts. */
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val _peerLocations = MutableStateFlow<Map<Int, Pair<Double, Double>>>(emptyMap())
    /** Discovered peer locations indexed by their node IDs. */
    val peerLocations: StateFlow<Map<Int, Pair<Double, Double>>> = _peerLocations.asStateFlow()

    private val _phoneLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    /** Current GPS coordinates of the phone for tactical mapping. */
    val phoneLocation: StateFlow<Pair<Double, Double>?> = _phoneLocation.asStateFlow()

    private val _usePhoneGps = MutableStateFlow(false)
    /** Whether the app should use the phone's internal GPS for location tracking. */
    val usePhoneGps: StateFlow<Boolean> = _usePhoneGps.asStateFlow()

    private var locationListener: android.location.LocationListener? = null

    private val secureRandom = SecureRandom()
    
    private val peerLastSeen = mutableMapOf<Int, Long>()
    
    private val _myNodeId = MutableStateFlow(0)
    /** The local node's unique ID, dynamically updated upon connection. */
    val myNodeId: StateFlow<Int> = _myNodeId.asStateFlow()

    // Local air-gapped TTS
    private val ttsEngine = MeshTtsEngine(context)

    init {
        loadChannels()
        loadMessages()
        // Periodic background pruning of expired peer location markers (TTL: 60 seconds)
        viewModelScope.launch {
            while (isActive) {
                delay(10000L) // check every 10 seconds
                val now = System.currentTimeMillis()
                val expiredIds = peerLastSeen.filter { now - it.value > 60000L }.keys
                if (expiredIds.isNotEmpty()) {
                    val current = _peerLocations.value.toMutableMap()
                    var changed = false
                    for (id in expiredIds) {
                        if (current.containsKey(id)) {
                            current.remove(id)
                            changed = true
                        }
                    }
                    if (changed) {
                        _peerLocations.value = current
                    }
                    for (id in expiredIds) {
                        peerLastSeen.remove(id)
                    }
                }
            }
        }
    }

    private var incomingPacketsJob: Job? = null
    private var connectionStateJob: Job? = null

    /**
     * Connects the ViewModel to the active [MeshHardwareService].
     */
    fun setService(hardwareService: MeshHardwareService) {
        incomingPacketsJob?.cancel()
        connectionStateJob?.cancel()

        // Listen to incoming packets from the service
        incomingPacketsJob = viewModelScope.launch {
            hardwareService.incomingPackets.collect { packetBytes ->
                handleIncomingPacket(packetBytes)
            }
        }
        // Listen to connection state transitions to request config
        connectionStateJob = viewModelScope.launch {
            combine(
                hardwareService.bleConnectionState,
                hardwareService.usbConnectionState
            ) { bleState, usbState ->
                bleState == BleConnectionState.CONNECTED || usbState == UsbConnectionState.CONNECTED
            }.distinctUntilChanged().collect { connected ->
                if (connected) {
                    requestDeviceConfig()
                } else {
                    _peerLocations.value = emptyMap()
                    peerLastSeen.clear()
                    _myNodeId.value = 0
                }
            }
        }
        _service.value = hardwareService
    }

    /**
     * Requests the full configuration from the connected Meshtastic node.
     */
    fun requestDeviceConfig() {
        val serviceRef = _service.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Small delay to allow BluetoothGatt connection/descriptors to fully settle
            kotlinx.coroutines.delay(500)
            
            // Deactivate all existing channels in DB when a new device connects
            val activeChannels = databaseHelper.getActiveChannels()
            for (ch in activeChannels) {
                databaseHelper.insertChannel(ch.copy(active = false))
            }
            loadChannels() // Refresh UI channels to clear them out before sync
            
            val nonce = secureRandom.nextInt(1000000) + 1
            val toRadio = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(nonce)
                .build()
            val bytes = toRadio.toByteArray()
            serviceRef.sendPacket(bytes)
            Log.d(TAG, "Sent want_config_id=$nonce request to device")
        }
    }

    private suspend fun handleDeviceChannel(deviceChannel: ChannelProtos.Channel) {
        if (!deviceChannel.hasSettings()) {
            return
        }
        val settings = deviceChannel.settings
        val name = settings.name
        val psk = settings.psk.toByteArray()
        val index = deviceChannel.index
        val role = deviceChannel.role
        
        val displayName = if (name.isEmpty()) {
            if (role == ChannelProtos.Channel.Role.PRIMARY) "Meshtastic Primary" else "Channel $index"
        } else {
            name
        }

        // Handle Meshtastic default key mapping
        val finalPsk = when (psk.size) {
            1 -> {
                val valByte = psk[0].toInt()
                if (valByte in 1..10) {
                    val defaultKey = byteArrayOf(
                        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a.toByte(),
                        0x20.toByte(), 0x29.toByte(), 0x07.toByte(), 0x59.toByte(),
                        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(),
                        0xcf.toByte(), 0x4e.toByte(), 0x69.toByte(), 0x01.toByte()
                    )
                    if (valByte > 1) {
                        defaultKey[15] = (defaultKey[15] + (valByte - 1)).toByte()
                    }
                    defaultKey
                } else {
                    psk
                }
            }
            else -> psk
        }

        val channelId = "dev_ch_$index"
        val active = role != ChannelProtos.Channel.Role.DISABLED
        
        if (active) {
            val currentActive = databaseHelper.getActiveChannels()
            val mockPrimary = currentActive.firstOrNull { it.id == "ch_primary" }
            if (mockPrimary != null) {
                val deactivatedMock = mockPrimary.copy(active = false)
                databaseHelper.insertChannel(deactivatedMock)
            }
        }

        val channel = Channel(
            id = channelId,
            name = displayName,
            psk = finalPsk,
            active = active
        )

        withContext(Dispatchers.IO) {
            databaseHelper.insertChannel(channel)
        }
        
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = databaseHelper.getActiveChannels()
            val isConnected = bleConnectionState.value == BleConnectionState.CONNECTED ||
                              usbConnectionState.value == UsbConnectionState.CONNECTED
            
            if (list.isEmpty() && !isConnected) {
                // Pre-populate with a default AES-256 secure channel if database is empty and not connected
                val defaultKey = ByteArray(32).apply { secureRandom.nextBytes(this) }
                val defaultChannel = Channel("ch_primary", "Stealth-Mesh-Primary", defaultKey, true)
                databaseHelper.insertChannel(defaultChannel)
                _channels.value = listOf(defaultChannel)
                _activeChannel.value = defaultChannel
            } else {
                _channels.value = list
                _activeChannel.value = list.firstOrNull()
            }
        }
    }

    /**
     * Manually adds a new secure channel with a randomly generated key.
     */
    fun addChannel(name: String, useAes256: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val keySize = if (useAes256) 32 else 16
            val keyBytes = ByteArray(keySize).apply { secureRandom.nextBytes(this) }
            val id = "ch_${System.currentTimeMillis()}"
            val channel = Channel(id, name, keyBytes, true)
            databaseHelper.insertChannel(channel)
            loadChannels()
        }
    }

    /**
     * Selects a channel as active and clears its unread message count.
     */
    fun selectChannel(channel: Channel) {
        _activeChannel.value = channel
        
        val currentCounts = _unreadCounts.value.toMutableMap()
        currentCounts.remove(channel.id)
        _unreadCounts.value = currentCounts
        
        loadMessages()
    }

    /**
     * Loads messages from the database for the currently active channel.
     */
    fun loadMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = databaseHelper.getAllMessages()
            val activeCh = _activeChannel.value
            if (activeCh != null) {
                _messages.value = all.filter { it.channelId == activeCh.id }
            } else {
                _messages.value = emptyList()
            }
        }
    }

    /**
     * Deletes all messages from the database.
     */
    fun clearAllMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            databaseHelper.clearAllMessages()
            loadMessages()
        }
    }

    // Hardware operations forwards

    /** Triggers USB auto-connect in the hardware service. */
    fun autoConnectUsb() = _service.value?.autoConnectUsb()

    /** Disconnects the active USB device. */
    fun disconnectUsb() = _service.value?.disconnectUsbDevice()

    /** Starts a Bluetooth LE device scan. */
    fun startBleScan() = _service.value?.startBleScan()

    /** Stops any active Bluetooth LE scan. */
    fun stopBleScan() = _service.value?.stopBleScan()

    /** Initiates connection to the specified BLE device. */
    fun connectBle(device: BluetoothDevice) = _service.value?.connectBleDevice(device)

    /** Disconnects the active Bluetooth LE device. */
    fun disconnectBle() = _service.value?.disconnectBleDevice()

    /**
     * Speaks decrypted mesh alerts locally using Text-to-Speech synthesis.
     */
    fun speak(text: String) {
        ttsEngine.speakAlert(text)
    }

    /**
     * LSB Steganography helper to hide payload inside a copy of the source Bitmap.
     */
    fun hideMessageInBitmap(messageText: String, source: Bitmap): Bitmap? {
        val payload = messageText.toByteArray(Charsets.UTF_8)
        return StegoEngine.hidePayload(source, payload)
    }

    /**
     * LSB Steganography helper to extract secret text from a stego Bitmap.
     */
    fun extractMessageFromBitmap(bitmap: Bitmap): String? {
        val payload = StegoEngine.extractPayload(bitmap) ?: return null
        return String(payload, Charsets.UTF_8)
    }

    /**
     * Fetch the latest signal strength log for a given node.
     */
    fun getLatestSignalLog(nodeId: Int): SignalLog? {
        return databaseHelper.getLatestSignalLog(nodeId)
    }

    /**
     * Generates a carrier bitmap, embeds the message payload, and saves it to local app storage.
     */
    fun saveStegoBackup(messageText: String): String? {
        val mockBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(mockBitmap)
        val paint = android.graphics.Paint()
        val shader = android.graphics.LinearGradient(
            0f, 0f, 256f, 256f,
            android.graphics.Color.DKGRAY, android.graphics.Color.BLUE,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, 256f, 256f, paint)
        
        paint.shader = null
        paint.color = android.graphics.Color.GREEN
        paint.textSize = 20f
        canvas.drawText("Sovereign Carrier", 20f, 50f, paint)

        val stegoBitmap = hideMessageInBitmap(messageText, mockBitmap) ?: return null
        
        return try {
            val file = java.io.File(context.filesDir, "stego_backup_${System.currentTimeMillis()}.png")
            val out = java.io.FileOutputStream(file)
            stegoBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stego backup", e)
            null
        }
    }

    /**
     * Lists all saved stego backup carrier files from local app storage.
     */
    fun listStegoBackups(): List<java.io.File> {
        val dir = context.filesDir
        return dir.listFiles { _, name -> name.startsWith("stego_backup_") && name.endsWith(".png") }?.toList() ?: emptyList()
    }

    /**
     * Loads a carrier image, extracts the embedded payload, and returns the decrypted secret string.
     */
    fun extractStegoBackup(file: java.io.File): String? {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return extractMessageFromBitmap(bitmap)
    }

    /**
     * Encrypts and transmits a raw text message, caching it locally in the database.
     */
    fun sendMessage(text: String) {
        val channel = _activeChannel.value ?: return
        val serviceRef = _service.value ?: return
        val localNodeId = _myNodeId.value
        if (localNodeId == 0) {
            Log.w(TAG, "sendMessage: Gated, local node ID is still 0 (device info not synced yet)")
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val packetId = secureRandom.nextInt(1000000000) // positive integer to avoid sign issues
            val textBytes = text.toByteArray(Charsets.UTF_8)
            
            // Extract the channel index from channel ID (e.g. dev_ch_1 -> index 1)
            val channelIndex = if (channel.id.startsWith("dev_ch_")) {
                channel.id.substringAfter("dev_ch_").toIntOrNull() ?: 0
            } else {
                0
            }

            // Encrypt using AES-CTR with the channel key (for local database caching only)
            val encryptedBytes = MeshCryptoEngine.encrypt(textBytes, channel.psk, packetId, _myNodeId.value)
            
            // Build the ToRadio protobuf wrapper containing the MeshPacket
            val toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(
                    MeshProtos.MeshPacket.newBuilder()
                        .setTo(0xFFFFFFFF.toInt()) // Broadcast
                        .setFrom(_myNodeId.value)
                        .setChannel(channelIndex)
                        .setId(packetId)
                        .setDecoded(
                            MeshProtos.Data.newBuilder()
                                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                                .setPayload(com.google.protobuf.ByteString.copyFrom(textBytes))
                        )
                )
                .build()
            
            val packetBytes = toRadio.toByteArray()
            
            // Transmit protobuf packet over active hardware link
            val sendSuccess = serviceRef.sendPacket(packetBytes) >= 0
            Log.d(TAG, "sendMessage: sent ToRadio packet, success=$sendSuccess, channelIndex=$channelIndex")
            
            if (encryptedBytes != null) {
                // Cache locally as sent message
                val msg = Message(
                    id = 0,
                    packetId = packetId,
                    senderId = _myNodeId.value,
                    receiverId = 0, // Broadcast
                    timestamp = System.currentTimeMillis() / 1000,
                    payloadEncrypted = encryptedBytes,
                    payloadDecrypted = text,
                    channelId = channel.id,
                    encryptionType = if (channel.psk.size == 32) "AES-256" else "AES-128",
                    isRx = false
                )
                databaseHelper.insertMessage(msg)
                loadMessages()
            }
        }
    }

    /**
     * Decrypts and stores an incoming packet, matching it to the active channel.
     */
    private suspend fun handleIncomingPacket(packetBytes: ByteArray) {
        withContext(Dispatchers.IO) {
            // Check if there is a 4-byte serial header (0x94, 0xC3)
            val protobufBytes = if (packetBytes.size >= 4 && packetBytes[0] == 0x94.toByte() && packetBytes[1] == 0xC3.toByte()) {
                val len = ((packetBytes[2].toInt() and 0xFF) shl 8) or (packetBytes[3].toInt() and 0xFF)
                if (packetBytes.size >= 4 + len) {
                    packetBytes.copyOfRange(4, 4 + len)
                } else {
                    packetBytes.copyOfRange(4, packetBytes.size)
                }
            } else {
                packetBytes
            }

            val fromRadio = try {
                MeshProtos.FromRadio.parseFrom(protobufBytes)
            } catch (e: Exception) {
                null
            }

            if (fromRadio != null && fromRadio.payloadVariantCase != MeshProtos.FromRadio.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) {
                when (fromRadio.payloadVariantCase) {
                    MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                        val myNodeNum = fromRadio.myInfo.myNodeNum
                        if (myNodeNum != 0) {
                            _myNodeId.value = myNodeNum
                            Log.d(TAG, "Updated local node ID to ${Integer.toHexString(myNodeNum)}")
                        }
                    }
                    MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> {
                        handleDeviceChannel(fromRadio.channel)
                    }
                    MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                        val meshPacket = fromRadio.packet
                        var plainText: String? = null
                        val packetId = meshPacket.id
                        val senderNodeId = meshPacket.from
                        
                        when (meshPacket.payloadVariantCase) {
                            MeshProtos.MeshPacket.PayloadVariantCase.DECODED -> {
                                val decodedData = meshPacket.decoded
                                if (decodedData.portnum == Portnums.PortNum.TEXT_MESSAGE_APP) {
                                    plainText = decodedData.payload.toStringUtf8()
                                } else if (decodedData.portnum == Portnums.PortNum.POSITION_APP) {
                                    try {
                                        val position = MeshProtos.Position.parseFrom(decodedData.payload)
                                        if (position.latitudeI != 0 || position.longitudeI != 0) {
                                            val lat = position.latitudeI / 1e7
                                            val lon = position.longitudeI / 1e7
                                            val peerId = senderNodeId.toInt()
                                            peerLastSeen[peerId] = System.currentTimeMillis()
                                            val current = _peerLocations.value.toMutableMap()
                                            current[peerId] = Pair(lat, lon)
                                            _peerLocations.value = current
                                        }
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }
                            }
                            MeshProtos.MeshPacket.PayloadVariantCase.ENCRYPTED -> {
                                val cipherText = meshPacket.encrypted.toByteArray()
                                val targetChannelId = "dev_ch_${meshPacket.channel}"
                                val ch = _channels.value.find { it.id == targetChannelId } ?: _activeChannel.value
                                val decrypted = if (ch != null) {
                                    MeshCryptoEngine.decrypt(
                                        cipherText,
                                        ch.psk,
                                        packetId.toInt(),
                                        senderNodeId.toInt()
                                    )
                                } else {
                                    null
                                }
                                if (decrypted != null) {
                                    val data = try {
                                        MeshProtos.Data.parseFrom(decrypted)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (data != null) {
                                        if (data.portnum == Portnums.PortNum.TEXT_MESSAGE_APP) {
                                            plainText = data.payload.toStringUtf8()
                                        } else if (data.portnum == Portnums.PortNum.POSITION_APP) {
                                            try {
                                                val position = MeshProtos.Position.parseFrom(data.payload)
                                                if (position.latitudeI != 0 || position.longitudeI != 0) {
                                                    val lat = position.latitudeI / 1e7
                                                    val lon = position.longitudeI / 1e7
                                                    val peerId = senderNodeId.toInt()
                                                    peerLastSeen[peerId] = System.currentTimeMillis()
                                                    val current = _peerLocations.value.toMutableMap()
                                                    current[peerId] = Pair(lat, lon)
                                                    _peerLocations.value = current
                                                }
                                            } catch (e: Exception) {
                                                // Ignore
                                            }
                                        }
                                    } else {
                                        // Fallback to legacy custom raw encrypted text
                                        plainText = String(decrypted, Charsets.UTF_8)
                                    }
                                }
                            }
                            else -> {}
                        }

                        if (plainText != null) {
                            val targetChannelId = "dev_ch_${meshPacket.channel}"
                            val ch = _channels.value.find { it.id == targetChannelId } ?: _activeChannel.value
                            val msg = Message(
                                id = 0,
                                packetId = packetId.toInt(),
                                senderId = senderNodeId.toInt(),
                                receiverId = _myNodeId.value,
                                timestamp = System.currentTimeMillis() / 1000,
                                payloadEncrypted = meshPacket.toByteArray(),
                                payloadDecrypted = plainText,
                                channelId = ch?.id ?: targetChannelId,
                                encryptionType = if (ch?.psk?.size == 32) "AES-256" else "AES-128",
                                isRx = true
                            )
                            databaseHelper.insertMessage(msg)

                            // Update unread counts if received on a non-active channel
                            val currentActiveCh = _activeChannel.value
                            val msgChannelId = ch?.id ?: targetChannelId
                            if (currentActiveCh == null || msgChannelId != currentActiveCh.id) {
                                val currentCounts = _unreadCounts.value.toMutableMap()
                                val count = currentCounts[msgChannelId] ?: 0
                                currentCounts[msgChannelId] = count + 1
                                _unreadCounts.value = currentCounts
                            }
                            
                            val mockRssi = if (meshPacket.rxRssi != 0) meshPacket.rxRssi else (-75 + secureRandom.nextInt(30))
                            val mockSnr = if (meshPacket.rxSnr != 0f) meshPacket.rxSnr.toDouble() else (3.0 + secureRandom.nextDouble() * 10.0)
                            val signalLog = SignalLog(
                                id = 0,
                                nodeId = senderNodeId.toInt(),
                                rssi = mockRssi,
                                snr = mockSnr,
                                timestamp = System.currentTimeMillis() / 1000
                            )
                            databaseHelper.insertSignalLog(signalLog)
                            loadMessages()
                        }
                    }
                    else -> {}
                }
            } else {
                // Fallback to legacy custom service decryption logic
                val activeCh = _activeChannel.value
                if (activeCh != null) {
                    val mockPacketId = secureRandom.nextInt()
                    val mockSenderNodeId = 87654321 // Mock sender node ID
                    
                    val decryptedBytes = MeshCryptoEngine.decrypt(
                        packetBytes,
                        activeCh.psk,
                        mockPacketId,
                        mockSenderNodeId
                    )

                    val plainText = decryptedBytes?.let {
                        val decoded = String(it, Charsets.UTF_8)
                        if (decoded.all { char -> char.isLetterOrDigit() || char.isWhitespace() || char in ".,!?:-" }) {
                            decoded
                        } else {
                            null
                        }
                    }

                    val msg = Message(
                        id = 0,
                        packetId = mockPacketId,
                        senderId = mockSenderNodeId,
                        receiverId = _myNodeId.value,
                        timestamp = System.currentTimeMillis() / 1000,
                        payloadEncrypted = packetBytes,
                        payloadDecrypted = plainText ?: "[Decryption Failed / Corrupted Bytes]",
                        channelId = activeCh.id,
                        encryptionType = if (activeCh.psk.size == 32) "AES-256" else "AES-128",
                        isRx = true
                    )
                    
                    databaseHelper.insertMessage(msg)

                    val mockRssi = -75 + secureRandom.nextInt(30)
                    val mockSnr = 3.0 + secureRandom.nextDouble() * 10.0
                    val signalLog = SignalLog(
                        id = 0,
                        nodeId = mockSenderNodeId,
                        rssi = mockRssi,
                        snr = mockSnr,
                        timestamp = System.currentTimeMillis() / 1000
                    )
                    databaseHelper.insertSignalLog(signalLog)

                    loadMessages()
                }
            }
        }
    }

    private var lastGpsUpdateToNodeTime = 0L

    private fun sendLocationUpdateToNode(latitude: Double, longitude: Double) {
        val localNodeId = _myNodeId.value
        if (localNodeId == 0) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastGpsUpdateToNodeTime < 30000L) {
            // Rate limit: at most once every 30 seconds
            return
        }

        val serviceRef = _service.value ?: return
        lastGpsUpdateToNodeTime = now

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val position = MeshProtos.Position.newBuilder()
                    .setLatitudeI((latitude * 1e7).toInt())
                    .setLongitudeI((longitude * 1e7).toInt())
                    .setTime((System.currentTimeMillis() / 1000).toInt())
                    .build()

                val packetId = secureRandom.nextInt(1000000000)
                val toRadio = MeshProtos.ToRadio.newBuilder()
                    .setPacket(
                        MeshProtos.MeshPacket.newBuilder()
                            .setId(packetId)
                            .setFrom(localNodeId)
                            .setTo(0xFFFFFFFF.toInt())
                            .setDecoded(
                                MeshProtos.Data.newBuilder()
                                    .setPortnum(Portnums.PortNum.POSITION_APP)
                                    .setPayload(position.toByteString())
                            )
                    )
                    .build()

                val packetBytes = toRadio.toByteArray()
                serviceRef.sendPacket(packetBytes)
            } catch (e: Exception) {
                // Fail silently on serialization/transmitting issues
            }
        }
    }

    /**
     * Toggles the use of the phone's internal GPS for location reporting.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun setUsePhoneGps(enabled: Boolean) {
        _usePhoneGps.value = enabled
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        if (enabled) {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    _phoneLocation.value = Pair(location.latitude, location.longitude)
                    sendLocationUpdateToNode(location.latitude, location.longitude)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationListener = listener
            
            try {
                if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        5000L,
                        5f,
                        listener,
                        android.os.Looper.getMainLooper()
                    )
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        5000L,
                        5f,
                        listener,
                        android.os.Looper.getMainLooper()
                    )
                }
                
                val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (lastKnown != null) {
                    _phoneLocation.value = Pair(lastKnown.latitude, lastKnown.longitude)
                    sendLocationUpdateToNode(lastKnown.latitude, lastKnown.longitude)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permissions missing or denied: ${e.message}")
                _usePhoneGps.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start location updates: ${e.message}")
                _usePhoneGps.value = false
            }
        } else {
            locationListener?.let {
                locationManager.removeUpdates(it)
            }
            locationListener = null
            _phoneLocation.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
        locationListener?.let {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            locationManager.removeUpdates(it)
        }
    }

    companion object {
        private const val TAG = "SovereignViewModel"
    }
}
