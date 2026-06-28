package com.sovereignmesh.android.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sovereignmesh.android.crypto.MeshCryptoEngine
import com.sovereignmesh.android.database.Channel
import com.sovereignmesh.android.database.MeshDatabaseHelper
import com.sovereignmesh.android.database.Message
import com.sovereignmesh.android.database.SignalLog
import com.sovereignmesh.android.hardware.MeshHardwareService
import com.sovereignmesh.android.hardware.ble.BleConnectionState
import com.sovereignmesh.android.hardware.usb.UsbConnectionState
import com.sovereignmesh.android.util.stego.StegoEngine
import com.sovereignmesh.android.util.tts.MeshTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.Portnums

@OptIn(ExperimentalCoroutinesApi::class)
class SovereignViewModel(
    private val context: Context,
    private val databaseHelper: MeshDatabaseHelper
) : ViewModel() {

    private val _service = MutableStateFlow<MeshHardwareService?>(null)
    val service: StateFlow<MeshHardwareService?> = _service.asStateFlow()

    // Expose flows from background service
    val usbConnectionState = _service.flatMapLatest { service ->
        service?.usbConnectionState ?: flowOf(UsbConnectionState.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsbConnectionState.DISCONNECTED)

    val bleConnectionState = _service.flatMapLatest { service ->
        service?.bleConnectionState ?: flowOf(BleConnectionState.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BleConnectionState.DISCONNECTED)

    val bleErrorMessage = _service.flatMapLatest { service ->
        service?.bleErrorMessage ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val discoveredBleDevices = _service.flatMapLatest { service ->
        service?.discoveredBleDevices ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Database cached items
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow<Channel?>(null)
    val activeChannel: StateFlow<Channel?> = _activeChannel.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val _phoneLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val phoneLocation: StateFlow<Pair<Double, Double>?> = _phoneLocation.asStateFlow()

    private val _usePhoneGps = MutableStateFlow(false)
    val usePhoneGps: StateFlow<Boolean> = _usePhoneGps.asStateFlow()

    private var locationListener: android.location.LocationListener? = null

    private val secureRandom = SecureRandom()
    private var myNodeId = 11223344 // Hardcoded mock node ID for current client

    // Local air-gapped TTS
    private val ttsEngine = MeshTtsEngine(context)

    init {
        loadChannels()
        loadMessages()
    }

    private var incomingPacketsJob: kotlinx.coroutines.Job? = null
    private var connectionStateJob: kotlinx.coroutines.Job? = null

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
                }
            }
        }
        _service.value = hardwareService
    }

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
            android.util.Log.d("SovereignViewModel", "Sent want_config_id=$nonce request to device")
        }
    }

    private suspend fun handleDeviceChannel(deviceChannel: ChannelProtos.Channel) {
        android.util.Log.d("SovereignViewModel", "handleDeviceChannel entry: index=${deviceChannel.index}, role=${deviceChannel.role}, hasSettings=${deviceChannel.hasSettings()}")
        if (!deviceChannel.hasSettings()) {
            android.util.Log.w("SovereignViewModel", "handleDeviceChannel returning early: no settings for channel index ${deviceChannel.index}")
            return
        }
        val settings = deviceChannel.settings
        val name = settings.name
        val psk = settings.psk.toByteArray()
        val index = deviceChannel.index
        val role = deviceChannel.role
        
        android.util.Log.d("SovereignViewModel", "Received channel from device: index=$index, name=$name, role=$role, pskSize=${psk.size}")

        val displayName = if (name.isEmpty()) {
            if (role == ChannelProtos.Channel.Role.PRIMARY) "Meshtastic Primary" else "Channel $index"
        } else {
            name
        }

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

        val insertSuccess = withContext(Dispatchers.IO) {
            databaseHelper.insertChannel(channel)
        }
        android.util.Log.d("SovereignViewModel", "Inserted channel to DB: $displayName (active=$active), success=$insertSuccess")
        
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = databaseHelper.getActiveChannels()
            val isConnected = bleConnectionState.value == BleConnectionState.CONNECTED ||
                              usbConnectionState.value == UsbConnectionState.CONNECTED
            
            android.util.Log.d("SovereignViewModel", "loadChannels: active channels count=${list.size}, isConnected=$isConnected")
            for (ch in list) {
                android.util.Log.d("SovereignViewModel", "  -> Active Channel in DB: id=${ch.id}, name=${ch.name}, active=${ch.active}")
            }

            if (list.isEmpty() && !isConnected) {
                // Pre-populate with a default AES-256 secure channel if database is empty and not connected
                val defaultKey = ByteArray(32).apply { secureRandom.nextBytes(this) }
                val defaultChannel = Channel("ch_primary", "Stealth-Mesh-Primary", defaultKey, true)
                val success = databaseHelper.insertChannel(defaultChannel)
                android.util.Log.d("SovereignViewModel", "Pre-populated mock channel Stealth-Mesh-Primary success=$success")
                _channels.value = listOf(defaultChannel)
                _activeChannel.value = defaultChannel
            } else {
                _channels.value = list
                _activeChannel.value = list.firstOrNull()
                android.util.Log.d("SovereignViewModel", "Updated channels UI state flow: activeChannel=${_activeChannel.value?.name}")
            }
        }
    }

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

    fun selectChannel(channel: Channel) {
        _activeChannel.value = channel
        
        val currentCounts = _unreadCounts.value.toMutableMap()
        currentCounts.remove(channel.id)
        _unreadCounts.value = currentCounts
        
        loadMessages()
    }

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

    fun clearAllMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            databaseHelper.clearAllMessages()
            loadMessages()
        }
    }

    // Hardware operations forwards

    fun autoConnectUsb() {
        _service.value?.autoConnectUsb()
    }

    fun disconnectUsb() {
        _service.value?.disconnectUsbDevice()
    }

    fun startBleScan() {
        _service.value?.startBleScan()
    }

    fun stopBleScan() {
        _service.value?.stopBleScan()
    }

    fun connectBle(device: BluetoothDevice) {
        _service.value?.connectBleDevice(device)
    }

    fun disconnectBle() {
        _service.value?.disconnectBleDevice()
    }

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
            e.printStackTrace()
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
            val encryptedBytes = MeshCryptoEngine.encrypt(textBytes, channel.psk, packetId, myNodeId)
            
            // Build the ToRadio protobuf wrapper containing the MeshPacket
            val toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(
                    MeshProtos.MeshPacket.newBuilder()
                        .setTo(0xFFFFFFFF.toInt()) // Broadcast
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
            android.util.Log.d("SovereignViewModel", "sendMessage: sent ToRadio packet, success=$sendSuccess, channelIndex=$channelIndex")
            
            if (encryptedBytes != null) {
                // Cache locally as sent message
                val msg = Message(
                    id = 0,
                    packetId = packetId,
                    senderId = myNodeId,
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
                android.util.Log.d("SovereignViewModel", "Parsed FromRadio message: variant=${fromRadio.payloadVariantCase}")
                when (fromRadio.payloadVariantCase) {
                    MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                        val myNodeNum = fromRadio.myInfo.myNodeNum
                        if (myNodeNum != 0) {
                            myNodeId = myNodeNum.toInt()
                            android.util.Log.d("SovereignViewModel", "Updated myNodeId dynamically to $myNodeId")
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
                                    plainText = String(decrypted, Charsets.UTF_8)
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
                                receiverId = myNodeId,
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
                        receiverId = myNodeId,
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

    @android.annotation.SuppressLint("MissingPermission")
    fun setUsePhoneGps(enabled: Boolean) {
        _usePhoneGps.value = enabled
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        if (enabled) {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    _phoneLocation.value = Pair(location.latitude, location.longitude)
                    android.util.Log.d("SovereignViewModel", "GPS Location updated: lat=${location.latitude}, lon=${location.longitude}")
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
                }
            } catch (e: SecurityException) {
                android.util.Log.e("SovereignViewModel", "Location permissions missing or denied: ${e.message}")
                _usePhoneGps.value = false
            } catch (e: Exception) {
                android.util.Log.e("SovereignViewModel", "Failed to start location updates: ${e.message}")
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
}
