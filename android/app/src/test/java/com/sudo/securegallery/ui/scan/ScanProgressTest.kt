package com.sudo.securegallery.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scan screen names the step a running scan is on, not only the fingerprint count. */
class ScanProgressTest {
    @Test
    fun fingerprintingComesFirst() {
        val p = ScanProgress.of(found = 1681, processed = 900, detect = 900, fields = 0, redact = 0, running = true, failed = false)
        assertEquals(ScanStage.FINGERPRINT, p.stage)
        assertEquals(781, p.stageLeft)
    }

    @Test
    fun thenTheDetectorCountsThePhotosItLookedAt() {
        val p = ScanProgress.of(found = 1681, processed = 1681, detect = 1341, fields = 2, redact = 0, running = true, failed = false)
        assertEquals(ScanStage.DETECT, p.stage)
        assertEquals(340, p.stageDone)
        assertEquals(1341, p.stageLeft)
    }

    @Test
    fun thenFieldsThenCopiesThenFinishing() {
        assertEquals(ScanStage.FIELDS, ScanProgress.of(10, 10, 0, 3, 1, true, false).stage)
        assertEquals(ScanStage.REDACT, ScanProgress.of(10, 10, 0, 0, 2, true, false).stage)
        assertEquals(2, ScanProgress.of(10, 10, 0, 0, 2, true, false).stageLeft)
        assertEquals(ScanStage.FINISHING, ScanProgress.of(10, 10, 0, 0, 0, true, false).stage)
    }
}
