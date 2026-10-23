package com.sudo.securegallery.redact

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.sudo.securegallery.data.RedactionBox
import java.io.ByteArrayOutputStream

/**
 * Draws the redaction and encodes the copy (§8.2 step 3): an opaque solid fill on every box,
 * flattened into the pixels and saved as JPEG quality 95. The pixels underneath are replaced,
 * not covered by a layer.
 */
object RedactedJpeg {
    const val QUALITY = 95
    const val FILL = Color.BLACK

    private val paint = Paint().apply {
        color = FILL
        // Fill plus a 2 px outline, without anti-aliasing, so edge pixels are fully covered.
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        isAntiAlias = false
    }

    /** Fills every box on [bitmap] (mutable, in original-image pixels). */
    fun fill(bitmap: Bitmap, boxes: List<RedactionBox>) {
        val canvas = Canvas(bitmap)
        for (box in boxes) {
            val c = box.corners
            val path = Path().apply {
                moveTo(c[0], c[1])
                for (i in 2 until c.size step 2) lineTo(c[i], c[i + 1])
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    /** JPEG bytes with [exif] as the only metadata. */
    fun encode(bitmap: Bitmap, exif: KeptExif): ByteArray {
        val jpeg = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) }.toByteArray()
        check(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "encoder did not write a JPEG" }
        // The encoder writes no EXIF; the kept fields go right after the start-of-image marker.
        return jpeg.copyOfRange(0, 2) + exif.app1Segment() + jpeg.copyOfRange(2, jpeg.size)
    }
}
