package com.sovereignmesh.android.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MeshCryptoEngine {

    private const val AES_CTR = "AES/CTR/NoPadding"
    private const val AES = "AES"

    /**
     * Decrypts a packet payload using AES-CTR with derived IV from packet details.
     */
    fun decrypt(payload: ByteArray, key: ByteArray, packetId: Int, senderId: Int): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(AES_CTR)
            val secretKey = SecretKeySpec(key, AES)
            val iv = generateIv(packetId, senderId)
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(payload)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Encrypts a packet payload using AES-CTR with derived IV from packet details.
     */
    fun encrypt(payload: ByteArray, key: ByteArray, packetId: Int, senderId: Int): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(AES_CTR)
            val secretKey = SecretKeySpec(key, AES)
            val iv = generateIv(packetId, senderId)
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(payload)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Derives a 16-byte Initialization Vector (IV) for AES-CTR based on:
     * [ Packet ID (4 bytes, big endian) | Sender Node ID (4 bytes, big endian) | 0x00...0x00 (8 bytes) ]
     */
    fun generateIv(packetId: Int, senderId: Int): ByteArray {
        val iv = ByteArray(16)
        
        // Write packetId in big endian (bytes 0 to 3)
        iv[0] = (packetId ushr 24).toByte()
        iv[1] = (packetId ushr 16).toByte()
        iv[2] = (packetId ushr 8).toByte()
        iv[3] = packetId.toByte()

        // Write senderId in big endian (bytes 4 to 7)
        iv[4] = (senderId ushr 24).toByte()
        iv[5] = (senderId ushr 16).toByte()
        iv[6] = (senderId ushr 8).toByte()
        iv[7] = senderId.toByte()

        // Bytes 8-15 remain 0
        return iv
    }
}
