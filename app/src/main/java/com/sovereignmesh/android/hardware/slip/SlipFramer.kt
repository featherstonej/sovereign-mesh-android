package com.sovereignmesh.android.hardware.slip

import java.io.ByteArrayOutputStream

object SlipFramer {
    private const val END = 0xC0.toByte()
    private const val ESC = 0xDB.toByte()
    private const val ESC_END = 0xDC.toByte()
    private const val ESC_ESC = 0xDD.toByte()

    /**
     * Encodes a raw packet byte array into a SLIP-framed packet.
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
     * Resets the state of the decoder.
     */
    fun reset() {
        buffer.reset()
        escaped = false
    }
}
