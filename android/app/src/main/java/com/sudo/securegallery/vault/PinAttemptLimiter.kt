package com.sudo.securegallery.vault

/**
 * Slows down PIN guessing (§8.3): the first [FREE_ATTEMPTS] wrong PINs cost nothing, then each
 * one doubles the wait, from 30 seconds up to one hour. A correct PIN resets the count. The
 * count is saved, so restarting the app does not reset it.
 */
class PinAttemptLimiter(
    private val store: VaultStore,
    private val now: () -> Long,
) {
    /** Milliseconds until the next attempt is allowed; 0 if allowed now. */
    fun waitMillis(): Long {
        val (failures, lastFailure) = store.readAttempts()
        val delay = delayAfter(failures)
        return (lastFailure + delay - now()).coerceAtLeast(0)
    }

    fun recordFailure() {
        val (failures, _) = store.readAttempts()
        store.writeAttempts(failures + 1, now())
    }

    fun recordSuccess() = store.writeAttempts(0, 0)

    companion object {
        const val FREE_ATTEMPTS = 4
        private const val FIRST_DELAY = 30_000L
        private const val MAX_DELAY = 60 * 60_000L

        fun delayAfter(failures: Int): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)
            return (FIRST_DELAY shl doublings).coerceAtMost(MAX_DELAY)
        }
    }
}
