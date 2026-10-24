package com.sudo.securegallery.redact

import android.graphics.BitmapFactory
import android.graphics.RectF
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.scan.ImageSource

/**
 * Makes the redacted copy of one localized item (§8.2 steps 3 to 5, #18, #19): decode the
 * original at full resolution, fill every box, encode JPEG q95 with only the kept EXIF, and save
 * it next to the original. One full-size bitmap exists at a time, and it is recycled before the
 * next one is decoded.
 *
 * With a [recheck], the saved file is read back and checked (§8.5). On failure every box grows by
 * 15% per side and the copy is overwritten, up to [MAX_RECHECK_RETRIES] times; then the whole
 * image is filled and the item becomes NEEDS_CHECK. Only a copy that passed is marked
 * `recheckPassed`, and only such items may lose their original later (#22).
 *
 * The original is only read. If a step fails the item keeps no copy and is tried again on the
 * next scan; after [MAX_ATTEMPTS] failures it becomes FAILED, with the original untouched.
 */
class Redactor(
    private val source: ImageSource,
    private val store: RedactedCopyStore,
    private val recheck: Recheck? = null,
) {

    suspend fun redact(item: Item): Item {
        try {
            val exif = source.open(item.mediaStoreId)?.use(KeptExif::read) ?: return gone(item)
            // The document region in original-image pixels, from the detector's corners.
            val region = Recheck.region(item.cardCorners)
            // The original's numbers, for the digit check; memory only, dropped with this call.
            val windows = recheck?.let { r ->
                val image = source.loadForOcr(item.mediaStoreId, Recheck.OCR_MAX_SIDE) ?: return failedAttempt(item)
                try {
                    val s = image.bitmap.width.toFloat() / image.originalWidth
                    r.numberWindows(image.bitmap, region?.let { RectF(it.left * s, it.top * s, it.right * s, it.bottom * s) })
                } finally {
                    image.bitmap.recycle()
                }
            }.orEmpty()
            var boxes = item.boxes
            val first = render(item, boxes, exif) ?: return failedAttempt(item)
            val copyId = store.save(item.mediaStoreId, first) ?: return gone(item)
            if (recheck == null) return item.copy(redactedMediaStoreId = copyId, retryCount = 0)

            var retries = 0
            while (true) {
                val saved = store.read(copyId) ?: return failedAttempt(item)
                val passed = recheck.passes(saved, boxes, region, windows)
                val done = item.copy(redactedMediaStoreId = copyId, boxes = boxes, retryCount = retries, recheckPassed = passed)
                if (passed) return if (retries > MAX_RECHECK_RETRIES) done.moveToCheck() else done
                if (retries > MAX_RECHECK_RETRIES) return done.moveToCheck() // whole image filled and still failing
                retries++
                boxes = if (retries <= MAX_RECHECK_RETRIES) {
                    boxes.map { Recheck.grow(it) }
                } else {
                    val (w, h) = imageSize(saved)
                    listOf(RedactionBox(FieldType.WHOLE_DOCUMENT, listOf(0f, 0f, w, 0f, w, h, 0f, h), 1f))
                }
                store.overwrite(copyId, render(item, boxes, exif) ?: return failedAttempt(item))
            }
        } catch (_: RuntimeException) {
            return failedAttempt(item)
        } catch (_: OutOfMemoryError) {
            return failedAttempt(item)
        }
    }

    /** The original with [boxes] filled, as JPEG bytes; null if it cannot be decoded. */
    private fun render(item: Item, boxes: List<RedactionBox>, exif: KeptExif): ByteArray? {
        val bitmap = source.loadFullResolution(item.mediaStoreId) ?: return null
        try {
            RedactedJpeg.fill(bitmap, boxes)
            return RedactedJpeg.encode(bitmap, exif)
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageSize(jpeg: ByteArray): Pair<Float, Float> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        return options.outWidth.toFloat() to options.outHeight.toFloat()
    }

    private fun Item.moveToCheck() = if (state == ItemState.NEEDS_CHECK) this else moveTo(ItemState.NEEDS_CHECK)

    /** The original was deleted since the scan: there is nothing left to protect. */
    private fun gone(item: Item) = item.moveTo(ItemState.FAILED)

    private fun failedAttempt(item: Item): Item {
        val attempts = item.retryCount + 1
        return if (attempts >= MAX_ATTEMPTS) item.moveTo(ItemState.FAILED).copy(retryCount = attempts) else item.copy(retryCount = attempts)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_RECHECK_RETRIES = 2
    }
}
