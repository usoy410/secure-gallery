package com.sudo.securegallery.localize

import android.graphics.Matrix
import com.sudo.securegallery.detect.Corner

/**
 * Card geometry for field localization (§8.2), ported from `validation/localize/core.py`.
 * Homographies use [Matrix.setPolyToPoly] with four points, so no OpenCV is needed.
 */
object Geometry {
    /** The template's card space: (0, 0) is the top-left corner, (1, 1) the bottom-right. */
    val UNIT_SQUARE = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))

    /**
     * Four corners (TL, TR, BR, BL), or null if fewer than three are known. One missing corner
     * (null) is filled in as a parallelogram from the other three.
     */
    fun completeCorners(raw: List<Corner?>?): List<Corner>? {
        if (raw == null || raw.size != 4) return null
        val missing = raw.indices.filter { raw[it] == null }
        if (missing.size > 1) return null
        if (missing.isEmpty()) return raw.map { it!! }
        val i = missing.single()
        val prev = raw[(i + 3) % 4]!!
        val next = raw[(i + 1) % 4]!!
        val opposite = raw[(i + 2) % 4]!!
        return raw.mapIndexed { j, p -> if (j == i) Corner(prev.x + next.x - opposite.x, prev.y + next.y - opposite.y) else p!! }
    }

    /** The perspective transform taking each [src] corner onto the matching [dst] corner, or null if degenerate. */
    fun homography(src: List<Corner>, dst: List<Corner>): Matrix? {
        require(src.size == 4 && dst.size == 4) { "a homography needs 4 point pairs" }
        val matrix = Matrix()
        return if (matrix.setPolyToPoly(src.toFloats(), 0, dst.toFloats(), 0, 4)) matrix else null
    }

    fun project(matrix: Matrix, polygon: List<Corner>): List<Corner> {
        val points = polygon.toFloats()
        matrix.mapPoints(points)
        return polygon.indices.map { Corner(points[2 * it], points[2 * it + 1]) }
    }

    fun List<Corner>.toFloats(): FloatArray = FloatArray(size * 2) { if (it % 2 == 0) this[it / 2].x else this[it / 2].y }

    /** Left, top, right, bottom of the polygon's bounding box. */
    fun bounds(polygon: List<Corner>): FloatArray =
        floatArrayOf(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })

    fun rectangle(left: Float, top: Float, right: Float, bottom: Float) =
        listOf(Corner(left, top), Corner(right, top), Corner(right, bottom), Corner(left, bottom))
}
