package com.sudo.securegallery.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.detect.Corner
import com.sudo.securegallery.detect.Detection
import com.sudo.securegallery.detect.DetectorImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun detectedImageKeepsItsClassConfidenceAndCorners() = runTest {
        source.files[1] = "id photo".toByteArray()
        source.files[2] = "cat photo".toByteArray()
        val corners = listOf(Corner(10f, 20f), Corner(110f, 20f), Corner(110f, 80f), Corner(10f, 80f))
        val detector = com.sudo.securegallery.detect.DocumentDetector { image ->
            assertEquals(640, maxOf(image.width, image.height)) // decoded at detection size only
            if (image.originalWidth == 1600) {
                listOf(
                    Detection(DocumentClass.LTO, 0.5f, floatArrayOf(10f, 20f, 110f, 80f), corners),
                    Detection(DocumentClass.PHILSYS, 0.9f, floatArrayOf(10f, 20f, 110f, 80f), corners),
                )
            } else {
                emptyList()
            }
        }
        source.decodable[1] = 1600
        source.decodable[2] = 1200
        GalleryScanner(source, dao, detector).scan()

        val id = dao.byMediaStoreId(1)!!
        assertEquals(ItemState.PENDING, id.state) // waits for field localization (#17)
        assertEquals(DocumentClass.PHILSYS, id.documentClass) // the most confident detection
        assertEquals(0.9f, id.documentConfidence)
        assertEquals(CardCorners(listOf(10f, 20f, 110f, 20f, 110f, 80f, 10f, 80f)), id.cardCorners)
        assertEquals(ItemState.NOT_DETECTED, dao.byMediaStoreId(2)!!.state)
    }

    @Test
    fun itemsHashedWithoutAModelAreDetectedOnTheNextScan() = runTest {
        source.files[1] = "id photo".toByteArray()
        source.decodable[1] = 1600
        GalleryScanner(source, dao, detector = null).scan() // an older build, or no model
        assertNull(dao.byMediaStoreId(1)!!.documentClass)

        GalleryScanner(source, dao, cardDetector).scan()
        assertEquals(DocumentClass.PHILSYS, dao.byMediaStoreId(1)!!.documentClass)
    }

    @Test
    fun aFailingImageDoesNotBlockTheOthers() = runTest {
        source.files[1] = "broken".toByteArray()
        source.files[2] = "id photo".toByteArray()
        source.decodable[1] = 1601
        source.decodable[2] = 1600
        val detector = com.sudo.securegallery.detect.DocumentDetector { image ->
            if (image.originalWidth == 1601) throw IllegalStateException("bad image") else cardDetector.detect(image)
        }
        GalleryScanner(source, dao, detector).scan()
        val broken = dao.byMediaStoreId(1)!!
        assertEquals(sha256Hex("broken"), broken.contentHash) // the hash is kept
        assertNull(broken.documentClass) // and detection is tried again next time
        assertEquals(DocumentClass.PHILSYS, dao.byMediaStoreId(2)!!.documentClass)
    }

    @Test
    fun aChangedFileLosesItsOldDetection() = runTest {
        source.files[1] = "id photo".toByteArray()
        source.decodable[1] = 1600
        GalleryScanner(source, dao, cardDetector).scan()
        assertNotNull(dao.byMediaStoreId(1)!!.cardCorners)

        source.files[1] = "edited, now unreadable".toByteArray()
        source.modified[1] = 99L
        source.decodable.remove(1L)
        GalleryScanner(source, dao, cardDetector).scan()
        val item = dao.byMediaStoreId(1)!!
        assertNull(item.documentClass)
        assertNull(item.cardCorners)
    }

    private val cardDetector = com.sudo.securegallery.detect.DocumentDetector { image ->
        if (image.originalWidth == 1600) {
            listOf(Detection(DocumentClass.PHILSYS, 0.9f, floatArrayOf(0f, 0f, 1f, 1f), List(4) { Corner(it.toFloat(), 0f) }))
        } else {
            emptyList()
        }
    }

    @Test
    fun withoutAModelImagesAreOnlyHashed() = runTest {
        source.files[1] = "id photo".toByteArray()
        source.decodable[1] = 1600
        GalleryScanner(source, dao, detector = null).scan()
        val item = dao.byMediaStoreId(1)!!
        assertEquals(ItemState.PENDING, item.state)
        assertNull(item.documentClass)
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

        /** Images that decode, with their original long side; decoded at most 640 px. */
        val decodable = mutableMapOf<Long, Int>()

        override fun loadForDetection(mediaStoreId: Long, maxSide: Int): DetectorImage? {
            val long = decodable[mediaStoreId] ?: return null
            val w = minOf(long, maxSide)
            val h = w * 3 / 4
            return DetectorImage(IntArray(w * h), w, h, long, long * 3 / 4)
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
