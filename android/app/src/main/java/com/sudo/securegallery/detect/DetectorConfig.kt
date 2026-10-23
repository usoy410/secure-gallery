package com.sudo.securegallery.detect

import com.sudo.securegallery.data.DocumentClass

/**
 * Everything the detector needs that is not code: model shape from `detector.json` (written by
 * `ml.export`) and thresholds from `detection.json`. Thresholds start at the §8.2
 * placeholders and are tuned on the validation split later, so they live in config.
 */
data class DetectorConfig(
    val inputSize: Int = 640,
    val classes: List<DocumentClass> = listOf(DocumentClass.PHILSYS, DocumentClass.LTO, DocumentClass.PAYMENT_CARD),
    val numKeypoints: Int = 4,
    /** Below this a detection is dropped; an image with none is NOT_DETECTED (§8.2). */
    val minConfidence: Float = 0.35f,
    /** At or above this the document is "high" confidence (§8.2). */
    val highConfidence: Float = 0.6f,
    /** Overlap above which the weaker of two boxes is dropped (non-maximum suppression). */
    val nmsIou: Float = 0.5f,
    val maxDetections: Int = 5,
    /** True when the model gives coordinates as fractions of [inputSize] (0..1), not pixels. */
    val normalizedCoordinates: Boolean = true,
) {
    /** Values per anchor in the raw output: box (4) + one score per class + x, y per keypoint. */
    val channels: Int get() = 4 + classes.size + numKeypoints * 2
}
