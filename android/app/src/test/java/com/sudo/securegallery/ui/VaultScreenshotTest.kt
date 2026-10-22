package com.sudo.securegallery.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
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

    private fun capture(name: String, state: VaultUiState, dark: Boolean = false) {
        composeTestRule.setContent {
            SecureGalleryTheme(darkTheme = dark) {
                VaultScreen(state, onPinChange = {}, onSubmitPin = {}, onBiometricUnlock = {}, onBiometricToggle = {}, onLock = {})
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
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
}
