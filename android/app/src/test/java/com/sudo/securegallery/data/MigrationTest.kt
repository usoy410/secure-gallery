package com.sudo.securegallery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Older databases upgrade without losing items: version 1 (#15) and version 2 (#16) to the
 * current one (issues #16, #18). The old tables are built from the committed schema export, then
 * Room opens the file: it runs the auto-migrations and checks the result against the current
 * entities, failing on any mismatch.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun version1To2KeepsItemsAndAddsEmptyCardCorners() = runTest {
        val path = createFromSchema(1, "migration-test-1.db")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
        val item = room.itemDao().byMediaStoreId(42)!!
        assertEquals("abc", item.contentHash)
        assertNull(item.cardCorners)
        room.close()
    }

    @Test
    fun version2To3KeepsItemsWithNoRedactedCopyYet() = runTest {
        val path = createFromSchema(2, "migration-test-2.db")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
        val item = room.itemDao().byMediaStoreId(42)!!
        assertEquals("abc", item.contentHash)
        assertNull(item.redactedMediaStoreId)
        room.close()
    }

    @Test
    fun version3To4MarksNoCopyAsRechecked() = runTest {
        val path = createFromSchema(3, "migration-test-3.db")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
        val item = room.itemDao().byMediaStoreId(42)!!
        assertEquals("abc", item.contentHash)
        assertEquals(false, item.recheckPassed)
        room.close()
    }

    @Test
    fun version4To5StartsWithNoCorrections() = runTest {
        val path = createFromSchema(4, "migration-test-4.db")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
        assertEquals(0, room.itemDao().byMediaStoreId(42)!!.corrections)
        room.close()
    }

    /** A database file at [version], built from its committed schema export, holding one item. */
    private fun createFromSchema(version: Int, name: String): File {
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/com.sudo.securegallery.data.AppDatabase/$version.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "items"))
            val indices = entity.getJSONArray("indices")
            for (i in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "items"))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO items (media_store_id, date_modified, size_bytes, content_hash, state, " +
                    "document_class, document_confidence, boxes, retry_count) " +
                    "VALUES (42, 1, 2, 'abc', 'PENDING', NULL, NULL, '', 0)",
            )
            db.version = version
        }
        return path
    }
}
