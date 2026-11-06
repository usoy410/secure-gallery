package com.sudo.securegallery.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMetricsTest {
    @Test
    fun sumsCallsAndCountsFailedAttemptsWithoutPersistingExceptionText() = runTest {
        var now = 0L
        val metrics = ScanMetrics { now }
        metrics.measure(ScanMetrics.Stage.DETECT) { now += 2_000_000_000L }
        try {
            metrics.measure(ScanMetrics.Stage.DETECT) {
                now += 4_000_000_000L
                error("sensitive text must not be recorded")
            }
        } catch (_: IllegalStateException) { }
        val report = metrics.data().getString("scanDetails")!!
        assertTrue(report.contains("detect: 6.00 s / 2 calls (avg 3.00 s)"))
        assertTrue(!report.contains("sensitive text"))
        assertEquals(0, metrics.data().getInt("checked", -1))
    }

    @Test
    fun preservesCancellationAndRecordsTimeSpent() = runTest {
        var now = 0L
        val metrics = ScanMetrics { now }
        val cancelled = CancellationException("stop")
        try {
            metrics.measure(ScanMetrics.Stage.OCR) {
                now += 1_000_000_000L
                throw cancelled
            }
        } catch (e: CancellationException) {
            assertTrue(e === cancelled)
        }
        assertTrue(metrics.data().getString("scanDetails")!!.contains("ocr: 1.00 s / 1 calls"))
    }
}
