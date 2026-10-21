package com.sudo.securegallery.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudo.securegallery.ui.inbox.InboxScreen
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for [InboxScreen] covering both Light and Dark themes.
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
}
