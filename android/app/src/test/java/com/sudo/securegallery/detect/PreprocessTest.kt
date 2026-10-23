package com.sudo.securegallery.detect

import org.junit.Assert.assertEquals
import org.junit.Test

/** Letterboxing into the square model input (issue #16). */
class PreprocessTest {

    @Test
    fun wideImageIsCentredWithGreyBarsAboveAndBelow() {
        // 4 x 2 pixels, all pure red, into an 8 x 8 input: 8 x 4 image, 2 px bars.
        val lb = Letterbox.fit(4, 2, 8)
        assertEquals(2f, lb.scale)
        assertEquals(0f, lb.padX)
        assertEquals(2f, lb.padY)
        val input = Preprocess.toInput(IntArray(8) { 0xFFFF0000.toInt() }, 4, 2, lb)
        val plane = 64
        val grey = 114f / 255f
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val inImage = y in 2..5
                assertEquals("r $x,$y", if (inImage) 1f else grey, input[y * 8 + x], 1e-6f)
                assertEquals("g $x,$y", if (inImage) 0f else grey, input[plane + y * 8 + x], 1e-6f)
            }
        }
    }

    @Test
    fun downsampledDecodeStillMapsToTheOriginalSize() {
        // The phone decodes a 1600 x 1200 photo at 640 x 480; the letterbox uses the original.
        val lb = Letterbox.fit(1600, 1200, 640)
        val input = Preprocess.toInput(IntArray(640 * 480) { 0xFF0000FF.toInt() }, 640, 480, lb)
        assertEquals(1f, input[2 * 640 * 640 + 320 * 640 + 320], 1e-6f) // blue in the middle
        assertEquals(114f / 255f, input[2 * 640 * 640 + 10 * 640 + 320], 1e-6f) // grey bar at the top
    }
}
