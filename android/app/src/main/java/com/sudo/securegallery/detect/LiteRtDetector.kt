package com.sudo.securegallery.detect

import android.content.res.AssetManager
import com.sudo.securegallery.data.DocumentClass
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs the YOLO11n-pose detector from `assets/detector.tflite` with LiteRT, on the GPU when the
 * phone supports it and on the CPU otherwise (§8.6). Not thread-safe: the scan runs one image
 * at a time.
 */
class LiteRtDetector private constructor(
    private val interpreter: Interpreter,
    private val gpu: GpuDelegate?,
    val config: DetectorConfig,
) : DocumentDetector, AutoCloseable {
    private val postProcessor = YoloPosePostProcessor(config)
    private val input = ByteBuffer.allocateDirect(3 * config.inputSize * config.inputSize * 4).order(ByteOrder.nativeOrder())
    private val outputFloats = interpreter.getOutputTensor(0).numElements()
    private val output = ByteBuffer.allocateDirect(outputFloats * 4).order(ByteOrder.nativeOrder())

    /** "GPU" or "CPU", for the performance report (#32). */
    val accelerator: String get() = if (gpu != null) "GPU" else "CPU"

    override fun detect(image: DetectorImage): List<Detection> {
        val letterbox = Letterbox.fit(image.originalWidth, image.originalHeight, config.inputSize)
        input.rewind()
        input.asFloatBuffer().put(Preprocess.toInput(image.argb, image.width, image.height, letterbox))
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val raw = FloatArray(outputFloats).also { output.asFloatBuffer().get(it) }
        return postProcessor.process(raw, letterbox)
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
    }

    companion object {
        const val MODEL = "detector.tflite"
        const val METADATA = "detector.json"
        const val THRESHOLDS = "detection.json"
        private const val MIN_MODEL_BYTES = 100_000L // an LFS pointer is about 130 bytes

        /**
         * Null when the app was built without a usable model, for example a clone without Git
         * LFS, which has a small pointer file in its place. Detection is then skipped.
         */
        fun open(assets: AssetManager): LiteRtDetector? {
            if (MODEL !in assets.list("").orEmpty()) return null
            if (assets.openFd(MODEL).use { it.declaredLength } < MIN_MODEL_BYTES) return null
            val config = loadConfig(assets)
            val model = map(assets)
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                val gpu = GpuDelegate(compat.bestOptionsForThisDevice)
                try {
                    return LiteRtDetector(Interpreter(model, Interpreter.Options().addDelegate(gpu)), gpu, config)
                } catch (_: Exception) {
                    // The GPU could not run every op: fall back to the CPU.
                    gpu.close()
                }
            }
            return LiteRtDetector(Interpreter(model, Interpreter.Options().setNumThreads(4)), null, config)
        }

        /** Memory-maps the model straight from the APK (it is stored uncompressed). */
        private fun map(assets: AssetManager): MappedByteBuffer = assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

        fun loadConfig(assets: AssetManager): DetectorConfig {
            val meta = JSONObject(assets.open(METADATA).bufferedReader().use { it.readText() })
            val thresholds = JSONObject(assets.open(THRESHOLDS).bufferedReader().use { it.readText() })
            return parseConfig(meta, thresholds)
        }

        fun parseConfig(meta: JSONObject, thresholds: JSONObject): DetectorConfig {
            val names = meta.getJSONArray("class_names")
            val defaults = DetectorConfig()
            return DetectorConfig(
                inputSize = meta.getJSONArray("input_size").getInt(0),
                classes = List(names.length()) { DocumentClass.fromId(names.getString(it)) },
                numKeypoints = meta.getInt("num_keypoints"),
                minConfidence = thresholds.optDouble("not_detected_below", defaults.minConfidence.toDouble()).toFloat(),
                highConfidence = thresholds.optDouble("high_at_or_above", defaults.highConfidence.toDouble()).toFloat(),
                nmsIou = thresholds.optDouble("nms_iou", defaults.nmsIou.toDouble()).toFloat(),
                maxDetections = thresholds.optInt("max_detections", defaults.maxDetections),
                normalizedCoordinates = meta.optString("output_coordinates", "normalized") == "normalized",
            )
        }
    }
}
