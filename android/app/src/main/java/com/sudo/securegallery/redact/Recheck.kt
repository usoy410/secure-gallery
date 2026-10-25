package com.sudo.securegallery.redact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.OcrEngine
import kotlin.math.abs

/**
 * The on-device recheck of a saved redacted copy (§8.5, #19). Both checks must pass:
 *
 * 1. Pixels: inside every box, at least [MIN_SOLID_SHARE] of the pixels are within
 *    [TOLERANCE] of the fill on every channel, read back from the saved JPEG. A [EDGE_PX] rim
 *    is left out, as in the desktop leak test, because JPEG rings next to a black edge.
 * 2. Text: OCR over the document region finds no field rule match, and no run of 4 digits
 *    from the original's ID or card numbers.
 *
 * The original's numbers are read into memory for one item and dropped; they are never
 * stored, logged or put in an exception.
 */
open class Recheck(private val rules: FieldRules, private val ocr: OcrEngine) {

    /**
     * Every 4-digit window of the numbers the rules find on [original], inside [region] (in
     * [original]'s pixels; the whole image if null). Memory only.
     */
    open suspend fun numberWindows(original: Bitmap, region: RectF? = null): Set<String> {
        val area = crop(original, region)
        try {
            return rules.numberValues(ocr.recognize(area)).flatMap { digitWindows(it) }.toSet()
        } finally {
            if (area !== original) area.recycle()
        }
    }

    /**
     * True if the saved [jpeg] passes both checks. [region] is the document area in
     * original-image pixels (the whole image if null); [windows] comes from [numberWindows].
     */
    open suspend fun passes(jpeg: ByteArray, boxes: List<RedactionBox>, region: RectF?, windows: Set<String>): Boolean {
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        val (width, height) = full.width to full.height
        val solid = try {
            boxes.all { solidShare(full, it) >= MIN_SOLID_SHARE }
        } finally {
            full.recycle()
        }
        return solid && textGone(jpeg, width, height, region, windows)
    }

    private suspend fun textGone(jpeg: ByteArray, width: Int, height: Int, region: RectF?, windows: Set<String>): Boolean {
        val sample = Integer.highestOneBit(maxOf(1, maxOf(width, height) / OCR_MAX_SIDE))
        val small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scale = small.width.toFloat() / width
        val area = crop(small, region?.let { RectF(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) })
        try {
            val lines = ocr.recognize(area)
            if (rules.match(lines).isNotEmpty()) return false
            val seen = lines.flatMap { line -> digitWindows(line.words.joinToString(" ") { it.text }) }
            return seen.none { it in windows }
        } finally {
            if (area !== small) area.recycle()
            small.recycle()
        }
    }

    companion object {
        const val MIN_SOLID_SHARE = 0.995
        const val TOLERANCE = 8
        const val EDGE_PX = 3f
        const val OCR_MAX_SIDE = 2048
        const val GROW_PER_SIDE = 0.15f
        private val DIGIT_RUN = Regex("[0-9]{4,}")

        /** [image] cut to [region] (clamped to the image), or [image] itself if null. */
        private fun crop(image: Bitmap, region: RectF?): Bitmap {
            if (region == null) return image
            val left = region.left.toInt().coerceIn(0, image.width - 1)
            val top = region.top.toInt().coerceIn(0, image.height - 1)
            val right = region.right.toInt().coerceIn(left + 1, image.width)
            val bottom = region.bottom.toInt().coerceIn(top + 1, image.height)
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }

        /** Share of the box's pixels (minus the rim) within [TOLERANCE] of black. */
        fun solidShare(image: Bitmap, box: RedactionBox): Double {
            val c = box.corners
            val xs = (0 until 4).map { c[2 * it] }
            val ys = (0 until 4).map { c[2 * it + 1] }
            var inside = 0
            var solid = 0
            val left = xs.min().toInt().coerceAtLeast(0)
            val right = xs.max().toInt().coerceAtMost(image.width - 1)
            val top = ys.min().toInt().coerceAtLeast(0)
            val bottom = ys.max().toInt().coerceAtMost(image.height - 1)
            for (y in top..bottom) for (x in left..right) {
                if (!insideBy(xs, ys, x + 0.5f, y + 0.5f, EDGE_PX)) continue
                inside++
                val p = image.getPixel(x, y)
                if (abs(Color.red(p)) <= TOLERANCE && abs(Color.green(p)) <= TOLERANCE && abs(Color.blue(p)) <= TOLERANCE) solid++
            }
            // A box too thin to have pixels past the rim cannot hide anything readable.
            return if (inside == 0) 1.0 else solid.toDouble() / inside
        }

        /** True if (x, y) lies inside the quadrilateral, at least [margin] px from every edge. */
        private fun insideBy(xs: List<Float>, ys: List<Float>, x: Float, y: Float, margin: Float): Boolean {
            // Signed area tells the winding, so the test works for either corner order.
            val area = (0 until 4).sumOf { i -> (xs[i] * ys[(i + 1) % 4] - xs[(i + 1) % 4] * ys[i]).toDouble() }
            val sign = if (area >= 0) 1f else -1f
            for (i in 0 until 4) {
                val ax = xs[i]; val ay = ys[i]
                val bx = xs[(i + 1) % 4]; val by = ys[(i + 1) % 4]
                val len = kotlin.math.hypot(bx - ax, by - ay)
                if (len == 0f) return false
                val cross = sign * ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / len
                if (cross < margin) return false
            }
            return true
        }

        /** The box grown by [fraction] of its width and height on every side (§8.2 step 4). */
        fun grow(box: RedactionBox, fraction: Float = GROW_PER_SIDE): RedactionBox {
            val c = box.corners
            val cx = (0 until 4).map { c[2 * it] }.average().toFloat()
            val cy = (0 until 4).map { c[2 * it + 1] }.average().toFloat()
            val factor = 1f + 2f * fraction
            return box.copy(corners = c.mapIndexed { i, v -> if (i % 2 == 0) cx + (v - cx) * factor else cy + (v - cy) * factor })
        }

        /** Every 4-digit window of every run of 4 or more digits, with spaces and dashes removed. */
        fun digitWindows(text: String): Set<String> =
            DIGIT_RUN.findAll(text.replace(" ", "").replace("-", "")).flatMap { m ->
                (0..m.value.length - 4).map { m.value.substring(it, it + 4) }
            }.toSet()
    }
}
