package com.sudo.securegallery.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.ImageSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

/**
 * Vaulting an item end to end on Robolectric's app storage (issue #20): nothing decrypted ever
 * reaches the files, cache or database folders, and a failed check marks the item FAILED while
 * the original stays.
 */
@RunWith(AndroidJUnit4::class)
class NoPlaintextOnDiskTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val pin = "482915".toCharArray()

    // A recognisable byte pattern; if it shows up in any file, plaintext leaked to disk.
    private val original = ByteArray(50_000) { i -> "SG-PLAINTEXT-MARKER-"[i % 20].code.toByte() }
    private val source = object : ImageSource {
        var opened = 0
        override suspend fun listImages() = listOf(GalleryImage(1, 1, original.size.toLong()))
        override fun open(mediaStoreId: Long): InputStream? = if (mediaStoreId == 1L) {
            opened++
            original.inputStream()
        } else null
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun indexed(mediaStoreId: Long): Item {
        db.itemDao().insertNew(listOf(Item(mediaStoreId = mediaStoreId, dateModified = 1, sizeBytes = 1, contentHash = Vault.sha256Hex(original))))
        return db.itemDao().byMediaStoreId(mediaStoreId)!!
    }

    @Test
    fun storingAndReadingLeavesNoPlaintextInAppStorage() = runTest {
        val vault = testVault(File(context.filesDir, "vault"))
        vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        val item = indexed(1)

        val result = ItemVaulter(vault, source, db.itemDao()).vault(key, item)
        assertTrue(result is Vault.StoreResult.Verified)
        assertArrayEquals(original, vault.read(key, ItemVaulter.fileId(item)))

        val dirs = listOfNotNull(context.dataDir, context.filesDir, context.cacheDir, context.codeCacheDir,
            context.noBackupFilesDir, context.externalCacheDir)
        val files = dirs.flatMap { it.walkTopDown().filter(File::isFile).toList() }.distinct()
        assertTrue("vault file should exist", files.any { it.name.endsWith(".sgv") })
        val marker = "SG-PLAINTEXT-MARKER-".toByteArray()
        val leaking = files.filter { it.readBytes().containsSequence(marker) }
        assertEquals(emptyList<File>(), leaking)
    }

    @Test
    fun failedCheckMarksTheItemFailedAndKeepsTheOriginal() = runTest {
        val vault = testVault(File(context.filesDir, "vault-tamper"), afterWrite = ::flipLastByte)
        vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        val item = indexed(1)

        val result = ItemVaulter(vault, source, db.itemDao()).vault(key, item)
        assertTrue(result is Vault.StoreResult.Failed)
        assertEquals(ItemState.FAILED, db.itemDao().byMediaStoreId(1)!!.state)
        assertTrue(!vault.contains(ItemVaulter.fileId(item)))
        // The original was only read, never changed.
        assertArrayEquals(original, source.open(1)!!.readBytes())
    }

    @Test
    fun missingOriginalMarksTheItemFailed() = runTest {
        val vault = testVault(File(context.filesDir, "vault-missing"))
        vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        val result = ItemVaulter(vault, source, db.itemDao()).vault(key, indexed(2))
        assertEquals(Vault.StoreResult.Failed("original is missing"), result)
        assertEquals(ItemState.FAILED, db.itemDao().byMediaStoreId(2)!!.state)
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
