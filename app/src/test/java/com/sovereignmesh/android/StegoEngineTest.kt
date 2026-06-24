package com.sovereignmesh.android

import android.graphics.Bitmap
import com.sovereignmesh.android.util.stego.StegoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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
