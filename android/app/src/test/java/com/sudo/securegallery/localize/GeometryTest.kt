package com.sudo.securegallery.localize

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.detect.Corner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Polygon mapping (issue #17): corners completed like the desktop harness, a
 * `Matrix.setPolyToPoly` homography from the template's unit square, and the bundled templates
 * with the §8.6 picture margin.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeometryTest {
    @Test
    fun oneMissingCornerIsFilledInAsAParallelogram() {
        val tl = Corner(10f, 10f)
        val tr = Corner(110f, 20f)
        val br = Corner(100f, 80f)
        val bl = Corner(0f, 70f)
        assertEquals(listOf(tl, tr, br, bl), Geometry.completeCorners(listOf(tl, tr, br, bl)))
        // prev + next - opposite, as in core.complete_corners
        assertEquals(Corner(10f, 10f), Geometry.completeCorners(listOf(null, tr, br, bl))!![0])
        assertEquals(Corner(100f, 80f), Geometry.completeCorners(listOf(tl, tr, null, bl))!![2])
        assertNull(Geometry.completeCorners(listOf(null, tr, null, bl)))
        assertNull(Geometry.completeCorners(listOf(tl, tr, br)))
        assertNull(Geometry.completeCorners(null))
    }

    @Test
    fun homographyMapsTheUnitSquareOntoThePerspectiveCard() {
        val card = listOf(Corner(100f, 50f), Corner(500f, 90f), Corner(470f, 330f), Corner(80f, 300f))
        val matrix = Geometry.homography(Geometry.UNIT_SQUARE, card)
        assertNotNull(matrix)
        val mapped = Geometry.project(matrix!!, Geometry.UNIT_SQUARE)
        for ((got, want) in mapped.zip(card)) assertNear(want, got)
        // A perspective map is not affine: the centre goes to where the diagonals cross.
        val centre = Geometry.project(matrix, listOf(Corner(0.5f, 0.5f))).single()
        assertNear(diagonalCrossing(card), centre)
    }

    @Test
    fun degenerateCornersGiveNoHomography() {
        val line = List(4) { Corner(it * 10f, it * 10f) }
        assertNull(Geometry.homography(Geometry.UNIT_SQUARE, line))
    }

    @Test
    fun bundledTemplatesCoverEveryClassWithSensitiveFieldsOnly() {
        val templates = CardTemplate.loadAll(ApplicationProvider.getApplicationContext<Context>().assets)
        assertEquals(
            listOf("lto-dl-current", "lto-dl-previous", "payment-card-back", "payment-card-front", "philsys-card", "philsys-ephilid"),
            templates.map { it.id },
        )
        assertEquals(DocumentClass.entries.toSet(), templates.map { it.documentClass }.toSet())
        val philsys = templates.first { it.id == "philsys-card" }
        assertTrue(FieldType.PCN in philsys.fields.map { it.type })
        assertTrue(FieldType.QR_CODE in philsys.fields.map { it.type })
    }

    @Test
    fun pictureFieldsGetThreePercentOfTheCardWidthOnEverySide() {
        val face = TemplateField(FieldType.FACE_PHOTO, Geometry.rectangle(0.1f, 0.2f, 0.3f, 0.6f))
        val name = TemplateField(FieldType.FULL_NAME, Geometry.rectangle(0.4f, 0.2f, 0.9f, 0.3f))
        val template = CardTemplate("t", DocumentClass.PHILSYS, 1.5f, listOf(face, name))
        val padded = Geometry.bounds(template.paddedPolygon(face))
        assertEquals(0.07f, padded[0], 1e-6f)
        assertEquals(0.2f - 0.045f, padded[1], 1e-6f)
        assertEquals(0.33f, padded[2], 1e-6f)
        assertEquals(0.6f + 0.045f, padded[3], 1e-6f)
        assertEquals(name.polygon, template.paddedPolygon(name))
    }

    private fun diagonalCrossing(c: List<Corner>): Corner {
        // Solve c0 + t (c2 - c0) = c1 + s (c3 - c1).
        val (ax, ay) = c[0].x to c[0].y
        val (bx, by) = c[2].x - ax to c[2].y - ay
        val (cx, cy) = c[1].x to c[1].y
        val (dx, dy) = c[3].x - cx to c[3].y - cy
        val t = ((cx - ax) * dy - (cy - ay) * dx) / (bx * dy - by * dx)
        return Corner(ax + t * bx, ay + t * by)
    }

    private fun assertNear(want: Corner, got: Corner) {
        assertEquals(want.x, got.x, 0.05f)
        assertEquals(want.y, got.y, 0.05f)
    }
}
