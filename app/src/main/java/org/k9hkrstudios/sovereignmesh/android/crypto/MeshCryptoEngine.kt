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

package org.k9hkrstudios.sovereignmesh.android.crypto

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCryptoEngine provides native implementations of the Meshtastic AES-CTR
 * encryption/decryption algorithm.
 *
 * It ensures that message confidentiality is maintained over the mesh network
 * using standard cryptographic primitives.
 */
object MeshCryptoEngine {

    private const val TAG = "MeshCryptoEngine"
    private const val AES_CTR = "AES/CTR/NoPadding"
    private const val AES = "AES"

    /**
     * Decrypts a packet payload using AES-CTR with derived IV from packet details.
     * @param payload The encrypted byte array.
     * @param key The pre-shared channel key (16 or 32 bytes).
     * @param packetId The unique ID of the packet.
     * @param senderId The node ID of the sender.
     * @return The decrypted byte array, or null if decryption fails.
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
            Log.e(TAG, "Decryption failure", e)
            null
        }
    }

    /**
     * Encrypts a packet payload using AES-CTR with derived IV from packet details.
     * @param payload The raw byte array.
     * @param key The pre-shared channel key (16 or 32 bytes).
     * @param packetId The unique ID of the packet.
     * @param senderId The node ID of the sender.
     * @return The encrypted byte array, or null if encryption fails.
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
            Log.e(TAG, "Encryption failure", e)
            null
        }
    }

    /**
     * Derives a 16-byte Initialization Vector (IV) for AES-CTR based on the packet
     * ID and sender node ID as per the Meshtastic protocol.
     *
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
