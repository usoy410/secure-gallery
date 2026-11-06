package com.sudo.securegallery.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudo.securegallery.scan.PhotoAccess
import com.sudo.securegallery.ui.scan.ScanProgress
import com.sudo.securegallery.ui.scan.ScanScreen
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the scan screen: no access yet, scanning, done (issue #15), failed (#65). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScanScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun capture(name: String, access: PhotoAccess, progress: ScanProgress, dark: Boolean = false) {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = dark) {
                ScanScreen(access = access, progress = progress, onRequestAccess = {}, onScanNow = {})
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun scanScreen_noAccess() = capture("scan_no_access", PhotoAccess.NONE, ScanProgress())

    @Test
    fun scanScreen_scanning() =
        capture("scan_scanning", PhotoAccess.FULL, ScanProgress(found = 1240, processed = 310, running = true))

    @Test
    fun scanScreen_scanning_dark() =
        capture("scan_scanning_dark", PhotoAccess.FULL, ScanProgress(found = 1240, processed = 310, running = true), dark = true)

    @Test
    fun scanScreen_detecting() =
        capture("scan_detecting", PhotoAccess.FULL, ScanProgress.of(1681, 1681, 1341, 0, 0, running = true, failed = false))

    @Test
    fun scanScreen_done() = capture("scan_done", PhotoAccess.FULL, ScanProgress(found = 1240, processed = 1240))

    @Test
    fun scanScreen_failedRunIsNotShownAsComplete() {
        capture("scan_failed", PhotoAccess.FULL, ScanProgress(found = 1240, processed = 600, failed = true))
        composeTestRule.onNodeWithText("Scan failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan complete").assertDoesNotExist()
    }

    @Test
    fun scanScreen_doneShowsFoundAndChecked() {
        capture("scan_done_none_found", PhotoAccess.FULL, ScanProgress(found = 0, processed = 0))
        composeTestRule.onNodeWithText("0 photos indexed").assertIsDisplayed()
    }

    @Test
    fun scanScreen_separatesCheckedImagesFromCandidatesAndShowsDetails() {
        capture("scan_diagnostics", PhotoAccess.FULL, ScanProgress(
            found = 1800, processed = 1800, running = true,
            stage = com.sudo.securegallery.ui.scan.ScanStage.DETECT,
            checked = 26, candidates = 3, detectionFailures = 1, detectionTotal = 1800,
            details = "This scan • elapsed 240.00 s",
        ))
        composeTestRule.onNodeWithText("This run: 26 of 1800 images checked · 3 possible cards · Retry needed: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan details").performClick()
        composeTestRule.onNodeWithText("This scan • elapsed 240.00 s").assertIsDisplayed()
    }

    @Test
    fun scanScreen_done_selectedPhotosOnly() =
        capture("scan_done_selected", PhotoAccess.SELECTED, ScanProgress(found = 12, processed = 12))
}
