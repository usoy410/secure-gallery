package com.sudo.securegallery.secure

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.redact.BoxEdits
import com.sudo.securegallery.redact.KeptExif
import com.sudo.securegallery.redact.Recheck
import com.sudo.securegallery.redact.RedactedCopyStore
import com.sudo.securegallery.redact.RedactedJpeg
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.vault.Vault
import java.nio.ByteBuffer

/**
 * Saving corrected boxes from the review inbox (§8.4, #24). The copy is rendered again from
 * the original, never from the old copy, so a box the user shrank shows what is really there.
 *
 * The original comes from the gallery while it is still there, else from the vault (needs the
 * key); either way its hash must match the scan. The new copy overwrites the app's own file,
 * so no system prompt appears, and the recheck (§8.5) runs again. A copy that fails it is kept,
 * because it is what the user asked for, but it loses `recheckPassed` and its item becomes
 * NEEDS_CHECK where §8.4 allows, so its original is not deleted on its strength.
 *
 * Only the boxes and the correction counts are stored, never what is under a box.
 */
class CorrectRedaction(
    private val dao: ItemDao,
    private val vault: Vault,
    private val source: ImageSource,
    private val store: RedactedCopyStore,
    private val recheck: Recheck?,
    private val vaultId: (Item) -> String,
    /** Decodes upright (EXIF rotation applied) and mutable, at most [maxSide] px per side. */
    private val decode: (bytes: ByteArray, maxSide: Int) -> Decoded? = ::decodeUpright,
) {
    /** A decoded original: [bitmap] may be scaled down from the upright [width] x [height]. */
    class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    sealed interface Result {
        /** Saved; [item] is the stored row, and its `recheckPassed` says whether the copy passed. */
        data class Saved(val item: Item) : Result
        data class Failed(val reason: String) : Result
    }

    /** The original's bytes, checked against the scan's hash, or null if it cannot be read. */
    fun original(key: Vault.Key?, item: Item): ByteArray? {
        val inGallery = if (item.state != ItemState.VAULTED) source.open(item.mediaStoreId)?.use { it.readBytes() } else null
        val bytes = inGallery ?: key?.takeIf { vault.contains(vaultId(item)) }?.let { vault.read(it, vaultId(item)) }
        return bytes?.takeIf { Vault.sha256Hex(it) == item.contentHash }
    }

    /** The redacted copy scaled to at most [maxSide] px per side, for the viewer (#23). */
    fun copyPreview(item: Item, maxSide: Int): Decoded? =
        item.redactedMediaStoreId?.let(store::read)?.let { decode(it, maxSide) }

    /** The original scaled to at most [maxSide] px per side, for the editor. */
    fun preview(key: Vault.Key?, item: Item, maxSide: Int): Decoded? = original(key, item)?.let { decode(it, maxSide) }

    suspend fun save(key: Vault.Key?, item: Item, edits: BoxEdits): Result {
        val copyId = item.redactedMediaStoreId ?: return Result.Failed("no redacted copy")
        val bytes = original(key, item) ?: return Result.Failed("original not available")
        val region = Recheck.region(item.cardCorners)
        val full = decode(bytes, Int.MAX_VALUE)?.bitmap ?: return Result.Failed("cannot decode the original")
        val jpeg: ByteArray
        val windows: Set<String>
        try {
            // The original's numbers for the digit check, read before the fill; memory only.
            windows = recheck?.let { r ->
                val s = minOf(1f, Recheck.OCR_MAX_SIDE.toFloat() / maxOf(full.width, full.height))
                val small = Bitmap.createScaledBitmap(full, maxOf(1, (full.width * s).toInt()), maxOf(1, (full.height * s).toInt()), true)
                try {
                    r.numberWindows(small, region?.let { RectF(it.left * s, it.top * s, it.right * s, it.bottom * s) })
                } finally {
                    if (small !== full) small.recycle()
                }
            }.orEmpty()
            RedactedJpeg.fill(full, edits.boxes)
            jpeg = RedactedJpeg.encode(full, bytes.inputStream().use(KeptExif::read))
        } finally {
            full.recycle()
        }
        store.overwrite(copyId, jpeg)
        val saved = store.read(copyId) ?: return Result.Failed("copy not readable")
        val passed = recheck?.passes(saved, edits.boxes, region, windows) == true

        val state = when {
            passed && item.state == ItemState.NEEDS_CHECK -> ItemState.REDACTED
            !passed && item.state == ItemState.REDACTED -> ItemState.NEEDS_CHECK
            else -> item.state
        }
        val c = edits.done
        val updated = (if (state == item.state) item else item.moveTo(state)).copy(
            boxes = edits.boxes,
            recheckPassed = passed,
            corrections = item.corrections + c.total,
            correctedMoves = item.correctedMoves + c.moves,
            correctedResizes = item.correctedResizes + c.resizes,
            correctedAdds = item.correctedAdds + c.adds,
            correctedDeletes = item.correctedDeletes + c.deletes,
        )
        dao.update(updated)
        return Result.Saved(updated)
    }
}

/** [bytes] decoded with EXIF rotation applied, mutable, scaled down to at most [maxSide] px. */
fun decodeUpright(bytes: ByteArray, maxSide: Int): CorrectRedaction.Decoded? = try {
    var w = 0
    var h = 0
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        w = info.size.width
        h = info.size.height
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
    }
    CorrectRedaction.Decoded(bitmap, w, h)
} catch (_: java.io.IOException) {
    null
}
