package com.sudo.securegallery.ui.inbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sudo.securegallery.R
import com.sudo.securegallery.ui.SecureWindow

/** What the editor's gestures and buttons do; coordinates are original-image pixels. */
class EditorActions(
    val select: (Int?) -> Unit = {},
    val move: (Float, Float) -> Unit = { _, _ -> },
    val moveCorner: (Int, Float, Float) -> Unit = { _, _, _ -> },
    val endGesture: () -> Unit = {},
    val add: () -> Unit = {},
    val delete: () -> Unit = {},
    val save: () -> Unit = {},
    val cancel: () -> Unit = {},
)

/**
 * The box editor (#24): the original with every box outlined over it. Tap a box to select it,
 * drag it to move, drag one of its corner handles to resize. Handles take a 48 dp touch target.
 * The screen shows the original, so it sets `FLAG_SECURE` (§8.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxEditorScreen(state: EditorState, actions: EditorActions, modifier: Modifier = Modifier) {
    SecureWindow()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                actions = {
                    TextButton(onClick = actions.cancel, enabled = !state.saving) { Text(stringResource(R.string.editor_cancel)) }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.add, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.editor_add))
                    }
                    OutlinedButton(
                        onClick = actions.delete,
                        enabled = !state.saving && state.selected != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Text(stringResource(R.string.editor_delete))
                    }
                    Button(onClick = actions.save, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.editor_save))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.editor_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            BoxCanvas(state, actions, Modifier.fillMaxSize().padding(8.dp))
        }
    }
}

@Composable
private fun BoxCanvas(state: EditorState, actions: EditorActions, modifier: Modifier) {
    val image = remember(state.preview) { state.preview.asImageBitmap() }
    val current by rememberUpdatedState(state)
    val handleRadius = with(LocalDensity.current) { HANDLE_TOUCH_DP.dp.toPx() / 2 }
    val description = stringResource(R.string.editor_image)
    val selectedColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val fit = Fit(current, size.width.toFloat(), size.height.toFloat())
                    val (x, y) = fit.toImage(tap)
                    actions.select(current.edits.boxes.indexOfLast { contains(it.corners, x, y) }.takeIf { it >= 0 })
                }
            }
            .pointerInput(Unit) {
                var corner: Int? = null
                var moving = false
                var pointer = Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        pointer = start
                        val fit = Fit(current, size.width.toFloat(), size.height.toFloat())
                        corner = current.selected?.let { i ->
                            val c = current.edits.boxes[i].corners
                            (0 until 4).firstOrNull { k -> (fit.toScreen(c[2 * k], c[2 * k + 1]) - start).getDistance() <= handleRadius }
                        }
                        moving = false
                        if (corner == null) {
                            val (x, y) = fit.toImage(start)
                            val hit = current.edits.boxes.indexOfLast { contains(it.corners, x, y) }
                            if (hit >= 0) {
                                actions.select(hit)
                                moving = true
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                        val fit = Fit(current, size.width.toFloat(), size.height.toFloat())
                        val c = corner
                        if (c != null) {
                            val (x, y) = fit.toImage(pointer)
                            actions.moveCorner(c, x, y)
                        } else if (moving) {
                            actions.move(amount.x / fit.scale, amount.y / fit.scale)
                        }
                    },
                    onDragEnd = { actions.endGesture() },
                    onDragCancel = { actions.endGesture() },
                )
            },
    ) {
        val fit = Fit(state, size.width, size.height)
        drawImage(
            image,
            dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
            dstSize = IntSize((state.edits.width * fit.scale).toInt(), (state.edits.height * fit.scale).toInt()),
        )
        state.edits.boxes.forEachIndexed { i, box ->
            val c = box.corners
            val path = Path().apply {
                moveTo(fit.toScreen(c[0], c[1]).x, fit.toScreen(c[0], c[1]).y)
                for (k in 1 until 4) fit.toScreen(c[2 * k], c[2 * k + 1]).let { lineTo(it.x, it.y) }
                close()
            }
            val selected = i == state.selected
            val color = if (selected) selectedColor else BOX_COLOR
            drawPath(path, color.copy(alpha = 0.35f))
            drawPath(path, color, style = Stroke(width = (if (selected) 3 else 2).dp.toPx()))
            if (selected) {
                for (k in 0 until 4) {
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = fit.toScreen(c[2 * k], c[2 * k + 1]))
                    drawCircle(color, radius = 7.dp.toPx(), center = fit.toScreen(c[2 * k], c[2 * k + 1]))
                }
            }
        }
    }
}

/** The image fitted and centred in the canvas: image pixels to screen and back. */
private class Fit(state: EditorState, width: Float, height: Float) {
    val scale = minOf(width / state.edits.width, height / state.edits.height)
    val left = (width - state.edits.width * scale) / 2
    val top = (height - state.edits.height * scale) / 2

    fun toScreen(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)

    fun toImage(p: Offset) = (p.x - left) / scale to (p.y - top) / scale
}

/** True if ([x], [y]) is inside the convex quadrilateral [c], whichever way it winds. */
internal fun contains(c: List<Float>, x: Float, y: Float): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val ax = c[2 * i]; val ay = c[2 * i + 1]
        val bx = c[(2 * i + 2) % 8]; val by = c[(2 * i + 3) % 8]
        val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s != 0) {
            if (sign == 0) sign = s else if (s != sign) return false
        }
    }
    return true
}

private const val HANDLE_TOUCH_DP = 48
private val BOX_COLOR = Color(0xFFE53935)
