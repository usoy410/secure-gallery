package com.sudo.securegallery.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One unlock of the vault (§8.3). Holds the data key in memory while unlocked and wipes it on
 * [lock], which happens when the app goes to the background or after [IDLE_MILLIS] without
 * [touch].
 */
class VaultSession(private val scope: CoroutineScope) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var key: Vault.Key? = null
    private var idleTimer: Job? = null

    /** The data key, or null while locked. Counts as activity. */
    fun key(): Vault.Key? = key?.also { touch() }

    fun start(newKey: Vault.Key) {
        key?.takeIf { it !== newKey }?.close()
        key = newKey
        _unlocked.value = true
        touch()
    }

    /** Call on user activity in vault screens; restarts the idle timer. */
    fun touch() {
        if (key == null) return
        idleTimer?.cancel()
        idleTimer = scope.launch {
            delay(IDLE_MILLIS)
            lock()
        }
    }

    fun lock() {
        idleTimer?.cancel()
        idleTimer = null
        key?.close()
        key = null
        _unlocked.value = false
    }

    companion object {
        const val IDLE_MILLIS = 2 * 60_000L
    }
}
