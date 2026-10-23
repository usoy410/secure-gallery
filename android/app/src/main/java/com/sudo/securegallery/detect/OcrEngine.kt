package com.sudo.securegallery.detect

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A detected word with bounding coordinates in image space (§8.2).
 *
 * Placed in ephemeral memory only; never stored on disk or in the database.
 *
 * @property text Recognized token text.
 * @property left Left bounding coordinate.
 * @property top Top bounding coordinate.
 * @property right Right bounding coordinate.
 * @property bottom Bottom bounding coordinate.
 * @property start Character start index relative to its line.
 */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val start: Int = 0,
)

/**
 * One line of detected text containing tokenized words (§8.2).
 *
 * @property text Complete line text.
 * @property words Word tokens with localized bounding boxes.
 * @property blockIndex Structural paragraph or block group index.
 * @property lineIndex Sequence index of this line within its block.
 */
data class OcrLine(
    val text: String,
    val words: List<OcrWord>,
    val blockIndex: Int = 0,
    val lineIndex: Int = 0,
)

/**
 * Interface abstracting on-device optical character recognition (§8.2, §8.8).
 *
 * Decouples the hybrid localizer from the ML Kit library, allowing full JVM unit testing.
 */
interface OcrEngine {
    /**
     * Extracts text lines and word bounding boxes from an input bitmap.
     *
     * @param bitmap Input cropped region or full-resolution document bitmap.
     * @return List of extracted lines with word-level bounding coordinates.
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
}

/**
 * Production [OcrEngine] using Google ML Kit Text Recognition v2 bundled Latin model (§8.2, §8.8).
 *
 * Runs fully on-device without internet permissions or Google Play Services dependencies.
 */
class MlKitOcrEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : OcrEngine, Closeable {

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = processImage(inputImage)
        return mapVisionText(visionText)
    }

    private suspend fun processImage(image: InputImage): Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (continuation.isActive) continuation.resume(text)
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
                .addOnCanceledListener {
                    continuation.cancel()
                }
        }

    private fun mapVisionText(text: Text): List<OcrLine> {
        val result = mutableListOf<OcrLine>()
        for ((blockIdx, block) in text.textBlocks.withIndex()) {
            for ((lineIdx, line) in block.lines.withIndex()) {
                val words = mutableListOf<OcrWord>()
                for (element in line.elements) {
                    val box = element.boundingBox ?: line.boundingBox
                    if (box != null) {
                        words.add(
                            OcrWord(
                                text = element.text,
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat(),
                            ),
                        )
                    }
                }
                if (words.isNotEmpty()) {
                    result.add(
                        OcrLine(
                            text = line.text,
                            words = words,
                            blockIndex = blockIdx,
                            lineIndex = lineIdx,
                        ),
                    )
                }
            }
        }
        return result
    }

    override fun close() {
        recognizer.close()
    }
}
