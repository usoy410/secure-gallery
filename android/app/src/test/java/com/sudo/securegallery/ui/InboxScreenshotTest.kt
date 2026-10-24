package com.sudo.securegallery.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.redact.BoxEdits
import com.sudo.securegallery.ui.inbox.BoxEditorScreen
import com.sudo.securegallery.ui.inbox.EditorActions
import com.sudo.securegallery.ui.inbox.EditorState
import com.sudo.securegallery.ui.inbox.InboxScreen
import com.sudo.securegallery.ui.inbox.ViewerActions
import com.sudo.securegallery.ui.inbox.ViewerScreen
import com.sudo.securegallery.ui.inbox.ViewerState
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for [InboxScreen] covering both Light and Dark themes, the inbox with mixed
 * states, the box editor (#24) and the viewer with the eye toggle (#23). The editor's image is a plain synthetic card shape.
 *
 * Runs on JVM using Robolectric Native Graphics and Roborazzi for fast, emulator-free screenshot verification.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class InboxScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun inboxScreen_lightTheme_emptyState() {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = false) {
                InboxScreen()
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/inbox_light.png")
    }

    @Test
    fun inboxScreen_darkTheme_emptyState() {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = true) {
                InboxScreen()
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/inbox_dark.png")
    }

    private fun box(type: FieldType, x: Float, y: Float, w: Float, h: Float) =
        RedactionBox(type, listOf(x, y, x + w, y, x + w, y + h, x, y + h), 0.9f)

    private val mixed = listOf(
        Item(id = 3, mediaStoreId = 3, dateModified = 1, sizeBytes = 1, state = ItemState.NEEDS_CHECK, documentClass = DocumentClass.PHILSYS, boxes = listOf(box(FieldType.WHOLE_DOCUMENT, 0f, 0f, 10f, 10f)), redactedMediaStoreId = 30),
        Item(id = 2, mediaStoreId = 2, dateModified = 1, sizeBytes = 1, state = ItemState.VAULTED, documentClass = DocumentClass.LTO, boxes = List(5) { box(FieldType.NAME, 0f, 0f, 10f, 10f) }, redactedMediaStoreId = 20),
        Item(id = 1, mediaStoreId = 1, dateModified = 1, sizeBytes = 1, state = ItemState.REDACTED, documentClass = DocumentClass.PAYMENT_CARD, boxes = List(3) { box(FieldType.CARD_NUMBER, 0f, 0f, 10f, 10f) }, redactedMediaStoreId = 10),
        Item(id = 4, mediaStoreId = 4, dateModified = 1, sizeBytes = 1, state = ItemState.ORIGINAL_STILL_PUBLIC, documentClass = DocumentClass.PHILSYS, boxes = List(4) { box(FieldType.PCN, 0f, 0f, 10f, 10f) }, redactedMediaStoreId = 40),
    )

    @Test
    fun inboxScreen_lightTheme_mixedStates() {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = false) {
                InboxScreen(items = mixed)
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/inbox_mixed_light.png")
    }

    @Test
    fun inboxScreen_darkTheme_mixedStates() {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = true) {
                InboxScreen(items = mixed)
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/inbox_mixed_dark.png")
    }

    @Test
    fun boxEditor_lightTheme_selectedBox() {
        val card = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(220, 232, 245))
            Canvas(this).drawRect(40f, 120f, 240f, 400f, Paint().apply { color = android.graphics.Color.rgb(180, 190, 200) })
        }
        val boxes = listOf(box(FieldType.FACE_PHOTO, 40f, 120f, 200f, 280f), box(FieldType.PCN, 300f, 140f, 380f, 50f), box(FieldType.USER_BOX, 300f, 260f, 260f, 50f))
        val state = EditorState(
            Item(id = 1, mediaStoreId = 1, dateModified = 1, sizeBytes = 1, state = ItemState.NEEDS_CHECK, boxes = boxes),
            card,
            BoxEdits(boxes, 800f, 500f),
            selected = 1,
        )
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = false) {
                BoxEditorScreen(state, EditorActions())
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/box_editor_light.png")
    }

    /** A synthetic card: light background, grey photo area, dark text bars. */
    private fun card(redacted: Boolean) = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.rgb(220, 232, 245))
        val canvas = Canvas(this)
        val grey = Paint().apply { color = android.graphics.Color.rgb(150, 160, 170) }
        canvas.drawRect(40f, 120f, 240f, 400f, grey)
        for (row in 0 until 3) canvas.drawRect(300f, 150f + 90f * row, 700f, 180f + 90f * row, grey)
        if (redacted) {
            val black = Paint().apply { color = android.graphics.Color.BLACK }
            canvas.drawRect(40f, 120f, 240f, 400f, black)
            canvas.drawRect(290f, 140f, 710f, 190f, black)
        }
    }

    private val vaulted = Item(id = 9, mediaStoreId = 9, dateModified = 1, sizeBytes = 1, state = ItemState.VAULTED, documentClass = DocumentClass.PHILSYS, redactedMediaStoreId = 90)

    private fun viewer(state: ViewerState, unlocked: Boolean, name: String) {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = false) {
                ViewerScreen(state, unlocked, ViewerActions())
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun viewer_redactedCopy() = viewer(ViewerState(vaulted, card(redacted = true)), unlocked = true, "viewer_redacted")

    @Test
    fun viewer_original() =
        viewer(ViewerState(vaulted, card(redacted = true), card(redacted = false), showOriginal = true), unlocked = true, "viewer_original")

    @Test
    fun viewer_lockedPrompt() = viewer(ViewerState(vaulted, card(redacted = true)), unlocked = false, "viewer_locked")
}
