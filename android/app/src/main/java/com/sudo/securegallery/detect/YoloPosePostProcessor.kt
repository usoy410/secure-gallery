package com.sudo.securegallery.detect

/**
 * Turns the raw YOLO11-pose output into [Detection]s (§8.2).
 *
 * The tensor is `[1, channels, anchors]`, channel-major: rows 0-3 are the box centre and size,
 * then one score per class, then x, y for each keypoint, in model-input pixels or (with
 * [DetectorConfig.normalizedCoordinates]) as fractions of the input size. Steps:
 * keep anchors whose best class score reaches the threshold, drop overlaps (class-agnostic NMS:
 * one card cannot be two documents), map back through the letterbox, and order the corners.
 */
class YoloPosePostProcessor(private val config: DetectorConfig) {

    fun process(output: FloatArray, letterbox: Letterbox): List<Detection> {
        val channels = config.channels
        require(output.size % channels == 0) { "output size ${output.size} is not a multiple of $channels" }
        val anchors = output.size / channels
        val unit = if (config.normalizedCoordinates) config.inputSize.toFloat() else 1f
        fun at(channel: Int, anchor: Int) = output[channel * anchors + anchor]
        fun px(channel: Int, anchor: Int) = at(channel, anchor) * unit

        val candidates = mutableListOf<Candidate>()
        for (a in 0 until anchors) {
            var bestClass = 0
            var bestScore = at(4, a)
            for (c in 1 until config.classes.size) {
                val s = at(4 + c, a)
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < config.minConfidence) continue
            val cx = px(0, a)
            val cy = px(1, a)
            val w = px(2, a)
            val h = px(3, a)
            val kp = FloatArray(config.numKeypoints * 2) { px(4 + config.classes.size + it, a) }
            candidates += Candidate(bestClass, bestScore, floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), kp)
        }

        val kept = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (kept.size >= config.maxDetections) break
            if (kept.none { iou(it.box, c.box) > config.nmsIou }) kept += c
        }

        return kept.map { c ->
            val tl = letterbox.toOriginal(c.box[0], c.box[1])
            val br = letterbox.toOriginal(c.box[2], c.box[3])
            val corners = (0 until config.numKeypoints).map { letterbox.toOriginal(c.keypoints[2 * it], c.keypoints[2 * it + 1]) }
            Detection(
                documentClass = config.classes[c.classIndex],
                confidence = c.score,
                box = floatArrayOf(tl.x, tl.y, br.x, br.y),
                corners = orderCorners(corners),
            )
        }
    }

    private class Candidate(val classIndex: Int, val score: Float, val box: FloatArray, val keypoints: FloatArray)

    companion object {
        fun iou(a: FloatArray, b: FloatArray): Float {
            val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
            val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
            if (w <= 0f || h <= 0f) return 0f
            val inter = w * h
            val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
            return if (union <= 0f) 0f else inter / union
        }

        /**
         * Orders four points top-left, top-right, bottom-right, bottom-left (clockwise on screen),
         * whatever order the model gave them in. Sorting by angle around the centre keeps the
         * order right for cards rotated up to 45 degrees either way.
         */
        fun orderCorners(points: List<Corner>): List<Corner> {
            if (points.size != 4) return points
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            // Clockwise on screen (y grows down) is increasing atan2(y, x).
            val clockwise = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
            return List(4) { clockwise[(start + it) % 4] }
        }
    }
}
