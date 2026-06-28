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

package com.sovereignmesh.android.hardware.slip

import java.io.ByteArrayOutputStream

/**
 * SlipFramer provides utilities for encoding raw payloads into Serial Line IP (SLIP)
 * framed packets, as used by the Meshtastic serial protocol.
 */
object SlipFramer {
    private const val END = 0xC0.toByte()
    private const val ESC = 0xDB.toByte()
    private const val ESC_END = 0xDC.toByte()
    private const val ESC_ESC = 0xDD.toByte()

    /**
     * Encodes a raw packet byte array into a SLIP-framed packet.
     * @param packet The raw payload to encode.
     * @return The SLIP-framed byte array.
     */
    fun encode(packet: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        // Standard practice: write an initial END byte to flush any noise on the line
        output.write(END.toInt())
        for (b in packet) {
            when (b) {
                END -> {
                    output.write(ESC.toInt())
                    output.write(ESC_END.toInt())
                }
                ESC -> {
                    output.write(ESC.toInt())
                    output.write(ESC_ESC.toInt())
                }
                else -> {
                    output.write(b.toInt())
                }
            }
        }
        output.write(END.toInt())
        return output.toByteArray()
    }
}

/**
 * SlipDecoder maintains state for an incoming stream of SLIP-framed bytes,
 * extracting complete packets as they are received.
 */
class SlipDecoder {
    private val buffer = ByteArrayOutputStream()
    private var escaped = false

    companion object {
        private const val END = 0xC0.toByte()
        private const val ESC = 0xDB.toByte()
        private const val ESC_END = 0xDC.toByte()
        private const val ESC_ESC = 0xDD.toByte()
    }

    /**
     * Feeds incoming raw bytes and returns a list of successfully decoded complete packets.
     * @param bytes The raw bytes received from the serial link.
     * @return A list of zero or more complete decoded packets.
     */
    fun feed(bytes: ByteArray): List<ByteArray> {
        val completedPackets = mutableListOf<ByteArray>()

        for (b in bytes) {
            if (escaped) {
                when (b) {
                    ESC_END -> buffer.write(END.toInt())
                    ESC_ESC -> buffer.write(ESC.toInt())
                    else -> {
                        // Protocol violation: write escape character and current character as is
                        buffer.write(ESC.toInt())
                        buffer.write(b.toInt())
                    }
                }
                escaped = false
            } else {
                when (b) {
                    END -> {
                        if (buffer.size() > 0) {
                            completedPackets.add(buffer.toByteArray())
                            buffer.reset()
                        }
                    }
                    ESC -> escaped = true
                    else -> buffer.write(b.toInt())
                }
            }
        }

        return completedPackets
    }

    /**
     * Resets the state of the decoder, clearing any partially received packets.
     */
    fun reset() {
        buffer.reset()
        escaped = false
    }
}
