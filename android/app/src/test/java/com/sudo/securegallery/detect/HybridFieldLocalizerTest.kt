package com.sudo.securegallery.detect

import android.graphics.Bitmap
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * JVM unit tests for [HybridFieldLocalizer] (§5.1, §8.2).
 */
@RunWith(RobolectricTestRunner::class)
class HybridFieldLocalizerTest {

    private lateinit var templates: List<CardTemplate>
    private lateinit var rules: FieldRules

    @Before
    fun setUp() {
        val templatesDir = File("../../templates").let {
            if (it.exists()) it else File("templates")
        }
        val rulesFile = File("../../templates/rules/fields.json").let {
            if (it.exists()) it else File("templates/rules/fields.json")
        }
        templates = CardTemplate.loadAll(templatesDir)
        rules = FieldRules.load(rulesFile)
    }

    @Test
    fun layoutFirstComputesBoxesEvenWhenTextCannotBeRead() = runTest {
        val emptyOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = emptyList()
        }
        val localizer = HybridFieldLocalizer(templates, rules, emptyOcr)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)

        val item = Item(
            mediaStoreId = 1,
            dateModified = 0,
            sizeBytes = 100,
            documentClass = DocumentClass.PHILSYS,
            documentConfidence = 0.85f,
            cardCorners = CardCorners(listOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f)),
        )

        val result = localizer.localize(item, bitmap)
        assertFalse(result.fallback)
        // Even with no text read, all sensitive template fields are redacted!
        assertTrue(result.boxes.isNotEmpty())
        assertTrue(result.boxes.any { it.fieldType == FieldType.PCN })
        assertTrue(result.boxes.any { it.fieldType == FieldType.FACE_PHOTO })
        assertTrue(result.boxes.any { it.fieldType == FieldType.QR_CODE })
        // Since no OCR rules matched, state becomes NEEDS_CHECK
        assertEquals(ItemState.NEEDS_CHECK, result.state)
    }

    @Test
    fun ocrRefinementEnlargesBoxWhenTextExtendsOutsideTemplate() = runTest {
        val template = templates.first { it.id == "philsys-card" }
        val pcnField = template.fields.first { it.type == FieldType.PCN }
        // Template PCN in 1000x600 image: [0.36, 0.20] to [0.94, 0.29] -> x: 360 to 940, y: 120 to 174
        // Suppose OCR finds text reaching x = 960 (extending to the right)
        val ocrEngine = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
                return listOf(
                    OcrLine(
                        text = "1234-5678-9012-3456",
                        words = listOf(
                            OcrWord("1234-5678-9012-3456", 0f, 0f, bitmap.width.toFloat() + 20f, bitmap.height.toFloat()),
                        ),
                    ),
                )
            }
        }

        val localizer = HybridFieldLocalizer(templates, rules, ocrEngine)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val item = Item(
            mediaStoreId = 1,
            dateModified = 0,
            sizeBytes = 100,
            documentClass = DocumentClass.PHILSYS,
            documentConfidence = 0.90f,
            cardCorners = CardCorners(listOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f)),
        )

        val result = localizer.localize(item, bitmap)
        val pcnBox = result.boxes.first { it.fieldType == FieldType.PCN }

        // Final box is never smaller than the template region
        assertTrue(pcnBox.corners[0] <= 360f)
        assertTrue(pcnBox.corners[1] <= 120f)
        // High confidence and rule matched -> REDACTED
        assertEquals(ItemState.REDACTED, result.state)
    }

    @Test
    fun fallbackWithoutCornersUsesOcrFirstRulesOnly() = runTest {
        val ocrEngine = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
                return listOf(
                    OcrLine(
                        text = "PCN: 1111-2222-3333-4444",
                        words = listOf(
                            OcrWord("PCN:", 10f, 10f, 40f, 30f),
                            OcrWord("1111-2222-3333-4444", 45f, 10f, 200f, 30f),
                        ),
                    ),
                )
            }
        }

        val localizer = HybridFieldLocalizer(templates, rules, ocrEngine)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val itemWithoutCorners = Item(
            mediaStoreId = 1,
            dateModified = 0,
            sizeBytes = 100,
            documentClass = DocumentClass.PHILSYS,
            documentConfidence = 0.70f,
            cardCorners = null, // No corners found
        )

        val result = localizer.localize(itemWithoutCorners, bitmap)
        assertFalse(result.fallback)
        assertEquals(1, result.boxes.size)
        assertEquals(FieldType.PCN, result.boxes[0].fieldType)
        assertEquals(ItemState.NEEDS_CHECK, result.state)
    }

    @Test
    fun fallbackWithoutCornersAndNoOcrMatchYieldsWholeDocumentBox() = runTest {
        val emptyOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = emptyList()
        }

        val localizer = HybridFieldLocalizer(templates, rules, emptyOcr)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val item = Item(
            mediaStoreId = 1,
            dateModified = 0,
            sizeBytes = 100,
            documentClass = DocumentClass.PHILSYS,
            documentConfidence = 0.70f,
            cardCorners = null,
        )

        val result = localizer.localize(item, bitmap)
        assertTrue(result.fallback)
        assertEquals(1, result.boxes.size)
        assertEquals(FieldType.WHOLE_DOCUMENT, result.boxes[0].fieldType)
        assertEquals(listOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f), result.boxes[0].corners)
        assertEquals(ItemState.NEEDS_CHECK, result.state)
    }

    @Test
    fun confidenceLevelSetsRedactedOnlyWhenDocHighAndRulesMatched() = runTest {
        val matchingOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = listOf(
                OcrLine("1234-5678-9012-3456", listOf(OcrWord("1234-5678-9012-3456", 10f, 10f, 100f, 30f))),
            )
        }
        val emptyOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = emptyList()
        }

        val localizerHigh = HybridFieldLocalizer(templates, rules, matchingOcr)
        val localizerEmpty = HybridFieldLocalizer(templates, rules, emptyOcr)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val corners = CardCorners(listOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f))

        // Doc >= 0.6 AND matched -> REDACTED
        val highAndMatched = Item(mediaStoreId = 1, dateModified = 0, sizeBytes = 10, documentClass = DocumentClass.PHILSYS, documentConfidence = 0.75f, cardCorners = corners)
        assertEquals(ItemState.REDACTED, localizerHigh.localize(highAndMatched, bitmap).state)

        // Doc >= 0.6 BUT unreadable/missing -> NEEDS_CHECK
        val highAndUnreadable = Item(mediaStoreId = 2, dateModified = 0, sizeBytes = 10, documentClass = DocumentClass.PHILSYS, documentConfidence = 0.75f, cardCorners = corners)
        assertEquals(ItemState.NEEDS_CHECK, localizerEmpty.localize(highAndUnreadable, bitmap).state)

        // Doc < 0.6 EVEN IF matched -> NEEDS_CHECK
        val lowAndMatched = Item(mediaStoreId = 3, dateModified = 0, sizeBytes = 10, documentClass = DocumentClass.PHILSYS, documentConfidence = 0.45f, cardCorners = corners)
        assertEquals(ItemState.NEEDS_CHECK, localizerHigh.localize(lowAndMatched, bitmap).state)
    }
}
