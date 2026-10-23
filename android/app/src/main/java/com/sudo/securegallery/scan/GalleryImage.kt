package com.sudo.securegallery.scan

import com.sudo.securegallery.detect.DetectorImage
import java.io.InputStream

/** One public gallery image as MediaStore reports it. No pixels, no file name. */
data class GalleryImage(
    val mediaStoreId: Long,
    val dateModified: Long,
    val sizeBytes: Long,
)

/** Where the scan reads images from: MediaStore in the app, a fake in tests. */
interface ImageSource {
    /** Every image the scan should index, oldest first. */
    suspend fun listImages(): List<GalleryImage>

    /** The image's bytes, or null if it no longer exists. */
    fun open(mediaStoreId: Long): InputStream?

    /**
     * The image decoded so its long side is at most [maxSide] pixels, for the detector. Never
     * decodes at full resolution (§8.2). Null if it cannot be decoded.
     */
    fun loadForDetection(mediaStoreId: Long, maxSide: Int): DetectorImage? = null
}
