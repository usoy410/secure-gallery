package com.sudo.securegallery.detect

/**
 * Builds the model input from an already downsampled image: letterbox into a square of
 * [Letterbox.inputSize] with grey padding (114, as YOLO trains with), scale to 0..1, and lay out
 * as NCHW float (all red, then all green, then all blue).
 *
 * @param argb pixels of the [width] x [height] image, as `Bitmap.getPixels` returns them.
 */
object Preprocess {
    private const val PAD = 114f / 255f

    fun toInput(argb: IntArray, width: Int, height: Int, letterbox: Letterbox): FloatArray {
        val size = letterbox.inputSize
        val plane = size * size
        val out = FloatArray(3 * plane) { PAD }
        // The image is drawn at scale relative to the original; here it is already at most
        // `size` on its long side, so map each input pixel back to the nearest source pixel.
        val drawnW = (letterbox.originalWidth * letterbox.scale)
        val drawnH = (letterbox.originalHeight * letterbox.scale)
        val x0 = letterbox.padX
        val y0 = letterbox.padY
        for (y in 0 until size) {
            val sy = ((y + 0.5f - y0) / drawnH * height).toInt()
            if (y + 0.5f < y0 || sy !in 0 until height) continue
            for (x in 0 until size) {
                val sx = ((x + 0.5f - x0) / drawnW * width).toInt()
                if (x + 0.5f < x0 || sx !in 0 until width) continue
                val p = argb[sy * width + sx]
                val i = y * size + x
                out[i] = ((p shr 16) and 0xFF) / 255f
                out[plane + i] = ((p shr 8) and 0xFF) / 255f
                out[2 * plane + i] = (p and 0xFF) / 255f
            }
        }
        return out
    }
}
