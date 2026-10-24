package com.sudo.securegallery.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sudo.securegallery.SecureGalleryApplication
import kotlinx.coroutines.CancellationException

/** Runs [GalleryScanner] in the background, one image at a time (§8.2). */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val container = (applicationContext as SecureGalleryApplication).container
        container.galleryScanner.scan()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class and message only: no exception here carries OCR text or field values (§8.4).
        Log.e(TAG, "scan failed: ${e.javaClass.simpleName}: ${e.message}")
        Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "gallery-scan"
        private const val TAG = "SecureGalleryScan"

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
