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

package org.k9hkrstudios.sovereignmesh.android

import org.k9hkrstudios.sovereignmesh.android.crypto.MeshCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [MeshCryptoEngine] to verify AES-CTR parity and IV generation.
 */
class CryptoEngineTest {

    @Test
    fun testIvGeneration() {
        val packetId = 0x12345678
        val senderId = 0x0A0B0C0D
        
        val iv = MeshCryptoEngine.generateIv(packetId, senderId)
        
        // Expected IV layout:
        // [ 0x12, 0x34, 0x56, 0x78, 0x0A, 0x0B, 0x0C, 0x0D, 0, 0, 0, 0, 0, 0, 0, 0 ]
        val expected = byteArrayOf(
            0x12, 0x34, 0x56, 0x78,
            0x0A, 0x0B, 0x0C, 0x0D,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        
        assertArrayEquals(expected, iv)
    }

    @Test
    fun testEncryptionDecryptionParity() {
        val secretKey = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16
        ) // 128-bit key
        
        val packetId = 42
        val senderId = 999
        val message = "This is a private sovereign message.".toByteArray(Charsets.UTF_8)
        
        // Encrypt message
        val encrypted = MeshCryptoEngine.encrypt(message, secretKey, packetId, senderId)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotEmpty())
        
        // Decrypt back
        val decrypted = MeshCryptoEngine.decrypt(encrypted, secretKey, packetId, senderId)
        assertNotNull(decrypted)
        
        // Verify original content
        assertArrayEquals(message, decrypted)
        assertEquals("This is a private sovereign message.", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun testDecryptionFailureWithWrongKey() {
        val correctKey = ByteArray(16) { it.toByte() }
        val incorrectKey = ByteArray(16) { (it + 1).toByte() }
        val packetId = 100
        val senderId = 200
        val message = "Sovereign communications".toByteArray(Charsets.UTF_8)
        
        val encrypted = MeshCryptoEngine.encrypt(message, correctKey, packetId, senderId)
        assertNotNull(encrypted)
        
        // Decrypt with wrong key
        val decrypted = MeshCryptoEngine.decrypt(encrypted!!, incorrectKey, packetId, senderId)
        assertNotNull(decrypted)
        
        // In CTR mode, decrypting with a wrong key/IV yields garbage output of the same length, not a cipher error
        assertEquals(message.size, decrypted!!.size)
        // Ensure decrypted message is not equal to original message
        var isDifferent = false
        for (i in message.indices) {
            if (message[i] != decrypted[i]) {
                isDifferent = true
                break
            }
        }
        assertTrue("Decrypted message with wrong key should yield garbage, not match original", isDifferent)
    }

    @Test
    fun testDecryptionFailureWithMismatchedIv() {
        val key = ByteArray(16) { it.toByte() }
        val message = "Off-grid local messaging".toByteArray(Charsets.UTF_8)
        
        // Encrypt with packetId = 1, senderId = 2
        val encrypted = MeshCryptoEngine.encrypt(message, key, 1, 2)
        assertNotNull(encrypted)
        
        // Decrypt with packetId = 1, senderId = 3 (wrong sender ID)
        val decrypted = MeshCryptoEngine.decrypt(encrypted!!, key, 1, 3)
        assertNotNull(decrypted)
        
        // Verify mismatch yields garbage bytes
        assertEquals(message.size, decrypted!!.size)
        var isDifferent = false
        for (i in message.indices) {
            if (message[i] != decrypted[i]) {
                isDifferent = true
                break
            }
        }
        assertTrue("Decrypted message with wrong IV parameters should yield garbage", isDifferent)
    }
}
