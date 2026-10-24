package com.sudo.securegallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The item table holds no OCR text or field values (§8.4, CLAUDE.md hard rules).
 *
 * Adding a column means changing [ALLOWED_COLUMNS] here, so the change shows up in review.
 */
@RunWith(AndroidJUnit4::class)
class ItemSchemaTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun itemTableHasOnlyTheColumnsSection84Allows() {
        val columns = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(items)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(name)
        }
        assertEquals(ALLOWED_COLUMNS, columns)
    }

    @Test
    fun databaseHasNoOtherTables() {
        val tables = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        assertEquals(setOf("items"), tables)
    }

    @Test
    fun boxesRoundTripThroughTheDatabase() = runTest {
        val boxes = listOf(
            RedactionBox(FieldType.PCN, listOf(1f, 2f, 3f, 2f, 3f, 4f, 1f, 4f), 0.9f),
            RedactionBox(FieldType.FACE_PHOTO, listOf(0f, 0f, 10.5f, 0f, 10.5f, 20f, 0f, 20f), 1f),
        )
        val dao = db.itemDao()
        dao.insertNew(listOf(Item(mediaStoreId = 7, dateModified = 1, sizeBytes = 2, boxes = boxes,
            documentClass = DocumentClass.PHILSYS, documentConfidence = 0.8f)))
        val stored = dao.byMediaStoreId(7)!!
        assertEquals(boxes, stored.boxes)
        assertEquals(DocumentClass.PHILSYS, stored.documentClass)
    }

    @Test
    fun boxColumnRejectsAnythingButKnownFieldTypesAndNumbers() {
        assertThrows(NoSuchElementException::class.java) {
            Converters.decodeBoxes("JUAN DELA CRUZ:1,2,3,4,5,6,7,8:0.9")
        }
        assertThrows(NumberFormatException::class.java) {
            Converters.decodeBoxes("full_name:JUAN,2,3,4,5,6,7,8:0.9")
        }
    }

    companion object {
        val ALLOWED_COLUMNS = setOf(
            "id", "media_store_id", "date_modified", "size_bytes", "content_hash", "state",
            "document_class", "document_confidence", "boxes", "retry_count", "card_corners",
            "redacted_media_store_id", "recheck_passed",
        )
    }
}
