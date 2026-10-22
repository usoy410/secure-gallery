package com.sudo.securegallery.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** What the vault screen shows. The PIN lives here only while typed, never on disk. */
data class VaultUiState(
    val setUp: Boolean = true,
    val unlocked: Boolean = false,
    val pin: String = "",
    val busy: Boolean = false,
    val message: VaultMessage? = null,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
)

sealed interface VaultMessage {
    data object WrongPin : VaultMessage
    data class TooSoon(val seconds: Long) : VaultMessage
    data object BiometricInvalidated : VaultMessage
    data object BiometricFailed : VaultMessage
}

class VaultViewModel(
    private val vault: Vault,
    private val session: VaultSession,
    biometricAvailable: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        VaultUiState(
            setUp = vault.isSetUp(),
            unlocked = session.unlocked.value,
            biometricAvailable = biometricAvailable,
            biometricEnabled = vault.isBiometricEnabled(),
        ),
    )
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { session.unlocked.collect { open -> _state.update { it.copy(unlocked = open) } } }
    }

    fun onPinChange(pin: String) = _state.update { it.copy(pin = pin.filter(Char::isDigit).take(MAX_PIN), message = null) }

    fun submitPin() {
        val pin = _state.value.pin.toCharArray()
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            // PBKDF2 takes about a second on purpose; keep it off the main thread.
            val result = withContext(Dispatchers.Default) { vault.unlock(pin).also { pin.fill('0') } }
            _state.update { it.copy(busy = false, pin = "") }
            when (result) {
                is Vault.Unlock.Success -> session.start(result.key)
                is Vault.Unlock.WrongPin -> _state.update {
                    it.copy(message = if (result.waitMillis > 0) VaultMessage.TooSoon(result.waitMillis.toSeconds()) else VaultMessage.WrongPin)
                }
                is Vault.Unlock.TooSoon -> _state.update { it.copy(message = VaultMessage.TooSoon(result.waitMillis.toSeconds())) }
            }
        }
    }

    /** The cipher to authenticate for a biometric unlock, or null (the message says why). */
    fun biometricUnlockCipher(): Cipher? = when (val start = vault.startBiometricUnlock()) {
        is Vault.BiometricStart.Ready -> start.cipher
        Vault.BiometricStart.Invalidated -> {
            _state.update { it.copy(biometricEnabled = false, message = VaultMessage.BiometricInvalidated) }
            null
        }
        Vault.BiometricStart.NotEnabled -> {
            _state.update { it.copy(biometricEnabled = false) }
            null
        }
    }

    fun onBiometricUnlocked(cipher: Cipher) {
        val key = vault.finishBiometricUnlock(cipher)
        if (key == null) _state.update { it.copy(message = VaultMessage.BiometricFailed) } else session.start(key)
    }

    fun biometricEnrollCipher(): Cipher? = if (session.key() == null) null else vault.biometricEnrollCipher()

    fun onBiometricEnrolled(cipher: Cipher) {
        val key = session.key() ?: return
        vault.enableBiometric(key, cipher)
        _state.update { it.copy(biometricEnabled = true, message = null) }
    }

    fun disableBiometric() {
        vault.disableBiometric()
        _state.update { it.copy(biometricEnabled = false) }
    }

    fun onBiometricError() = _state.update { it.copy(message = VaultMessage.BiometricFailed) }

    fun lock() = session.lock()

    private fun Long.toSeconds() = (this + 999) / 1000

    companion object {
        const val MAX_PIN = 12

        fun factory(container: AppContainer, biometricAvailable: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { VaultViewModel(container.vault, container.vaultSession, biometricAvailable) }
        }
    }
}
