package com.sudo.securegallery.setup

/**
 * First-run setup (§8.3, §10.2): one idea per screen, in this order.
 *
 * Only some steps are saved as done. PIN and recovery code stay in memory until the user
 * confirms the code is saved; the vault is created only then, so an interrupted setup goes
 * back to the PIN step and never leaves a code that nobody saw.
 */
enum class SetupStep {
    WELCOME,
    PHOTOS,
    PIN,
    RECOVERY,
    BIOMETRIC,
    NOTICE,
    DONE,
}

object SetupFlow {
    /** The screen after [step]; the biometric screen is skipped on phones without one. */
    fun next(step: SetupStep, biometricAvailable: Boolean): SetupStep = when (step) {
        SetupStep.WELCOME -> SetupStep.PHOTOS
        SetupStep.PHOTOS -> SetupStep.PIN
        SetupStep.PIN -> SetupStep.RECOVERY
        SetupStep.RECOVERY -> if (biometricAvailable) SetupStep.BIOMETRIC else SetupStep.NOTICE
        SetupStep.BIOMETRIC -> SetupStep.NOTICE
        SetupStep.NOTICE, SetupStep.DONE -> SetupStep.DONE
    }

    /**
     * Where to resume after an interruption.
     *
     * @param completed the last step saved as done, or null on a fresh install.
     * @param vaultCreated whether the vault exists, which is the truth for the PIN and code.
     */
    fun resume(completed: SetupStep?, vaultCreated: Boolean, biometricAvailable: Boolean): SetupStep {
        val saved = completed?.let { next(it, biometricAvailable) } ?: SetupStep.WELCOME
        return when {
            // The vault was made but the app stopped before saving the step.
            vaultCreated && saved <= SetupStep.RECOVERY -> next(SetupStep.RECOVERY, biometricAvailable)
            // No vault (for example app data was cleared): the PIN step has to run again.
            !vaultCreated && saved > SetupStep.PIN -> SetupStep.PIN
            else -> saved
        }
    }
}
