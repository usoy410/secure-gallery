package com.sudo.securegallery.detect

import com.sudo.securegallery.data.DocumentClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Post-processing of the raw YOLO11-pose tensor (issue #16). */
class YoloPosePostProcessorTest {
    // Pixel coordinates keep the arithmetic readable; normalized output is checked below.
    private val config = DetectorConfig(normalizedCoordinates = false)
    private val anchors = 8400
    private val processor = YoloPosePostProcessor(config)
    private val square = Letterbox.fit(640, 640, 640)

    /** A raw tensor with the given anchors set; every other value is zero. */
    private fun tensor(vararg rows: Pair<Int, FloatArray>): FloatArray {
        val out = FloatArray(config.channels * anchors)
        for ((anchor, values) in rows) values.forEachIndexed { c, v -> out[c * anchors + anchor] = v }
        return out
    }

    private fun anchor(cx: Float, cy: Float, w: Float, h: Float, scores: FloatArray, corners: FloatArray) =
        floatArrayOf(cx, cy, w, h, *scores, *corners)

    private val card = floatArrayOf(220f, 250f, 420f, 250f, 420f, 390f, 220f, 390f)

    @Test
    fun keepsTheBestClassAboveTheThreshold() {
        val out = tensor(7 to anchor(320f, 320f, 200f, 140f, floatArrayOf(0.1f, 0.8f, 0.2f), card))
        val d = processor.process(out, square).single()
        assertEquals(DocumentClass.LTO, d.documentClass)
        assertEquals(0.8f, d.confidence, 1e-6f)
        assertEquals(listOf(220f, 250f, 420f, 390f), d.box.toList())
        assertEquals(Corner(220f, 250f), d.corners[0])
    }

    @Test
    fun belowTheThresholdIsNotDetected() {
        val out = tensor(7 to anchor(320f, 320f, 200f, 140f, floatArrayOf(0.34f, 0.1f, 0.1f), card))
        assertTrue(processor.process(out, square).isEmpty())
        val looser = YoloPosePostProcessor(config.copy(minConfidence = 0.3f))
        assertEquals(1, looser.process(out, square).size)
    }

    @Test
    fun overlappingBoxesKeepOnlyTheStrongestAcrossClasses() {
        val out = tensor(
            1 to anchor(320f, 320f, 200f, 140f, floatArrayOf(0.9f, 0f, 0f), card),
            2 to anchor(322f, 318f, 198f, 142f, floatArrayOf(0f, 0.7f, 0f), card), // same card, other class
            3 to anchor(100f, 100f, 80f, 50f, floatArrayOf(0f, 0f, 0.6f), card), // a second card
        )
        val found = processor.process(out, square)
        assertEquals(listOf(DocumentClass.PHILSYS, DocumentClass.PAYMENT_CARD), found.map { it.documentClass })
    }

    @Test
    fun coordinatesMapBackThroughTheLetterbox() {
        // A 1600 x 1200 photo is scaled by 0.4 to 640 x 480 and padded 80 px top and bottom.
        val lb = Letterbox.fit(1600, 1200, 640)
        assertEquals(0.4f, lb.scale, 1e-6f)
        assertEquals(80f, lb.padY, 1e-6f)
        val out = tensor(0 to anchor(320f, 320f, 200f, 100f, floatArrayOf(0.9f, 0f, 0f), floatArrayOf(220f, 270f, 420f, 270f, 420f, 370f, 220f, 370f)))
        val d = processor.process(out, lb).single()
        assertEquals(listOf(550f, 475f, 1050f, 725f), d.box.toList())
        assertEquals(Corner(550f, 475f), d.corners[0])
        assertEquals(Corner(1050f, 725f), d.corners[2])
    }

    @Test
    fun cornersAreOrderedClockwiseFromTopLeft() {
        val shuffled = listOf(Corner(10f, 90f), Corner(90f, 90f), Corner(10f, 10f), Corner(90f, 10f))
        assertEquals(
            listOf(Corner(10f, 10f), Corner(90f, 10f), Corner(90f, 90f), Corner(10f, 90f)),
            YoloPosePostProcessor.orderCorners(shuffled),
        )
        // A card turned 30 degrees clockwise.
        val turned = listOf(Corner(60f, 0f), Corner(147f, 50f), Corner(97f, 137f), Corner(10f, 87f))
        assertEquals(turned, YoloPosePostProcessor.orderCorners(turned.reversed()))
    }

    @Test
    fun normalizedOutputIsScaledToTheInput() {
        val normalized = YoloPosePostProcessor(DetectorConfig())
        val out = tensor(7 to anchor(0.5f, 0.5f, 0.3125f, 0.21875f, floatArrayOf(0.9f, 0f, 0f), card.map { it / 640f }.toFloatArray()))
        val d = normalized.process(out, square).single()
        assertEquals(listOf(220f, 250f, 420f, 390f), d.box.map { kotlin.math.round(it) })
        assertEquals(Corner(220f, 250f), d.corners[0].let { Corner(kotlin.math.round(it.x), kotlin.math.round(it.y)) })
    }

    @Test
    fun atMostMaxDetections() {
        val rows = (0 until 10).map { i ->
            i to anchor(40f + i * 60f, 40f, 30f, 30f, floatArrayOf(0.9f - i * 0.01f, 0f, 0f), card)
        }.toTypedArray()
        assertEquals(config.maxDetections, processor.process(tensor(*rows), square).size)
    }
}
