package com.sudo.securegallery.scan

import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.detect.DetectorConfig
import com.sudo.securegallery.detect.DocumentDetector
import com.sudo.securegallery.detect.FieldLocalizer
import java.io.InputStream
import java.security.MessageDigest

/**
 * The first full scan (§8.2): index every gallery image, then process them one at a time.
 *
 * Processing hashes the file (streamed, never decoded) and then, when a model is bundled, runs
 * the detector on a copy decoded at detection size (#16). An image with no detection at or
 * above the threshold becomes NOT_DETECTED. A detected image runs hybrid field localization (#17)
 * to locate sensitive fields and transitions to REDACTED or NEEDS_CHECK; if no localizer is
 * configured, it stays PENDING with its class, confidence, and corners.
 */
class GalleryScanner(
    private val source: ImageSource,
    private val dao: ItemDao,
    private val detector: DocumentDetector? = null,
    private val detectorConfig: DetectorConfig = DetectorConfig(),
    private val fieldLocalizer: FieldLocalizer? = null,
) {
    data class Result(val found: Int, val processed: Int, val missing: Int)

    suspend fun scan(): Result {
        val images = source.listImages()
        index(images)
        var processed = 0
        var missing = 0
        while (true) {
            val item = dao.nextUnhashed() ?: break
            val hash = source.open(item.mediaStoreId)?.use(::sha256)
            if (hash == null) {
                // Deleted or unreadable since the query: nothing to protect, stop retrying it.
                dao.update(item.moveTo(ItemState.FAILED))
                missing++
            } else {
                val detected = detect(item.copy(contentHash = hash))
                val localized = if (detected.state == ItemState.PENDING && detected.documentClass != null && fieldLocalizer != null) {
                    localize(detected)
                } else {
                    detected
                }
                dao.update(localized)
                processed++
            }
        }
        return Result(found = images.size, processed = processed, missing = missing)
    }

    private suspend fun localize(item: Item): Item {
        val localizer = fieldLocalizer ?: return item
        val bitmap = source.loadForOcr(item.mediaStoreId) ?: return item
        val result = try {
            localizer.localize(item, bitmap)
        } finally {
            bitmap.recycle()
        }
        return item.copy(
            boxes = result.boxes,
            state = result.state,
        )
    }

    private fun detect(item: Item): Item {
        val detector = detector ?: return item
        val image = source.loadForDetection(item.mediaStoreId, detectorConfig.inputSize) ?: return item
        val best = detector.detect(image).maxByOrNull { it.confidence }
            ?: return item.moveTo(ItemState.NOT_DETECTED).copy(documentClass = null, documentConfidence = null, cardCorners = null)
        return item.copy(
            documentClass = best.documentClass,
            documentConfidence = best.confidence,
            cardCorners = CardCorners(best.corners.flatMap { listOf(it.x, it.y) }),
        )
    }

    /** Adds new images; a changed file that was not flagged is indexed again. */
    private suspend fun index(images: List<GalleryImage>) {
        dao.insertNew(
            images.map { Item(mediaStoreId = it.mediaStoreId, dateModified = it.dateModified, sizeBytes = it.sizeBytes) },
        )
        for (image in images) {
            val item = dao.byMediaStoreId(image.mediaStoreId) ?: continue
            val changed = item.dateModified != image.dateModified || item.sizeBytes != image.sizeBytes
            if (changed && item.state.canMoveTo(ItemState.PENDING)) {
                dao.update(
                    item.moveTo(ItemState.PENDING).copy(
                        dateModified = image.dateModified,
                        sizeBytes = image.sizeBytes,
                        contentHash = null,
                    ),
                )
            }
        }
    }

    companion object {
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
