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

package com.sovereignmesh.android

import com.sovereignmesh.android.hardware.slip.SlipDecoder
import com.sovereignmesh.android.hardware.slip.SlipFramer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [SlipFramer] and [SlipDecoder] to verify protocol correctness.
 */
class SlipFramerTest {

    @Test
    fun testStandardEncoding() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val expected = byteArrayOf(0xC0.toByte(), 1, 2, 3, 4, 0xC0.toByte())
        val encoded = SlipFramer.encode(payload)
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEscapingEncoding() {
        // Payload containing END (0xC0) and ESC (0xDB)
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte())
        val expected = byteArrayOf(
            0xC0.toByte(),
            0xDB.toByte(), 0xDC.toByte(), // ESC, ESC_END
            0xDB.toByte(), 0xDD.toByte(), // ESC, ESC_ESC
            0xC0.toByte()
        )
        val encoded = SlipFramer.encode(payload)
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testDecoderFeedStandard() {
        val decoder = SlipDecoder()
        val data = byteArrayOf(0xC0.toByte(), 5, 6, 7, 0xC0.toByte())
        val packets = decoder.feed(data)
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(5, 6, 7), packets[0])
    }

    @Test
    fun testDecoderFeedEscaped() {
        val decoder = SlipDecoder()
        val data = byteArrayOf(
            0xC0.toByte(),
            0xDB.toByte(), 0xDC.toByte(), // ESC, ESC_END -> 0xC0
            0xDB.toByte(), 0xDD.toByte(), // ESC, ESC_ESC -> 0xDB
            0xC0.toByte()
        )
        val packets = decoder.feed(data)
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0xDB.toByte()), packets[0])
    }

    @Test
    fun testDecoderFeedFragmented() {
        val decoder = SlipDecoder()
        
        // First chunk - starts a packet but doesn't end it
        val chunk1 = byteArrayOf(0xC0.toByte(), 10, 11)
        val packets1 = decoder.feed(chunk1)
        assertEquals(0, packets1.size)

        // Second chunk - completes the packet and starts/ends another one
        val chunk2 = byteArrayOf(12, 0xC0.toByte(), 0xC0.toByte(), 13, 14, 0xC0.toByte())
        val packets2 = decoder.feed(chunk2)
        assertEquals(2, packets2.size)
        assertArrayEquals(byteArrayOf(10, 11, 12), packets2[0])
        assertArrayEquals(byteArrayOf(13, 14), packets2[1])
    }
}
