package com.sovereignmesh.android.database

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.sovereignmesh.android.crypto.MeshKeystoreManager
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

data class Channel(
    val id: String,
    val name: String,
    val psk: ByteArray,
    val active: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Channel
        if (id != other.id) return false
        if (name != other.name) return false
        if (!psk.contentEquals(other.psk)) return false
        if (active != other.active) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + psk.contentHashCode()
        result = 31 * result + active.hashCode()
        return result
    }
}

data class Message(
    val id: Long,
    val packetId: Int,
    val senderId: Int,
    val receiverId: Int,
    val timestamp: Long,
    val payloadEncrypted: ByteArray,
    val payloadDecrypted: String?,
    val channelId: String,
    val encryptionType: String,
    val isRx: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Message
        if (id != other.id) return false
        if (packetId != other.packetId) return false
        if (senderId != other.senderId) return false
        if (receiverId != other.receiverId) return false
        if (timestamp != other.timestamp) return false
        if (!payloadEncrypted.contentEquals(other.payloadEncrypted)) return false
        if (payloadDecrypted != other.payloadDecrypted) return false
        if (channelId != other.channelId) return false
        if (encryptionType != other.encryptionType) return false
        if (isRx != other.isRx) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + packetId
        result = 31 * result + senderId
        result = 31 * result + receiverId
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payloadEncrypted.contentHashCode()
        result = 31 * result + (payloadDecrypted?.hashCode() ?: 0)
        result = 31 * result + channelId.hashCode()
        result = 31 * result + encryptionType.hashCode()
        result = 31 * result + isRx.hashCode()
        return result
    }
}

data class SignalLog(
    val id: Long,
    val nodeId: Int,
    val rssi: Int,
    val snr: Double,
    val timestamp: Long
)

class MeshDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    MeshKeystoreManager(context).getOrCreateDatabasePasscode(),
    null,
    DATABASE_VERSION,
    1,
    null,
    null,
    false
) {

    companion object {
        private const val DATABASE_NAME = "sovereign_mesh_db"
        private const val DATABASE_VERSION = 2 // Incremented version to apply table migration
        private const val TAG = "MeshDatabaseHelper"

        // Table Channels
        private const val TABLE_CHANNELS = "channels"
        private const val COL_CH_ID = "id"
        private const val COL_CH_NAME = "name"
        private const val COL_CH_PSK = "psk"
        private const val COL_CH_ACTIVE = "active"

        // Table Messages
        private const val TABLE_MESSAGES = "messages"
        private const val COL_MSG_ID = "id"
        private const val COL_MSG_PACKET_ID = "packet_id"
        private const val COL_MSG_SENDER_ID = "sender_id"
        private const val COL_MSG_RECEIVER_ID = "receiver_id"
        private const val COL_MSG_TIMESTAMP = "timestamp"
        private const val COL_MSG_PAYLOAD_ENCRYPTED = "payload_encrypted"
        private const val COL_MSG_PAYLOAD_DECRYPTED = "payload_decrypted"
        private const val COL_MSG_CHANNEL_ID = "channel_id"
        private const val COL_MSG_ENCRYPTION_TYPE = "encryption_type"
        private const val COL_MSG_IS_RX = "is_rx"

        // Table Signal Logs
        private const val TABLE_SIGNAL_LOGS = "signal_logs"
        private const val COL_SIG_ID = "id"
        private const val COL_SIG_NODE_ID = "node_id"
        private const val COL_SIG_RSSI = "rssi"
        private const val COL_SIG_SNR = "snr"
        private const val COL_SIG_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createChannelsTable = """
            CREATE TABLE $TABLE_CHANNELS (
                $COL_CH_ID TEXT PRIMARY KEY,
                $COL_CH_NAME TEXT,
                $COL_CH_PSK BLOB,
                $COL_CH_ACTIVE INTEGER
            )
        """.trimIndent()

        val createMessagesTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_MSG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MSG_PACKET_ID INTEGER,
                $COL_MSG_SENDER_ID INTEGER,
                $COL_MSG_RECEIVER_ID INTEGER,
                $COL_MSG_TIMESTAMP INTEGER,
                $COL_MSG_PAYLOAD_ENCRYPTED BLOB,
                $COL_MSG_PAYLOAD_DECRYPTED TEXT,
                $COL_MSG_CHANNEL_ID TEXT,
                $COL_MSG_ENCRYPTION_TYPE TEXT,
                $COL_MSG_IS_RX INTEGER,
                FOREIGN KEY($COL_MSG_CHANNEL_ID) REFERENCES $TABLE_CHANNELS($COL_CH_ID)
            )
        """.trimIndent()

        val createSignalLogsTable = """
            CREATE TABLE $TABLE_SIGNAL_LOGS (
                $COL_SIG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SIG_NODE_ID INTEGER,
                $COL_SIG_RSSI INTEGER,
                $COL_SIG_SNR REAL,
                $COL_SIG_TIMESTAMP INTEGER
            )
        """.trimIndent()

        db.execSQL(createChannelsTable)
        db.execSQL(createMessagesTable)
        db.execSQL(createSignalLogsTable)
        Log.d(TAG, "SQLCipher tables created successfully")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHANNELS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SIGNAL_LOGS")
        onCreate(db)
    }

    /**
     * Gets a writable database.
     */
    fun getWritableEncryptedDatabase(): SQLiteDatabase {
        return writableDatabase as SQLiteDatabase
    }

    /**
     * Gets a readable database.
     */
    fun getReadableEncryptedDatabase(): SQLiteDatabase {
        return readableDatabase as SQLiteDatabase
    }

    // CRUD Channel Methods

    fun insertChannel(channel: Channel): Boolean {
        val db = getWritableEncryptedDatabase()
        val values = ContentValues().apply {
            put(COL_CH_ID, channel.id)
            put(COL_CH_NAME, channel.name)
            put(COL_CH_PSK, channel.psk)
            put(COL_CH_ACTIVE, if (channel.active) 1 else 0)
        }
        val result = db.insert(
            TABLE_CHANNELS,
            SQLiteDatabase.CONFLICT_REPLACE,
            values
        )
        return result != -1L
    }

    fun getActiveChannels(): List<Channel> {
        val channels = mutableListOf<Channel>()
        val db = getReadableEncryptedDatabase()
        val query = "SELECT * FROM $TABLE_CHANNELS WHERE $COL_CH_ACTIVE = 1"
        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            val idxId = cursor.getColumnIndexOrThrow(COL_CH_ID)
            val idxName = cursor.getColumnIndexOrThrow(COL_CH_NAME)
            val idxPsk = cursor.getColumnIndexOrThrow(COL_CH_PSK)
            val idxActive = cursor.getColumnIndexOrThrow(COL_CH_ACTIVE)
            do {
                channels.add(
                    Channel(
                        id = cursor.getString(idxId),
                        name = cursor.getString(idxName),
                        psk = cursor.getBlob(idxPsk),
                        active = cursor.getInt(idxActive) == 1
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return channels
    }

    // CRUD Message Methods

    fun insertMessage(message: Message): Boolean {
        val db = getWritableEncryptedDatabase()
        val values = ContentValues().apply {
            put(COL_MSG_PACKET_ID, message.packetId)
            put(COL_MSG_SENDER_ID, message.senderId)
            put(COL_MSG_RECEIVER_ID, message.receiverId)
            put(COL_MSG_TIMESTAMP, message.timestamp)
            put(COL_MSG_PAYLOAD_ENCRYPTED, message.payloadEncrypted)
            put(COL_MSG_PAYLOAD_DECRYPTED, message.payloadDecrypted)
            put(COL_MSG_CHANNEL_ID, message.channelId)
            put(COL_MSG_ENCRYPTION_TYPE, message.encryptionType)
            put(COL_MSG_IS_RX, if (message.isRx) 1 else 0)
        }
        val result = db.insert(
            TABLE_MESSAGES,
            SQLiteDatabase.CONFLICT_NONE,
            values
        )
        return result != -1L
    }

    fun getAllMessages(): List<Message> {
        val messages = mutableListOf<Message>()
        val db = getReadableEncryptedDatabase()
        val cursor = db.rawQuery("SELECT * FROM $TABLE_MESSAGES ORDER BY $COL_MSG_TIMESTAMP ASC", null)

        if (cursor.moveToFirst()) {
            val idxId = cursor.getColumnIndexOrThrow(COL_MSG_ID)
            val idxPacketId = cursor.getColumnIndexOrThrow(COL_MSG_PACKET_ID)
            val idxSenderId = cursor.getColumnIndexOrThrow(COL_MSG_SENDER_ID)
            val idxReceiverId = cursor.getColumnIndexOrThrow(COL_MSG_RECEIVER_ID)
            val idxTimestamp = cursor.getColumnIndexOrThrow(COL_MSG_TIMESTAMP)
            val idxPayloadEncrypted = cursor.getColumnIndexOrThrow(COL_MSG_PAYLOAD_ENCRYPTED)
            val idxPayloadDecrypted = cursor.getColumnIndexOrThrow(COL_MSG_PAYLOAD_DECRYPTED)
            val idxChannelId = cursor.getColumnIndexOrThrow(COL_MSG_CHANNEL_ID)
            val idxEncryptionType = cursor.getColumnIndexOrThrow(COL_MSG_ENCRYPTION_TYPE)
            val idxIsRx = cursor.getColumnIndexOrThrow(COL_MSG_IS_RX)
            
            do {
                messages.add(
                    Message(
                        id = cursor.getLong(idxId),
                        packetId = cursor.getInt(idxPacketId),
                        senderId = cursor.getInt(idxSenderId),
                        receiverId = cursor.getInt(idxReceiverId),
                        timestamp = cursor.getLong(idxTimestamp),
                        payloadEncrypted = cursor.getBlob(idxPayloadEncrypted),
                        payloadDecrypted = cursor.getString(idxPayloadDecrypted),
                        channelId = cursor.getString(idxChannelId),
                        encryptionType = cursor.getString(idxEncryptionType),
                        isRx = cursor.getInt(idxIsRx) == 1
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return messages
    }

    // CRUD Signal Log Methods

    fun insertSignalLog(signalLog: SignalLog): Boolean {
        val db = getWritableEncryptedDatabase()
        val values = ContentValues().apply {
            put(COL_SIG_NODE_ID, signalLog.nodeId)
            put(COL_SIG_RSSI, signalLog.rssi)
            put(COL_SIG_SNR, signalLog.snr)
            put(COL_SIG_TIMESTAMP, signalLog.timestamp)
        }
        val result = db.insert(
            TABLE_SIGNAL_LOGS,
            SQLiteDatabase.CONFLICT_NONE,
            values
        )
        return result != -1L
    }

    fun getLatestSignalLog(nodeId: Int): SignalLog? {
        val db = getReadableEncryptedDatabase()
        val query = "SELECT * FROM $TABLE_SIGNAL_LOGS WHERE $COL_SIG_NODE_ID = ? ORDER BY $COL_SIG_TIMESTAMP DESC LIMIT 1"
        val cursor = db.rawQuery(query, arrayOf(nodeId.toString()))
        var log: SignalLog? = null
        if (cursor.moveToFirst()) {
            val idxId = cursor.getColumnIndexOrThrow(COL_SIG_ID)
            val idxNodeId = cursor.getColumnIndexOrThrow(COL_SIG_NODE_ID)
            val idxRssi = cursor.getColumnIndexOrThrow(COL_SIG_RSSI)
            val idxSnr = cursor.getColumnIndexOrThrow(COL_SIG_SNR)
            val idxTimestamp = cursor.getColumnIndexOrThrow(COL_SIG_TIMESTAMP)
            log = SignalLog(
                id = cursor.getLong(idxId),
                nodeId = cursor.getInt(idxNodeId),
                rssi = cursor.getInt(idxRssi),
                snr = cursor.getDouble(idxSnr),
                timestamp = cursor.getLong(idxTimestamp)
            )
        }
        cursor.close()
        return log
    }
}
