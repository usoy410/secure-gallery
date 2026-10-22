package com.sudo.securegallery.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unlocked session locks after 2 idle minutes or on demand, and wipes the key (issue #21). */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionTest {
    private val scope = TestScope()
    private val session = VaultSession(scope)
    private fun newKey() = Vault.Key(ByteArray(32) { 7 })

    @Test
    fun locksAfterTwoIdleMinutesAndWipesTheKey() {
        val key = newKey()
        session.start(key)
        assertTrue(session.unlocked.value)
        scope.advanceTimeBy(VaultSession.IDLE_MILLIS - 1)
        scope.runCurrent()
        assertTrue(session.unlocked.value)
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertFalse(session.unlocked.value)
        assertNull(session.key())
        assertTrue(key.bytes.all { it == 0.toByte() })
    }

    @Test
    fun activityRestartsTheIdleTimer() {
        session.start(newKey())
        scope.advanceTimeBy(90_000)
        session.touch()
        scope.advanceTimeBy(90_000)
        scope.runCurrent()
        assertTrue(session.unlocked.value)
        assertNotNull(session.key()) // reading the key also counts as activity
        scope.advanceTimeBy(VaultSession.IDLE_MILLIS + 1)
        scope.runCurrent()
        assertFalse(session.unlocked.value)
    }

    @Test
    fun lockIsImmediateForExampleOnBackground() {
        val key = newKey()
        session.start(key)
        session.lock()
        assertFalse(session.unlocked.value)
        assertTrue(key.bytes.all { it == 0.toByte() })
        session.touch() // nothing to keep alive
        assertNull(session.key())
    }

    @Test
    fun aNewUnlockWipesThePreviousKey() {
        val first = newKey()
        session.start(first)
        session.start(newKey())
        assertTrue(first.bytes.all { it == 0.toByte() })
        assertTrue(session.unlocked.value)
    }
}
