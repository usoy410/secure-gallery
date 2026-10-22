package com.sudo.securegallery.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery-code format and the wrong-PIN delay schedule (issue #20). */
class RecoveryAndLimiterTest {

    @Test
    fun recoveryCodesAre24UnambiguousCharacters() {
        val codes = List(50) { String(RecoveryCode.generate()) }
        assertTrue(codes.all { it.length == 24 && it.none { c -> c in "ILOU" } })
        assertEquals(50, codes.toSet().size)
    }

    @Test
    fun typedCodesAreNormalized() {
        val code = "0123456789ABCDEFGHJKMNPQ".toCharArray()
        assertEquals("0123-4567-89AB-CDEF-GHJK-MNPQ", RecoveryCode.format(code))
        val typed = " o123-4567-89ab-cdef-ghjk-mnpq ".toCharArray()
        assertEquals(String(code), String(RecoveryCode.normalize(typed)!!))
        assertEquals("1", String(RecoveryCode.normalize("I".repeat(24).toCharArray())!!).take(1))
        assertNull(RecoveryCode.normalize("too short".toCharArray()))
        assertNull(RecoveryCode.normalize("U".repeat(24).toCharArray()))
    }

    @Test
    fun delayDoublesAfterTheFreeAttemptsUpToOneHour() {
        val delays = (1..14).map(PinAttemptLimiter::delayAfter)
        assertEquals(listOf(0L, 0L, 0L, 0L, 30_000L, 60_000L, 120_000L, 240_000L), delays.take(8))
        assertEquals(3_600_000L, delays.last())
        assertEquals(3_600_000L, PinAttemptLimiter.delayAfter(1_000))
    }
}
