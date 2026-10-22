package com.sudo.securegallery.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sudo.securegallery.SecureGalleryApplication

/** Runs [GalleryScanner] in the background, one image at a time (§8.2). */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SecureGalleryApplication).container
        container.galleryScanner.scan()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "gallery-scan"

        /** Starts a scan unless one is already running. */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScanWorker>().build(),
            )
        }
    }
}
