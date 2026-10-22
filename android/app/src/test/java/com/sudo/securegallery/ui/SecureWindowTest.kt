package com.sudo.securegallery.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [SecureWindow] sets FLAG_SECURE only while it is on screen (issue #21). */
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun flagFollowsTheComposable() {
        val shown = mutableStateOf(true)
        rule.setContent { if (shown.value) SecureWindow() }
        rule.waitForIdle()
        assertEquals(true, secure())
        shown.value = false
        rule.waitForIdle()
        assertEquals(false, secure())
    }
}
