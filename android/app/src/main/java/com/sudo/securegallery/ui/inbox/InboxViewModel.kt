package com.sudo.securegallery.ui.inbox

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sudo.securegallery.AppContainer
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.redact.BoxEdits
import com.sudo.securegallery.secure.CorrectRedaction
import com.sudo.securegallery.vault.VaultSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item open in the box editor (#24). [preview] is the original, scaled down; [edits] holds
 * the boxes in original-image pixels.
 */
data class EditorState(
    val item: Item,
    val preview: Bitmap,
    val edits: BoxEdits,
    val selected: Int? = null,
    val saving: Boolean = false,
)

enum class InboxMessage { VAULT_LOCKED, NOT_AVAILABLE, SAVED, SAVED_NEEDS_CHECK, SAVE_FAILED }

/**
 * The review inbox (§8.4, #24): the list of processed items, NEEDS_CHECK first, and the box
 * editor. The editor shows the original, so it opens only while the vault is unlocked (§8.3).
 */
class InboxViewModel(
    dao: ItemDao,
    private val session: VaultSession?,
    private val corrector: CorrectRedaction?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val items: StateFlow<List<Item>> = dao.inbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _message = MutableStateFlow<InboxMessage?>(null)
    val message: StateFlow<InboxMessage?> = _message.asStateFlow()

    init {
        // The editor shows the original: it closes as soon as the vault locks.
        session?.let { s -> viewModelScope.launch { s.unlocked.collect { if (!it) close() } } }
    }

    fun open(item: Item) {
        val corrector = corrector ?: return
        val key = session?.key() ?: run { _message.value = InboxMessage.VAULT_LOCKED; return }
        viewModelScope.launch {
            val decoded = withContext(io) { runCatching { corrector.preview(key, item, PREVIEW_MAX_SIDE) }.getOrNull() }
            if (decoded == null) {
                _message.value = InboxMessage.NOT_AVAILABLE
                return@launch
            }
            val edits = BoxEdits(item.boxes, decoded.width.toFloat(), decoded.height.toFloat())
            _editor.value = EditorState(item, decoded.bitmap, edits)
        }
    }

    fun select(index: Int?) = edit { it.copy(selected = index) }

    fun move(dx: Float, dy: Float) = edit { s -> s.selected?.let { s.copy(edits = s.edits.move(it, dx, dy)) } ?: s }

    fun moveCorner(corner: Int, x: Float, y: Float) =
        edit { s -> s.selected?.let { s.copy(edits = s.edits.moveCorner(it, corner, x, y)) } ?: s }

    fun endGesture() = edit { it.copy(edits = it.edits.endGesture()) }

    /** Adds a box in the middle of the image, a fifth of its shorter side across, and selects it. */
    fun add() = edit { s ->
        val e = s.edits
        val added = e.add(e.width / 2, e.height / 2, minOf(e.width, e.height) / 5)
        s.copy(edits = added, selected = added.boxes.lastIndex)
    }

    fun deleteSelected() = edit { s -> s.selected?.let { s.copy(edits = s.edits.delete(it), selected = null) } ?: s }

    fun save() {
        val state = _editor.value ?: return
        val corrector = corrector ?: return
        val key = session?.key()
        _editor.value = state.copy(saving = true)
        viewModelScope.launch {
            val result = withContext(io) { runCatching { corrector.save(key, state.item, state.edits.endGesture()) }.getOrNull() }
            _message.value = when (result) {
                is CorrectRedaction.Result.Saved ->
                    if (result.item.recheckPassed) InboxMessage.SAVED else InboxMessage.SAVED_NEEDS_CHECK
                else -> InboxMessage.SAVE_FAILED
            }
            if (result is CorrectRedaction.Result.Saved) close() else _editor.update { it?.copy(saving = false) }
        }
    }

    fun close() {
        _editor.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() = close()

    private fun edit(change: (EditorState) -> EditorState) {
        session?.touch()
        _editor.update { s -> s?.takeIf { !it.saving }?.let(change) ?: s }
    }

    companion object {
        /** Largest side of the editor's preview, enough for a phone screen. */
        const val PREVIEW_MAX_SIDE = 2048

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { InboxViewModel(container.database.itemDao(), container.vaultSession, container.correctRedaction) }
        }
    }
}
