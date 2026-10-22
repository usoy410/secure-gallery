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

/** Counts shown on the scan screen. */
data class ScanProgress(val found: Int = 0, val processed: Int = 0, val running: Boolean = false)

class ScanViewModel(
    dao: ItemDao,
    private val workManager: WorkManager,
) : ViewModel() {

    private val running: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(ScanWorker.UNIQUE_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    val progress: StateFlow<ScanProgress> =
        combine(dao.countAll(), dao.countProcessed(), running, ::ScanProgress)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanProgress())

    fun scanNow() = ScanWorker.enqueue(workManager)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScanViewModel(container.database.itemDao(), container.workManager) }
        }
    }
}
