package com.sudo.securegallery.detect

import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.scan.toDetectorImage
import org.json.JSONArray
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * The bundled model on the emulator (issue #16): it loads from the app's assets and finds the
 * right document class on at least 4 of 5 synthetic photos it never saw in training.
 */
@RunWith(AndroidJUnit4::class)
class DetectorInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun findsTheRightClassOnSyntheticPhotos() {
        val opened = LiteRtDetector.open(app.assets)
        assertNotNull("detector.tflite is missing from the app's assets", opened)
        val detector = opened!!
        val fixtures = JSONArray(testAssets.open("fixtures/fixtures.json").bufferedReader().use { it.readText() })
        var correct = 0
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val bytes = testAssets.open("fixtures/${fixture.getString("file")}").use { it.readBytes() }
            var originalW = 0
            var originalH = 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                originalW = info.size.width
                originalH = info.size.height
                val scale = minOf(1f, detector.config.inputSize.toFloat() / maxOf(originalW, originalH))
                decoder.setTargetSize((originalW * scale).toInt(), (originalH * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val started = System.nanoTime()
            val best = detector.detect(bitmap.toDetectorImage(originalW, originalH)).maxByOrNull { it.confidence }
            val millis = (System.nanoTime() - started) / 1_000_000
            val expected = DocumentClass.fromId(fixture.getString("document_class"))
            Log.i(TAG, "${fixture.getString("file")}: expected $expected, got ${best?.documentClass} " +
                "(${best?.confidence}) in $millis ms on ${detector.accelerator}")
            if (best?.documentClass == expected) correct++
            if (best != null) assertTrue(best.corners.size == 4)
        }
        detector.close()
        assertTrue("only $correct of ${fixtures.length()} correct", correct >= 4)
    }

    private companion object {
        const val TAG = "DetectorTest"
    }
}
