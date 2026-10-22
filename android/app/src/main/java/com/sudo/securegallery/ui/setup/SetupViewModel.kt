package com.sudo.securegallery.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.setup.SetupFlow
import com.sudo.securegallery.setup.SetupProgressStore
import com.sudo.securegallery.setup.SetupStep
import com.sudo.securegallery.vault.RecoveryCode
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

enum class PinError { TOO_SHORT, MISMATCH }

/** What the setup screens show. PIN and recovery code live here only, never on disk. */
data class SetupUiState(
    val step: SetupStep? = null, // null while the saved progress loads
    val pin: String = "",
    val pinConfirm: String = "",
    val pinError: PinError? = null,
    val recoveryCode: String = "",
    val recoverySaved: Boolean = false,
    val busy: Boolean = false,
    val biometricFailed: Boolean = false,
    val biometricAvailable: Boolean = false,
)

class SetupViewModel(
    private val vault: Vault,
    private val session: VaultSession,
    private val progress: SetupProgressStore,
    biometricAvailable: Boolean,
    /** Where PBKDF2 runs; tests pass their own dispatcher. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState(biometricAvailable = biometricAvailable))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private var code: CharArray? = null

    init {
        viewModelScope.launch {
            var step = SetupFlow.resume(progress.completed(), vault.isSetUp(), biometricAvailable)
            // Turning on biometrics needs the key from this session; after a restart it is gone,
            // and the user can turn it on later from the Vault tab.
            if (step == SetupStep.BIOMETRIC && session.key() == null) step = SetupStep.NOTICE
            _state.update { it.copy(step = step) }
        }
    }

    /** Moves past a screen that has nothing to keep (welcome, photos, biometric, notice). */
    fun continueFrom(step: SetupStep) {
        viewModelScope.launch {
            progress.markCompleted(step)
            val next = SetupFlow.next(step, _state.value.biometricAvailable)
            if (next == SetupStep.DONE) progress.markCompleted(SetupStep.DONE)
            _state.update { it.copy(step = next, biometricFailed = false) }
        }
    }

    fun onPinChange(pin: String) = _state.update { it.copy(pin = pin.digits(), pinError = null) }

    fun onPinConfirmChange(pin: String) = _state.update { it.copy(pinConfirm = pin.digits(), pinError = null) }

    /** Checks the PIN and shows a fresh recovery code. Nothing is saved yet. */
    fun submitPin() {
        val s = _state.value
        val error = when {
            s.pin.length < Vault.MIN_PIN_LENGTH -> PinError.TOO_SHORT
            s.pin != s.pinConfirm -> PinError.MISMATCH
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(pinError = error) }
            return
        }
        code?.fill('0')
        val fresh = RecoveryCode.generate()
        code = fresh
        _state.update {
            it.copy(step = SetupStep.RECOVERY, recoveryCode = RecoveryCode.format(fresh), recoverySaved = false)
        }
    }

    fun onRecoverySavedChange(saved: Boolean) = _state.update { it.copy(recoverySaved = saved) }

    /** Back from the recovery code to the PIN: the code shown is thrown away. */
    fun backToPin() {
        code?.fill('0')
        code = null
        _state.update { it.copy(step = SetupStep.PIN, recoveryCode = "", recoverySaved = false) }
    }

    /** The user confirmed the code is saved: create the vault and start an unlocked session. */
    fun confirmRecovery() {
        val pin = _state.value.pin.toCharArray()
        val recovery = code ?: return
        if (!_state.value.recoverySaved) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val key = withContext(work) { vault.create(pin, recovery) }
            pin.fill('0')
            recovery.fill('0')
            code = null
            session.start(key)
            progress.markCompleted(SetupStep.RECOVERY)
            val next = SetupFlow.next(SetupStep.RECOVERY, _state.value.biometricAvailable)
            _state.update {
                it.copy(step = next, busy = false, pin = "", pinConfirm = "", recoveryCode = "", recoverySaved = false)
            }
        }
    }

    fun biometricEnrollCipher(): Cipher? = if (session.key() == null) null else vault.biometricEnrollCipher()

    fun onBiometricEnrolled(cipher: Cipher) {
        val key = session.key() ?: return
        vault.enableBiometric(key, cipher)
        continueFrom(SetupStep.BIOMETRIC)
    }

    fun onBiometricError() = _state.update { it.copy(biometricFailed = true) }

    override fun onCleared() {
        code?.fill('0')
    }

    private fun String.digits() = filter(Char::isDigit).take(MAX_PIN)

    companion object {
        const val MAX_PIN = 12

        fun factory(container: AppContainer, biometricAvailable: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SetupViewModel(container.vault, container.vaultSession, container.setupProgress, biometricAvailable)
            }
        }
    }
}
