package com.sudo.securegallery.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.util.Log
import com.sudo.securegallery.detect.DetectorImage
import com.sudo.securegallery.localize.OcrImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Reads the public gallery through MediaStore with [ImageQuery]. */
class MediaStoreImageSource(
    private val resolver: ContentResolver,
    private val ownPackage: String,
) : ImageSource {
    private companion object {
        const val TAG = "SecureGalleryScan"
    }

    override suspend fun listImages(): List<GalleryImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<GalleryImage>()
        var rows = 0
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ImageQuery.projection,
            null,
            null,
            ImageQuery.SORT_ORDER,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val owner = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            val pending = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
            while (cursor.moveToNext()) {
                rows++
                val kept = ImageQuery.keep(
                    mimeType = cursor.getStringOrNull(mime),
                    displayName = cursor.getStringOrNull(name),
                    owner = cursor.getStringOrNull(owner),
                    pending = if (cursor.isNull(pending)) null else cursor.getInt(pending),
                    ownPackage = ownPackage,
                )
                if (kept) images += GalleryImage(cursor.getLong(id), cursor.getLong(modified), cursor.getLong(size))
            }
        }
        // Counts only: never a file name or anything else about the photos.
        Log.i(TAG, "MediaStore returned $rows images, ${images.size} kept for the scan")
        images
    }

    override fun loadForDetection(mediaStoreId: Long, maxSide: Int): DetectorImage? {
        val image = decode(mediaStoreId, maxSide) ?: return null
        return image.bitmap.toDetectorImage(image.originalWidth, image.originalHeight).also { image.bitmap.recycle() }
    }

    override fun loadForOcr(mediaStoreId: Long, maxSide: Int): OcrImage? = decode(mediaStoreId, maxSide)

    override fun loadFullResolution(mediaStoreId: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun decode(mediaStoreId: Long, maxSide: Int): OcrImage? {
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
        return OcrImage(bitmap, originalWidth, originalHeight)
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

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
