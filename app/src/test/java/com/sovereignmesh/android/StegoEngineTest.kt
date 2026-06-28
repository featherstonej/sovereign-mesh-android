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

import android.graphics.Bitmap
import com.sovereignmesh.android.util.stego.StegoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Unit tests for the [StegoEngine] to verify payload embedding and extraction parity.
 */
class StegoEngineTest {

    @Test
    fun testHideAndExtractParity() {
        val width = 100
        val height = 100
        val pixelsArray = IntArray(width * height)

        // Mock source bitmap
        val sourceBitmap = mock(Bitmap::class.java)
        `when`(sourceBitmap.width).thenReturn(width)
        `when`(sourceBitmap.height).thenReturn(height)

        // Mock stego bitmap
        val stegoBitmap = mock(Bitmap::class.java)
        `when`(stegoBitmap.width).thenReturn(width)
        `when`(stegoBitmap.height).thenReturn(height)

        // Stub copy method
        `when`(sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(stegoBitmap)

        // Stub getPixels and setPixels using matchers
        doAnswer { invocation ->
            val dest = invocation.getArgument<IntArray>(0)
            System.arraycopy(pixelsArray, 0, dest, 0, pixelsArray.size)
            null
        }.`when`(stegoBitmap).getPixels(
            any(IntArray::class.java),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt()
        )

        doAnswer { invocation ->
            val src = invocation.getArgument<IntArray>(0)
            System.arraycopy(src, 0, pixelsArray, 0, pixelsArray.size)
            null
        }.`when`(stegoBitmap).setPixels(
            any(IntArray::class.java),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt()
        )

        val payload = "SovereignMeshSecretPayload".toByteArray(Charsets.UTF_8)

        // Hide payload
        val resultBitmap = StegoEngine.hidePayload(sourceBitmap, payload)
        assertNotNull(resultBitmap)

        // Extract payload
        val extracted = StegoEngine.extractPayload(resultBitmap!!)
        assertNotNull(extracted)

        assertArrayEquals(payload, extracted)
    }
}
