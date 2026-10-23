package com.sudo.securegallery.detect

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the critical privacy rule (§8.4): OCR text stays in memory only.
 *
 * Recognized text, card numbers, and cardholder names must never be saved
 * to the Item, its RedactionBoxes, logs, or the database.
 */
@RunWith(AndroidJUnit4::class)
class NoPlaintextLeakedTest {

    private lateinit var db: AppDatabase
    private lateinit var templates: List<CardTemplate>
    private lateinit var rules: FieldRules

    private val sensitivePcn = "1234-5678-9012-3456"
    private val sensitiveName = "DELA CRUZ, JUAN"
    private val sensitiveDob = "1990/01/15"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val templatesDir = File("../../templates").let {
            if (it.exists()) it else File("templates")
        }
        val rulesFile = File("../../templates/rules/fields.json").let {
            if (it.exists()) it else File("templates/rules/fields.json")
        }
        templates = CardTemplate.loadAll(templatesDir)
        rules = FieldRules.load(rulesFile)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun itemAndDatabaseContainNoRecognizedPlaintext() = runTest {
        val ocrEngine = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = listOf(
                OcrLine(sensitivePcn, listOf(OcrWord(sensitivePcn, 10f, 10f, 100f, 30f))),
                OcrLine(sensitiveName, listOf(OcrWord(sensitiveName, 10f, 40f, 120f, 60f))),
                OcrLine(sensitiveDob, listOf(OcrWord(sensitiveDob, 10f, 70f, 80f, 90f))),
            )
        }

        val localizer = HybridFieldLocalizer(templates, rules, ocrEngine)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val item = Item(
            mediaStoreId = 42,
            dateModified = 100,
            sizeBytes = 200,
            documentClass = DocumentClass.PHILSYS,
            documentConfidence = 0.88f,
            cardCorners = CardCorners(listOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f)),
        )

        val result = localizer.localize(item, bitmap)
        val updatedItem = item.copy(boxes = result.boxes, state = result.state)

        // Save into Room database
        db.itemDao().insertNew(listOf(updatedItem))
        val fromDb = db.itemDao().byMediaStoreId(42)!!

        // String representation of the item and its boxes
        val itemDump = "$updatedItem $fromDb ${result.boxes}"

        assertFalse(itemDump.contains(sensitivePcn))
        assertFalse(itemDump.contains(sensitiveName))
        assertFalse(itemDump.contains(sensitiveDob))
        assertFalse(itemDump.contains("1234"))
        assertFalse(itemDump.contains("DELA"))
        assertFalse(itemDump.contains("1990"))

        // Verify boxes only contain FieldType, numbers, and confidence
        for (box in fromDb.boxes) {
            assertTrue(box.fieldType.id.isNotEmpty())
            assertEquals(8, box.corners.size)
        }
    }
}
