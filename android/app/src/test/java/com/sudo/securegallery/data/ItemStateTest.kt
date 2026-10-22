package com.sudo.securegallery.data

import com.sudo.securegallery.data.ItemState.FAILED
import com.sudo.securegallery.data.ItemState.NEEDS_CHECK
import com.sudo.securegallery.data.ItemState.NOT_DETECTED
import com.sudo.securegallery.data.ItemState.ORIGINAL_STILL_PUBLIC
import com.sudo.securegallery.data.ItemState.PENDING
import com.sudo.securegallery.data.ItemState.REDACTED
import com.sudo.securegallery.data.ItemState.RESTORED
import com.sudo.securegallery.data.ItemState.VAULTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State transitions from §8.4 (issue #15). */
class ItemStateTest {

    private fun item(state: ItemState) = Item(mediaStoreId = 1, dateModified = 0, sizeBytes = 0, state = state)

    @Test
    fun newItemsStartPending() {
        assertEquals(PENDING, Item(mediaStoreId = 1, dateModified = 0, sizeBytes = 0).state)
    }

    @Test
    fun happyPathReachesVaulted() {
        val vaulted = item(PENDING).moveTo(REDACTED).moveTo(VAULTED)
        assertEquals(VAULTED, vaulted.state)
    }

    @Test
    fun deniedDeletionCanBeRetriedOrRestored() {
        val publicItem = item(REDACTED).moveTo(ORIGINAL_STILL_PUBLIC)
        assertTrue(publicItem.state.canMoveTo(VAULTED))
        assertTrue(publicItem.state.canMoveTo(RESTORED))
    }

    @Test
    fun anyProcessingStepCanFailBeforeTheOriginalIsDeleted() {
        for (state in listOf(PENDING, REDACTED, NEEDS_CHECK)) {
            assertTrue(state.name, state.canMoveTo(FAILED))
        }
        assertFalse(VAULTED.canMoveTo(FAILED))
    }

    @Test
    fun restoredIsFinal() {
        // §8.4: an image marked "not sensitive" is never flagged again automatically.
        for (next in ItemState.entries) {
            assertFalse(next.name, RESTORED.canMoveTo(next))
        }
    }

    @Test
    fun onlyUnflaggedOrFailedItemsGoBackToPending() {
        val back = ItemState.entries.filter { it.canMoveTo(PENDING) }.toSet()
        assertEquals(setOf(NOT_DETECTED, FAILED), back)
    }

    @Test
    fun vaultedOriginalsCannotSkipBackToPublic() {
        assertFalse(VAULTED.canMoveTo(ORIGINAL_STILL_PUBLIC))
        assertFalse(VAULTED.canMoveTo(REDACTED))
        assertFalse(PENDING.canMoveTo(VAULTED))
    }

    @Test(expected = IllegalStateException::class)
    fun illegalMoveThrows() {
        item(PENDING).moveTo(VAULTED)
    }
}
