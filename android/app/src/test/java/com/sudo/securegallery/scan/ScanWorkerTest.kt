package com.sudo.securegallery.scan

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * New photos are processed in the background (issue #27): a content trigger on MediaStore's
 * images, battery not low, charging only when the setting is on, re-armed after every run.
 */
@RunWith(AndroidJUnit4::class)
class ScanWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun watch(): WorkInfo = workManager.getWorkInfosForUniqueWork(ScanWorker.WATCH_NAME).get().single()

    @Test
    fun theTriggerWatchesTheGalleryWhileTheBatteryIsNotLow() {
        ScanWorker.watchNewPhotos(workManager, chargingOnly = false)
        val info = watch()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        val trigger = info.constraints.contentUriTriggers.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, trigger.uri)
        assertTrue(trigger.isTriggeredForDescendants)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertFalse(info.constraints.requiresCharging())
    }

    @Test
    fun theChargingSettingReplacesTheTrigger() {
        ScanWorker.watchNewPhotos(workManager, chargingOnly = false)
        ScanWorker.watchNewPhotos(workManager, chargingOnly = true)
        assertTrue(watch().constraints.requiresCharging()) // one trigger, the newest settings
    }

    @Test
    fun everyRunReArmsTheTrigger() = runTest {
        val result = TestListenableWorkerBuilder<ScanWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(WorkInfo.State.ENQUEUED, watch().state)
    }
}
