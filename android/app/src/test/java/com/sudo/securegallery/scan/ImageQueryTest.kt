package com.sudo.securegallery.scan

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore.Images.Media
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * Runs the real MediaStore query against a fake MediaStore table (issue #15): still images
 * only, no GIFs, nothing the app wrote itself, nothing still being written. The filters run
 * in Kotlin, not in a selection, and a null column keeps the photo (#65).
 */
@RunWith(AndroidJUnit4::class)
class ImageQueryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        FakeMediaProvider.selections.clear()
        val db = FakeMediaProvider.db
        fun row(id: Long, mime: String?, name: String?, owner: String?, pending: Int? = 0) =
            db.insert("images", null, ContentValues().apply {
                put(Media._ID, id); put(Media.MIME_TYPE, mime); put(Media.DISPLAY_NAME, name)
                put(Media.OWNER_PACKAGE_NAME, owner); put(Media.IS_PENDING, pending)
                put(Media.DATE_MODIFIED, 1_700_000_000L + id); put(Media.SIZE, 1000L * id)
            })
        row(1, "image/jpeg", "IMG_0001.jpg", "com.android.camera")
        row(2, "image/png", "Screenshot_1.png", null)
        row(3, "image/gif", "funny.gif", "com.android.chrome")
        row(4, "image/jpeg", "IMG_0001.sg-redacted.jpg", null) // own copy after a reinstall
        row(5, "image/jpeg", "IMG_0005.jpg", context.packageName) // written by this app
        row(6, "image/jpeg", "IMG_0006.jpg", "com.android.camera", pending = 1)
        row(7, "image/heic", "IMG_0007.heic", "com.android.camera")
        // Values MediaStore can leave null for a caller that does not own the file (#65).
        row(8, null, null, null, pending = null)
    }

    @Test
    fun queryKeepsOnlyOtherAppsFinishedStillImages() = runTest {
        val images = MediaStoreImageSource(context.contentResolver, context.packageName).listImages()
        assertEquals(listOf(1L, 2L, 7L, 8L), images.map { it.mediaStoreId })
        assertEquals(GalleryImage(7, 1_700_000_007L, 7000L), images[2])
    }

    @Test
    fun theQueryHasNoSelectionSoMediaStoreCannotDropRows() = runTest {
        MediaStoreImageSource(context.contentResolver, context.packageName).listImages()
        // On a MediaTek Android 14 phone a selection on OWNER_PACKAGE_NAME returned no rows to
        // the app (#65); the filters run in Kotlin instead.
        assertEquals(listOf<String?>(null), FakeMediaProvider.selections)
    }

    @Test
    fun keepTreatsNullAsKeep() {
        val own = context.packageName
        assertTrue(ImageQuery.keep("image/jpeg", "IMG_1.jpg", "com.android.camera", 0, own))
        assertTrue(ImageQuery.keep(null, null, null, null, own))
        assertFalse(ImageQuery.keep("image/gif", "a.gif", null, 0, own))
        assertFalse(ImageQuery.keep("image/jpeg", "IMG_1.jpg", own, 0, own))
        assertFalse(ImageQuery.keep("image/jpeg", "IMG_1.sg-redacted.jpg", null, 0, own))
        assertFalse(ImageQuery.keep("image/jpeg", "IMG_1.jpg", null, 1, own))
    }

    /** A MediaStore stand-in that answers queries from an in-memory SQLite table. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null).apply {
                execSQL(
                    "CREATE TABLE images (${Media._ID} INTEGER PRIMARY KEY, ${Media.MIME_TYPE} TEXT, " +
                        "${Media.DISPLAY_NAME} TEXT, ${Media.OWNER_PACKAGE_NAME} TEXT, " +
                        "${Media.IS_PENDING} INTEGER, ${Media.DATE_MODIFIED} INTEGER, ${Media.SIZE} INTEGER)",
                )
            }
            return true
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            assertEquals(Media.EXTERNAL_CONTENT_URI, uri)
            selections += selection
            return db.query("images", projection, selection, selectionArgs, null, null, sortOrder)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            lateinit var db: SQLiteDatabase
            val selections = mutableListOf<String?>()
        }
    }
}
