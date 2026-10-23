package com.sudo.securegallery.redact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.RedactionBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * The redacted copy's pixels and metadata (issue #18, §8.2 step 3): every box is an opaque fill
 * that survives JPEG q95, and the copy's EXIF holds only the capture date and orientation 1.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedactedJpegTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A tilted box, as the localizer gives for a card photographed at an angle. */
    private val box = RedactionBox(FieldType.PCN, listOf(100f, 80f, 300f, 110f, 290f, 170f, 90f, 140f), 0.9f)

    @Test
    fun everyPixelInsideABoxIsReplacedBySolidFill() {
        val bitmap = stripes(400, 300)
        RedactedJpeg.fill(bitmap, listOf(box))
        val decoded = decode(RedactedJpeg.encode(bitmap, KeptExif(null, null)))

        var inside = 0
        var solid = 0
        var outsideChanged = 0
        for (y in 0 until decoded.height) for (x in 0 until decoded.width) {
            val p = decoded.getPixel(x, y)
            val dark = listOf(Color.red(p), Color.green(p), Color.blue(p)).all { abs(it - 0) <= 8 }
            when (insideBy(x + 0.5f, y + 0.5f, -3f)) {
                true -> { inside++; if (dark) solid++ }
                false -> if (dark && !insideBy(x + 0.5f, y + 0.5f, 6f)) outsideChanged++
            }
        }
        // §8.5 pixel check: at least 99.5% of the box within 8 of the fill on every channel.
        assertTrue("only $solid of $inside pixels solid", solid >= inside * 0.995)
        assertEquals(0, outsideChanged)
    }

    @Test
    fun theCopyKeepsOnlyTheCaptureDate() {
        val original = tmp.newFile("original.jpg")
        stripes(320, 240).compress(Bitmap.CompressFormat.JPEG, 90, original.outputStream())
        ExifInterface(original).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
            setAttribute(ExifInterface.TAG_MAKE, "Synthetic Phone Co")
            setAttribute(ExifInterface.TAG_MODEL, "SP-1")
            setAttribute(ExifInterface.TAG_SOFTWARE, "synthetic 1.0")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(14.5995, 120.9842)
            saveAttributes()
        }
        val kept = original.inputStream().use(KeptExif::read)
        assertEquals(KeptExif("2024:05:06 07:08:09", "+08:00"), kept)

        val copy = RedactedJpeg.encode(stripes(320, 240), kept)
        val exif = ExifInterface(ByteArrayInputStream(copy))
        assertEquals("2024:05:06 07:08:09", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+08:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        assertNull(exif.latLong)
        for (tag in listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_MAKER_NOTE)) {
            assertNull(tag, exif.getAttribute(tag))
        }
        assertFalse(exif.hasThumbnail())
        assertEquals(320, decode(copy).width) // still a valid JPEG
    }

    @Test
    fun anOriginalWithoutExifGivesACopyWithoutADate() {
        val plain = java.io.ByteArrayOutputStream().also { stripes(64, 48).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val kept = KeptExif.read(ByteArrayInputStream(plain.toByteArray()))
        assertEquals(KeptExif(null, null), kept)
        val exif = ExifInterface(ByteArrayInputStream(RedactedJpeg.encode(stripes(64, 48), kept)))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyRealDatesAreWritten() {
        KeptExif("JUAN DELA CRUZ", null)
    }

    /** Light and dark stripes, so a missed pixel would show as light. */
    private fun stripes(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if ((x / 4) % 2 == 0) Color.WHITE else Color.rgb(200, 200, 200))
    }

    private fun decode(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    /** True if (x, y) is inside the box polygon grown (or shrunk, if negative) by [margin] px. */
    private fun insideBy(x: Float, y: Float, margin: Float): Boolean {
        val c = box.corners
        for (i in 0 until 4) {
            val ax = c[2 * i]; val ay = c[2 * i + 1]
            val bx = c[(2 * i + 2) % 8]; val by = c[(2 * i + 3) % 8]
            val len = kotlin.math.hypot(bx - ax, by - ay)
            // Clockwise corners in image space: the inside is on the right of each edge.
            val cross = ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / len
            if (cross < -margin) return false
        }
        return true
    }
}
