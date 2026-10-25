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
import com.sudo.securegallery.secure.DeletePhoto
import com.sudo.securegallery.secure.RestoreOriginal
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

/**
 * One item open in the viewer (#23): the redacted copy, and the original once the eye toggle
 * decrypted it. The original lives in memory only and is dropped when the vault locks.
 */
data class ViewerState(
    val item: Item,
    val redacted: Bitmap,
    val original: Bitmap? = null,
    val showOriginal: Boolean = false,
    val loading: Boolean = false,
)

enum class InboxMessage { VAULT_LOCKED, NOT_AVAILABLE, SAVED, SAVED_NEEDS_CHECK, SAVE_FAILED, DELETED, DELETE_KEPT, RESTORED, RESTORE_FAILED }

/**
 * The review inbox (§8.4, #24): the list of processed items, NEEDS_CHECK first, the viewer
 * with the eye toggle (#23) and the box editor. Both show the original only while the vault is
 * unlocked (§8.3), and drop it when the vault locks.
 */
class InboxViewModel(
    dao: ItemDao,
    private val session: VaultSession?,
    private val corrector: CorrectRedaction?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deleter: DeletePhoto? = null,
    private val restorer: RestoreOriginal? = null,
) : ViewModel() {
    val items: StateFlow<List<Item>> = dao.inbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The gallery grid on the scan tab (#77), newest first. */
    val galleryItems: StateFlow<List<Item>> = dao.gallery().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while the viewer's "delete for good" warning is open (#77). */
    private val _deleteWarning = MutableStateFlow(false)
    val deleteWarning: StateFlow<Boolean> = _deleteWarning.asStateFlow()
    private var pendingDelete: Item? = null

    /** True while the viewer's "not sensitive" confirmation is open (#25). */
    private val _restoreConfirm = MutableStateFlow(false)
    val restoreConfirm: StateFlow<Boolean> = _restoreConfirm.asStateFlow()

    /** What to do once the vault unlocks, for a step that asked for it. */
    private var afterUnlock: (() -> Unit)? = null

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _message = MutableStateFlow<InboxMessage?>(null)
    val message: StateFlow<InboxMessage?> = _message.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    /** True while the eye toggle waits for the user to unlock the vault. */
    private val _unlockRequested = MutableStateFlow(false)
    val unlockRequested: StateFlow<Boolean> = _unlockRequested.asStateFlow()

    init {
        session?.let { s ->
            viewModelScope.launch {
                s.unlocked.collect { unlocked ->
                    if (!unlocked) {
                        // The original is on screen only while unlocked.
                        close()
                        _viewer.update { it?.copy(original = null, showOriginal = false, loading = false) }
                    } else if (_unlockRequested.value) {
                        _unlockRequested.value = false
                        afterUnlock?.invoke()
                        afterUnlock = null
                    }
                }
            }
        }
    }

    /** Opens the viewer on [item]'s redacted copy. */
    fun open(item: Item) {
        val corrector = corrector ?: return
        viewModelScope.launch {
            val decoded = withContext(io) { runCatching { corrector.copyPreview(item, PREVIEW_MAX_SIDE) }.getOrNull() }
            if (decoded == null) {
                _message.value = InboxMessage.NOT_AVAILABLE
                return@launch
            }
            _viewer.value = ViewerState(item, decoded.bitmap)
        }
    }

    /** The eye toggle: original or redacted copy. While locked it asks for an unlock first. */
    fun toggleOriginal() {
        val state = _viewer.value ?: return
        if (state.showOriginal) {
            _viewer.value = state.copy(showOriginal = false)
            return
        }
        val key = session?.key() ?: return askUnlock(::toggleOriginal)
        if (state.original != null) {
            _viewer.value = state.copy(showOriginal = true)
            return
        }
        val corrector = corrector ?: return
        _viewer.value = state.copy(loading = true)
        viewModelScope.launch {
            val decoded = withContext(io) { runCatching { corrector.preview(key, state.item, PREVIEW_MAX_SIDE) }.getOrNull() }
            if (decoded == null) _message.value = InboxMessage.NOT_AVAILABLE
            _viewer.update { v ->
                if (v?.item?.id != state.item.id || session?.key() == null) v?.copy(loading = false)
                else v.copy(original = decoded?.bitmap, showOriginal = decoded != null, loading = false)
            }
        }
    }

    fun requestDelete() {
        if (_viewer.value != null) _deleteWarning.value = true
    }

    fun cancelDelete() {
        _deleteWarning.value = false
    }

    /**
     * After the warning: hands the public files to [launch] for the system delete dialog. If none
     * is left in the gallery, the vault file and the item go at once.
     */
    fun confirmDelete(launch: (List<Long>) -> Unit) {
        _deleteWarning.value = false
        val item = _viewer.value?.item ?: return
        val deleter = deleter ?: return
        viewModelScope.launch {
            val ids = withContext(io) { deleter.publicFiles(item) }
            pendingDelete = item
            if (ids.isEmpty()) onDeleteResult(true) else launch(ids)
        }
    }

    /** The system delete dialog's answer. */
    fun onDeleteResult(allowed: Boolean) {
        val item = pendingDelete ?: return
        val deleter = deleter ?: return
        pendingDelete = null
        viewModelScope.launch {
            val deleted = withContext(io) { runCatching { deleter.finish(item, allowed) }.getOrDefault(false) }
            _message.value = if (deleted) InboxMessage.DELETED else InboxMessage.DELETE_KEPT
            if (deleted) closeViewer()
        }
    }

    fun requestRestore() {
        if (_viewer.value != null) _restoreConfirm.value = true
    }

    fun cancelRestore() {
        _restoreConfirm.value = false
    }

    /**
     * "Not sensitive" (#25): the original goes back to its album, and the copy and the vault
     * file go. Needs the unlocked vault; while locked it asks for an unlock first.
     */
    fun confirmRestore() {
        _restoreConfirm.value = false
        val item = _viewer.value?.item ?: return
        val restorer = restorer ?: return
        val key = session?.key() ?: return askUnlock(::confirmRestore)
        viewModelScope.launch {
            val result = withContext(io) { runCatching { restorer.restore(key, item) }.getOrNull() }
            val restored = result is RestoreOriginal.Result.Restored
            _message.value = if (restored) InboxMessage.RESTORED else InboxMessage.RESTORE_FAILED
            if (restored) closeViewer()
        }
    }

    private fun askUnlock(then: () -> Unit) {
        afterUnlock = then
        _unlockRequested.value = true
    }

    fun cancelUnlock() {
        afterUnlock = null
        _unlockRequested.value = false
    }

    fun closeViewer() {
        _viewer.value = null
        _unlockRequested.value = false
        afterUnlock = null
        _deleteWarning.value = false
        _restoreConfirm.value = false
    }

    /** Opens the box editor on [item]; needs the unlocked vault. */
    fun openEditor(item: Item) {
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
            if (result is CorrectRedaction.Result.Saved) {
                // The viewer showed the old copy: back to the list.
                close()
                closeViewer()
            } else {
                _editor.update { it?.copy(saving = false) }
            }
        }
    }

    fun close() {
        _editor.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        close()
        closeViewer()
    }

    private fun edit(change: (EditorState) -> EditorState) {
        session?.touch()
        _editor.update { s -> s?.takeIf { !it.saving }?.let(change) ?: s }
    }

    companion object {
        /** Largest side of the editor's preview, enough for a phone screen. */
        const val PREVIEW_MAX_SIDE = 2048

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InboxViewModel(
                    container.database.itemDao(),
                    container.vaultSession,
                    container.correctRedaction,
                    deleter = container.deletePhoto,
                    restorer = container.restoreOriginal,
                )
            }
        }
    }
}
