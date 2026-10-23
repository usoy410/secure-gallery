package com.sudo.securegallery.redact

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.detect.LiteRtDetector
import com.sudo.securegallery.localize.CardTemplate
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.HybridLocalizer
import com.sudo.securegallery.localize.MlKitOcrEngine
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageQuery
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.scan.MediaStoreImageSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole pipeline on the emulator's real MediaStore (issue #18): 20 synthetic photos carrying
 * GPS, device tags and an unredacted thumbnail are scanned, detected, localized and redacted.
 * Each copy must sit in its original's album with only the capture date kept and the rotation
 * applied, and each original must be byte-for-byte unchanged. The copies and their boxes are
 * exported to `Download/sg-redaction-export/`, where CI picks them up for the Python leak test
 * (D11, D13). Logs counts only, never text.
 */
@RunWith(AndroidJUnit4::class)
class RedactionInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val resolver = app.contentResolver
    private val album = "Pictures/SG redaction test/"

    @Test
    fun redactsEveryPhotoIntoItsAlbumAndLeavesTheOriginal() = runBlocking {
        val ids = testAssets.list("redaction").orEmpty().filter { it.endsWith(".jpg") }.map { it.removeSuffix(".jpg") }.sorted()
        assertEquals(20, ids.size)
        val originals = ids.associateWith { insertOriginal(it) }
        val copies = mutableListOf<Long>()
        try {
            val before = originals.mapValues { (_, id) -> sha256(imageUri(id)) }
            val detector = LiteRtDetector.open(app.assets)
            assertNotNull("detector.tflite is missing from the app's assets", detector)
            val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            val mediaStore = MediaStoreImageSource(resolver, app.packageName)
            // The test inserted the photos itself, so the app owns them and the normal query
            // skips them; list exactly these instead.
            val source = object : ImageSource by mediaStore {
                override suspend fun listImages() = originals.values.map { GalleryImage(it, 1, 1) }
            }
            val ocr = MlKitOcrEngine()
            val localizer = HybridLocalizer(CardTemplate.loadAll(app.assets), FieldRules.load(app.assets), ocr, detector!!.config.highConfidence)
            val started = System.nanoTime()
            GalleryScanner(source, db.itemDao(), detector, detector.config, localizer, Redactor(source, MediaStoreCopyStore(resolver))).scan()
            Log.i(TAG, "pipeline on ${ids.size} photos took ${(System.nanoTime() - started) / 1_000_000} ms")
            ocr.close()
            detector.close()

            clearExport()
            for ((name, originalId) in originals) {
                val item = db.itemDao().byMediaStoreId(originalId)!!
                Log.i(TAG, "$name: ${item.state} ${item.documentClass} ${item.boxes.size} boxes")
                val copyId = item.redactedMediaStoreId
                assertNotNull("$name has no redacted copy (${item.state})", copyId)
                copies += copyId!!
                assertTrue(item.state == ItemState.REDACTED || item.state == ItemState.NEEDS_CHECK)

                // The original is never modified.
                assertEquals(name, before.getValue(name), sha256(imageUri(originalId)))

                // Same album, app-owned marker, and the date MediaStore files it under.
                val original = row(originalId)
                val copy = row(copyId)
                assertEquals(original.path, copy.path)
                assertTrue(copy.name, copy.name.contains(ImageQuery.REDACTED_NAME_MARKER))
                assertEquals(app.packageName, copy.owner)
                assertNotNull("§20.1: MediaStore set no DATE_TAKEN", copy.dateTaken)
                assertEquals("§20.1: DATE_TAKEN of $name", original.dateTaken, copy.dateTaken)

                // Only the capture date survives; the rotation is in the pixels.
                val bytes = resolver.openInputStream(imageUri(copyId))!!.use { it.readBytes() }
                val exif = ExifInterface(bytes.inputStream())
                assertNull(exif.latLong)
                assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
                assertFalse(exif.hasThumbnail())
                assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
                assertTrue(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)!!.startsWith("2024:05:"))
                val size = JSONObject(testAssets.open("redaction/$name.json").bufferedReader().use { it.readText() })
                    .getJSONArray("image_size")
                assertEquals("$name is upright", size.getInt(0) to size.getInt(1), decodedSize(bytes))

                export("$name.jpg", "image/jpeg", bytes)
                val boxes = JSONArray()
                for (box in item.boxes) {
                    boxes.put(JSONObject().put("field_type", box.fieldType.id).put("polygon", JSONArray(box.corners.chunked(2).map { JSONArray(it) })))
                }
                export("$name.boxes.json", "application/json", JSONObject().put("boxes", boxes).toString().toByteArray())
            }
            db.close()
        } finally {
            for (id in originals.values + copies) resolver.delete(imageUri(id), null, null)
        }
    }

    private fun insertOriginal(name: String): Long {
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, album)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { out -> testAssets.open("redaction/$name.jpg").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return ContentUris.parseId(uri)
    }

    private data class Row(val name: String, val path: String?, val owner: String?, val dateTaken: Long?)

    private fun row(id: Long): Row {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        return resolver.query(imageUri(id), projection, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            Row(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3))
        }
    }

    private fun imageUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun sha256(uri: Uri): String = resolver.openInputStream(uri)!!.use(GalleryScanner::sha256)

    private fun decodedSize(jpeg: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        return options.outWidth to options.outHeight
    }

    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Removes the previous run's export, so the folder holds exactly this run's files. */
    private fun clearExport() {
        resolver.delete(downloads, "${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf(EXPORT_PATH))
    }

    /** Downloads survive the test APK's uninstall, so CI can pull them with adb afterwards. */
    private fun export(name: String, mime: String, bytes: ByteArray) {
        val uri = resolver.insert(
            downloads,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_PATH)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
    }

    private companion object {
        const val TAG = "RedactionTest"
        const val EXPORT_PATH = "Download/sg-redaction-export/"
    }
}
