package com.sudo.securegallery.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * The batch export (#29) is debug only: it lives in the instrumented-test APK, so a release
 * build can never contain it. Unit tests run against the app's own classes, so the export
 * class must not be found here, and the app's manifest must not mention it.
 */
class EvalNotInAppTest {
    @Test
    fun theExportIsNotPartOfTheApp() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.sudo.securegallery.eval.BatchExportTest")
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("eval", ignoreCase = true))
        assertFalse(manifest.contains("android:debuggable=\"true\""))
    }
}
