package com.sudo.securegallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Placeholder instrumented test to verify the Android connected test environment
 * on the CI emulator (§8.9).
 */
@RunWith(AndroidJUnit4::class)
class PlaceholderInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.sudo.securegallery", appContext.packageName)
    }
}
