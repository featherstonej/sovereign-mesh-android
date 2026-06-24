package com.sovereignmesh.android.util.stego

import android.graphics.Bitmap
import java.nio.ByteBuffer

object StegoEngine {

    /**
     * Embeds a byte array payload into the Least Significant Bits (LSB) of a copy of the source Bitmap.
     * Returns the stego-bitmap, or null if the payload is too large for the image.
     */
    fun hidePayload(source: Bitmap, payload: ByteArray): Bitmap? {
        val width = source.width
        val height = source.height
        
        // Prepare full payload = [ Length (4 bytes, Big Endian) | Payload bytes ]
        val fullPayload = ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        val totalBitsRequired = fullPayload.size * 8
        // Each pixel has 3 channels (Red, Green, Blue) to hide 1 bit per channel
        val totalBitsCapacity = width * height * 3

        if (totalBitsRequired > totalBitsCapacity) {
            return null // Bitmap is too small to carry this payload
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
     * Returns the payload, or null if no valid payload is detected or if extraction fails.
     */
    fun extractPayload(stegoBitmap: Bitmap): ByteArray? {
        val width = stegoBitmap.width
        val height = stegoBitmap.height

        val pixels = IntArray(width * height)
        stegoBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

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
