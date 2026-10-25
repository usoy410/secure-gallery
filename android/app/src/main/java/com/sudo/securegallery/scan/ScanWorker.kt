package com.sudo.securegallery.scan

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sudo.securegallery.SecureGalleryApplication
import com.sudo.securegallery.secure.ReadyNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * Runs [GalleryScanner] in the background, one image at a time (§8.2). Started by "Scan now"
 * ([enqueue], no constraints) or by a change to the gallery ([watchNewPhotos], #27). Either way,
 * the run ends by re-arming the gallery trigger, because a content trigger fires only once.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SecureGalleryApplication).container
        try {
            // Long scans must run in the foreground, or Android stops them after 10 minutes.
            setForeground(ScanNotification.foregroundInfo(applicationContext))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground start from the background (a gallery trigger with
            // the app closed). Such a run has only a few new photos, so it goes on without one.
            Log.w(TAG, "scan without foreground: ${e.javaClass.simpleName}")
        }
        return try {
            // A triggered scan and "Scan now" can start together; only one walks the gallery.
            oneScanAtATime.withLock { container.galleryScanner.scan() }
            ReadyNotification.post(applicationContext, container.database.itemDao().countReadyToSecure().first())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class and message only: no exception here carries OCR text or field values (§8.4).
            Log.e(TAG, "scan failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure()
        } finally {
            watchNewPhotos(WorkManager.getInstance(applicationContext), container.scanPrefs.chargingOnly())
        }
    }

    companion object {
        const val UNIQUE_NAME = "gallery-scan"
        const val WATCH_NAME = "gallery-scan-new-photos"
        private const val TAG = "SecureGalleryScan"
        private val oneScanAtATime = Mutex()

        /**
         * Scans again when MediaStore's images change (a new photo or screenshot), waiting 30 s
         * for more changes and at most 2 min, while the battery is not low, and only while
         * charging if [chargingOnly]. Replaces any earlier trigger, so the setting applies at once.
         */
        fun watchNewPhotos(workManager: WorkManager, chargingOnly: Boolean) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
                .setTriggerContentMaxDelay(Duration.ofMinutes(2))
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(chargingOnly)
                .build()
            workManager.enqueueUniqueWork(
                WATCH_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(constraints).build(),
            )
        }

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
