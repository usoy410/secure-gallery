package com.sudo.securegallery.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import com.sudo.securegallery.detect.DetectorImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Reads the public gallery through MediaStore with [ImageQuery]. */
class MediaStoreImageSource(
    private val resolver: ContentResolver,
    private val ownPackage: String,
) : ImageSource {

    override suspend fun listImages(): List<GalleryImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<GalleryImage>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ImageQuery.projection,
            ImageQuery.selection,
            ImageQuery.selectionArgs(ownPackage),
            ImageQuery.SORT_ORDER,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                images += GalleryImage(cursor.getLong(id), cursor.getLong(modified), cursor.getLong(size))
            }
        }
        images
    }

    override fun loadForDetection(mediaStoreId: Long, maxSide: Int): DetectorImage? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        var originalWidth = 0
        var originalHeight = 0
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                originalWidth = info.size.width
                originalHeight = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(originalWidth, originalHeight))
                // The decoder subsamples while reading, so the full-size bitmap never exists.
                decoder.setTargetSize(maxOf(1, (originalWidth * scale).toInt()), maxOf(1, (originalHeight * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: java.io.IOException) {
            return null
        }
        return bitmap.toDetectorImage(originalWidth, originalHeight).also { bitmap.recycle() }
    }

    override fun open(mediaStoreId: Long): InputStream? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        return try {
            resolver.openInputStream(uri)
        } catch (_: java.io.FileNotFoundException) {
            null
        }
    }
}

/** Pixels of a decoded bitmap for the detector. EXIF rotation is already applied by ImageDecoder. */
fun Bitmap.toDetectorImage(originalWidth: Int = width, originalHeight: Int = height): DetectorImage {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)
    return DetectorImage(argb, width, height, originalWidth, originalHeight)
}
