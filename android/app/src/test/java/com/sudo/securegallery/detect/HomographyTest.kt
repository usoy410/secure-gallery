package com.sudo.securegallery.detect

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM unit tests for homography and polygon mapping using Matrix.setPolyToPoly.
 */
@RunWith(RobolectricTestRunner::class)
class HomographyTest {

    @Test
    fun completeCornersReturnsOriginalWhenAllFourProvided() {
        val corners = listOf(
            Corner(10f, 20f),
            Corner(110f, 20f),
            Corner(110f, 80f),
            Corner(10f, 80f),
        )
        val completed = Homography.completeCorners(corners)
        assertEquals(corners, completed)
    }

    @Test
    fun completeCornersReconstructsMissingCornerAsParallelogram() {
        val c0 = Corner(10f, 20f)
        val c1 = Corner(110f, 20f)
        val c2 = Corner(110f, 80f)
        val missingBL = listOf(c0, c1, c2, null)

        val completed = Homography.completeCorners(missingBL)
        assertNotNull(completed)
        assertEquals(4, completed!!.size)
        // BL = prev(BR) + next(TL) - opp(TR) = c2 + c0 - c1
        // (110 + 10 - 110, 80 + 20 - 20) = (10, 80)
        assertEquals(10f, completed[3].x, 0.001f)
        assertEquals(80f, completed[3].y, 0.001f)
    }

    @Test
    fun completeCornersFailsIfMoreThanOneCornerMissing() {
        val twoMissing = listOf(Corner(10f, 20f), null, Corner(110f, 80f), null)
        assertNull(Homography.completeCorners(twoMissing))

        val invalidSize = listOf(Corner(10f, 20f), Corner(110f, 20f))
        assertNull(Homography.completeCorners(invalidSize))
    }

    @Test
    fun setPolyToPolyMapsUnitSquareToCorners() {
        val corners = listOf(
            Corner(100f, 200f),
            Corner(500f, 200f),
            Corner(500f, 400f),
            Corner(100f, 400f),
        )
        val matrix = Homography.createHomographyMatrix(corners)
        assertNotNull(matrix)

        // Map the unit square polygon
        val unitSquare = listOf(
            PointF(0f, 0f),
            PointF(1f, 0f),
            PointF(1f, 1f),
            PointF(0f, 1f),
        )
        val mapped = Homography.mapPolygon(matrix!!, unitSquare)
        assertEquals(8, mapped.size)

        assertEquals(100f, mapped[0], 0.1f)
        assertEquals(200f, mapped[1], 0.1f)
        assertEquals(500f, mapped[2], 0.1f)
        assertEquals(200f, mapped[3], 0.1f)
        assertEquals(500f, mapped[4], 0.1f)
        assertEquals(400f, mapped[5], 0.1f)
        assertEquals(100f, mapped[6], 0.1f)
        assertEquals(400f, mapped[7], 0.1f)
    }

    @Test
    fun mapPolygonProjectsInteriorRegionCorrectly() {
        val corners = listOf(
            Corner(0f, 0f),
            Corner(1000f, 0f),
            Corner(1000f, 600f),
            Corner(0f, 600f),
        )
        val matrix = Homography.createHomographyMatrix(corners)!!

        // A field spanning [0.1, 0.2] to [0.9, 0.4] in unit space
        val field = listOf(
            PointF(0.1f, 0.2f),
            PointF(0.9f, 0.2f),
            PointF(0.9f, 0.4f),
            PointF(0.1f, 0.4f),
        )
        val mapped = Homography.mapPolygon(matrix, field)
        assertEquals(100f, mapped[0], 0.1f)
        assertEquals(120f, mapped[1], 0.1f)
        assertEquals(900f, mapped[2], 0.1f)
        assertEquals(120f, mapped[3], 0.1f)
        assertEquals(900f, mapped[4], 0.1f)
        assertEquals(240f, mapped[5], 0.1f)
        assertEquals(100f, mapped[6], 0.1f)
        assertEquals(240f, mapped[7], 0.1f)
    }
}
