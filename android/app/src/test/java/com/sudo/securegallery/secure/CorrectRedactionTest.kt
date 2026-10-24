package com.sudo.securegallery.secure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.OcrEngine
import com.sudo.securegallery.localize.OcrLine
import com.sudo.securegallery.localize.OcrWord
import com.sudo.securegallery.redact.BoxEdits
import com.sudo.securegallery.redact.Corrections
import com.sudo.securegallery.redact.Recheck
import com.sudo.securegallery.redact.RedactedCopyStore
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.testVault
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Saving corrected boxes (issue #24): the copy is rendered again from the hash-checked original
 * and overwritten, the recheck runs again, and only the boxes and correction counts are stored.
 * A fake OCR engine "reads" a card number while the probe spot (150, 50) is still light.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CorrectRedactionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private lateinit var vault: Vault
    private lateinit var key: Vault.Key
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val recheck = Recheck(FieldRules.load(context.assets), ProbeOcr())
    private val original = ByteArrayOutputStream().also { white().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private val store = FakeStore()
    private val source = FakeSource()

    /** Misses the number at (150, 50). */
    private val wrongBox = RedactionBox(FieldType.CARD_NUMBER, listOf(10f, 30f, 60f, 30f, 60f, 70f, 10f, 70f), 0.8f)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.itemDao()
        vault = testVault(tmp.root)
        vault.setUp("123456".toCharArray())
        key = (vault.unlock("123456".toCharArray()) as Vault.Unlock.Success).key
    }

    @After
    fun tearDown() = db.close()

    private suspend fun item(state: ItemState, inGallery: Boolean = false): Item {
        dao.insertNew(listOf(Item(mediaStoreId = 7, dateModified = 1, sizeBytes = 1)))
        val item = dao.byMediaStoreId(7)!!.copy(
            contentHash = Vault.sha256Hex(original), state = state, boxes = listOf(wrongBox),
            redactedMediaStoreId = 900, recheckPassed = state != ItemState.NEEDS_CHECK,
        )
        dao.update(item)
        vault.store(key, "item-${item.id}", original.inputStream())
        source.present = inGallery
        store.bytes = ByteArrayOutputStream().also { black().compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return item
    }

    private fun corrector() = CorrectRedaction(dao, vault, source, store, recheck, { "item-${it.id}" }) { bytes, maxSide ->
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inMutable = true })
        val bitmap = if (maxOf(full.width, full.height) <= maxSide) full else Bitmap.createScaledBitmap(full, maxSide, maxSide * full.height / full.width, true)
        CorrectRedaction.Decoded(bitmap, full.width, full.height)
    }

    /** The wrong box dragged over the number. */
    private fun movedOverNumber(item: Item) =
        BoxEdits(item.boxes, 200f, 100f).move(0, 120f, 0f).endGesture()

    @Test
    fun aVaultedItemIsRenderedAgainFromTheVaultAndRechecked() = runTest {
        val item = item(ItemState.VAULTED)
        val result = corrector().save(key, item, movedOverNumber(item)) as CorrectRedaction.Result.Saved

        assertTrue(result.item.recheckPassed)
        assertEquals(ItemState.VAULTED, result.item.state)
        val copy = decode(store.bytes!!)
        assertTrue(Color.red(copy.getPixel(150, 50)) < 40) // the new box
        assertTrue(Color.red(copy.getPixel(30, 50)) > 220) // the old box's area shows the original again
        val stored = dao.byId(item.id)!!
        assertEquals(listOf(130f, 30f, 180f, 30f, 180f, 70f, 130f, 70f), stored.boxes.single().corners)
        assertEquals(1, stored.correctedMoves)
        assertEquals(1, stored.corrections)
        assertEquals(1, store.overwrites)
    }

    @Test
    fun aCheckedFixMovesNeedsCheckToRedacted() = runTest {
        val item = item(ItemState.NEEDS_CHECK, inGallery = true)
        val result = corrector().save(null, item, movedOverNumber(item)) as CorrectRedaction.Result.Saved
        assertEquals(ItemState.REDACTED, result.item.state)
        assertTrue(dao.byId(item.id)!!.recheckPassed)
    }

    @Test
    fun aCopyThatFailsTheRecheckIsKeptButNeedsACheck() = runTest {
        // The user deletes the only box: the number shows again.
        val item = item(ItemState.REDACTED, inGallery = true)
        val edits = BoxEdits(item.boxes, 200f, 100f).delete(0)
        val result = corrector().save(null, item, edits) as CorrectRedaction.Result.Saved
        assertFalse(result.item.recheckPassed)
        assertEquals(ItemState.NEEDS_CHECK, result.item.state)
        assertEquals(1, store.overwrites)
        val stored = dao.byId(item.id)!!
        assertEquals(emptyList<RedactionBox>(), stored.boxes)
        assertEquals(Corrections(deletes = 1), Corrections(stored.correctedMoves, stored.correctedResizes, stored.correctedAdds, stored.correctedDeletes))
        assertFalse(stored in dao.readyToSecure())
    }

    @Test
    fun aVaultedItemNeedsTheKey() = runTest {
        val item = item(ItemState.VAULTED)
        val before = store.bytes!!.copyOf()
        assertTrue(corrector().save(null, item, movedOverNumber(item)) is CorrectRedaction.Result.Failed)
        assertNull(corrector().preview(null, item, 64))
        assertArrayEquals(before, store.bytes)
    }

    @Test
    fun anOriginalThatChangedSinceTheScanIsNotUsed() = runTest {
        val item = item(ItemState.REDACTED, inGallery = true).copy(contentHash = "0".repeat(64))
        vault.delete("item-${item.id}")
        assertTrue(corrector().save(key, item, movedOverNumber(item)) is CorrectRedaction.Result.Failed)
        assertEquals(0, store.overwrites)
        assertEquals(ItemState.REDACTED, dao.byId(item.id)!!.state) // nothing stored
    }

    @Test
    fun thePreviewIsScaledDown() = runTest {
        val item = item(ItemState.VAULTED)
        val preview = corrector().preview(key, item, 100)!!
        assertEquals(100, preview.bitmap.width)
        assertEquals(200, preview.width) // the original's size, for box coordinates
    }

    private fun white() = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun black() = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun decode(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    /** Reads a Luhn-valid card number while (150, 50) is light. */
    private class ProbeOcr : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
            if (Color.red(bitmap.getPixel(150 * bitmap.width / 200, 50 * bitmap.height / 100)) <= 128) return emptyList()
            return listOf(OcrLine(0, 0, (0 until 4).map { i -> OcrWord(if (i == 0) "4111" else "1111", 32f * i, 0f, 32f * i + 30f, 10f) }))
        }
    }

    private class FakeStore : RedactedCopyStore {
        var bytes: ByteArray? = null
        var overwrites = 0
        override fun save(originalId: Long, jpeg: ByteArray): Long = error("the copy already exists")
        override fun read(copyId: Long): ByteArray? = bytes
        override fun overwrite(copyId: Long, jpeg: ByteArray) {
            bytes = jpeg
            overwrites++
        }
    }

    private inner class FakeSource : ImageSource {
        var present = false
        override suspend fun listImages() = listOf(GalleryImage(7, 1, original.size.toLong()))
        override fun open(mediaStoreId: Long) = if (present) ByteArrayInputStream(original) else null
    }
}
