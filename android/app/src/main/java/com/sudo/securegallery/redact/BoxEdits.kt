package com.sudo.securegallery.redact

import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.RedactionBox

/** Box corrections by kind (§8.4, #24), for the correction-rate metric. Counts only. */
data class Corrections(val moves: Int = 0, val resizes: Int = 0, val adds: Int = 0, val deletes: Int = 0) {
    val total: Int get() = moves + resizes + adds + deletes
}

/**
 * The boxes of one item while the user corrects them in the review inbox (#24), in
 * original-image pixels, plus what was done to them.
 *
 * A drag changes the boxes many times; it counts as one correction when it ends
 * ([endGesture]), and only if something moved. Adding and deleting count at once. Every box
 * stays inside the image and convex, so the fill and the recheck see a proper quadrilateral.
 */
data class BoxEdits(
    val boxes: List<RedactionBox>,
    val width: Float,
    val height: Float,
    val done: Corrections = Corrections(),
    private val pending: Kind? = null,
) {
    enum class Kind { MOVE, RESIZE }

    /** Moves box [index] by ([dx], [dy]), stopping at the image edge. */
    fun move(index: Int, dx: Float, dy: Float): BoxEdits {
        val c = boxes[index].corners
        val xs = c.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = c.filterIndexed { i, _ -> i % 2 == 1 }
        val mx = dx.coerceIn(-xs.min(), width - xs.max())
        val my = dy.coerceIn(-ys.min(), height - ys.max())
        if (mx == 0f && my == 0f) return this
        val moved = c.mapIndexed { i, v -> if (i % 2 == 0) v + mx else v + my }
        return replace(index, moved, Kind.MOVE)
    }

    /** Puts corner [corner] (0 to 3, clockwise from top-left) of box [index] at ([x], [y]). */
    fun moveCorner(index: Int, corner: Int, x: Float, y: Float): BoxEdits {
        val c = boxes[index].corners.toMutableList()
        c[2 * corner] = x.coerceIn(0f, width)
        c[2 * corner + 1] = y.coerceIn(0f, height)
        if (c == boxes[index].corners || !isConvex(c) || area(c) < MIN_SIDE * MIN_SIDE) return this
        return replace(index, c, Kind.RESIZE)
    }

    /** Adds a square box of side [side] centred on ([cx], [cy]), inside the image. */
    fun add(cx: Float, cy: Float, side: Float): BoxEdits {
        val s = side.coerceAtMost(minOf(width, height))
        val left = (cx - s / 2).coerceIn(0f, width - s)
        val top = (cy - s / 2).coerceIn(0f, height - s)
        val box = RedactionBox(FieldType.USER_BOX, listOf(left, top, left + s, top, left + s, top + s, left, top + s), 1f)
        return copy(boxes = boxes + box, done = done.copy(adds = done.adds + 1))
    }

    fun delete(index: Int): BoxEdits =
        copy(boxes = boxes.filterIndexed { i, _ -> i != index }, done = done.copy(deletes = done.deletes + 1))

    /** Counts the drag that just ended, if it changed anything. */
    fun endGesture(): BoxEdits = when (pending) {
        null -> this
        Kind.MOVE -> copy(done = done.copy(moves = done.moves + 1), pending = null)
        Kind.RESIZE -> copy(done = done.copy(resizes = done.resizes + 1), pending = null)
    }

    private fun replace(index: Int, corners: List<Float>, kind: Kind) =
        copy(boxes = boxes.mapIndexed { i, b -> if (i == index) b.copy(corners = corners) else b }, pending = kind)

    companion object {
        /** Smallest box the editor keeps, in image pixels per side. */
        const val MIN_SIDE = 4f

        /** True if the four corners turn the same way at every corner (no fold or bow tie). */
        fun isConvex(c: List<Float>): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val ax = c[2 * i]; val ay = c[2 * i + 1]
                val bx = c[(2 * i + 2) % 8]; val by = c[(2 * i + 3) % 8]
                val cx = c[(2 * i + 4) % 8]; val cy = c[(2 * i + 5) % 8]
                val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s == 0) return false
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

        /** Shoelace area of the quadrilateral. */
        fun area(c: List<Float>): Float {
            var sum = 0f
            for (i in 0 until 4) sum += c[2 * i] * c[(2 * i + 3) % 8] - c[(2 * i + 2) % 8] * c[2 * i + 1]
            return kotlin.math.abs(sum) / 2
        }
    }
}
