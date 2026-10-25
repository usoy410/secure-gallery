package com.sudo.securegallery.redact

import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.RedactionBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The box editor's model (issue #24): edits stay inside the image and each gesture counts once. */
class BoxEditsTest {
    private val box = RedactionBox(FieldType.PCN, listOf(10f, 10f, 50f, 10f, 50f, 30f, 10f, 30f), 0.9f)
    private val edits = BoxEdits(listOf(box), width = 100f, height = 60f)

    @Test
    fun aDragOfManyStepsCountsAsOneMove() {
        val dragged = edits.move(0, 5f, 0f).move(0, 5f, 2f).endGesture()
        assertEquals(listOf(20f, 12f, 60f, 12f, 60f, 32f, 20f, 32f), dragged.boxes[0].corners)
        assertEquals(Corrections(moves = 1), dragged.done)
        assertEquals(FieldType.PCN, dragged.boxes[0].fieldType)
    }

    @Test
    fun aMoveStopsAtTheImageEdge() {
        val moved = edits.move(0, 500f, -500f)
        assertEquals(listOf(60f, 0f, 100f, 0f, 100f, 20f, 60f, 20f), moved.boxes[0].corners)
    }

    @Test
    fun aGestureThatChangedNothingIsNotCounted() {
        val atEdge = edits.move(0, -10f, -10f).endGesture()
        assertEquals(Corrections(moves = 1), atEdge.done)
        assertEquals(atEdge, atEdge.move(0, -5f, -5f).endGesture()) // already at the corner
        assertEquals(Corrections(), edits.endGesture().done)
    }

    @Test
    fun draggingACornerResizesAndCountsOnce() {
        val resized = edits.moveCorner(0, 2, 70f, 40f).moveCorner(0, 2, 80f, 45f).endGesture()
        assertEquals(listOf(10f, 10f, 50f, 10f, 80f, 45f, 10f, 30f), resized.boxes[0].corners)
        assertEquals(Corrections(resizes = 1), resized.done)
    }

    @Test
    fun aCornerCannotFoldTheBoxOrLeaveTheImage() {
        // Bottom-right dragged past the top-left corner would make a bow tie: ignored.
        assertEquals(edits.boxes, edits.moveCorner(0, 2, 5f, 5f).boxes)
        val clamped = edits.moveCorner(0, 2, 500f, 500f)
        assertEquals(listOf(100f, 60f), clamped.boxes[0].corners.subList(4, 6))
    }

    @Test
    fun addAndDeleteCountAtOnce() {
        val added = edits.add(cx = 95f, cy = 30f, side = 20f)
        assertEquals(2, added.boxes.size)
        assertEquals(FieldType.USER_BOX, added.boxes[1].fieldType)
        assertEquals(listOf(80f, 20f, 100f, 20f, 100f, 40f, 80f, 40f), added.boxes[1].corners) // kept inside
        val deleted = added.delete(0)
        assertEquals(listOf(FieldType.USER_BOX), deleted.boxes.map { it.fieldType })
        assertEquals(Corrections(adds = 1, deletes = 1), deleted.done)
        assertEquals(2, deleted.done.total)
    }

    @Test
    fun convexityAndArea() {
        assertTrue(BoxEdits.isConvex(box.corners))
        assertFalse(BoxEdits.isConvex(listOf(0f, 0f, 10f, 10f, 10f, 0f, 0f, 10f)))
        assertEquals(800f, BoxEdits.area(box.corners))
    }
}
