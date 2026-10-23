package com.sudo.securegallery.localize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.detect.Corner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Hybrid localization (issue #17, §8.2): template fields mapped onto the photo, OCR on the
 * rectified card, the union of both, the two fallbacks and the confidence level. A fake OCR
 * engine places synthetic words; the photo is 2000 x 1500 decoded at half size, so every box
 * must also come back in original-image pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HybridLocalizerTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets
    private val templates = CardTemplate.loadAll(assets)
    private val rules = FieldRules.load(assets)

    /** A card seen at an angle, in original-image pixels. */
    private val corners = listOf(Corner(300f, 200f), Corner(1700f, 260f), Corner(1660f, 1100f), Corner(260f, 1040f))

    private val philsysWords = listOf(
        Word(FieldType.PCN, "1234-5678-9012-3456", 0.65f, 0.245f),
        Word(FieldType.FULL_NAME, "DELA CRUZ, JUAN", 0.65f, 0.42f),
        Word(FieldType.DATE_OF_BIRTH, "1990/01/02", 0.53f, 0.60f),
        Word(FieldType.ADDRESS, "12 MABINI ST", 0.53f, 0.78f),
    )

    @Test
    fun readsTheRectifiedCardAndCoversEveryTemplateField() = runTest {
        val ocr = FakeOcr(philsysWords)
        val result = localizer(ocr).localize(photo(), DocumentClass.PHILSYS, 0.9f, corners)

        val top = hypot(1400f, 60f)
        val side = hypot(40f, 840f)
        assertEquals(listOf(1400 to (1400 * side / top).roundToInt()), ocr.sizes) // one pass, upright card
        val card = template("philsys-card")
        for (expected in layoutPolygons(card)) assertTrue("missing ${expected.first}", result.boxes.any { it.near(expected) })
        assertEquals(card.fields.size + philsysWords.size, result.boxes.size) // the ePhilID layout is not used
        assertEquals(philsysWords.map { it.type }.toSet(), result.boxes.drop(card.fields.size).map { it.fieldType }.toSet())
        assertEquals(ItemState.REDACTED, result.state)
        assertFalse(result.fallback)
    }

    @Test
    fun lowDocumentConfidenceNeedsACheck() = runTest {
        val result = localizer(FakeOcr(philsysWords)).localize(photo(), DocumentClass.PHILSYS, 0.5f, corners)
        assertEquals(ItemState.NEEDS_CHECK, result.state)
        assertEquals(template("philsys-card").fields.size + philsysWords.size, result.boxes.size)
    }

    @Test
    fun anUnreadFieldIsStillCoveredByTheTemplateButNeedsACheck() = runTest {
        val words = philsysWords.filter { it.type != FieldType.ADDRESS }
        val result = localizer(FakeOcr(words)).localize(photo(), DocumentClass.PHILSYS, 0.9f, corners)
        assertEquals(ItemState.NEEDS_CHECK, result.state)
        val address = layoutPolygons(template("philsys-card")).first { it.first == FieldType.ADDRESS }
        assertTrue(result.boxes.any { it.near(address) })
    }

    @Test
    fun anOcrBoxReachingPastTheTemplateIsAddedNeverShrunk() = runTest {
        // The printed number drifted right, past the template's PCN region.
        val drifted = philsysWords.map { if (it.type == FieldType.PCN) it.copy(x = 0.80f, halfWidth = 0.18f) else it }
        val result = localizer(FakeOcr(drifted)).localize(photo(), DocumentClass.PHILSYS, 0.9f, corners)
        val layout = layoutPolygons(template("philsys-card")).first { it.first == FieldType.PCN }
        assertTrue(result.boxes.any { it.near(layout) }) // the template region stays
        val layoutRight = Geometry.bounds(layout.second)[2]
        assertTrue(result.boxes.any { it.fieldType == FieldType.PCN && Geometry.bounds(it.points())[2] > layoutRight + 50 })
    }

    @Test
    fun withoutCornersOnlyTheOcrFirstRulesRun() = runTest {
        val ocr = FakeOcr(emptyList()) { listOf(line(0, "1234-5678-9012-3456", 100f, 100f, 300f, 130f)) }
        val result = localizer(ocr).localize(photo(), DocumentClass.PHILSYS, 0.9f, null)
        assertEquals(listOf(1000 to 750), ocr.sizes) // the whole decoded photo, not a card
        assertEquals(1, result.boxes.size)
        val box = result.boxes.single()
        assertEquals(FieldType.PCN, box.fieldType)
        assertEquals(listOf(192f, 192f, 600f, 192f, 600f, 268f, 192f, 268f), box.corners) // pad 4, then x2
        assertEquals(ItemState.NEEDS_CHECK, result.state)
        assertFalse(result.fallback)
    }

    @Test
    fun withoutCornersAndNoFieldTheWholeDocumentIsOneBox() = runTest {
        val result = localizer(FakeOcr(emptyList())).localize(photo(), DocumentClass.LTO, 0.9f, null)
        assertTrue(result.fallback)
        assertEquals(ItemState.NEEDS_CHECK, result.state)
        assertEquals(listOf(RedactionBox(FieldType.WHOLE_DOCUMENT, listOf(0f, 0f, 2000f, 0f, 2000f, 1500f, 0f, 1500f), 0.9f)), result.boxes)
    }

    @Test
    fun oneMissingCornerIsInferred() = runTest {
        val parallelogram = listOf(Corner(300f, 200f), Corner(1700f, 260f), Corner(1660f, 1060f), Corner(260f, 1000f))
        val missing = parallelogram.mapIndexed { i, c -> if (i == 2) null else c }
        val full = localizer(FakeOcr(philsysWords)).localize(photo(), DocumentClass.PHILSYS, 0.9f, parallelogram)
        val inferred = localizer(FakeOcr(philsysWords)).localize(photo(), DocumentClass.PHILSYS, 0.9f, missing)
        assertEquals(full.boxes.size, inferred.boxes.size)
        for ((a, b) in full.boxes.zip(inferred.boxes)) assertTrue(a.near(a.fieldType to b.points()))
        assertEquals(ItemState.REDACTED, inferred.state)
    }

    @Test
    fun whenOcrCannotTellTheVariantEveryVariantIsCovered() = runTest {
        val result = localizer(FakeOcr(emptyList())).localize(photo(), DocumentClass.PHILSYS, 0.9f, corners)
        val both = layoutPolygons(template("philsys-card")) + layoutPolygons(template("philsys-ephilid"))
        assertEquals(both.size, result.boxes.size)
        for (expected in both) assertTrue(result.boxes.any { it.near(expected) })
        assertEquals(ItemState.NEEDS_CHECK, result.state)
        assertFalse(result.fallback)
    }

    @Test
    fun variantsSharingALayoutCanStillBeHighConfidence() = runTest {
        val words = listOf(
            Word(FieldType.LICENSE_NUMBER, "A01-23-456789", 0.65f, 0.26f),
            Word(FieldType.FULL_NAME, "DELA CRUZ, JUAN", 0.65f, 0.395f),
            Word(FieldType.DATE_OF_BIRTH, "1990/01/02", 0.49f, 0.625f),
            Word(FieldType.ADDRESS, "12 MABINI ST", 0.65f, 0.87f),
        )
        val result = localizer(FakeOcr(words)).localize(photo(), DocumentClass.LTO, 0.9f, corners)
        assertEquals(ItemState.REDACTED, result.state)
    }

    @Test
    fun aDarkCardIsAlsoReadInverted() = runTest {
        val ocr = FakeOcr(emptyList())
        localizer(ocr).localize(photo(Color.BLACK), DocumentClass.PAYMENT_CARD, 0.9f, corners)
        assertEquals(2, ocr.sizes.size)
        assertTrue(ocr.centreBrightness[0] < 20)
        assertTrue(ocr.centreBrightness[1] > 235)

        val light = FakeOcr(emptyList())
        localizer(light).localize(photo(Color.WHITE), DocumentClass.PAYMENT_CARD, 0.9f, corners)
        assertEquals(1, light.sizes.size)
    }

    private fun localizer(ocr: OcrEngine) = HybridLocalizer(templates, rules, ocr)

    private fun photo(color: Int = Color.WHITE) =
        OcrImage(Bitmap.createBitmap(1000, 750, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, 2000, 1500)

    private fun template(id: String) = templates.first { it.id == id }

    /** Each template field mapped straight into original-image pixels. */
    private fun layoutPolygons(template: CardTemplate): List<Pair<FieldType, List<Corner>>> {
        val matrix = Geometry.homography(Geometry.UNIT_SQUARE, corners)!!
        return template.fields.map { it.type to Geometry.project(matrix, template.paddedPolygon(it)) }
    }

    private fun RedactionBox.points() = corners.chunked(2).map { Corner(it[0], it[1]) }

    private fun RedactionBox.near(expected: Pair<FieldType, List<Corner>>) =
        fieldType == expected.first && points().zip(expected.second).all { (a, b) -> abs(a.x - b.x) < 2f && abs(a.y - b.y) < 2f }

    /** A synthetic word centred at ([x], [y]) in card space (fractions of the card). */
    private data class Word(val type: FieldType, val text: String, val x: Float, val y: Float, val halfWidth: Float = 0.05f)

    private class FakeOcr(
        private val words: List<Word>,
        private val fixed: (() -> List<OcrLine>)? = null,
    ) : OcrEngine {
        val sizes = mutableListOf<Pair<Int, Int>>()
        val centreBrightness = mutableListOf<Int>()

        override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
            sizes += bitmap.width to bitmap.height
            centreBrightness += Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            fixed?.let { return it() }
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            return words.mapIndexed { i, word ->
                line(i, word.text, (word.x - word.halfWidth) * w, word.y * h - 12f, (word.x + word.halfWidth) * w, word.y * h + 12f)
            }
        }
    }

    private companion object {
        /** One OCR line in its own block, its words spread evenly over the box. */
        fun line(block: Int, text: String, left: Float, top: Float, right: Float, bottom: Float): OcrLine {
            val parts = text.split(" ")
            val step = (right - left) / parts.size
            return OcrLine(block, 0, parts.mapIndexed { i, p -> OcrWord(p, left + i * step, top, left + (i + 1) * step - 4f, bottom) })
        }
    }
}
