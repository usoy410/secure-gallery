package com.sudo.securegallery.secure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.testVault
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * Only originals with a verified vault copy and a rechecked redacted copy are offered for
 * deletion, and the delete dialog's answer sets VAULTED or ORIGINAL_STILL_PUBLIC (issue #22).
 */
@RunWith(AndroidJUnit4::class)
class SecureOriginalsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private lateinit var vault: Vault
    private lateinit var key: Vault.Key
    private val files = mutableMapOf<Long, ByteArray>()
    private val source = object : ImageSource {
        override suspend fun listImages() = emptyList<GalleryImage>()
        override fun open(mediaStoreId: Long) = files[mediaStoreId]?.let(::ByteArrayInputStream)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.itemDao()
        vault = testVault(tmp.root)
        vault.setUp("123456".toCharArray())
        key = (vault.unlock("123456".toCharArray()) as Vault.Unlock.Success).key
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(id: Long, recheckPassed: Boolean = true, copy: Long? = 900 + id, bytes: String = "original $id"): Item {
        files[id] = bytes.toByteArray()
        val hash = GalleryScanner.sha256(ByteArrayInputStream(bytes.toByteArray()))
        dao.insertNew(listOf(Item(mediaStoreId = id, dateModified = 1, sizeBytes = 1)))
        val item = dao.byMediaStoreId(id)!!.copy(
            contentHash = hash,
            state = ItemState.REDACTED,
            boxes = listOf(RedactionBox(FieldType.PCN, listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0.9f)),
            redactedMediaStoreId = copy,
            recheckPassed = recheckPassed,
        )
        dao.update(item)
        return item
    }

    @Test
    fun onlyRecheckedCopiesWithAVerifiedVaultCopyAreOffered() = runTest {
        add(1)
        add(2, recheckPassed = false)
        add(3, copy = null)
        val secure = SecureOriginals(dao, vault, source)
        val offered = secure.prepare(key)
        assertEquals(listOf(1L), offered.map { it.mediaStoreId })
        assertArrayEquals("original 1".toByteArray(), vault.read(key, secure.vaultId(offered.single())))
        assertFalse(vault.contains("item-2"))
    }

    @Test
    fun anOriginalChangedSinceTheScanIsNeitherVaultedNorOffered() = runTest {
        add(1)
        files[1] = "edited after the scan".toByteArray()
        assertEquals(emptyList<Item>(), SecureOriginals(dao, vault, source).prepare(key))
    }

    @Test
    fun allowMarksVaultedOnlyWhenTheOriginalIsGone() = runTest {
        add(1)
        add(2)
        val secure = SecureOriginals(dao, vault, source)
        val offered = secure.prepare(key)
        files.remove(1L) // the dialog deleted 1; 2 is somehow still there
        secure.finish(offered, allowed = true)
        assertEquals(ItemState.VAULTED, dao.byMediaStoreId(1)!!.state)
        assertEquals(ItemState.ORIGINAL_STILL_PUBLIC, dao.byMediaStoreId(2)!!.state)
    }

    @Test
    fun denyKeepsEverythingAndTheItemCanBeRetried() = runTest {
        add(1)
        val secure = SecureOriginals(dao, vault, source)
        secure.finish(secure.prepare(key), allowed = false)
        assertEquals(ItemState.ORIGINAL_STILL_PUBLIC, dao.byMediaStoreId(1)!!.state)
        assertArrayEquals("original 1".toByteArray(), files[1L]) // untouched
        // Retry: still offered, and the vault copy is reused, not written again.
        assertEquals(listOf(1L), secure.prepare(key).map { it.mediaStoreId })
    }
}
