package com.sudo.securegallery.ui.inbox

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sudo.securegallery.R
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The gallery grid on the scan tab (#77): every processed photo, newest first. Thumbnails come
 * from the public redacted copy only, through [thumbnail]; an original never appears here.
 * Tapping one opens the viewer with the eye toggle (#23).
 */
@Composable
fun GalleryGrid(
    items: List<Item>,
    onOpen: (Item) -> Unit,
    thumbnail: suspend (Item) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.gallery_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.gallery_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.fillMaxWidth()) {
            items(items, key = { it.id }) { item -> Cell(item, onOpen, thumbnail) }
        }
    }
}

@Composable
private fun Cell(item: Item, onOpen: (Item) -> Unit, thumbnail: suspend (Item) -> Bitmap?) {
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(item) },
    ) {
        RedactedPreview(item, thumbnail, Modifier.fillMaxSize())
        if (item.state == ItemState.NEEDS_CHECK) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.inbox_state_needs_check),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(2.dp)
                    .size(18.dp),
            )
        }
    }
}

/** Shared by the review list and grid. Only the supplied redacted-copy loader is used. */
@Composable
internal fun RedactedPreview(
    item: Item,
    thumbnail: suspend (Item) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(item.id, item.redactedMediaStoreId, item.boxes) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(item.id, item.redactedMediaStoreId, item.boxes) { bitmap = thumbnail(item) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), stringResource(R.string.gallery_thumbnail),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.HideImage, stringResource(R.string.gallery_preview_unavailable),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
    }
}

/** A small thumbnail of [item]'s public redacted copy, or null. Kept in memory only. */
suspend fun redactedThumbnail(resolver: ContentResolver, item: Item): Bitmap? {
    val id = item.redactedMediaStoreId ?: return null
    return withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        try {
            resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
        } catch (_: java.io.IOException) {
            decodeRedactedThumbnail(resolver, uri)
        } catch (_: SecurityException) {
            decodeRedactedThumbnail(resolver, uri)
        }
    }
}

// Some providers can open the saved copy but cannot generate a thumbnail.
// Decode that same redacted URI at bounded size, never the original URI.
private fun decodeRedactedThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val scale = minOf(1f, THUMB_PX.toFloat() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()),
            maxOf(1, (info.size.height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (_: java.io.IOException) {
    null
} catch (_: SecurityException) {
    null
}

private const val THUMB_PX = 320
