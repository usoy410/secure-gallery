package com.sudo.securegallery.ui.vault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** True if the phone has a strong biometric enrolled. */
fun hasStrongBiometric(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Shows `BiometricPrompt` for [cipher] and hands back the authenticated cipher. Cancelling or
 * choosing the PIN calls neither callback.
 */
fun authenticateWithBiometric(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    negativeText: String,
    onSuccess: (Cipher) -> Unit,
    onError: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess) ?: onError()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    onError()
                }
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .setNegativeButtonText(negativeText)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}
