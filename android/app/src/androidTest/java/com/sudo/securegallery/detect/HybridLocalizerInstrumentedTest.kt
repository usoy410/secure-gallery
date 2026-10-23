package com.sudo.securegallery.detect

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.scan.toDetectorImage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Instrumented test for on-device hybrid field localization (#17).
 *
 * Runs bundled ML Kit Text Recognition v2 and template homography on the 5 synthetic
 * photo fixtures in androidTest/assets/fixtures.
 */
@RunWith(AndroidJUnit4::class)
class HybridLocalizerInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun localizesSensitiveFieldsOnSyntheticFixtures() = runBlocking {
        val templates = CardTemplate.loadAll(app.assets)
        assertTrue("Templates must be bundled in app assets", templates.isNotEmpty())

        val rules = FieldRules.load(app.assets)
        assertNotNull("Field rules must be bundled in app assets", rules)

        val ocrEngine = MlKitOcrEngine()
        val localizer = HybridFieldLocalizer(templates, rules, ocrEngine)

        val openedDetector = LiteRtDetector.open(app.assets)
        val detector = openedDetector

        val fixturesJson = testAssets.open("fixtures/fixtures.json").bufferedReader().use { it.readText() }
        val fixtures = JSONArray(fixturesJson)

        var successfullyLocalised = 0

        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val fileName = fixture.getString("file")
            val expectedClass = DocumentClass.fromId(fixture.getString("document_class"))

            val bytes = testAssets.open("fixtures/$fileName").use { it.readBytes() }
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

            // Find corners using detector if available, or four corners of the card
            val detection = detector?.let {
                val scaled = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
                val detImg = scaled.toDetectorImage(bitmap.width, bitmap.height)
                val best = it.detect(detImg).maxByOrNull { d -> d.confidence }
                scaled.recycle()
                best
            }

            val corners = detection?.corners ?: listOf(
                Corner(0f, 0f),
                Corner(bitmap.width.toFloat(), 0f),
                Corner(bitmap.width.toFloat(), bitmap.height.toFloat()),
                Corner(0f, bitmap.height.toFloat()),
            )

            val item = Item(
                mediaStoreId = i.toLong() + 1,
                dateModified = 0,
                sizeBytes = bytes.size.toLong(),
                documentClass = expectedClass,
                documentConfidence = detection?.confidence ?: 0.85f,
                cardCorners = CardCorners(corners.flatMap { listOf(it.x, it.y) }),
            )

            val started = System.nanoTime()
            val result = localizer.localize(item, bitmap)
            val millis = (System.nanoTime() - started) / 1_000_000

            Log.i(TAG, "$fileName: localized ${result.boxes.size} boxes in $millis ms, state=${result.state}, fallback=${result.fallback}")

            // Verify boxes were computed
            assertTrue("$fileName must produce redaction boxes", result.boxes.isNotEmpty())
            assertFalse("$fileName must not trigger whole-doc fallback", result.fallback)

            // Verify expected sensitive field types are covered
            val boxTypes = result.boxes.map { it.fieldType }
            when (expectedClass) {
                DocumentClass.PHILSYS -> {
                    assertTrue(boxTypes.contains(FieldType.PCN))
                    assertTrue(boxTypes.contains(FieldType.FACE_PHOTO))
                }
                DocumentClass.LTO -> {
                    assertTrue(boxTypes.contains(FieldType.LICENSE_NUMBER))
                    assertTrue(boxTypes.contains(FieldType.FACE_PHOTO))
                }
                DocumentClass.PAYMENT_CARD -> {
                    assertTrue(
                        boxTypes.contains(FieldType.CARD_NUMBER) ||
                            boxTypes.contains(FieldType.CVV),
                    )
                }
            }

            successfullyLocalised++
            bitmap.recycle()
        }

        detector?.close()
        ocrEngine.close()

        assertEquals("All fixtures should localize sensitive fields", fixtures.length(), successfullyLocalised)
    }

    private companion object {
        const val TAG = "HybridLocalizerTest"
    }
}
