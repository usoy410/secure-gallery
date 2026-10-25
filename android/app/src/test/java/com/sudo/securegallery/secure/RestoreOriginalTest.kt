package com.sudo.securegallery.secure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.testVault
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * "Not sensitive" puts the original back and only then removes the copy and the vault file
 * (issue #25). Any failure deletes nothing.
 */
@RunWith(AndroidJUnit4::class)
class RestoreOriginalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private lateinit var vault: Vault
    private lateinit var key: Vault.Key
    private val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5)
    private val gallery = FakeGallery()

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

    /** An item whose original was vaulted and deleted, with its copy in "DCIM/Camera/". */
    private suspend fun vaulted(): Item {
        gallery.files[900] = Triple("DCIM/Camera/", "IMG_0001.sg-redacted.jpg", byteArrayOf(9))
        dao.insertNew(listOf(Item(mediaStoreId = 7, dateModified = 1, sizeBytes = 1)))
        val item = dao.byMediaStoreId(7)!!.copy(
            contentHash = Vault.sha256Hex(original), state = ItemState.VAULTED,
            redactedMediaStoreId = 900, recheckPassed = true,
        )
        dao.update(item)
        vault.store(key, "item-${item.id}", original.inputStream())
        return item
    }

    private fun restorer() = RestoreOriginal(dao, vault, gallery) { "item-${it.id}" }

    @Test
    fun theOriginalComesBackToItsAlbumThenTheCopyAndVaultFileGo() = runTest {
        val item = vaulted()
        val result = restorer().restore(key, item) as RestoreOriginal.Result.Restored
        val (newId, file) = gallery.files.entries.single().let { it.key to it.value }
        assertEquals(newId, result.mediaStoreId)
        assertEquals("DCIM/Camera/", file.first)
        assertEquals("IMG_0001.jpg", file.second)
        assertArrayEquals(original, file.third)
        assertFalse(vault.contains("item-${item.id}"))
        val restored = dao.byMediaStoreId(newId)!!
        assertEquals(ItemState.RESTORED, restored.state)
        assertEquals(1, restored.corrections)
        assertEquals(null, restored.redactedMediaStoreId)
        assertTrue(restored.state.let { s -> ItemState.entries.none { s.canMoveTo(it) } }) // never re-flagged
    }

    @Test
    fun aFailedWriteDeletesNothing() = runTest {
        val item = vaulted()
        gallery.corruptWrites = true
        assertTrue(restorer().restore(key, item) is RestoreOriginal.Result.Failed)
        assertTrue(900L in gallery.files) // the copy stays
        assertTrue(vault.contains("item-${item.id}")) // and so does the vault file
        assertEquals(ItemState.VAULTED, dao.byMediaStoreId(7)!!.state)
        assertEquals(setOf(900L), gallery.files.keys) // the bad restore was removed
    }

    @Test
    fun anOriginalStillInTheGalleryOnlyLosesItsCopy() = runTest {
        val item = vaulted().copy(state = ItemState.ORIGINAL_STILL_PUBLIC).also { dao.update(it) }
        gallery.files[7] = Triple("DCIM/Camera/", "IMG_0001.jpg", original)
        assertEquals(RestoreOriginal.Result.Restored(7), restorer().restore(key, item))
        assertEquals(setOf(7L), gallery.files.keys)
        assertEquals(ItemState.RESTORED, dao.byMediaStoreId(7)!!.state)
    }

    @Test
    fun imageTypeComesFromTheBytes() {
        assertEquals("image/jpeg" to ".jpg", RestoreOriginal.imageType(original))
        assertEquals(null, RestoreOriginal.imageType(byteArrayOf(1, 2, 3, 4, 5)))
    }

    private class FakeGallery : RestoreOriginal.Gallery {
        val files = sortedMapOf<Long, Triple<String?, String, ByteArray>>()
        var corruptWrites = false
        private var next = 1000L

        override fun locate(id: Long) = files[id]?.let { it.first to it.second }
        override fun exists(id: Long) = id in files
        override fun insert(relativePath: String?, displayName: String, mimeType: String, bytes: ByteArray): Long {
            val stored = if (corruptWrites) bytes.copyOf().also { it[it.size - 1]++ } else bytes
            return next++.also { files[it] = Triple(relativePath, displayName, stored) }
        }
        override fun read(id: Long) = files[id]?.third
        override fun delete(id: Long) {
            files.remove(id)
        }
    }
}
