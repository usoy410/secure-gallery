package com.sudo.securegallery.vault

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.ui.vault.TransferMessage
import com.sudo.securegallery.ui.vault.VaultViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** The uninstall warning shows once, and export/import work from the vault screen (issue #26). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VaultTransferViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: VaultPrefs
    private val pin = "482915".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = VaultPrefs(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") })
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun VaultViewModel.await(check: (com.sudo.securegallery.ui.vault.VaultUiState) -> Boolean) =
        runBlocking { withTimeout(5_000) { state.first(check) } }

    @Test
    fun warningShowsOnUnlockUntilDismissedForGood() {
        val vault = testVault(tmp.newFolder("vault"))
        vault.setUp(pin)
        val session = VaultSession(CoroutineScope(dispatcher))
        val vm = VaultViewModel(vault, session, biometricAvailable = false, prefs = prefs, io = dispatcher)

        session.start((vault.unlock(pin) as Vault.Unlock.Success).key)
        vm.await { it.showExportWarning }
        vm.dismissExportWarning(never = false)
        assertFalse(vm.state.value.showExportWarning)

        session.lock()
        session.start((vault.unlock(pin) as Vault.Unlock.Success).key)
        vm.await { it.showExportWarning } // back, since "never" was not ticked
        vm.dismissExportWarning(never = true)
        runBlocking { withTimeout(5_000) { while (!prefs.exportWarningDismissed()) Unit } }

        session.lock()
        session.start((vault.unlock(pin) as Vault.Unlock.Success).key)
        vm.await { it.unlocked }
        assertFalse(vm.state.value.showExportWarning)
    }

    @Test
    fun exportThenImportThroughTheScreenAndTheSessionLocks() {
        val old = testVault(tmp.newFolder("old"))
        val code = old.setUp(pin)
        old.store((old.unlock(pin) as Vault.Unlock.Success).key, "item-1", "original".toByteArray().inputStream())
        val oldVm = VaultViewModel(old, VaultSession(CoroutineScope(dispatcher)), false, io = dispatcher)
        val file = ByteArrayOutputStream()
        oldVm.export { file }
        oldVm.await { it.transfer == TransferMessage.Exported(1) }

        val fresh = testVault(tmp.newFolder("fresh"))
        fresh.setUp("735102".toCharArray())
        val session = VaultSession(CoroutineScope(dispatcher))
        session.start((fresh.unlock("735102".toCharArray()) as Vault.Unlock.Success).key)
        val vm = VaultViewModel(fresh, session, false, io = dispatcher)

        vm.startImport { file.toByteArray().inputStream() }
        vm.onImportCodeChange("0000-0000-0000-0000-0000-0000")
        vm.onImportPinChange("735102")
        vm.confirmImport()
        vm.await { it.transfer == TransferMessage.WrongRecoveryCode }
        assertTrue(vm.state.value.importing) // stays open to try again

        vm.onImportCodeChange(RecoveryCode.format(code))
        vm.confirmImport()
        vm.await { it.transfer == TransferMessage.Imported(1) }
        assertFalse(vm.state.value.importing)
        assertFalse(session.unlocked.value) // re-unlock with the PIN
        val key = (fresh.unlock("735102".toCharArray()) as Vault.Unlock.Success).key
        assertEquals("original", String(fresh.read(key, "item-1")))
    }
}
