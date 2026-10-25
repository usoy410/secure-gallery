package com.sudo.securegallery.redact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.OcrEngine
import com.sudo.securegallery.localize.OcrImage
import com.sudo.securegallery.localize.OcrLine
import com.sudo.securegallery.localize.OcrWord
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.ImageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The on-device recheck and its retries (issue #19, §8.5, §8.2 step 4). A fake OCR engine
 * "reads" a card number whenever a probe spot of the image is still light, so a box that misses
 * the number fails the text check until it grows over it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecheckTest {
    private val rules = FieldRules.load(ApplicationProvider.getApplicationContext<Context>().assets)
    private val box = RedactionBox(FieldType.PCN, listOf(40f, 40f, 120f, 40f, 120f, 70f, 40f, 70f), 0.9f)

    @Test
    fun aSolidFillPassesThePixelCheckAndAHalfFillDoesNot() {
        val full = render(listOf(box))
        assertTrue(Recheck.solidShare(full, box) >= Recheck.MIN_SOLID_SHARE)
        val half = render(listOf(box.copy(corners = listOf(40f, 40f, 80f, 40f, 80f, 70f, 40f, 70f))))
        assertTrue(Recheck.solidShare(half, box) < 0.6)
    }

    @Test
    fun growAddsFifteenPercentPerSide() {
        assertEquals(listOf(28f, 35.5f, 132f, 35.5f, 132f, 74.5f, 28f, 74.5f), Recheck.grow(box).corners)
    }

    @Test
    fun digitWindowsIgnoreSpacesAndDashes() {
        assertEquals(setOf("1234", "2345"), Recheck.digitWindows("12 34-5 and 99"))
        assertEquals(emptySet<String>(), Recheck.digitWindows("no digits"))
    }

    @Test
    fun aCopyThatPassesIsMarkedAndKept() = runTest {
        val source = FakeSource()
        val done = Redactor(source, FakeStore(), Recheck(rules, ProbeOcr(80, 55))).redact(item())
        assertTrue(done.recheckPassed)
        assertEquals(0, done.retryCount)
        assertEquals(ItemState.REDACTED, done.state)
        assertEquals(listOf(box), done.boxes)
    }

    @Test
    fun anUndersizedBoxGrowsUntilTheTextIsGone() = runTest {
        // The number reaches past the box's right edge, inside the box grown once.
        val store = FakeStore()
        val done = Redactor(FakeSource(), store, Recheck(rules, ProbeOcr(130, 55))).redact(item())
        assertTrue(done.recheckPassed)
        assertEquals(1, done.retryCount)
        assertEquals(ItemState.REDACTED, done.state)
        assertEquals(listOf(Recheck.grow(box)), done.boxes)
        assertEquals(2, store.writes) // saved, then overwritten once
        assertTrue(isDark(store.bytes!!, 130, 55))
    }

    @Test
    fun afterTwoRetriesTheWholeImageIsFilledAndNeedsACheck() = runTest {
        // Far from the box: two growths do not reach it.
        val store = FakeStore()
        val done = Redactor(FakeSource(), store, Recheck(rules, ProbeOcr(195, 5))).redact(item())
        assertEquals(3, done.retryCount)
        assertEquals(ItemState.NEEDS_CHECK, done.state)
        assertEquals(FieldType.WHOLE_DOCUMENT, done.boxes.single().fieldType)
        assertTrue(done.recheckPassed)
        assertEquals(4, store.writes)
        assertTrue(isDark(store.bytes!!, 195, 5))
    }

    @Test
    fun fourDigitsOfTheOriginalNumberComingBackFailTheTextCheck() = runTest {
        // No rule matches "REF 5678 X", but 5678 is part of the original's card number.
        val ocr = OcrEngine { bitmap ->
            if (bitmap.getPixel(80, 55) == Color.WHITE) {
                listOf(line("1234-5678-9012-3456"))
            } else {
                listOf(line("REF 5678 X"))
            }
        }
        val recheck = Recheck(rules, ocr)
        val windows = recheck.numberWindows(white())
        assertTrue("5678" in windows)
        val copy = encode(render(listOf(box)))
        assertFalse(recheck.passes(copy, listOf(box), null, windows))
        assertTrue(recheck.passes(copy, listOf(box), null, emptySet()))
    }

    @Test
    fun withoutARecheckNothingIsMarkedAsPassed() = runTest {
        val done = Redactor(FakeSource(), FakeStore()).redact(item())
        assertFalse(done.recheckPassed)
    }

    private fun item() = Item(id = 1, mediaStoreId = 7, dateModified = 1, sizeBytes = 3, contentHash = "h", state = ItemState.REDACTED, boxes = listOf(box))

    private fun white() = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun render(boxes: List<RedactionBox>) = white().also { RedactedJpeg.fill(it, boxes) }.let { decode(encode(it)) }

    private fun encode(bitmap: Bitmap) = RedactedJpeg.encode(bitmap, KeptExif(null, null))

    private fun decode(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    private fun isDark(jpeg: ByteArray, x: Int, y: Int) = Color.red(decode(jpeg).getPixel(x, y)) < 40

    private fun line(text: String) = OcrLine(0, 0, text.split(" ").mapIndexed { i, w -> OcrWord(w, 10f + 60f * i, 10f, 60f + 60f * i, 30f) })

    /** Reads a Luhn-valid card number while the probe spot is light, nothing once it is dark. */
    private class ProbeOcr(private val x: Int, private val y: Int) : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
            val light = Color.red(bitmap.getPixel(x, y)) > 128
            return if (light) {
                listOf(OcrLine(0, 0, listOf(OcrWord("4111", 0f, 0f, 30f, 10f), OcrWord("1111", 32f, 0f, 62f, 10f), OcrWord("1111", 64f, 0f, 94f, 10f), OcrWord("1111", 96f, 0f, 126f, 10f))))
            } else {
                emptyList()
            }
        }
    }

    private class FakeStore : RedactedCopyStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun save(originalId: Long, jpeg: ByteArray): Long = 500L.also { bytes = jpeg; writes++ }
        override fun read(copyId: Long): ByteArray? = bytes
        override fun overwrite(copyId: Long, jpeg: ByteArray) {
            bytes = jpeg
            writes++
        }
    }

    private inner class FakeSource : ImageSource {
        private val original = ByteArrayOutputStream().also { white().compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

        override suspend fun listImages() = listOf(GalleryImage(7, 1, original.size.toLong()))
        override fun open(mediaStoreId: Long): InputStream = ByteArrayInputStream(original)
        override fun loadForOcr(mediaStoreId: Long, maxSide: Int) = OcrImage(white(), 200, 100)
        override fun loadFullResolution(mediaStoreId: Long): Bitmap = white()
    }
}
