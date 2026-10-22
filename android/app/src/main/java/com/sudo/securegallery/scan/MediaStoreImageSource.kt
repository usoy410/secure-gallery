package com.sudo.securegallery.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
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

    override fun open(mediaStoreId: Long): InputStream? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        return try {
            resolver.openInputStream(uri)
        } catch (_: java.io.FileNotFoundException) {
            null
        }
    }
}
