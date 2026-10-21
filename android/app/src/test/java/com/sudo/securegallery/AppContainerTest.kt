package com.sudo.securegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
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
}
