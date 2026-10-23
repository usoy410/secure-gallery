package com.sudo.securegallery.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.ui.setup.PinError
import com.sudo.securegallery.ui.setup.SetupViewModel
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultSession
import com.sudo.securegallery.vault.testVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Setup creates the vault only after the recovery code is confirmed, and resumes safely (issue #28). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SetupViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var progress: SetupProgressStore
    private lateinit var vault: Vault
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val file = File(tmp.root, "setup.preferences_pb")
        progress = SetupProgressStore(PreferenceDataStoreFactory.create(scope = storeScope) { file })
        vault = testVault(File(tmp.root, "vault"))
        session = VaultSession(CoroutineScope(dispatcher))
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(biometric: Boolean = true) = SetupViewModel(vault, session, progress, biometric, work = dispatcher)

    private fun SetupViewModel.awaitStep(step: SetupStep) = runBlocking {
        withTimeout(5_000) { state.first { it.step == step } }
    }

    @Test
    fun freshInstallWalksToThePin() {
        val vm = viewModel()
        vm.awaitStep(SetupStep.WELCOME)
        vm.continueFrom(SetupStep.WELCOME)
        vm.awaitStep(SetupStep.PHOTOS)
        vm.continueFrom(SetupStep.PHOTOS)
        vm.awaitStep(SetupStep.PIN)
        assertEquals(SetupStep.PHOTOS, runBlocking { progress.completed() })
    }

    @Test
    fun pinMustBeLongEnoughAndMatch() {
        val vm = viewModel()
        vm.awaitStep(SetupStep.WELCOME)
        vm.onPinChange("1234")
        vm.onPinConfirmChange("1234")
        vm.submitPin()
        assertEquals(PinError.TOO_SHORT, vm.state.value.pinError)
        vm.onPinChange("482915")
        vm.onPinConfirmChange("482916")
        vm.submitPin()
        assertEquals(PinError.MISMATCH, vm.state.value.pinError)
        vm.onPinChange("48a29b15") // only digits are kept
        assertEquals("482915", vm.state.value.pin)
    }

    @Test
    fun vaultIsCreatedOnlyAfterTheCodeIsConfirmed() {
        val vm = viewModel()
        vm.awaitStep(SetupStep.WELCOME)
        vm.onPinChange("482915")
        vm.onPinConfirmChange("482915")
        vm.submitPin()
        val shown = vm.state.value.recoveryCode
        assertEquals(SetupStep.RECOVERY, vm.state.value.step)
        assertEquals(29, shown.length) // 24 characters in groups of four
        assertFalse(vault.isSetUp())

        vm.confirmRecovery() // checkbox not ticked: nothing happens
        assertFalse(vault.isSetUp())

        vm.onRecoverySavedChange(true)
        vm.confirmRecovery()
        vm.awaitStep(SetupStep.BIOMETRIC)
        assertTrue(vault.isSetUp())
        assertTrue(session.unlocked.value)
        assertEquals("", vm.state.value.recoveryCode) // wiped from the screen state
        assertEquals(SetupStep.RECOVERY, runBlocking { progress.completed() })
        // The code the user saw is the one that opens the vault, and so is the PIN.
        assertNotNull(vault.unlockWithRecoveryCode(shown.toCharArray()))
        assertTrue(vault.unlock("482915".toCharArray()) is Vault.Unlock.Success)
    }

    @Test
    fun goingBackDiscardsTheCode() {
        val vm = viewModel()
        vm.awaitStep(SetupStep.WELCOME)
        vm.onPinChange("482915")
        vm.onPinConfirmChange("482915")
        vm.submitPin()
        val first = vm.state.value.recoveryCode
        vm.backToPin()
        assertEquals(SetupStep.PIN, vm.state.value.step)
        vm.submitPin()
        assertTrue(vm.state.value.recoveryCode != first)
    }

    @Test
    fun interruptedAtTheCodeResumesAtThePinWithNoVault() {
        runBlocking { progress.markCompleted(SetupStep.PHOTOS) }
        val vm = viewModel()
        vm.awaitStep(SetupStep.PIN)
        assertFalse(vault.isSetUp())
    }

    @Test
    fun withoutAnUnlockedSessionTheBiometricStepIsSkippedOnResume() {
        vault.setUp("482915".toCharArray())
        runBlocking { progress.markCompleted(SetupStep.RECOVERY) }
        val vm = viewModel(biometric = true)
        vm.awaitStep(SetupStep.NOTICE)
    }

    @Test
    fun finishingMarksSetupDone() {
        vault.setUp("482915".toCharArray())
        runBlocking { progress.markCompleted(SetupStep.BIOMETRIC) }
        val vm = viewModel()
        vm.awaitStep(SetupStep.NOTICE)
        vm.continueFrom(SetupStep.NOTICE)
        vm.awaitStep(SetupStep.DONE)
        assertTrue(runBlocking { progress.isDone() })
        assertNull(vm.state.value.pinError)
    }

}
