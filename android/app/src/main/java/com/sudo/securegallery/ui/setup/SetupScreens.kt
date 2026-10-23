package com.sudo.securegallery.ui.setup

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudo.securegallery.R
import com.sudo.securegallery.scan.PhotoAccess
import com.sudo.securegallery.setup.SetupStep
import com.sudo.securegallery.ui.SecureWindow
import com.sudo.securegallery.ui.vault.authenticateWithBiometric

/** First-run setup with its state, permission request and `BiometricPrompt`. */
@Composable
fun SetupRoute(viewModel: SetupViewModel, onFinished: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Allowed or not, setup goes on; the Scan tab asks again if needed.
        viewModel.continueFrom(SetupStep.PHOTOS)
    }
    val title = stringResource(R.string.vault_biometric_prompt_title)
    val usePin = stringResource(R.string.vault_biometric_prompt_use_pin)

    LaunchedEffect(state.step) { if (state.step == SetupStep.DONE) onFinished() }
    val step = state.step ?: return
    SetupScreen(
        step = step,
        state = state,
        actions = SetupActions(
            onContinue = { viewModel.continueFrom(step) },
            onAllowPhotos = { photos.launch(PhotoAccess.permissionsFor(Build.VERSION.SDK_INT)) },
            onPinChange = viewModel::onPinChange,
            onPinConfirmChange = viewModel::onPinConfirmChange,
            onSubmitPin = viewModel::submitPin,
            onRecoverySavedChange = viewModel::onRecoverySavedChange,
            onConfirmRecovery = viewModel::confirmRecovery,
            onBackToPin = viewModel::backToPin,
            onEnableBiometric = {
                viewModel.biometricEnrollCipher()?.let { cipher ->
                    authenticateWithBiometric(activity, cipher, title, usePin, viewModel::onBiometricEnrolled, viewModel::onBiometricError)
                } ?: viewModel.onBiometricError()
            },
        ),
    )
}

/** Everything a setup screen can do, so each screen stays stateless. */
data class SetupActions(
    val onContinue: () -> Unit = {},
    val onAllowPhotos: () -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onPinConfirmChange: (String) -> Unit = {},
    val onSubmitPin: () -> Unit = {},
    val onRecoverySavedChange: (Boolean) -> Unit = {},
    val onConfirmRecovery: () -> Unit = {},
    val onBackToPin: () -> Unit = {},
    val onEnableBiometric: () -> Unit = {},
)

@Composable
fun SetupScreen(step: SetupStep, state: SetupUiState, actions: SetupActions) {
    val total = if (state.biometricAvailable) 6 else 5
    val number = when (step) {
        SetupStep.BIOMETRIC -> 5
        SetupStep.NOTICE -> total
        else -> step.ordinal + 1
    }
    when (step) {
        SetupStep.WELCOME -> Page(number, total, Icons.Outlined.Shield, R.string.setup_welcome_title, R.string.setup_welcome_body) {
            Primary(R.string.setup_get_started, actions.onContinue)
        }
        SetupStep.PHOTOS -> Page(number, total, Icons.Outlined.PhotoLibrary, R.string.setup_photos_title, R.string.setup_photos_body) {
            Primary(R.string.setup_photos_allow, actions.onAllowPhotos)
            Secondary(R.string.setup_not_now, actions.onContinue)
        }
        SetupStep.PIN -> {
            SecureWindow()
            Page(number, total, Icons.Outlined.Password, R.string.setup_pin_title, R.string.setup_pin_body) {
                PinField(state.pin, actions.onPinChange, R.string.setup_pin_label, error = null)
                Spacer(Modifier.height(8.dp))
                val error = when (state.pinError) {
                    PinError.TOO_SHORT -> stringResource(R.string.setup_pin_too_short)
                    PinError.MISMATCH -> stringResource(R.string.setup_pin_mismatch)
                    null -> null
                }
                PinField(state.pinConfirm, actions.onPinConfirmChange, R.string.setup_pin_confirm_label, error)
                Spacer(Modifier.height(16.dp))
                Primary(R.string.setup_continue, actions.onSubmitPin)
            }
        }
        SetupStep.RECOVERY -> {
            SecureWindow()
            Page(number, total, Icons.Outlined.Key, R.string.setup_recovery_title, R.string.setup_recovery_body) {
                RecoveryCodeCard(state.recoveryCode)
                Spacer(Modifier.height(16.dp))
                val label = stringResource(R.string.setup_recovery_saved)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .toggleable(state.recoverySaved, role = Role.Checkbox, onValueChange = actions.onRecoverySavedChange),
                ) {
                    Checkbox(checked = state.recoverySaved, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = actions.onConfirmRecovery,
                    enabled = state.recoverySaved && !state.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.setup_continue))
                }
                Secondary(R.string.setup_back, actions.onBackToPin, enabled = !state.busy)
            }
        }
        SetupStep.BIOMETRIC -> Page(number, total, Icons.Outlined.Fingerprint, R.string.setup_biometric_title, R.string.setup_biometric_body) {
            if (state.biometricFailed) {
                Text(
                    stringResource(R.string.vault_biometric_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            Primary(R.string.setup_biometric_turn_on, actions.onEnableBiometric)
            Secondary(R.string.setup_not_now, actions.onContinue)
        }
        SetupStep.NOTICE -> Page(number, total, Icons.Outlined.CloudOff, R.string.setup_notice_title, R.string.setup_notice_body) {
            Text(
                stringResource(R.string.setup_notice_uninstall),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Primary(R.string.setup_finish, actions.onContinue)
        }
        SetupStep.DONE -> Unit
    }
}

@Composable
private fun Page(
    number: Int,
    total: Int,
    icon: ImageVector,
    title: Int,
    body: Int,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.setup_step_of, number, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            content()
        }
    }
    }
}

@Composable
private fun Primary(text: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(text)) }
}

@Composable
private fun Secondary(text: Int, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(text)) }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: Int, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The code in groups of four. TalkBack reads it one character at a time. */
@Composable
private fun RecoveryCodeCard(code: String) {
    val spoken = code.split("-").joinToString(", ") { group -> group.toList().joinToString(" ") }
    Card(Modifier.fillMaxWidth().semantics { contentDescription = spoken }) {
        Text(
            code.replace("-", "  "),
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}
