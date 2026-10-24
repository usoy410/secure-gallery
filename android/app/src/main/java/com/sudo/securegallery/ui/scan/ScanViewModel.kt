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
import com.sudo.securegallery.scan.ScanPrefs
import com.sudo.securegallery.scan.ScanWorker
import com.sudo.securegallery.secure.SecureOriginals
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    val stage: ScanStage = ScanStage.FINGERPRINT,
    /** For [ScanStage.DETECT]: photos looked at so far; otherwise unused. */
    val stageDone: Int = 0,
    /** Photos the current step still has to do. */
    val stageLeft: Int = 0,
) {
    companion object {
        /**
         * The step a running scan is on (#65 follow-up). The scan does each step for every photo
         * before the next, so the first step with work left is the current one.
         */
        fun of(found: Int, processed: Int, detect: Int, fields: Int, redact: Int, running: Boolean, failed: Boolean) =
            when {
                processed < found -> ScanProgress(found, processed, running, failed, ScanStage.FINGERPRINT, processed, found - processed)
                detect > 0 -> ScanProgress(found, processed, running, failed, ScanStage.DETECT, processed - detect, detect)
                fields > 0 -> ScanProgress(found, processed, running, failed, ScanStage.FIELDS, 0, fields)
                redact > 0 -> ScanProgress(found, processed, running, failed, ScanStage.REDACT, 0, redact)
                else -> ScanProgress(found, processed, running, failed, ScanStage.FINISHING)
            }
    }
}

/** The steps of a scan, in order (§8.2). */
enum class ScanStage { FINGERPRINT, DETECT, FIELDS, REDACT, FINISHING }

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
    private val scanPrefs: ScanPrefs? = null,
) : ViewModel() {
    /** New photos are processed only while charging (#27). */
    val chargingOnly: StateFlow<Boolean> = (scanPrefs?.chargingOnly ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setChargingOnly(value: Boolean) {
        val prefs = scanPrefs ?: return
        viewModelScope.launch {
            prefs.setChargingOnly(value)
            ScanWorker.watchNewPhotos(workManager, value)
        }
    }

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
        combine(
            combine(dao.countAll(), dao.countProcessed(), ::Pair),
            combine(dao.countAwaitingDetection(), dao.countAwaitingLocalization(), dao.countAwaitingRedaction(), ::Triple),
            status,
        ) { (found, processed), (detect, fields, redact), work ->
            ScanProgress.of(found, processed, detect, fields, redact, work.running, work.failed)
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
                    container.scanPrefs,
                )
            }
        }
    }
}
