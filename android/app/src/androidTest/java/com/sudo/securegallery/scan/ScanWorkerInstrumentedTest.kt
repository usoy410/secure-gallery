package com.sudo.securegallery.scan

import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.sudo.securegallery.SecureGalleryApplication
import com.sudo.securegallery.data.ItemState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real worker + MediaStore integration for #27; only synthetic fixture bytes are inserted. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31) // executeShellCommandRw was added in API 31.
class ScanWorkerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecureGalleryApplication

    private fun shell(command: String): String {
        val pipes = instrumentation.uiAutomation.executeShellCommandRwe(command)
        try {
            pipes[1].close() // These commands do not consume stdin.
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0])
                .bufferedReader().use { it.readText() }
            val error = ParcelFileDescriptor.AutoCloseInputStream(pipes[2])
                .bufferedReader().use { it.readText() }
            check(error.isBlank()) { "Synthetic fixture shell operation failed: $error" }
            check(!output.contains("[ERROR]")) { "Synthetic fixture command was invalid: $output" }
            return output
        } finally {
            pipes.forEach { it.close() }
        }
    }

    @Test
    fun aNewGalleryPhotoIsProcessedAndTheWatchIsRearmed() = runBlocking {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        val container = app.container
        assertNotNull("The bundled detector must be usable", container.detector)
        val manager = container.workManager
        val dao = container.database.itemDao()
        val request = OneTimeWorkRequestBuilder<ScanWorker>().build()
        val name = "sg-background-${UUID.randomUUID()}.jpg"
        // Shell ownership represents another app (e.g. the camera). App-owned images
        // are deliberately filtered out by ImageQuery and would not exercise scanning.
        shell(
            "content insert --uri content://media/external_primary/images/media " +
                "--bind _display_name:s:$name --bind mime_type:s:image/jpeg " +
                "--bind relative_path:s:Pictures/SGBackgroundTest/ --bind is_pending:i:1",
        )
        // Android 14's content insert command does not print the returned URI.
        // Query our unique synthetic name instead of assuming an output format.
        val rows = shell(
            "content query --uri content://media/external_primary/images/media " +
                "--projection _id:_display_name " +
                "--extra android\\:query-arg-match-pending:i:1",
        )
        val row = checkNotNull(rows.lineSequence().firstOrNull { it.contains(name) }) {
            "Could not find the inserted synthetic MediaStore fixture"
        }
        val id = checkNotNull(Regex("_id=(\\d+)").find(row)?.groupValues?.get(1)).toLong()
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id,
        )
        try {
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("content write --uri $uri")
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                    instrumentation.context.assets.open("redaction/r01_philsys-card.jpg").use {
                        it.copyTo(output)
                    }
                }
                ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            } finally {
                pipes.forEach { it.close() }
            }
            shell("content update --uri $uri --bind is_pending:i:0")
            assertTrue(container.imageSource.listImages().any { it.mediaStoreId == id })

            manager.enqueue(request).result.get()
            val state = withTimeout(120_000) {
                var state = manager.getWorkInfoById(request.id).get()!!.state
                while (!state.isFinished) {
                    delay(250)
                    state = manager.getWorkInfoById(request.id).get()!!.state
                }
                state
            }
            assertEquals(WorkInfo.State.SUCCEEDED, state)
            val item = checkNotNull(dao.byMediaStoreId(id)) { "Worker did not index the new photo" }
            assertNotNull(item.contentHash)
            assertTrue(item.state == ItemState.REDACTED || item.state == ItemState.NEEDS_CHECK)
            assertNotNull("The worker must save a redacted copy", item.redactedMediaStoreId)
            val watch = manager.getWorkInfosForUniqueWork(ScanWorker.WATCH_NAME).get()
            assertTrue(watch.any { info ->
                info.state == WorkInfo.State.ENQUEUED &&
                    info.constraints.contentUriTriggers.any {
                        it.uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI && it.isTriggeredForDescendants
                    }
            })
        } finally {
            manager.cancelWorkById(request.id).result.get()
            manager.cancelUniqueWork(ScanWorker.WATCH_NAME).result.get()
            dao.byMediaStoreId(id)?.let { item ->
                item.redactedMediaStoreId?.let { copyId ->
                    app.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Images.Media._ID} = ?", arrayOf(copyId.toString()))
                }
                dao.delete(item)
            }
            shell("content delete --uri $uri")
        }
    }
}
