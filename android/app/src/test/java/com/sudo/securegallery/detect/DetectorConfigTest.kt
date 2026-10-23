package com.sudo.securegallery.detect

import com.sudo.securegallery.data.DocumentClass
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Thresholds and model shape come from the asset files, not code (issue #16). */
@RunWith(RobolectricTestRunner::class)
class DetectorConfigTest {
    private val assets = File("src/main/assets")

    @Test
    fun bundledFilesParse() {
        val config = LiteRtDetector.parseConfig(
            JSONObject(File(assets, LiteRtDetector.METADATA).readText()),
            JSONObject(File(assets, LiteRtDetector.THRESHOLDS).readText()),
        )
        assertEquals(640, config.inputSize)
        assertEquals(listOf(DocumentClass.PHILSYS, DocumentClass.LTO, DocumentClass.PAYMENT_CARD), config.classes)
        assertEquals(15, config.channels)
        assertEquals(0.35f, config.minConfidence) // §8.2 placeholder until tuned on val
        assertEquals(0.6f, config.highConfidence)
        assertEquals(true, config.normalizedCoordinates)
    }

    @Test
    fun thresholdsAreReadFromConfig() {
        val config = LiteRtDetector.parseConfig(
            JSONObject("""{"class_names":["philsys"],"input_size":[320,320],"num_keypoints":4}"""),
            JSONObject("""{"not_detected_below":0.5,"nms_iou":0.3}"""),
        )
        assertEquals(0.5f, config.minConfidence)
        assertEquals(0.3f, config.nmsIou)
        assertEquals(320, config.inputSize)
        assertEquals(13, config.channels) // 4 box + 1 class + 4 keypoints x 2
    }
}
