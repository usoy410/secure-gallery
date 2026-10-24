package com.sudo.securegallery.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.scan.ScanWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

class ScanViewModel(
    dao: ItemDao,
    private val workManager: WorkManager,
) : ViewModel() {

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
            initializer { ScanViewModel(container.database.itemDao(), container.workManager) }
        }
    }
}
