package com.sudo.securegallery.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultPrefs
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
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
    /** First unlock: warn that uninstalling deletes the vault (#26). */
    val showExportWarning: Boolean = false,
    /** Import dialog open, with what the user typed so far. */
    val importing: Boolean = false,
    val importCode: String = "",
    val importPin: String = "",
    val transferBusy: Boolean = false,
    val transfer: TransferMessage? = null,
)

/** Result of the last export or import, shown under the buttons. */
sealed interface TransferMessage {
    data class Exported(val items: Int) : TransferMessage
    data class Imported(val items: Int) : TransferMessage
    data object WrongRecoveryCode : TransferMessage
    data object VaultNotEmpty : TransferMessage
    data object InvalidFile : TransferMessage
    data object Failed : TransferMessage
}

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
    private val prefs: VaultPrefs? = null,
    /** Where file copying and PBKDF2 run; tests pass their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private var importSource: (() -> InputStream?)? = null

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
        viewModelScope.launch {
            session.unlocked.collect { open ->
                val warn = open && prefs != null && !prefs.exportWarningDismissed()
                _state.update { it.copy(unlocked = open, showExportWarning = warn) }
            }
        }
    }

    /** Closes the uninstall warning; with [never] it does not come back. */
    fun dismissExportWarning(never: Boolean) {
        _state.update { it.copy(showExportWarning = false) }
        if (never) viewModelScope.launch { prefs?.dismissExportWarning() }
    }

    /** Writes the export file to a stream the system file picker opened. */
    fun export(open: () -> OutputStream?) {
        _state.update { it.copy(transferBusy = true, transfer = null) }
        viewModelScope.launch {
            val message = withContext(io) {
                try {
                    open()?.use { TransferMessage.Exported(vault.export(it)) } ?: TransferMessage.Failed
                } catch (_: java.io.IOException) {
                    TransferMessage.Failed
                }
            }
            _state.update { it.copy(transferBusy = false, transfer = message) }
        }
    }

    /** The user picked an export file; ask for its recovery code and this phone's PIN. */
    fun startImport(open: () -> InputStream?) {
        importSource = open
        _state.update { it.copy(importing = true, importCode = "", importPin = "", transfer = null) }
    }

    fun onImportCodeChange(code: String) = _state.update { it.copy(importCode = code.take(40)) }

    fun onImportPinChange(pin: String) = _state.update { it.copy(importPin = pin.filter(Char::isDigit).take(MAX_PIN)) }

    fun cancelImport() {
        importSource = null
        _state.update { it.copy(importing = false, importCode = "", importPin = "") }
    }

    fun confirmImport() {
        val open = importSource ?: return
        val code = _state.value.importCode.toCharArray()
        val pin = _state.value.importPin.toCharArray()
        _state.update { it.copy(transferBusy = true) }
        viewModelScope.launch {
            val result = withContext(io) {
                try {
                    open()?.use { vault.importArchive(it, code, pin) }
                } catch (_: java.io.IOException) {
                    null
                } finally {
                    code.fill('0')
                    pin.fill('0')
                }
            }
            val message = when (result) {
                is Vault.ImportResult.Imported -> TransferMessage.Imported(result.items)
                Vault.ImportResult.WrongRecoveryCode -> TransferMessage.WrongRecoveryCode
                Vault.ImportResult.VaultNotEmpty -> TransferMessage.VaultNotEmpty
                is Vault.ImportResult.Invalid -> TransferMessage.InvalidFile
                null -> TransferMessage.Failed
            }
            if (result is Vault.ImportResult.Imported) {
                importSource = null
                // The vault now has the imported key: unlock again with the PIN just set.
                session.lock()
            }
            val keepOpen = message == TransferMessage.WrongRecoveryCode
            _state.update {
                it.copy(
                    transferBusy = false,
                    transfer = message,
                    importing = keepOpen,
                    importCode = if (keepOpen) it.importCode else "",
                    importPin = if (keepOpen) it.importPin else "",
                    biometricEnabled = vault.isBiometricEnabled(),
                )
            }
        }
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
            initializer { VaultViewModel(container.vault, container.vaultSession, biometricAvailable, container.vaultPrefs) }
        }
    }
}
