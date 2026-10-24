package com.sudo.securegallery.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.scan.ScanWorker
import com.sudo.securegallery.secure.SecureOriginals
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the scan screen shows. [failed] is true when the last scan stopped with an error (#65),
 * so the screen never reports a failed run as complete.
 */
data class ScanProgress(
    val found: Int = 0,
    val processed: Int = 0,
    val running: Boolean = false,
    val failed: Boolean = false,
)

/** Whether a scan is running, and whether the last one failed. */
private data class WorkStatus(val running: Boolean, val failed: Boolean)

/** The "ready to secure" card (#22): how many originals can go, and what stops it. */
data class SecureState(val ready: Int = 0, val busy: Boolean = false, val message: SecureMessage? = null)

enum class SecureMessage { VAULT_LOCKED, NOTHING_VERIFIED, SECURED, DENIED }

class ScanViewModel(
    dao: ItemDao,
    private val workManager: WorkManager,
    private val secure: SecureOriginals? = null,
    private val session: VaultSession? = null,
) : ViewModel() {
    private val secureFlow = MutableStateFlow(SecureState())
    private var pending: List<Item> = emptyList()

    val secureState: StateFlow<SecureState> =
        combine(dao.countReadyToSecure(), secureFlow) { ready, state -> state.copy(ready = ready) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecureState())

    /**
     * Vaults and verifies the ready originals, then hands the system delete dialog for them to
     * [launch]. Needs the unlocked vault.
     */
    fun secureNow(launch: (List<Item>) -> Unit) {
        val secure = secure ?: return
        val key = session?.key()
        if (key == null) {
            secureFlow.value = SecureState(message = SecureMessage.VAULT_LOCKED)
            return
        }
        viewModelScope.launch {
            secureFlow.value = SecureState(busy = true)
            val items = withContext(Dispatchers.IO) { secure.prepare(key) }
            pending = items
            secureFlow.value = SecureState(message = if (items.isEmpty()) SecureMessage.NOTHING_VERIFIED else null)
            if (items.isNotEmpty()) launch(items)
        }
    }

    /** The user's answer to the delete dialog. */
    fun onDeleteResult(allowed: Boolean) {
        val secure = secure ?: return
        val items = pending
        pending = emptyList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secure.finish(items, allowed) }
            secureFlow.value = SecureState(message = if (allowed) SecureMessage.SECURED else SecureMessage.DENIED)
        }
    }

    private val status: Flow<WorkStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(ScanWorker.UNIQUE_NAME)
        .map { infos ->
            val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            WorkStatus(running, failed = !running && infos.any { it.state == WorkInfo.State.FAILED })
        }

    val progress: StateFlow<ScanProgress> =
        combine(dao.countAll(), dao.countProcessed(), status) { found, processed, work ->
            ScanProgress(found, processed, work.running, work.failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanProgress())

    fun scanNow() = ScanWorker.enqueue(workManager)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScanViewModel(
                    container.database.itemDao(),
                    container.workManager,
                    SecureOriginals(container.database.itemDao(), container.vault, container.imageSource),
                    container.vaultSession,
                )
            }
        }
    }
}
