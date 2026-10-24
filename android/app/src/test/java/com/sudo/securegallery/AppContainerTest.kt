package com.sudo.securegallery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit test for [AppContainer] manual dependency injection container.
 */
@RunWith(AndroidJUnit4::class)
class AppContainerTest {

    @Test
    fun defaultAppContainer_initializesWithContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context)

        assertNotNull(container)
        assertSame(context, container.context)
    }

    @Test
    fun scanStillRunsWhenTheDetectorAndLocalizerCannotBeBuilt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(
            context,
            openDetector = { throw IllegalStateException("GPU delegate failed") },
            buildLocalizer = { _, _ -> throw java.io.FileNotFoundException("templates") },
        )
        assertNull(container.detector)
        assertNull(container.fieldLocalizer)
        val source = object : ImageSource {
            override suspend fun listImages() = listOf(GalleryImage(1, 1, 3))
            override fun open(mediaStoreId: Long) = "abc".byteInputStream()
        }
        val dao = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build().itemDao()
        // The scanner the container builds, with the same (absent) detector and localizer.
        GalleryScanner(source, dao, container.detector, localizer = container.fieldLocalizer).scan()
        assertEquals(1, dao.countProcessed().first())
        assertNotNull(container.galleryScanner)
    }
}
