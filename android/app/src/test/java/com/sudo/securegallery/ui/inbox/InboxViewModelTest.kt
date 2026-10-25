package com.sudo.securegallery.ui.inbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.redact.RedactedCopyStore
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.secure.CorrectRedaction
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultSession
import com.sudo.securegallery.vault.testVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * The inbox lists items NEEDS_CHECK first; the editor opens only while the vault is unlocked,
 * closes when it locks, and a save stores the counted corrections (issue #24).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private lateinit var vault: Vault
    private lateinit var session: VaultSession
    private val original = ByteArrayOutputStream().also {
        Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
    private var copy: ByteArray? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.itemDao()
        vault = testVault(tmp.root)
        vault.setUp("123456".toCharArray())
        session = VaultSession(sessionScope)
    }

    @After
    fun tearDown() {
        db.close()
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(): InboxViewModel {
        val source = object : ImageSource {
            override suspend fun listImages() = emptyList<GalleryImage>()
            override fun open(mediaStoreId: Long) = original.inputStream()
        }
        val store = object : RedactedCopyStore {
            override fun save(originalId: Long, jpeg: ByteArray): Long = error("not used")
            override fun read(copyId: Long) = copy
            override fun overwrite(copyId: Long, jpeg: ByteArray) {
                copy = jpeg
            }
        }
        val corrector = CorrectRedaction(dao, vault, source, store, recheck = null, vaultId = { "item-${it.id}" }) { bytes, _ ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inMutable = true })
            CorrectRedaction.Decoded(bitmap, bitmap.width, bitmap.height)
        }
        return InboxViewModel(dao, session, corrector, dispatcher)
    }

    private fun insert(mediaStoreId: Long, state: ItemState) = runBlocking {
        dao.insertNew(listOf(Item(mediaStoreId = mediaStoreId, dateModified = 1, sizeBytes = 1)))
        val box = RedactionBox(FieldType.PCN, listOf(10f, 10f, 60f, 10f, 60f, 30f, 10f, 30f), 0.9f)
        dao.update(
            dao.byMediaStoreId(mediaStoreId)!!.copy(
                contentHash = Vault.sha256Hex(original), state = state, boxes = listOf(box), redactedMediaStoreId = 900 + mediaStoreId,
            ),
        )
        dao.byMediaStoreId(mediaStoreId)!!
    }

    private fun unlock() = session.start((vault.unlock("123456".toCharArray()) as Vault.Unlock.Success).key)

    @Test
    fun needsCheckComesFirstAndUnprocessedItemsAreLeftOut() {
        insert(1, ItemState.REDACTED)
        insert(2, ItemState.NEEDS_CHECK)
        insert(3, ItemState.REDACTED)
        runBlocking { dao.insertNew(listOf(Item(mediaStoreId = 4, dateModified = 1, sizeBytes = 1))) }
        val vm = viewModel()
        val listed = runBlocking { withTimeout(5_000) { vm.items.first { it.isNotEmpty() } } }
        assertEquals(listOf(2L, 3L, 1L), listed.map { it.mediaStoreId })
    }

    @Test
    fun theEditorNeedsTheUnlockedVaultAndClosesWhenItLocks() {
        val item = insert(1, ItemState.REDACTED)
        val vm = viewModel()
        vm.open(item)
        assertEquals(InboxMessage.VAULT_LOCKED, vm.message.value)
        assertNull(vm.editor.value)

        unlock()
        vm.open(item)
        assertEquals(200f, vm.editor.value!!.edits.width)
        session.lock()
        assertNull(vm.editor.value)
    }

    @Test
    fun aSaveStoresTheBoxesAndTheCountedCorrections() {
        val item = insert(1, ItemState.REDACTED)
        unlock()
        val vm = viewModel()
        vm.open(item)
        vm.select(0)
        vm.move(20f, 0f)
        vm.move(20f, 0f)
        vm.endGesture()
        vm.add()
        vm.save()
        // Without a recheck the copy is never marked as passed.
        assertEquals(InboxMessage.SAVED_NEEDS_CHECK, runBlocking { withTimeout(5_000) { vm.message.first { it != null } } })
        assertNull(vm.editor.value)
        val stored = runBlocking { dao.byId(item.id)!! }
        assertEquals(2, stored.boxes.size)
        assertEquals(50f, stored.boxes[0].corners[0])
        assertEquals(listOf(1, 0, 1, 0), listOf(stored.correctedMoves, stored.correctedResizes, stored.correctedAdds, stored.correctedDeletes))
        assertEquals(2, stored.corrections)
        assertEquals(ItemState.NEEDS_CHECK, stored.state)
    }
}
