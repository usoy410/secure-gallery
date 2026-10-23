package com.sudo.securegallery.detect

import android.graphics.Bitmap
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import kotlin.math.ceil

/**
 * Result of field localization on a detected document (§8.2, §8.6).
 *
 * @property boxes Redaction boxes to cover sensitive fields in original image pixels.
 * @property state Determined state: [ItemState.REDACTED] or [ItemState.NEEDS_CHECK].
 * @property fallback True if the whole-document fallback box was required.
 * @property fieldRulesMatched True if at least one sensitive text field rule matched via OCR.
 */
data class LocalizationResult(
    val boxes: List<RedactionBox>,
    val state: ItemState,
    val fallback: Boolean,
    val fieldRulesMatched: Boolean,
)

/**
 * Contract for locating sensitive field boxes on detected documents (§8.2).
 */
fun interface FieldLocalizer {
    /**
     * Localizes redaction boxes on [item] using its detection metadata and bitmap.
     *
     * @param item Detected image item with class, confidence, and corner coordinates.
     * @param bitmap Decoded bitmap of the image (or downsampled for OCR).
     * @return [LocalizationResult] with calculated boxes and target pipeline state.
     */
    suspend fun localize(item: Item, bitmap: Bitmap): LocalizationResult
}

/**
 * Hybrid field localizer combining layout homography and OCR refinement (§5.1, §8.2).
 *
 * Implements Contribution 1: layout-first mapping covers template fields even when
 * degraded text cannot be read. OCR is then run on mapped text regions only to enlarge
 * boxes when printed values drift (never shrink them).
 *
 * @property templates Available card templates loaded from templates directory.
 * @property rules Shared field rules loaded from `templates/rules/fields.json`.
 * @property ocrEngine On-device OCR engine (ML Kit bundled Latin model or test fake).
 */
class HybridFieldLocalizer(
    private val templates: List<CardTemplate>,
    private val rules: FieldRules,
    private val ocrEngine: OcrEngine? = null,
) : FieldLocalizer {

    override suspend fun localize(item: Item, bitmap: Bitmap): LocalizationResult {
        val docClass = item.documentClass ?: return fallbackResult(bitmap)
        val corners = item.cardCorners?.xy?.let { raw ->
            if (raw.size == 8) {
                listOf(
                    Corner(raw[0], raw[1]),
                    Corner(raw[2], raw[3]),
                    Corner(raw[4], raw[5]),
                    Corner(raw[6], raw[7]),
                )
            } else null
        }

        val completedCorners = Homography.completeCorners(corners)

        // If no usable corners are found, fallback to OCR-first rules over the whole document.
        if (completedCorners == null) {
            return ocrFirstFallback(item, bitmap)
        }

        val matrix = Homography.createHomographyMatrix(completedCorners)
            ?: return ocrFirstFallback(item, bitmap)

        val template = selectTemplate(docClass, bitmap, completedCorners)
            ?: return ocrFirstFallback(item, bitmap)

        val boxes = mutableListOf<RedactionBox>()
        var matchedRuleCount = 0
        val docConf = item.documentConfidence ?: 1.0f

        for (field in template.sensitiveFieldsWithPadding()) {
            val mapped = Homography.mapPolygon(matrix, field.polygon)
            if (mapped.size != 8) continue

            if (field.type in CardTemplate.PICTURE_FIELDS || ocrEngine == null) {
                // Picture fields (face, QR, signature) are anchored by layout only (#54).
                boxes.add(RedactionBox(field.type, mapped, docConf))
            } else {
                // Text field: refine with OCR on the mapped region.
                val refined = refineTextRegion(bitmap, field.type, mapped, docConf)
                if (refined.matched) {
                    matchedRuleCount++
                }
                boxes.add(refined.box)
            }
        }

        val fieldRulesMatched = matchedRuleCount > 0
        val isHighConfidence = (item.documentConfidence ?: 0f) >= HIGH_CONFIDENCE_THRESHOLD && fieldRulesMatched
        val state = if (isHighConfidence) ItemState.REDACTED else ItemState.NEEDS_CHECK

        return LocalizationResult(
            boxes = boxes,
            state = state,
            fallback = false,
            fieldRulesMatched = fieldRulesMatched,
        )
    }

    private data class RefinedBox(val box: RedactionBox, val matched: Boolean)

    private suspend fun refineTextRegion(
        bitmap: Bitmap,
        fieldType: FieldType,
        mapped: List<Float>,
        confidence: Float,
    ): RefinedBox {
        val tMinX = minOf(mapped[0], mapped[2], mapped[4], mapped[6])
        val tMinY = minOf(mapped[1], mapped[3], mapped[5], mapped[7])
        val tMaxX = maxOf(mapped[0], mapped[2], mapped[4], mapped[6])
        val tMaxY = maxOf(mapped[1], mapped[3], mapped[5], mapped[7])

        // Add small margin around region for cropping to catch slight font drift.
        val cropLeft = maxOf(0, (tMinX - REGION_PAD).toInt())
        val cropTop = maxOf(0, (tMinY - REGION_PAD).toInt())
        val cropRight = minOf(bitmap.width, ceil(tMaxX + REGION_PAD).toInt())
        val cropBottom = minOf(bitmap.height, ceil(tMaxY + REGION_PAD).toInt())
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop

        if (cropWidth <= 0 || cropHeight <= 0 || ocrEngine == null) {
            return RefinedBox(RedactionBox(fieldType, mapped, confidence), false)
        }

        val cropBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        val ocrMatches = try {
            val lines = ocrEngine.recognize(cropBitmap)
            rules.match(lines)
        } finally {
            cropBitmap.recycle()
        }

        // Check if any rule matching this field type fired.
        val relevantMatch = ocrMatches.firstOrNull { it.fieldType == fieldType }
            ?: ocrMatches.firstOrNull()

        if (relevantMatch == null) {
            // No OCR rule matched: preserve template box (never shrunk).
            return RefinedBox(RedactionBox(fieldType, mapped, confidence), false)
        }

        // Map OCR bounding box from crop coordinates to original image coordinates.
        val ocrLeft = relevantMatch.left + cropLeft
        val ocrTop = relevantMatch.top + cropTop
        val ocrRight = relevantMatch.right + cropLeft
        val ocrBottom = relevantMatch.bottom + cropTop

        // Union: template region unioned with matched OCR box; never shrinks.
        val uMinX = minOf(tMinX, ocrLeft)
        val uMinY = minOf(tMinY, ocrTop)
        val uMaxX = maxOf(tMaxX, ocrRight)
        val uMaxY = maxOf(tMaxY, ocrBottom)

        val finalCorners = if (ocrLeft >= tMinX && ocrRight <= tMaxX && ocrTop >= tMinY && ocrBottom <= tMaxY) {
            mapped // Fully contained in template polygon
        } else {
            listOf(uMinX, uMinY, uMaxX, uMinY, uMaxX, uMaxY, uMinX, uMaxY)
        }

        return RefinedBox(RedactionBox(fieldType, finalCorners, confidence), true)
    }

    private suspend fun ocrFirstFallback(item: Item, bitmap: Bitmap): LocalizationResult {
        val ocrMatches = if (ocrEngine != null) {
            val lines = ocrEngine.recognize(bitmap)
            rules.match(lines)
        } else {
            emptyList()
        }

        if (ocrMatches.isNotEmpty()) {
            val boxes = ocrMatches.map { match ->
                RedactionBox(match.fieldType, match.toCorners(), item.documentConfidence ?: 1f)
            }
            return LocalizationResult(
                boxes = boxes,
                state = ItemState.NEEDS_CHECK,
                fallback = false,
                fieldRulesMatched = true,
            )
        }

        return fallbackResult(bitmap)
    }

    private fun fallbackResult(bitmap: Bitmap): LocalizationResult {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val wholeDocBox = RedactionBox(
            fieldType = FieldType.WHOLE_DOCUMENT,
            corners = listOf(0f, 0f, w, 0f, w, h, 0f, h),
            confidence = 1.0f,
        )
        return LocalizationResult(
            boxes = listOf(wholeDocBox),
            state = ItemState.NEEDS_CHECK,
            fallback = true,
            fieldRulesMatched = false,
        )
    }

    /**
     * Resolves the best template variant for a document class (§8.1, §8.2).
     */
    fun selectTemplate(
        docClass: DocumentClass,
        bitmap: Bitmap? = null,
        corners: List<Corner>? = null,
    ): CardTemplate? {
        val candidates = templates.filter { it.documentClass == docClass }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        // Disambiguate variants based on class.
        return when (docClass) {
            DocumentClass.PHILSYS -> {
                // If template specifies ePhilID or card, default to polycarbonate card.
                candidates.firstOrNull { it.id == "philsys-card" } ?: candidates.first()
            }
            DocumentClass.LTO -> {
                candidates.firstOrNull { it.id == "lto-dl-current" } ?: candidates.first()
            }
            DocumentClass.PAYMENT_CARD -> {
                candidates.firstOrNull { it.id == "payment-card-front" } ?: candidates.first()
            }
        }
    }

    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.6f
        const val REGION_PAD = 4f
    }
}
