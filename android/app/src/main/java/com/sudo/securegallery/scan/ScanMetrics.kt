package com.sudo.securegallery.scan

import androidx.work.Data
import java.util.Locale

/** Per-run aggregate diagnostics only: never image identifiers, paths, or OCR content. */
class ScanMetrics(private val clock: () -> Long = System::nanoTime) {
    enum class Stage { INDEX, HASH, DECODE, DETECT, OCR, REDACT, RECHECK }
    private val started = clock()
    private val nanos = LongArray(Stage.entries.size)
    private val calls = IntArray(Stage.entries.size)
    var checked = 0
    var candidates = 0
    var detectionFailures = 0
    var detectionTotal = 0
    var phase = "INDEX"

    suspend fun <T> measure(stage: Stage, block: suspend () -> T): T {
        val start = clock()
        try {
            return block()
        } finally {
            nanos[stage.ordinal] += (clock() - start).coerceAtLeast(0)
            calls[stage.ordinal]++
        }
    }

    fun data(): Data = Data.Builder()
        .putString("scanPhase", phase)
        .putInt("checked", checked)
        .putInt("candidates", candidates)
        .putInt("detectionFailures", detectionFailures)
        .putInt("detectionTotal", detectionTotal)
        .putString("scanDetails", buildString {
            appendLine("This scan • elapsed ${seconds(clock() - started)} s")
            appendLine("Detection: $checked / $detectionTotal images checked; $candidates possible cards; $detectionFailures attempts need retry")
            for (stage in Stage.entries) {
                if (calls[stage.ordinal] == 0) continue
                val total = nanos[stage.ordinal]
                appendLine("${stage.name.lowercase()}: ${seconds(total)} s / ${calls[stage.ordinal]} calls (avg ${seconds(total / calls[stage.ordinal])} s)")
            }
            append("Decode = detection/OCR image loading. Redact includes saving and recheck; recheck measures number extraction and saved-copy checks (a subset). Totals include failed attempts. Elapsed also includes database/progress work. Counts are for this run, not the entire library; possible cards are model predictions.")
        }).build()

    private fun seconds(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000_000.0)
}
