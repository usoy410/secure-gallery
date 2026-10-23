package com.sudo.securegallery.detect

import com.sudo.securegallery.data.DocumentClass

/** A point in original-image pixels. */
data class Corner(val x: Float, val y: Float)

/**
 * One detected document (§8.2): class, confidence, and the card's four corners in the
 * original image, ordered top-left, top-right, bottom-right, bottom-left.
 */
data class Detection(
    val documentClass: DocumentClass,
    val confidence: Float,
    val box: FloatArray, // left, top, right, bottom in original-image pixels
    val corners: List<Corner>,
) {
    override fun equals(other: Any?) = other is Detection && documentClass == other.documentClass &&
        confidence == other.confidence && box.contentEquals(other.box) && corners == other.corners

    override fun hashCode() = 31 * (31 * documentClass.hashCode() + confidence.hashCode()) + corners.hashCode()
}

/**
 * How an image was fitted into the square model input: scaled by [scale], then padded by
 * [padX] / [padY] on the left and top (letterbox).
 */
data class Letterbox(
    val originalWidth: Int,
    val originalHeight: Int,
    val inputSize: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
) {
    fun toOriginal(x: Float, y: Float) = Corner(
        ((x - padX) / scale).coerceIn(0f, originalWidth.toFloat()),
        ((y - padY) / scale).coerceIn(0f, originalHeight.toFloat()),
    )

    companion object {
        /** Fits a [width] x [height] image into a square of [inputSize], keeping its aspect ratio. */
        fun fit(width: Int, height: Int, inputSize: Int): Letterbox {
            val scale = minOf(inputSize.toFloat() / width, inputSize.toFloat() / height)
            val w = width * scale
            val h = height * scale
            return Letterbox(width, height, inputSize, scale, (inputSize - w) / 2f, (inputSize - h) / 2f)
        }
    }
}
