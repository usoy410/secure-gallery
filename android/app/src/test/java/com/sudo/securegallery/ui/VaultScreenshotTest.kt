package com.sudo.securegallery.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.sudo.securegallery.ui.vault.TransferMessage
import com.sudo.securegallery.ui.vault.VaultMessage
import com.sudo.securegallery.ui.vault.VaultScreen
import com.sudo.securegallery.ui.vault.VaultUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the vault: lock screen, PIN entry, error states, unlocked (issue #21). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class VaultScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val locked = VaultUiState(biometricAvailable = true, biometricEnabled = true)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, state: VaultUiState, dark: Boolean = false) {
        require(!state.importing) { "see VaultImportDialogScreenshotTest" }
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = dark) {
                VaultScreen(state, onPinChange = {}, onSubmitPin = {}, onBiometricUnlock = {}, onBiometricToggle = {}, onLock = {})
            }
        }
        // The warning dialog lives in its own window, so capture the whole screen for it.
        if (state.showExportWarning) {
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        } else {
            composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        }
    }

    @Test fun vault_locked() = capture("vault_locked", locked)

    @Test fun vault_locked_dark() = capture("vault_locked_dark", locked, dark = true)

    @Test fun vault_pinEntry() = capture("vault_pin_entry", locked.copy(pin = "4829"))

    @Test fun vault_wrongPin() = capture("vault_wrong_pin", locked.copy(message = VaultMessage.WrongPin))

    @Test fun vault_tooSoon() = capture("vault_too_soon", locked.copy(message = VaultMessage.TooSoon(30)))

    @Test fun vault_biometricInvalidated() = capture(
        "vault_biometric_invalidated",
        locked.copy(biometricEnabled = false, message = VaultMessage.BiometricInvalidated),
    )

    @Test fun vault_unlocked() = capture("vault_unlocked", locked.copy(unlocked = true))

    @Test fun vault_notSetUp() = capture("vault_not_set_up", VaultUiState(setUp = false))

    private val unlocked = locked.copy(unlocked = true)

    @Test fun vault_exported() = capture("vault_exported", unlocked.copy(transfer = TransferMessage.Exported(12)))

    @Test fun vault_exportWarning() = capture("vault_export_warning", unlocked.copy(showExportWarning = true))

    @Test fun vault_importNotEmpty() = capture("vault_import_not_empty", unlocked.copy(transfer = TransferMessage.VaultNotEmpty))
}

/**
 * The import dialog. Under the Pixel 7 device profile, Robolectric never goes idle with a text
 * field inside a dialog window (the same dialog idles on the default profile, and the screen
 * without the dialog idles on Pixel 7), so this one uses the default profile.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VaultImportDialogScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun vault_importDialog_wrongCode() {
        val state = VaultUiState(
            unlocked = true, importing = true, importCode = "7K3M-Q9TD", importPin = "482915",
            transfer = TransferMessage.WrongRecoveryCode,
        )
        composeTestRule.setContent {
            SecureGalleryTheme {
                VaultScreen(state, onPinChange = {}, onSubmitPin = {}, onBiometricUnlock = {}, onBiometricToggle = {}, onLock = {})
            }
        }
        composeTestRule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/vault_import_dialog.png")
    }
}
