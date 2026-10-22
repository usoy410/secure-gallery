package com.sudo.securegallery.setup

import com.sudo.securegallery.setup.SetupStep.BIOMETRIC
import com.sudo.securegallery.setup.SetupStep.DONE
import com.sudo.securegallery.setup.SetupStep.NOTICE
import com.sudo.securegallery.setup.SetupStep.PHOTOS
import com.sudo.securegallery.setup.SetupStep.PIN
import com.sudo.securegallery.setup.SetupStep.RECOVERY
import com.sudo.securegallery.setup.SetupStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Test

/** Setup order and where an interrupted setup resumes (issue #28). */
class SetupFlowTest {

    @Test
    fun stepsRunInOrderAndSkipBiometricWithoutHardware() {
        val withBio = generateSequence(WELCOME) { if (it == DONE) null else SetupFlow.next(it, true) }.toList()
        assertEquals(listOf(WELCOME, PHOTOS, PIN, RECOVERY, BIOMETRIC, NOTICE, DONE), withBio)
        assertEquals(NOTICE, SetupFlow.next(RECOVERY, biometricAvailable = false))
    }

    @Test
    fun freshInstallStartsAtWelcome() {
        assertEquals(WELCOME, SetupFlow.resume(null, vaultCreated = false, biometricAvailable = true))
    }

    @Test
    fun resumesAfterTheLastSavedStep() {
        assertEquals(PHOTOS, SetupFlow.resume(WELCOME, false, true))
        assertEquals(PIN, SetupFlow.resume(PHOTOS, false, true))
        assertEquals(NOTICE, SetupFlow.resume(BIOMETRIC, true, true))
        assertEquals(DONE, SetupFlow.resume(DONE, true, true))
    }

    @Test
    fun interruptedBeforeTheVaultExistsGoesBackToThePin() {
        // The recovery code lived only in memory, so it is gone; a new one will be shown.
        assertEquals(PIN, SetupFlow.resume(PHOTOS, vaultCreated = false, biometricAvailable = true))
        assertEquals(PIN, SetupFlow.resume(BIOMETRIC, vaultCreated = false, biometricAvailable = true))
    }

    @Test
    fun vaultCreatedButStepNotSavedSkipsAhead() {
        assertEquals(BIOMETRIC, SetupFlow.resume(PHOTOS, vaultCreated = true, biometricAvailable = true))
        assertEquals(NOTICE, SetupFlow.resume(PHOTOS, vaultCreated = true, biometricAvailable = false))
    }
}
