package com.sudo.securegallery.ui.inbox

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
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
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.redactedMediaStoreId, item.boxes) { bitmap = thumbnail(item) }
    val description = stringResource(R.string.gallery_thumbnail)
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(item) },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
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

/** A small thumbnail of [item]'s public redacted copy, or null. Kept in memory only. */
suspend fun redactedThumbnail(resolver: ContentResolver, item: Item): Bitmap? {
    val id = item.redactedMediaStoreId ?: return null
    return withContext(Dispatchers.IO) {
        try {
            resolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), Size(THUMB_PX, THUMB_PX), null)
        } catch (_: java.io.IOException) {
            null
        }
    }
}

private const val THUMB_PX = 320
