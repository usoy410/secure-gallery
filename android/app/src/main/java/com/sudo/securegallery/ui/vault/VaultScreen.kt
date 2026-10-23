package com.sudo.securegallery.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudo.securegallery.R
import com.sudo.securegallery.ui.SecureWindow
import javax.crypto.Cipher

/** Vault tab with its state and the `BiometricPrompt` calls. */
@Composable
fun VaultRoute(viewModel: VaultViewModel, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as FragmentActivity
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.vault_biometric_prompt_title)
    val usePin = stringResource(R.string.vault_biometric_prompt_use_pin)

    fun authenticate(cipher: Cipher, onSuccess: (Cipher) -> Unit) =
        authenticateWithBiometric(activity, cipher, title, usePin, onSuccess, viewModel::onBiometricError)

    val resolver = activity.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export { resolver.openOutputStream(uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.startImport { resolver.openInputStream(uri) }
    }
    val exportName = stringResource(R.string.vault_export_file_name)

    VaultScreen(
        transfer = VaultTransferActions(
            onExport = { exportLauncher.launch(exportName) },
            onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            onDismissWarning = viewModel::dismissExportWarning,
            onImportCodeChange = viewModel::onImportCodeChange,
            onImportPinChange = viewModel::onImportPinChange,
            onConfirmImport = viewModel::confirmImport,
            onCancelImport = viewModel::cancelImport,
        ),
        state = state,
        onPinChange = viewModel::onPinChange,
        onSubmitPin = viewModel::submitPin,
        onBiometricUnlock = { viewModel.biometricUnlockCipher()?.let { authenticate(it, viewModel::onBiometricUnlocked) } },
        onBiometricToggle = { on ->
            if (on) viewModel.biometricEnrollCipher()?.let { authenticate(it, viewModel::onBiometricEnrolled) }
            else viewModel.disableBiometric()
        },
        onLock = viewModel::lock,
        modifier = modifier,
    )
}

/** Export, import and the uninstall warning (#26). */
data class VaultTransferActions(
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onDismissWarning: (never: Boolean) -> Unit = {},
    val onImportCodeChange: (String) -> Unit = {},
    val onImportPinChange: (String) -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
)

/** Stateless vault screen: not set up, locked (PIN and biometric), or unlocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: VaultUiState,
    onPinChange: (String) -> Unit,
    onSubmitPin: () -> Unit,
    onBiometricUnlock: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    transfer: VaultTransferActions = VaultTransferActions(),
) {
    // No screenshots or recents thumbnail of PIN entry or an unlocked vault.
    SecureWindow()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                !state.setUp -> Heading(Icons.Outlined.Lock, R.string.vault_not_set_up_title, R.string.vault_not_set_up_body)
                state.unlocked -> Unlocked(state, onBiometricToggle, onLock, transfer)
                else -> Locked(state, onPinChange, onSubmitPin, onBiometricUnlock)
            }
        }
    }
}

@Composable
private fun Heading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(24.dp))
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Locked(state: VaultUiState, onPinChange: (String) -> Unit, onSubmitPin: () -> Unit, onBiometricUnlock: () -> Unit) {
    Heading(Icons.Outlined.Lock, R.string.vault_locked_title, R.string.vault_locked_body)
    Spacer(Modifier.height(24.dp))
    val error = state.message?.let { messageText(it) }
    OutlinedTextField(
        value = state.pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(R.string.vault_pin_label)) },
        singleLine = true,
        isError = state.message == VaultMessage.WrongPin || state.message is VaultMessage.TooSoon,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (state.pin.length >= MIN_PIN && !state.busy) onSubmitPin() }),
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmitPin,
        enabled = state.pin.length >= MIN_PIN && !state.busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (state.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.vault_unlock_button))
    }
    if (state.biometricAvailable && state.biometricEnabled) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBiometricUnlock, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.Fingerprint, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.vault_biometric_button))
        }
    }
}

@Composable
private fun Unlocked(state: VaultUiState, onBiometricToggle: (Boolean) -> Unit, onLock: () -> Unit, transfer: VaultTransferActions) {
    Heading(Icons.Outlined.LockOpen, R.string.vault_unlocked_title, R.string.vault_unlocked_body)
    Spacer(Modifier.height(24.dp))
    if (state.biometricAvailable) {
        val label = stringResource(R.string.vault_biometric_toggle)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = state.biometricEnabled,
                onCheckedChange = onBiometricToggle,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        state.message?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(16.dp))
    }
    OutlinedButton(onClick = transfer.onExport, enabled = !state.transferBusy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(stringResource(R.string.vault_export_button))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = transfer.onImport, enabled = !state.transferBusy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(stringResource(R.string.vault_import_button))
    }
    state.transfer?.let {
        Spacer(Modifier.height(8.dp))
        val isError = it !is TransferMessage.Exported && it !is TransferMessage.Imported
        Text(
            transferText(it),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onLock, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(stringResource(R.string.vault_lock_button))
    }

    if (state.showExportWarning) ExportWarningDialog(transfer.onDismissWarning)
    if (state.importing) ImportDialog(state, transfer)
}

@Composable
private fun ExportWarningDialog(onDismiss: (never: Boolean) -> Unit) {
    var never by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(never) },
        title = { Text(stringResource(R.string.vault_warning_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_warning_body))
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .toggleable(never, role = Role.Checkbox, onValueChange = { never = it }),
                ) {
                    Checkbox(checked = never, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.vault_warning_never))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(never) }) { Text(stringResource(R.string.vault_warning_ok)) } },
    )
}

@Composable
private fun ImportDialog(state: VaultUiState, transfer: VaultTransferActions) {
    AlertDialog(
        onDismissRequest = { if (!state.transferBusy) transfer.onCancelImport() },
        title = { Text(stringResource(R.string.vault_import_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_import_body))
                Spacer(Modifier.height(12.dp))
                val wrongCode = state.transfer == TransferMessage.WrongRecoveryCode
                OutlinedTextField(
                    value = state.importCode,
                    onValueChange = transfer.onImportCodeChange,
                    label = { Text(stringResource(R.string.vault_import_code_label)) },
                    singleLine = true,
                    isError = wrongCode,
                    supportingText = if (wrongCode) ({ Text(stringResource(R.string.vault_transfer_wrong_code)) }) else null,
                    // Shown as typed: 24 characters are hard to enter blind, and FLAG_SECURE
                    // keeps this screen out of screenshots.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.importPin,
                    onValueChange = transfer.onImportPinChange,
                    label = { Text(stringResource(R.string.vault_import_pin_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = transfer.onConfirmImport,
                enabled = !state.transferBusy && state.importPin.length >= MIN_PIN && state.importCode.isNotBlank(),
            ) { Text(stringResource(R.string.vault_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = transfer.onCancelImport, enabled = !state.transferBusy) { Text(stringResource(R.string.vault_import_cancel)) }
        },
    )
}

@Composable
private fun transferText(message: TransferMessage): String = when (message) {
    is TransferMessage.Exported -> pluralStringResource(R.plurals.vault_transfer_exported, message.items, message.items)
    is TransferMessage.Imported -> pluralStringResource(R.plurals.vault_transfer_imported, message.items, message.items)
    TransferMessage.WrongRecoveryCode -> stringResource(R.string.vault_transfer_wrong_code)
    TransferMessage.VaultNotEmpty -> stringResource(R.string.vault_transfer_not_empty)
    TransferMessage.InvalidFile -> stringResource(R.string.vault_transfer_invalid)
    TransferMessage.Failed -> stringResource(R.string.vault_transfer_failed)
}

@Composable
private fun messageText(message: VaultMessage): String = when (message) {
    VaultMessage.WrongPin -> stringResource(R.string.vault_wrong_pin)
    is VaultMessage.TooSoon -> stringResource(R.string.vault_too_soon, message.seconds)
    VaultMessage.BiometricInvalidated -> stringResource(R.string.vault_biometric_invalidated)
    VaultMessage.BiometricFailed -> stringResource(R.string.vault_biometric_failed)
}

private const val MIN_PIN = 6
