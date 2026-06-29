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

package org.k9hkrstudios.sovereignmesh.android.util.stego

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

/**
 * StegoEngine provides tactical Least Significant Bit (LSB) steganography
 * for embedding and extracting secret payloads within standard Bitmap images.
 *
 * This allows for out-of-band communication and data backups that are
 * visually indistinguishable from normal images.
 */
object StegoEngine {

    private const val TAG = "StegoEngine"

    /**
     * Embeds a byte array payload into the Least Significant Bits (LSB) of a copy of the source Bitmap.
     * @param source The carrier Bitmap image.
     * @param payload The raw byte array to hide.
     * @return A new stego-bitmap containing the hidden payload, or null if it cannot be embedded.
     */
    fun hidePayload(source: Bitmap, payload: ByteArray): Bitmap? {
        val width = source.width
        val height = source.height
        
        // Prepare full payload = [ Length (4 bytes, Big Endian) | Payload bytes ]
        val fullPayload = try {
            ByteBuffer.allocate(4 + payload.size)
                .putInt(payload.size)
                .put(payload)
                .array()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate payload buffer", e)
            return null
        }

        val totalBitsRequired = fullPayload.size * 8
        // Each pixel has 3 channels (Red, Green, Blue) to hide 1 bit per channel
        val totalBitsCapacity = width * height * 3

        if (totalBitsRequired > totalBitsCapacity) {
            Log.w(TAG, "Payload too large for carrier image ($totalBitsRequired > $totalBitsCapacity bits)")
            return null
        }

        // Create mutable copy of source bitmap
        val stegoBitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        
        val pixels = IntArray(width * height)
        stegoBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var pixelIdx = 0
        var channelIdx = 0 // 0 = Red, 1 = Green, 2 = Blue

        for (byte in fullPayload) {
            for (bitPos in 7 downTo 0) {
                val bit = (byte.toInt() ushr bitPos) and 1
                val pixel = pixels[pixelIdx]

                // Extract channels
                var r = (pixel ushr 16) and 0xFF
                var g = (pixel ushr 8) and 0xFF
                var b = pixel and 0xFF

                // Write bit to LSB of the selected channel
                when (channelIdx) {
                    0 -> r = (r and 0xFE) or bit
                    1 -> g = (g and 0xFE) or bit
                    2 -> b = (b and 0xFE) or bit
                }

                // Pack pixel back
                val alpha = (pixel ushr 24) and 0xFF
                pixels[pixelIdx] = (alpha shl 24) or (r shl 16) or (g shl 8) or b

                // Move to next channel/pixel
                channelIdx++
                if (channelIdx > 2) {
                    channelIdx = 0
                    pixelIdx++
                }
            }
        }

        stegoBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return stegoBitmap
    }

    /**
     * Extracts an embedded byte array payload from the stego-bitmap.
     * @param stegoBitmap The image containing a potential hidden payload.
     * @return The extracted payload, or null if extraction fails or no valid payload is detected.
     */
    fun extractPayload(stegoBitmap: Bitmap): ByteArray? {
        val width = stegoBitmap.width
        val height = stegoBitmap.height

        val pixels = IntArray(width * height)
        stegoBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val totalCapacityBits = width * height * 3
        if (totalCapacityBits < 32) {
            return null // Not enough capacity to even store the 32-bit length prefix
        }

        var pixelIdx = 0
        var channelIdx = 0 // 0 = Red, 1 = Green, 2 = Blue

        // 1. Read the length prefix (32 bits = 4 bytes)
        var length = 0
        for (i in 0 until 32) {
            val pixel = pixels[pixelIdx]
            val bit = when (channelIdx) {
                0 -> (pixel ushr 16) and 1
                1 -> (pixel ushr 8) and 1
                else -> pixel and 1
            }
            length = (length shl 1) or bit

            channelIdx++
            if (channelIdx > 2) {
                channelIdx = 0
                pixelIdx++
            }
        }

        // Validate length limits
        val maxPossibleLength = (width * height * 3 - 32) / 8
        if (length <= 0 || length > maxPossibleLength) {
            return null // No payload or corrupted length
        }

        // 2. Read the payload bytes
        val payload = ByteArray(length)
        for (byteIdx in 0 until length) {
            var byteVal = 0
            for (bitPos in 7 downTo 0) {
                val pixel = pixels[pixelIdx]
                val bit = when (channelIdx) {
                    0 -> (pixel ushr 16) and 1
                    1 -> (pixel ushr 8) and 1
                    else -> pixel and 1
                }
                byteVal = (byteVal shl 1) or bit

                channelIdx++
                if (channelIdx > 2) {
                    channelIdx = 0
                    pixelIdx++
                }
            }
            payload[byteIdx] = byteVal.toByte()
        }

        return payload
    }
}
