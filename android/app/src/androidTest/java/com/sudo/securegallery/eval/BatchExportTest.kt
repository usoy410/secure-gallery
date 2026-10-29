package com.sudo.securegallery.eval

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.detect.DocumentDetector
import com.sudo.securegallery.detect.LiteRtDetector
import com.sudo.securegallery.localize.CardTemplate
import com.sudo.securegallery.localize.FieldLocalizer
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.HybridLocalizer
import com.sudo.securegallery.localize.MlKitOcrEngine
import com.sudo.securegallery.localize.OcrImage
import com.sudo.securegallery.redact.MediaStoreCopyStore
import com.sudo.securegallery.redact.Recheck
import com.sudo.securegallery.redact.Redactor
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.scan.MediaStoreImageSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device batch export for the desktop harness (issue #29, §9.2). Debug only: it lives in the
 * instrumented-test APK, never in the app (`EvalNotInAppTest` checks that).
 *
 * Runs the app's real pipeline ([GalleryScanner] with the bundled detector, ML Kit localizer,
 * redactor and recheck) on every image in `eval-input/` of the app's external files folder,
 * one image at a time, and writes to `eval-output/`:
 * - `<id>.jpg`: the redacted copy exactly as saved, and `<id>.boxes.json` (leak-test format);
 * - `results.json`: per image the class, confidence, corners, state, boxes, retries, recheck
 *   result, timings per stage (ms) and peak memory (MB). No OCR text or field value.
 *
 * Skipped when `eval-input/` is missing or empty, so normal emulator runs are unaffected.
 * Commands: `validation/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class BatchExportTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = app.contentResolver

    // The adb helper transfers files with run-as, avoiding shell-owned external
    // storage files. Keep the external default for existing manual invocations.
    private fun evaluationRoot(): File =
        if (InstrumentationRegistry.getArguments().getString("batch_private_storage") == "true") app.filesDir
        else checkNotNull(app.getExternalFilesDir(null))

    private fun requireImages(images: List<File>, input: File) {
        if (InstrumentationRegistry.getArguments().getString("batch_required") == "true") {
            check(images.isNotEmpty()) { "Requested batch export has no readable images in $input" }
        }
        assumeTrue("no images in $input", images.isNotEmpty())
    }

    @Test
    fun exportPipelineResults() = runBlocking {
        val input = File(evaluationRoot(), "eval-input")
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }
        requireImages(images.toList(), input)
        val output = File(evaluationRoot(), "eval-output").apply { deleteRecursively(); mkdirs() }

        val detector = checkNotNull(LiteRtDetector.open(app.assets)) { "detector.tflite missing" }
        val rules = FieldRules.load(app.assets)
        val ocr = MlKitOcrEngine()
        val timer = StageTimer()
        val mediaStore = MediaStoreImageSource(resolver, app.packageName)
        var current: GalleryImage? = null
        val source = TimedSource(mediaStore, timer) { listOfNotNull(current) }
        val timedDetector = DocumentDetector { image -> timer.time("detect") { detector.detect(image) } }
        val localizer = HybridLocalizer(CardTemplate.loadAll(app.assets), rules, ocr, detector.config.highConfidence)
        val timedLocalizer = FieldLocalizer { image, cls, conf, corners -> timer.timeSuspend("ocr_localize") { localizer.localize(image, cls, conf, corners) } }
        val recheck = TimedRecheck(rules, ocr, timer)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val scanner = GalleryScanner(source, db.itemDao(), timedDetector, detector.config, timedLocalizer, Redactor(source, MediaStoreCopyStore(resolver), recheck))

        val results = JSONArray()
        val inserted = mutableListOf<Long>()
        try {
            for (file in images) {
                val id = file.nameWithoutExtension
                val mediaId = insert(file).also { inserted += it }
                current = GalleryImage(mediaId, file.lastModified() / 1000, file.length())
                timer.reset()
                val started = System.nanoTime()
                scanner.scan()
                val totalMs = (System.nanoTime() - started) / 1e6
                val item = db.itemDao().byMediaStoreId(mediaId)!!
                item.redactedMediaStoreId?.let { copyId ->
                    inserted += copyId
                    MediaStoreCopyStore(resolver).read(copyId)?.let { File(output, "$id.jpg").writeBytes(it) }
                    File(output, "$id.boxes.json").writeText(JSONObject().put("boxes", boxesJson(item.boxes)).toString())
                }
                val stages = timer.snapshot()
                // Whatever the scan spent outside the timed stages is hashing, rendering and saving.
                val other = totalMs - stages.values.sum()
                results.put(
                    JSONObject()
                        .put("id", id)
                        .put("state", item.state.name)
                        .put("document_class", item.documentClass?.id ?: JSONObject.NULL)
                        .put("document_confidence", item.documentConfidence?.toDouble() ?: JSONObject.NULL)
                        .put("card_corners", item.cardCorners?.let { c -> JSONArray(c.xy.chunked(2).map { JSONArray(it) }) } ?: JSONObject.NULL)
                        .put("boxes", boxesJson(item.boxes))
                        .put("retries", item.retryCount)
                        .put("recheck_passed", item.recheckPassed)
                        .put("timings_ms", JSONObject(stages.mapValues { Math.round(it.value) } + mapOf("hash_redact_save" to Math.round(other), "total" to Math.round(totalMs))))
                        .put("peak_memory_mb", Math.round(timer.peakBytes / 1e6)),
                )
                Log.i(TAG, "$id: ${item.state} in ${Math.round(totalMs)} ms")
            }
            File(output, "results.json").writeText(
                JSONObject()
                    .put("detector", JSONObject(app.assets.open("detector.json").bufferedReader().use { it.readText() }))
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                    .put("images", results)
                    .toString(1),
            )
        } finally {
            ocr.close()
            detector.close()
            db.close()
            for (mediaId in inserted) resolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId), null, null)
        }
    }

    private fun insert(file: File): Long {
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SG batch export/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return ContentUris.parseId(uri)
    }

    /**
     * Verification-only on-device D7 export path (issue #30, §8.5, §9.1, RQ6).
     *
     * Directly runs [Recheck] on supplied redacted images in `eval-d7-input/` or `d7-input/`
     * without running the detector or redactor (so failed redactions are scored as-is, not repaired).
     * Evaluates pixel solidness on applied boxes and OCR rules / number windows on the card region.
     * Exports `d7_results.json` and `results.json` with explicit direct outcomes:
     * "clean", "failed", or "skipped".
     */
    @Test
    fun exportD7VerificationResults() = runBlocking {
        val input = File(evaluationRoot(), "eval-d7-input").takeIf { it.exists() && it.listFiles()?.isNotEmpty() == true }
            ?: File(evaluationRoot(), "d7-input")
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }
        requireImages(images.toList(), input)
        val output = File(evaluationRoot(), "eval-d7-output").apply { deleteRecursively(); mkdirs() }

        val rules = FieldRules.load(app.assets)
        val ocr = MlKitOcrEngine()
        val timer = StageTimer()
        val recheck = TimedRecheck(rules, ocr, timer)
        val results = JSONArray()

        try {
            for (file in images) {
                val id = file.nameWithoutExtension
                val bytes = file.readBytes()
                val boxes = parseBoxes(File(input, "$id.boxes.json").takeIf { it.exists() } ?: File(input, "$id.json").takeIf { it.exists() })
                val region = parseRegion(File(input, "$id.json").takeIf { it.exists() })
                timer.reset()
                val started = System.nanoTime()
                val passed = recheck.passes(bytes, boxes, region, emptySet())
                val totalMs = (System.nanoTime() - started) / 1e6
                val outcome = if (passed) "clean" else "failed"

                results.put(
                    JSONObject()
                        .put("id", id)
                        .put("recheck_outcome", outcome)
                        .put("recheck_passed", passed)
                        .put("recheck_decision", outcome)
                        .put("verification_outcome", outcome)
                        .put("boxes", boxesJson(boxes))
                        .put("timings_ms", JSONObject(mapOf("recheck" to Math.round(totalMs), "total" to Math.round(totalMs))))
                        .put("peak_memory_mb", Math.round(timer.peakBytes / 1e6)),
                )
                Log.i(TAG, "D7 verification $id: $outcome in ${Math.round(totalMs)} ms")
            }

            val d7Payload = JSONObject()
                .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                .put("images", results)
                .toString(1)

            File(output, "d7_results.json").writeText(d7Payload)
            File(output, "results.json").writeText(d7Payload)
            File(evaluationRoot(), "eval-output").let { evalOut ->
                if (evalOut.exists()) {
                    File(evalOut, "d7_results.json").writeText(d7Payload)
                }
            }
        } finally {
            ocr.close()
        }
    }

    private fun parseBoxes(file: File?): List<RedactionBox> {
        if (file == null || !file.exists()) return emptyList()
        val boxes = mutableListOf<RedactionBox>()
        try {
            val root = JSONObject(file.readText())
            if (root.has("boxes")) {
                val arr = root.getJSONArray("boxes")
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val fieldType = try {
                        FieldType.fromId(b.getString("field_type"))
                    } catch (e: Exception) {
                        FieldType.USER_BOX
                    }
                    val poly = b.getJSONArray("polygon")
                    val corners = (0 until 4).flatMap { idx ->
                        val pt = poly.getJSONArray(idx)
                        listOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                    }
                    boxes += RedactionBox(fieldType, corners, 1.0f)
                }
            } else if (root.has("redaction")) {
                val redaction = root.getJSONObject("redaction")
                val boxesArr = redaction.optJSONArray("redaction_boxes")
                if (boxesArr != null) {
                    for (i in 0 until boxesArr.length()) {
                        val poly = boxesArr.getJSONArray(i)
                        val corners = (0 until 4).flatMap { idx ->
                            val pt = poly.getJSONArray(idx)
                            listOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                        }
                        boxes += RedactionBox(FieldType.USER_BOX, corners, 1.0f)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse boxes from ${file.name}", e)
        }
        return boxes
    }

    private fun parseRegion(file: File?): RectF? {
        if (file == null || !file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val corners = root.optJSONArray("card_corners")
            if (corners != null && corners.length() == 4) {
                val xy = (0 until 4).flatMap { idx ->
                    val pt = corners.getJSONArray(idx)
                    listOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                Recheck.region(CardCorners(xy))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun boxesJson(boxes: List<RedactionBox>) = JSONArray(
        boxes.map { b -> JSONObject().put("field_type", b.fieldType.id).put("confidence", b.confidence.toDouble()).put("polygon", JSONArray(b.corners.chunked(2).map { JSONArray(it) })) },
    )

    /** Milliseconds per stage for one image, and the highest memory use seen at a stage's end. */
    private class StageTimer {
        private val ms = linkedMapOf<String, Double>()
        var peakBytes = 0L
            private set

        fun reset() {
            ms.clear()
            peakBytes = 0
        }

        fun snapshot(): Map<String, Double> = LinkedHashMap(ms)

        fun <T> time(stage: String, block: () -> T): T {
            val start = System.nanoTime()
            try {
                return block()
            } finally {
                record(stage, start)
            }
        }

        suspend fun <T> timeSuspend(stage: String, block: suspend () -> T): T {
            val start = System.nanoTime()
            try {
                return block()
            } finally {
                record(stage, start)
            }
        }

        private fun record(stage: String, start: Long) {
            ms[stage] = (ms[stage] ?: 0.0) + (System.nanoTime() - start) / 1e6
            // Bitmaps live in the native heap; the Java heap holds the rest.
            val runtime = Runtime.getRuntime()
            peakBytes = maxOf(peakBytes, Debug.getNativeHeapAllocatedSize() + runtime.totalMemory() - runtime.freeMemory())
        }
    }

    /** MediaStore, listing only the image under test and timing every decode. */
    private class TimedSource(
        private val real: ImageSource,
        private val timer: StageTimer,
        private val list: () -> List<GalleryImage>,
    ) : ImageSource by real {
        override suspend fun listImages() = list()
        override fun loadForDetection(mediaStoreId: Long, maxSide: Int) = timer.time("decode") { real.loadForDetection(mediaStoreId, maxSide) }
        override fun loadForOcr(mediaStoreId: Long, maxSide: Int): OcrImage? = timer.time("decode") { real.loadForOcr(mediaStoreId, maxSide) }
        override fun loadFullResolution(mediaStoreId: Long): Bitmap? = timer.time("decode") { real.loadFullResolution(mediaStoreId) }
    }

    private class TimedRecheck(rules: FieldRules, ocr: MlKitOcrEngine, private val timer: StageTimer) : Recheck(rules, ocr) {
        override suspend fun numberWindows(original: Bitmap, region: RectF?) = timer.timeSuspend("recheck") { super.numberWindows(original, region) }
        override suspend fun passes(jpeg: ByteArray, boxes: List<RedactionBox>, region: RectF?, windows: Set<String>) =
            timer.timeSuspend("recheck") { super.passes(jpeg, boxes, region, windows) }
    }

    private companion object {
        const val TAG = "BatchExport"
    }
}
