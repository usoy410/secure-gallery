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

    @Test
    fun exportPipelineResults() = runBlocking {
        val input = File(app.getExternalFilesDir(null), "eval-input")
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }
        assumeTrue("no images in $input", images.isNotEmpty())
        val output = File(app.getExternalFilesDir(null), "eval-output").apply { deleteRecursively(); mkdirs() }

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
