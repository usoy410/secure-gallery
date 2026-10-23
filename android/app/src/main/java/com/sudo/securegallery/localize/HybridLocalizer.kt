package com.sudo.securegallery.localize

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.detect.Corner
import kotlin.math.hypot
import kotlin.math.roundToInt

/** An image decoded for OCR (long side at most [HybridLocalizer.OCR_MAX_SIDE]) and its full size. */
class OcrImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

/**
 * Where to redact on one detected document (§8.2).
 *
 * @property boxes in original-image pixels. Layout and OCR boxes are both kept, so the area
 *   covered is the union of template region and OCR box and is never shrunk.
 * @property state [ItemState.REDACTED] at high confidence, otherwise [ItemState.NEEDS_CHECK].
 * @property fallback true when the whole document is one box (no corners, or no layout, and no field read).
 */
data class Localization(val boxes: List<RedactionBox>, val state: ItemState, val fallback: Boolean)

/** Finds the boxes to redact on one detected document (§8.2). */
fun interface FieldLocalizer {
    /**
     * @param corners the detector's corners (TL, TR, BR, BL) in original-image pixels, a
     *   missing one null; null if the card's corners are unknown.
     */
    suspend fun localize(
        image: OcrImage,
        documentClass: DocumentClass,
        documentConfidence: Float,
        corners: List<Corner?>?,
    ): Localization
}

/**
 * Hybrid field localization (§5.1, §8.2): template fields mapped through the card's
 * homography, plus the OCR-first field rules. A port of `localize(..., "hybrid")` in
 * `validation/localize/core.py`; recognized text never leaves this call.
 *
 * Templates are per design variant, but the detector only gives the class. The variant whose
 * text fields hold the most OCR matches is used; on a tie every tied variant is used, so a
 * field is never left out because the wrong variant was picked. The level is high (§8.2) when
 * the document confidence is high and OCR read a field inside every template text field.
 */
class HybridLocalizer(
    private val templates: List<CardTemplate>,
    private val rules: FieldRules,
    private val ocr: OcrEngine,
    /** Document confidence at or above which a fully matched card is REDACTED (§8.2). */
    private val highConfidence: Float = 0.6f,
) : FieldLocalizer {
    override suspend fun localize(
        image: OcrImage,
        documentClass: DocumentClass,
        documentConfidence: Float,
        corners: List<Corner?>?,
    ): Localization {
        val bitmap = image.bitmap
        val toBitmap = bitmap.width.toFloat() / image.originalWidth
        val card = Geometry.completeCorners(corners?.map { c -> c?.let { Corner(it.x * toBitmap, it.y * toBitmap) } })

        val ocrFound = ocrBoxes(bitmap, card)
        var layout = emptyList<FieldBox>()
        var allTextFieldsRead = false
        if (card != null) {
            val toCard = Geometry.homography(Geometry.UNIT_SQUARE, card)
            val variants = templates.filter { it.documentClass == documentClass }
            if (toCard != null && variants.isNotEmpty()) {
                val mapped = variants.map { layoutBoxes(it, toCard) }
                val scores = mapped.map { boxes -> ocrFound.count { o -> boxes.any { it.isText() && it.contains(o) } } }
                val chosen = mapped.filterIndexed { i, _ -> scores[i] == scores.max() }
                layout = chosen.flatten()
                // Variants can share a layout (both LTO designs do), so a tie is fine when every
                // tied variant had all its text fields read.
                allTextFieldsRead = chosen.all { boxes -> boxes.filter { it.isText() }.all { f -> ocrFound.any { f.contains(it) } } }
            }
        }

        val confidence = documentConfidence.coerceIn(0f, 1f)
        val toOriginal = 1f / toBitmap
        // Python falls back only when there are no corners; with corners the layout is never
        // empty unless the homography is degenerate, which must not leave the card uncovered.
        if (layout.isEmpty() && ocrFound.isEmpty()) {
            val whole = Geometry.rectangle(0f, 0f, image.originalWidth.toFloat(), image.originalHeight.toFloat())
            return Localization(listOf(RedactionBox(FieldType.WHOLE_DOCUMENT, whole.flat(1f), confidence)), ItemState.NEEDS_CHECK, true)
        }
        val high = documentConfidence >= highConfidence && allTextFieldsRead
        return Localization(
            boxes = (layout + ocrFound).map { RedactionBox(it.fieldType, it.polygon.flat(toOriginal), confidence) },
            state = if (high) ItemState.REDACTED else ItemState.NEEDS_CHECK,
            fallback = false,
        )
    }

    /** Every sensitive template field mapped from card space into the image. */
    private fun layoutBoxes(template: CardTemplate, toCard: Matrix): List<FieldBox> =
        template.fields.map { FieldBox(it.type, Geometry.project(toCard, template.paddedPolygon(it))) }

    /**
     * Field-rule boxes in [bitmap] pixels. With corners, the card is first rectified to an
     * upright rectangle [RECTIFIED_WIDTH] wide (no template is used) and boxes are mapped back.
     */
    private suspend fun ocrBoxes(bitmap: Bitmap, card: List<Corner>?): List<FieldBox> {
        if (card == null) return findFields(bitmap)
        val top = hypot(card[1].x - card[0].x, card[1].y - card[0].y)
        val side = hypot(card[3].x - card[0].x, card[3].y - card[0].y)
        val width = RECTIFIED_WIDTH
        val height = (width * side / maxOf(top, 1f)).roundToInt().coerceIn(1, width * 4)
        val rect = Geometry.rectangle(0f, 0f, width.toFloat(), height.toFloat())
        val toImage = Geometry.homography(rect, card) ?: return findFields(bitmap)
        val toRect = Geometry.homography(card, rect) ?: return findFields(bitmap)
        val upright = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(upright).drawBitmap(bitmap, toRect, Paint(Paint.FILTER_BITMAP_FLAG))
            return findFields(upright).map { FieldBox(it.fieldType, Geometry.project(toImage, it.polygon)) }
        } finally {
            upright.recycle()
        }
    }

    /** OCR in grayscale, plus an inverted pass on a dark card (light text, dark panels). */
    private suspend fun findFields(image: Bitmap): List<FieldBox> {
        val found = mutableListOf<FieldBox>()
        val gray = filtered(image, ColorMatrix().apply { setSaturation(0f) })
        try {
            found += rules.match(ocr.recognize(gray))
            if (meanGray(gray) < DARK_MEAN) {
                val inverted = filtered(gray, INVERT)
                try {
                    found += rules.match(ocr.recognize(inverted))
                } finally {
                    inverted.recycle()
                }
            }
        } finally {
            gray.recycle()
        }
        return found
    }

    private fun filtered(image: Bitmap, colors: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(colors) })
        return out
    }

    /** Mean brightness (0-255) of the image shrunk to 32 x 32, as the desktop harness measures it. */
    private fun meanGray(gray: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(gray, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (small !== gray) small.recycle()
        return pixels.sumOf { (it shr 8) and 0xFF }.toFloat() / pixels.size
    }

    private fun FieldBox.isText() = fieldType !in CardTemplate.PICTURE_FIELDS

    /** True if the centre of [other] lies inside this box's bounds. */
    private fun FieldBox.contains(other: FieldBox): Boolean {
        val (l, t, r, b) = Geometry.bounds(polygon).toList()
        val o = Geometry.bounds(other.polygon)
        val cx = (o[0] + o[2]) / 2
        val cy = (o[1] + o[3]) / 2
        return cx in l..r && cy in t..b
    }

    private fun List<Corner>.flat(scale: Float) = flatMap { listOf(it.x * scale, it.y * scale) }

    companion object {
        /** Long side of the OCR decode (§8.2). */
        const val OCR_MAX_SIDE = 2048
        /** OCR reads the card at this width after rectification. */
        const val RECTIFIED_WIDTH = 1400
        /** Below this mean brightness the card is dark and gets an inverted OCR pass. */
        const val DARK_MEAN = 110f

        private val INVERT = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
