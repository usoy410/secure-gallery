package com.sudo.securegallery.detect

/** An image already decoded at detection size, with the size of the original file. */
class DetectorImage(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

/** Finds supported documents in one image (§8.2, first stage). */
fun interface DocumentDetector {
    fun detect(image: DetectorImage): List<Detection>
}
