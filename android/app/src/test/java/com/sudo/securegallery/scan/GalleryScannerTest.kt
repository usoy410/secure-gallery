package com.sudo.securegallery.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The scan indexes every image, then hashes them one at a time (issue #15). */
@RunWith(AndroidJUnit4::class)
class GalleryScannerTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private val source = FakeSource()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.itemDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun firstScanIndexesAndHashesEveryImage() = runTest {
        source.files[1] = "one".toByteArray()
        source.files[2] = "two".toByteArray()
        val result = GalleryScanner(source, dao).scan()

        assertEquals(GalleryScanner.Result(found = 2, processed = 2, missing = 0), result)
        assertEquals(2, dao.countAll().first())
        assertEquals(2, dao.countProcessed().first())
        val one = dao.byMediaStoreId(1)!!
        assertEquals(sha256Hex("one"), one.contentHash)
        assertEquals(ItemState.PENDING, one.state) // detection (#16) decides the rest
    }

    @Test
    fun imagesAreOpenedOneAtATime() = runTest {
        for (id in 1L..5L) source.files[id] = byteArrayOf(id.toByte())
        GalleryScanner(source, dao).scan()
        assertEquals(1, source.maxOpenAtOnce)
    }

    @Test
    fun secondScanSkipsUnchangedImages() = runTest {
        source.files[1] = "one".toByteArray()
        GalleryScanner(source, dao).scan()
        source.opened.clear()
        val again = GalleryScanner(source, dao).scan()
        assertEquals(0, again.processed)
        assertEquals(emptyList<Long>(), source.opened)
    }

    @Test
    fun changedImageIsIndexedAgainUnlessItIsAlreadyFlagged() = runTest {
        source.files[1] = "one".toByteArray()
        source.files[2] = "two".toByteArray()
        GalleryScanner(source, dao).scan()
        dao.update(dao.byMediaStoreId(1)!!.moveTo(ItemState.NOT_DETECTED))
        dao.update(dao.byMediaStoreId(2)!!.moveTo(ItemState.REDACTED))

        source.files[1] = "one, edited".toByteArray()
        source.files[2] = "two, edited".toByteArray()
        source.modified += 1L to 99L
        source.modified += 2L to 99L
        GalleryScanner(source, dao).scan()

        val one = dao.byMediaStoreId(1)!!
        assertEquals(sha256Hex("one, edited"), one.contentHash)
        assertEquals(ItemState.PENDING, one.state)
        // A redacted item keeps its state: its original is already in the pipeline.
        assertEquals(ItemState.REDACTED, dao.byMediaStoreId(2)!!.state)
    }

    @Test
    fun imageDeletedDuringTheScanIsMarkedFailedNotRetried() = runTest {
        source.files[1] = "one".toByteArray()
        source.listed += 3L // listed by MediaStore, gone when opened
        val result = GalleryScanner(source, dao).scan()
        assertEquals(1, result.missing)
        val gone = dao.byMediaStoreId(3)!!
        assertEquals(ItemState.FAILED, gone.state)
        assertNull(gone.contentHash)
        assertNull(dao.nextUnhashed())
        assertEquals(2, dao.countProcessed().first())
    }

    private fun sha256Hex(text: String) = GalleryScanner.sha256(ByteArrayInputStream(text.toByteArray()))

    private class FakeSource : ImageSource {
        val files = sortedMapOf<Long, ByteArray>()
        val listed = mutableSetOf<Long>()
        val modified = mutableMapOf<Long, Long>()
        val opened = mutableListOf<Long>()
        var openNow = 0
        var maxOpenAtOnce = 0

        override suspend fun listImages() = (files.keys + listed).sorted().map { id ->
            GalleryImage(id, modified[id] ?: 1L, files[id]?.size?.toLong() ?: 0L)
        }

        override fun open(mediaStoreId: Long): InputStream? {
            val bytes = files[mediaStoreId] ?: return null
            opened += mediaStoreId
            openNow++
            maxOpenAtOnce = maxOf(maxOpenAtOnce, openNow)
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    openNow--
                    super.close()
                }
            }
        }
    }
}
