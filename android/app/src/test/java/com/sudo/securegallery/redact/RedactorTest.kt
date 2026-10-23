package com.sudo.securegallery.redact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Making the copy for one item (issue #18): it is saved and linked, the original is only read,
 * the full-size bitmap is released, and failures are retried before the item is marked FAILED.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedactorTest {
    private val box = RedactionBox(FieldType.PCN, listOf(10f, 10f, 60f, 10f, 60f, 30f, 10f, 30f), 0.9f)
    private val item = Item(id = 1, mediaStoreId = 7, dateModified = 1, sizeBytes = 3, contentHash = "h", state = ItemState.REDACTED, boxes = listOf(box))

    @Test
    fun theCopyIsSavedAndLinkedAndTheOriginalIsOnlyRead() {
        val source = FakeSource()
        val before = GalleryScanner.sha256(ByteArrayInputStream(source.originalBytes))
        val store = FakeStore()
        val done = Redactor(source, store).redact(item)

        assertEquals(500L, done.redactedMediaStoreId)
        assertEquals(ItemState.REDACTED, done.state)
        assertEquals(listOf(7L), store.saved.map { it.first })
        val copy = store.saved.single().second.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertTrue(Color.red(copy.getPixel(35, 20)) <= 8)
        assertTrue(Color.red(copy.getPixel(80, 50)) > 240) // outside the box: untouched
        assertTrue(source.fullBitmaps.single().isRecycled)
        assertArrayEquals(source.originalSnapshot, source.originalBytes)
        assertEquals(before, GalleryScanner.sha256(ByteArrayInputStream(source.originalBytes)))
    }

    @Test
    fun aFailingSaveIsRetriedThenMarkedFailed() {
        val source = FakeSource()
        val broken = RedactedCopyStore { _, _ -> throw IllegalStateException("disk full") }
        var current = item
        repeat(Redactor.MAX_ATTEMPTS - 1) {
            current = Redactor(source, broken).redact(current)
            assertEquals(ItemState.REDACTED, current.state)
            assertNull(current.redactedMediaStoreId)
        }
        current = Redactor(source, broken).redact(current)
        assertEquals(ItemState.FAILED, current.state)
        assertTrue(source.fullBitmaps.all { it.isRecycled })
    }

    @Test
    fun anOriginalDeletedSinceTheScanIsMarkedFailed() {
        val source = FakeSource().apply { deleted = true }
        assertEquals(ItemState.FAILED, Redactor(source, FakeStore()).redact(item).state)
    }

    private class FakeStore : RedactedCopyStore {
        val saved = mutableListOf<Pair<Long, ByteArray>>()
        override fun save(originalId: Long, jpeg: ByteArray): Long = 500L.also { saved += originalId to jpeg }
    }

    private class FakeSource : ImageSource {
        val originalBytes = java.io.ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        val originalSnapshot = originalBytes.copyOf()
        val fullBitmaps = mutableListOf<Bitmap>()
        var deleted = false

        override suspend fun listImages() = listOf(GalleryImage(7, 1, originalBytes.size.toLong()))

        override fun open(mediaStoreId: Long): InputStream? = if (deleted) null else ByteArrayInputStream(originalBytes)

        override fun loadFullResolution(mediaStoreId: Long): Bitmap? =
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, BitmapFactory.Options().apply { inMutable = true })
                .also { fullBitmaps += it }
    }
}
