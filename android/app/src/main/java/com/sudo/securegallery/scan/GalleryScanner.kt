package com.sudo.securegallery.scan

import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.detect.DetectorConfig
import com.sudo.securegallery.detect.Corner
import com.sudo.securegallery.detect.DocumentDetector
import com.sudo.securegallery.localize.FieldLocalizer
import com.sudo.securegallery.localize.HybridLocalizer
import com.sudo.securegallery.redact.Redactor
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The first full scan (§8.2): index every gallery image, then process them one at a time.
 *
 * Processing hashes the file (streamed, never decoded) and then, when a model is bundled, runs
 * the detector on a copy decoded at detection size (#16). An image with no detection at or
 * above the threshold becomes NOT_DETECTED; a detected one keeps its class, confidence and
 * corners. Field localization (#17) then decodes it at OCR size, stores the boxes to redact
 * (never the text read) and sets REDACTED or NEEDS_CHECK by the §8.2 confidence level. Last,
 * the redactor saves each localized item's redacted copy next to the original (#18).
 */
class GalleryScanner(
    private val source: ImageSource,
    private val dao: ItemDao,
    private val detector: DocumentDetector? = null,
    private val detectorConfig: DetectorConfig = DetectorConfig(),
    private val localizer: FieldLocalizer? = null,
    private val redactor: Redactor? = null,
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
                dao.update(item.copy(contentHash = hash))
                processed++
            }
        }
        // Detection is a separate pass over every hashed item still waiting for it, so items
        // hashed by an earlier build (or one without a model) are picked up too.
        if (detector != null) {
            for (item in dao.awaitingDetection()) dao.update(detect(detector, item))
        }
        if (localizer != null) {
            for (item in dao.awaitingLocalization()) dao.update(localize(localizer, item))
        }
        if (redactor != null) {
            for (item in dao.awaitingRedaction()) dao.update(redactor.redact(item))
        }
        return Result(found = images.size, processed = processed, missing = missing)
    }

    /**
     * Runs the detector on one item. If the image cannot be decoded or the detector fails, the
     * item is left as it was and tried again on the next scan; one bad image never blocks the rest.
     */
    private fun detect(detector: DocumentDetector, item: Item): Item {
        val found = try {
            val image = source.loadForDetection(item.mediaStoreId, detectorConfig.inputSize) ?: return item
            detector.detect(image)
        } catch (_: RuntimeException) {
            return item
        }
        val best = found.maxByOrNull { it.confidence } ?: return item.moveTo(ItemState.NOT_DETECTED)
        return item.copy(
            documentClass = best.documentClass,
            documentConfidence = best.confidence,
            cardCorners = CardCorners(best.corners.flatMap { listOf(it.x, it.y) }),
        )
    }

    /**
     * Finds the boxes to redact on one detected item. Like detection, a failure leaves the item
     * as it was for the next scan. The OCR bitmap and everything read from it are dropped here.
     */
    private suspend fun localize(localizer: FieldLocalizer, item: Item): Item {
        val documentClass = item.documentClass ?: return item
        val found = try {
            val image = source.loadForOcr(item.mediaStoreId, HybridLocalizer.OCR_MAX_SIDE) ?: return item
            try {
                val corners = item.cardCorners?.xy?.chunked(2)?.map { Corner(it[0], it[1]) }
                localizer.localize(image, documentClass, item.documentConfidence ?: 0f, corners)
            } finally {
                image.bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            return item
        }
        return item.moveTo(found.state).copy(boxes = found.boxes)
    }

    /** Adds new images; a changed file that was not flagged is indexed again. */
    private suspend fun index(images: List<GalleryImage>) {
        dao.insertNew(
            images.map { Item(mediaStoreId = it.mediaStoreId, dateModified = it.dateModified, sizeBytes = it.sizeBytes) },
        )
        for (image in images) {
            val item = dao.byMediaStoreId(image.mediaStoreId) ?: continue
            val changed = item.dateModified != image.dateModified || item.sizeBytes != image.sizeBytes
            // Still PENDING (not yet past detection) or allowed back to PENDING: index again.
            val resettable = item.state == ItemState.PENDING || item.state.canMoveTo(ItemState.PENDING)
            if (changed && resettable) {
                dao.update(
                    item.copy(
                        state = ItemState.PENDING,
                        dateModified = image.dateModified,
                        sizeBytes = image.sizeBytes,
                        contentHash = null,
                        // New content: the old detection no longer applies.
                        documentClass = null,
                        documentConfidence = null,
                        cardCorners = null,
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
