package com.sudo.securegallery.secure

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.vault.KeystoreDeviceSecret
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The full "not sensitive" round trip on the emulator (issue #25): a synthetic photo is vaulted
 * with the real Keystore, removed from MediaStore with only its redacted copy left, then restored
 * into the same album with identical bytes, while the copy and the vault file go.
 */
@RunWith(AndroidJUnit4::class)
class RestoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private val resolver = app.contentResolver
    private val album = "Pictures/SG restore test/"

    private fun packagePermission(permission: String, action: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "pm $action ${app.packageName} $permission",
        ).close()
    }

    private fun insert(name: String, bytes: ByteArray): Long {
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.RELATIVE_PATH, album)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return ContentUris.parseId(uri)
    }

    @Test
    fun notSensitiveRestoresTheOriginal() = runBlocking {
        // Grant the target package's runtime media permissions before accessing its
        // MediaStore resolver; Android 14 otherwise rejects the synthetic URI.
        packagePermission(Manifest.permission.READ_MEDIA_IMAGES, "grant")
        packagePermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, "grant")
        val original = testAssets.open("redaction/r01_philsys-card.jpg").use { it.readBytes() }
        val dir = File(app.cacheDir, "restore-test-vault").apply { deleteRecursively() }
        val vault = Vault(VaultStore(dir), KeystoreDeviceSecret("restore-test"), iterations = 10_000)
        val pin = "123456".toCharArray()
        vault.setUp(pin)
        val key = (vault.unlock(pin) as Vault.Unlock.Success).key
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val gallery = MediaStoreGallery(resolver)
        val copyId = insert("r01_philsys-card.sg-redacted.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0))
        var restoredId: Long? = null
        try {
            db.itemDao().insertNew(listOf(Item(mediaStoreId = 424242, dateModified = 1, sizeBytes = 1)))
            val item = db.itemDao().byMediaStoreId(424242)!!.copy(
                contentHash = Vault.sha256Hex(original), state = ItemState.VAULTED,
                redactedMediaStoreId = copyId, recheckPassed = true,
            )
            db.itemDao().update(item)
            vault.store(key, "item-${item.id}", original.inputStream(), Vault.sha256Hex(original))

            val result = RestoreOriginal(db.itemDao(), vault, gallery) { "item-${it.id}" }.restore(key, item)

            val newId = (result as RestoreOriginal.Result.Restored).mediaStoreId
            restoredId = newId
            val restored = db.itemDao().byMediaStoreId(newId)!!
            assertEquals(ItemState.RESTORED, restored.state)
            assertEquals(album to "r01_philsys-card.jpg", gallery.locate(newId))
            assertArrayEquals(original, gallery.read(newId))
            assertFalse(gallery.exists(copyId))
            assertFalse(vault.contains("item-${item.id}"))
        } finally {
            gallery.delete(copyId)
            restoredId?.let(gallery::delete)
            db.close()
            dir.deleteRecursively()
            packagePermission(Manifest.permission.READ_MEDIA_IMAGES, "revoke")
            packagePermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, "revoke")
        }
    }
}
