package com.sudo.securegallery.detect

import android.graphics.PointF
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for template parsing and field padding rules (§8.1, §8.6).
 */
class CardTemplateTest {

    @Test
    fun parsesTemplateFromJson() {
        val file = File("../../templates/philsys-card.json").let {
            if (it.exists()) it else File("templates/philsys-card.json")
        }
        val template = CardTemplate.parse(file.readText(Charsets.UTF_8))

        assertEquals("philsys-card", template.id)
        assertEquals(DocumentClass.PHILSYS, template.documentClass)
        assertEquals(1.5858f, template.aspectRatio, 0.001f)

        val sensitive = template.sensitiveFieldsWithPadding()
        assertTrue(sensitive.any { it.type == FieldType.PCN })
        assertTrue(sensitive.any { it.type == FieldType.FACE_PHOTO })
        assertTrue(sensitive.any { it.type == FieldType.QR_CODE })
        assertTrue(sensitive.any { it.type == FieldType.SIGNATURE })
    }

    @Test
    fun pictureFieldsArePaddedByThreePercent() {
        val rawPoly = listOf(
            PointF(0.10f, 0.20f),
            PointF(0.40f, 0.20f),
            PointF(0.40f, 0.70f),
            PointF(0.10f, 0.70f),
        )
        val aspect = 1.5858f
        val padded = CardTemplate.padPolygon(rawPoly, aspect)

        val mx = 0.03f
        val my = 0.03f * aspect
        assertEquals(0.10f - mx, padded[0].x, 0.0001f)
        assertEquals(0.20f - my, padded[0].y, 0.0001f)
        assertEquals(0.40f + mx, padded[1].x, 0.0001f)
        assertEquals(0.20f - my, padded[1].y, 0.0001f)
        assertEquals(0.40f + mx, padded[2].x, 0.0001f)
        assertEquals(0.70f + my, padded[2].y, 0.0001f)
        assertEquals(0.10f - mx, padded[3].x, 0.0001f)
        assertEquals(0.70f + my, padded[3].y, 0.0001f)
    }
}
