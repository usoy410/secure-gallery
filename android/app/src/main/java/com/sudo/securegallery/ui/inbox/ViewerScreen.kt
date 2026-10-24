package com.sudo.securegallery.ui.inbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sudo.securegallery.R
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.ui.SecureWindow

/** What the viewer's buttons do. */
class ViewerActions(
    val toggle: () -> Unit = {},
    val edit: () -> Unit = {},
    val close: () -> Unit = {},
    val delete: () -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val cancelDelete: () -> Unit = {},
    val restore: () -> Unit = {},
    val confirmRestore: () -> Unit = {},
    val cancelRestore: () -> Unit = {},
)

/**
 * The viewer with the eye toggle (§8.3, #23): the redacted copy by default, the original after
 * a tap on the eye while the vault is unlocked. While locked the eye shows a lock and a tap asks
 * for an unlock. Pinch to zoom, drag to pan. `FLAG_SECURE` is set while the original is shown.
 * The delete button (#77) warns first that the photo cannot come back. "Not sensitive" (#25)
 * asks once, then puts the original back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    state: ViewerState,
    unlocked: Boolean,
    actions: ViewerActions,
    modifier: Modifier = Modifier,
    deleteWarning: Boolean = false,
    restoreConfirm: Boolean = false,
) {
    if (deleteWarning) DeleteWarning(actions)
    if (restoreConfirm) RestoreConfirm(actions)
    val showing = state.original?.takeIf { state.showOriginal && unlocked }
    if (showing != null) SecureWindow()
    val toggleLabel = stringResource(
        when {
            !unlocked -> R.string.viewer_unlock_to_show
            showing != null -> R.string.viewer_show_redacted
            else -> R.string.viewer_show_original
        },
    )
    val stateLabel = stringResource(if (showing != null) R.string.viewer_showing_original else R.string.viewer_showing_redacted)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.item.documentClass.label)) },
                navigationIcon = {
                    IconButton(onClick = actions.close) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.viewer_back))
                    }
                },
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = actions.toggle,
                            modifier = Modifier.semantics {
                                stateDescription = stateLabel
                                liveRegion = LiveRegionMode.Polite
                            },
                        ) {
                            Icon(
                                when {
                                    !unlocked -> Icons.Outlined.Lock
                                    showing != null -> Icons.Outlined.VisibilityOff
                                    else -> Icons.Outlined.Visibility
                                },
                                contentDescription = toggleLabel,
                            )
                        }
                    }
                    IconButton(onClick = actions.edit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.viewer_edit_boxes))
                    }
                    IconButton(onClick = actions.delete) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.viewer_delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stateLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ZoomableImage(state, showing ?: state.redacted, stateLabel)
            }
            OutlinedButton(
                onClick = actions.restore,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.viewer_not_sensitive))
            }
            if (state.item.state == ItemState.VAULTED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        stringResource(R.string.viewer_cloud_backup_note),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteWarning(actions: ViewerActions) {
    AlertDialog(
        onDismissRequest = actions.cancelDelete,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.viewer_delete_title)) },
        text = { Text(stringResource(R.string.viewer_delete_body)) },
        confirmButton = {
            TextButton(onClick = actions.confirmDelete) {
                Text(stringResource(R.string.viewer_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = actions.cancelDelete) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
private fun RestoreConfirm(actions: ViewerActions) {
    AlertDialog(
        onDismissRequest = actions.cancelRestore,
        title = { Text(stringResource(R.string.viewer_restore_title)) },
        text = { Text(stringResource(R.string.viewer_restore_body)) },
        confirmButton = { TextButton(onClick = actions.confirmRestore) { Text(stringResource(R.string.viewer_restore_confirm)) } },
        dismissButton = { TextButton(onClick = actions.cancelRestore) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Pinch to zoom (1x to 5x) and pan; the zoom stays when the eye toggle switches images. */
@Composable
private fun ZoomableImage(state: ViewerState, bitmap: android.graphics.Bitmap, description: String) {
    var scale by remember(state.item.id) { mutableFloatStateOf(1f) }
    var offset by remember(state.item.id) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        image,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .transformable(transform)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

private const val MAX_ZOOM = 5f
