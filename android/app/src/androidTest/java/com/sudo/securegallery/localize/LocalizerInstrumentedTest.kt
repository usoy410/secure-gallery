package com.sudo.securegallery.localize

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.detect.LiteRtDetector
import com.sudo.securegallery.scan.toDetectorImage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Hybrid localization on the emulator (issue #17): the bundled detector finds the card, the
 * bundled ML Kit model reads it with no network, and the shared field rules find fields on the
 * five synthetic photos. Logs counts and timings only, never the text read.
 */
@RunWith(AndroidJUnit4::class)
class LocalizerInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun findsFieldsOnSyntheticPhotos() = runBlocking {
        val detector = LiteRtDetector.open(app.assets)
        assertNotNull("detector.tflite is missing from the app's assets", detector)
        val templates = CardTemplate.loadAll(app.assets)
        val rules = FieldRules.load(app.assets)
        val mlKit = MlKitOcrEngine()
        var matches = 0
        val counting = OcrEngine { bitmap -> mlKit.recognize(bitmap).also { matches += rules.match(it).size } }
        val localizer = HybridLocalizer(templates, rules, counting, detector!!.config.highConfidence)

        val fixtures = JSONArray(testAssets.open("fixtures/fixtures.json").bufferedReader().use { it.readText() })
        var withFields = 0
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val bytes = testAssets.open("fixtures/${fixture.getString("file")}").use { it.readBytes() }
            val image = decode(bytes, HybridLocalizer.OCR_MAX_SIDE)
            val small = decode(bytes, detector.config.inputSize)
            val best = detector.detect(small.bitmap.toDetectorImage(small.originalWidth, small.originalHeight))
                .maxByOrNull { it.confidence }
            small.bitmap.recycle()
            val documentClass = best?.documentClass ?: DocumentClass.fromId(fixture.getString("document_class"))

            matches = 0
            val started = System.nanoTime()
            val result = localizer.localize(image, documentClass, best?.confidence ?: 0f, best?.corners)
            val millis = (System.nanoTime() - started) / 1_000_000
            image.bitmap.recycle()
            Log.i(TAG, "${fixture.getString("file")}: ${result.boxes.size} boxes, $matches rule matches, " +
                "${result.state}, fallback ${result.fallback}, $millis ms")

            assertTrue(result.boxes.isNotEmpty())
            assertTrue(result.state == ItemState.REDACTED || result.state == ItemState.NEEDS_CHECK)
            for (box in result.boxes) {
                // Boxes are in original-image pixels; a layout box may reach a little past the
                // photo edge (picture margin), never far.
                for ((j, v) in box.corners.withIndex()) {
                    val limit = if (j % 2 == 0) image.originalWidth else image.originalHeight
                    assertTrue("box outside the photo", v > -0.1f * limit && v < 1.1f * limit)
                }
            }
            if (matches > 0) withFields++
        }
        mlKit.close()
        detector.close()
        assertTrue("ML Kit found fields on only $withFields of ${fixtures.length()} photos", withFields >= 4)
    }

    private fun decode(bytes: ByteArray, maxSide: Int): OcrImage {
        var w = 0
        var h = 0
        val bitmap: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            w = info.size.width
            h = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return OcrImage(bitmap, w, h)
    }

    private companion object {
        const val TAG = "LocalizerTest"
    }
}
