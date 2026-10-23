package com.sudo.securegallery.detect

import android.graphics.Matrix
import android.graphics.PointF

/**
 * Geometric perspective projection and quadrilateral utilities (§5.1, §8.2).
 *
 * Implements homography from normalized card templates onto camera image coordinates
 * using [Matrix.setPolyToPoly] without native OpenCV dependencies.
 */
object Homography {

    /**
     * Resolves four card corners, completing one missing corner if three are known (§8.2).
     *
     * In perspective distortion or slight occlusion, if three corners are found, the fourth
     * is approximated by parallelogram completion matching the desktop reference (core.py).
     *
     * @param raw List of 4 potentially nullable corners in TL, TR, BR, BL order.
     * @return 4 non-null [Corner] points, or null if fewer than 3 corners are present.
     */
    fun completeCorners(raw: List<Corner?>?): List<Corner>? {
        if (raw == null || raw.size != 4) return null
        val pts = raw.toMutableList()
        val missingIndices = pts.indices.filter { pts[it] == null }
        if (missingIndices.size > 1) return null

        if (missingIndices.isNotEmpty()) {
            val i = missingIndices[0]
            val prev = pts[(i + 3) % 4] ?: return null
            val next = pts[(i + 1) % 4] ?: return null
            val opp = pts[(i + 2) % 4] ?: return null
            pts[i] = Corner(prev.x + next.x - opp.x, prev.y + next.y - opp.y)
        }

        @Suppress("UNCHECKED_CAST")
        return pts as List<Corner>
    }

    /**
     * Computes the 3x3 projective transformation matrix from the template's unit square
     * ((0,0), (1,0), (1,1), (0,1)) to the detected image corners using [Matrix.setPolyToPoly].
     *
     * @param corners 4 corners in original-image coordinates in TL, TR, BR, BL order.
     * @return Perspective [Matrix], or null if corners are collinear or non-invertible.
     */
    fun createHomographyMatrix(corners: List<Corner>): Matrix? {
        if (corners.size != 4) return null
        val src = floatArrayOf(
            0f, 0f, // Top-Left
            1f, 0f, // Top-Right
            1f, 1f, // Bottom-Right
            0f, 1f, // Bottom-Left
        )
        val dst = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y,
        )
        val matrix = Matrix()
        val success = matrix.setPolyToPoly(src, 0, dst, 0, 4)
        return if (success) matrix else null
    }

    /**
     * Projects a polygon from template unit-square coordinates into original-image space.
     *
     * @param matrix Perspective transformation matrix from [createHomographyMatrix].
     * @param polygon List of [PointF] in unit-square [0, 1] coordinate system.
     * @return Flattened array of image coordinates [x0, y0, x1, y1, ...].
     */
    fun mapPolygon(matrix: Matrix, polygon: List<PointF>): List<Float> {
        val src = FloatArray(polygon.size * 2) { i ->
            val pt = polygon[i / 2]
            if (i % 2 == 0) pt.x else pt.y
        }
        val dst = FloatArray(src.size)
        matrix.mapPoints(dst, src)
        return dst.toList()
    }
}
