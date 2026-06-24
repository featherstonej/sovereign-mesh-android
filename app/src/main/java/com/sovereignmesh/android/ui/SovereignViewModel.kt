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

    private val secureRandom = SecureRandom()
    private val myNodeId = 11223344 // Hardcoded mock node ID for current client

    // Local air-gapped TTS
    private val ttsEngine = MeshTtsEngine(context)

    init {
        loadChannels()
        loadMessages()
    }

    fun setService(hardwareService: MeshHardwareService) {
        // Listen to incoming packets from the service
        viewModelScope.launch {
            hardwareService.incomingPackets.collect { packetBytes ->
                handleIncomingPacket(packetBytes)
            }
        }
        _service.value = hardwareService
    }

    private fun loadChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = databaseHelper.getActiveChannels()
            if (list.isEmpty()) {
                // Pre-populate with a default AES-256 secure channel if database is empty
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
    }

    fun loadMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = databaseHelper.getAllMessages()
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
            val packetId = secureRandom.nextInt()
            val textBytes = text.toByteArray(Charsets.UTF_8)
            
            // Encrypt using AES-CTR with the channel key
            val encryptedBytes = MeshCryptoEngine.encrypt(textBytes, channel.psk, packetId, myNodeId)
            
            if (encryptedBytes != null) {
                // Transmit framed bytes over active hardware link
                serviceRef.sendPacket(encryptedBytes)
                
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
        val channel = _activeChannel.value ?: return
        
        withContext(Dispatchers.IO) {
            // For prototype scaffolding, we assume incoming packets are CTR-encrypted.
            // In a full implementation, we extract headers. Here, we generate mock headers for incoming bytes.
            val mockPacketId = secureRandom.nextInt()
            val mockSenderNodeId = 87654321 // Mock sender node ID
            
            // Attempt decryption with current channel key
            val decryptedBytes = MeshCryptoEngine.decrypt(
                packetBytes,
                channel.psk,
                mockPacketId,
                mockSenderNodeId
            )

            val plainText = decryptedBytes?.let {
                val decoded = String(it, Charsets.UTF_8)
                // Filter out non-printable garbage to verify decryption validity
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
                channelId = channel.id,
                encryptionType = if (channel.psk.size == 32) "AES-256" else "AES-128",
                isRx = true
            )
            
            databaseHelper.insertMessage(msg)

            // Log a mock RF Signal Attenuation Log for the incoming transmission
            val mockRssi = -75 + secureRandom.nextInt(30) // -75 to -46 dBm (High/Mid quality)
            val mockSnr = 3.0 + secureRandom.nextDouble() * 10.0 // 3.0 to 13.0 dB
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

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }
}
