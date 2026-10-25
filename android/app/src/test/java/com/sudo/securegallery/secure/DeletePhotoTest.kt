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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Deleting a photo (issue #77): the public files go through the system dialog, and the vault
 * file and the item go only once that dialog removed them. A denied dialog deletes nothing.
 */
@RunWith(AndroidJUnit4::class)
class DeletePhotoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao
    private lateinit var vault: Vault
    private val gallery = FakeGallery()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.itemDao()
        vault = testVault(tmp.root)
        vault.setUp("123456".toCharArray())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun item(state: ItemState): Item {
        gallery.ids += 900L
        if (state != ItemState.VAULTED) gallery.ids += 7L
        dao.insertNew(listOf(Item(mediaStoreId = 7, dateModified = 1, sizeBytes = 1)))
        val item = dao.byMediaStoreId(7)!!.copy(state = state, redactedMediaStoreId = 900, recheckPassed = true)
        dao.update(item)
        val key = (vault.unlock("123456".toCharArray()) as Vault.Unlock.Success).key
        vault.store(key, "item-${item.id}", byteArrayOf(1, 2, 3).inputStream())
        return item
    }

    private fun deleter() = DeletePhoto(dao, vault, gallery) { "item-${it.id}" }

    @Test
    fun aVaultedPhotoAsksOnlyForItsCopy() = runTest {
        val item = item(ItemState.VAULTED)
        assertEquals(listOf(900L), deleter().publicFiles(item))
    }

    @Test
    fun aPhotoWhoseOriginalIsStillPublicAsksForBoth() = runTest {
        val item = item(ItemState.REDACTED)
        assertEquals(listOf(900L, 7L), deleter().publicFiles(item))
    }

    @Test
    fun aDeniedDialogKeepsEverything() = runTest {
        val item = item(ItemState.VAULTED)
        assertFalse(deleter().finish(item, allowed = false))
        assertTrue(vault.contains("item-${item.id}"))
        assertNotNull(dao.byId(item.id))
    }

    @Test
    fun anAllowedDialogRemovesTheVaultFileAndTheItem() = runTest {
        val item = item(ItemState.VAULTED)
        gallery.ids -= 900L // the system dialog deleted the copy
        assertTrue(deleter().finish(item, allowed = true))
        assertFalse(vault.contains("item-${item.id}"))
        assertNull(dao.byId(item.id))
    }

    @Test
    fun aPublicFileLeftBehindKeepsTheVaultFile() = runTest {
        // "Allowed", but the original is still in the gallery: the vault file stays.
        val item = item(ItemState.ORIGINAL_STILL_PUBLIC)
        gallery.ids -= 900L
        assertFalse(deleter().finish(item, allowed = true))
        assertTrue(vault.contains("item-${item.id}"))
        assertNotNull(dao.byId(item.id))
    }

    private class FakeGallery : RestoreOriginal.Gallery {
        val ids = mutableSetOf<Long>()
        override fun locate(id: Long): Pair<String?, String>? = if (id in ids) null to "image.jpg" else null
        override fun exists(id: Long) = id in ids
        override fun insert(relativePath: String?, displayName: String, mimeType: String, bytes: ByteArray): Long = error("not used")
        override fun read(id: Long): ByteArray? = null
        override fun delete(id: Long) = error("deletion goes through the system dialog")
    }
}
