package com.sudo.securegallery.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudo.securegallery.setup.SetupStep
import com.sudo.securegallery.ui.setup.PinError
import com.sudo.securegallery.ui.setup.SetupActions
import com.sudo.securegallery.ui.setup.SetupScreen
import com.sudo.securegallery.ui.setup.SetupUiState
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every first-run setup screen, light and dark (issue #28). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class SetupScreenshotTest(private val name: String, private val step: SetupStep, private val state: SetupUiState) {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)

    private fun capture(dark: Boolean) {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = dark) { SetupScreen(step, state.copy(step = step), SetupActions()) }
        }
        val suffix = if (dark) "_dark" else ""
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/setup_$name$suffix.png")
    }

    companion object {
        private val base = SetupUiState(biometricAvailable = true)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = listOf(
            arrayOf("1_welcome", SetupStep.WELCOME, base),
            arrayOf("2_photos", SetupStep.PHOTOS, base),
            arrayOf("3_pin", SetupStep.PIN, base.copy(pin = "482915", pinConfirm = "4829")),
            arrayOf("3_pin_mismatch", SetupStep.PIN, base.copy(pin = "482915", pinConfirm = "482916", pinError = PinError.MISMATCH)),
            // A made-up code for the picture; real codes are random.
            arrayOf("4_recovery", SetupStep.RECOVERY, base.copy(recoveryCode = "7K3M-Q9TD-2XWB-H5RN-8CFJ-4VPZ")),
            arrayOf("5_biometric", SetupStep.BIOMETRIC, base),
            arrayOf("6_notice", SetupStep.NOTICE, base),
        )
    }
}
