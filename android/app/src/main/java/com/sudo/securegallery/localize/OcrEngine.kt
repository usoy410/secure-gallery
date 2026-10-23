package com.sudo.securegallery.localize

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads text from a bitmap. The result stays in memory and is dropped after matching (§8.4). */
fun interface OcrEngine {
    /** Lines with word boxes in [bitmap] pixels. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
}

/** ML Kit Text Recognition v2 with the bundled Latin model: no download, works offline (§8.6). */
class MlKitOcrEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : OcrEngine, AutoCloseable {

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                // ML Kit's message never holds recognized text, but keep only the type to be sure.
                .addOnFailureListener { cont.resumeWithException(OcrFailedException(it::class.java.simpleName)) }
        }
        return text.textBlocks.flatMapIndexed { b, block ->
            block.lines.mapIndexed { l, line ->
                OcrLine(
                    block = b,
                    line = l,
                    words = line.elements.mapNotNull { e ->
                        val box = e.boundingBox ?: return@mapNotNull null
                        OcrWord(e.text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                    },
                )
            }
        }
    }

    override fun close() = recognizer.close()
}

class OcrFailedException(cause: String) : RuntimeException("text recognition failed: $cause")
