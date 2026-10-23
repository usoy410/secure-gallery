package com.sudo.securegallery.detect

import com.sudo.securegallery.data.DocumentClass
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Kotlin post-processing on raw tensors recorded from the bundled model, compared with
 * Ultralytics' own predictions on the same photos (issue #16). Recorded by
 * `python -m ml.android_fixtures`; regenerate them whenever the model changes.
 */
@RunWith(RobolectricTestRunner::class) // for org.json
class RecordedTensorTest {
    private val names = listOf("philsys_card", "philsys_ephilid", "lto_current", "lto_previous", "payment_card")

    private fun load(name: String): JSONObject =
        JSONObject(javaClass.getResource("/detector/$name.json")!!.readText())

    @Test
    fun matchesTheReferenceImplementation() {
        for (name in names) {
            val record = load(name)
            val channels = record.getInt("channels")
            val anchors = record.getInt("anchors")
            val raw = FloatArray(channels * anchors)
            val sparse = record.getJSONArray("sparse")
            for (i in 0 until sparse.length()) {
                val row = sparse.getJSONArray(i)
                val anchor = row.getInt(0)
                for (c in 0 until channels) raw[c * anchors + anchor] = row.getDouble(c + 1).toFloat()
            }
            val image = record.getJSONObject("image")
            val letterbox = Letterbox.fit(image.getInt("width"), image.getInt("height"), record.getInt("input_size"))
            val found = YoloPosePostProcessor(DetectorConfig()).process(raw, letterbox)
            val reference = record.getJSONArray("reference")

            assertEquals(name, reference.length(), found.size)
            for (i in 0 until reference.length()) {
                val ref = reference.getJSONObject(i)
                val d = found[i]
                assertEquals(name, DocumentClass.fromId(ref.getString("document_class")), d.documentClass)
                assertEquals(name, ref.getDouble("confidence").toFloat(), d.confidence, 0.002f)
                // Ultralytics rounds the letterbox padding to whole pixels; allow for that.
                ref.getJSONArray("box").floats().zip(d.box.toList()).forEach { (e, a) -> assertEquals(name, e, a, 2.5f) }
                val refCorners = YoloPosePostProcessor.orderCorners(ref.getJSONArray("corners").corners())
                refCorners.zip(d.corners).forEach { (e, a) ->
                    assertEquals(name, e.x, a.x, 2.5f)
                    assertEquals(name, e.y, a.y, 2.5f)
                }
            }
        }
    }

    @Test
    fun recordedPhotosAreDetected() {
        // A sanity floor for the bundled model: every recorded photo has a detection.
        for (name in names) assertTrue(name, load(name).getJSONArray("reference").length() > 0)
    }

    private fun JSONArray.floats() = List(length()) { getDouble(it).toFloat() }

    private fun JSONArray.corners() = List(length()) { getJSONArray(it).let { p -> Corner(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } }
}
