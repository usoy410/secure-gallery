package com.sudo.securegallery.secure

import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.vault.Vault

/**
 * Moves originals into the vault and decides which may be deleted (§8.2 steps 1, 2 and 6, #22).
 *
 * An original is offered for deletion only when both hold:
 * - its redacted copy was saved and passed the on-device recheck (#19, `recheckPassed`), and
 * - it is in the vault, written, decrypted back and hash-checked against the hash taken at
 *   scan time ([Vault.store]), so a file changed since the scan is never vaulted or deleted.
 *
 * This class never deletes anything itself. Deletion happens only through the system dialog
 * from `MediaStore.createDeleteRequest` (CLAUDE.md, hard rules), and [finish] records what the
 * user chose. Storing needs the unlocked vault key, so this runs when the user acts on
 * "N photos ready to secure", not in the background scan.
 */
class SecureOriginals(
    private val dao: ItemDao,
    private val vault: Vault,
    private val source: ImageSource,
) {
    /** The vault file name of an item's original. */
    fun vaultId(item: Item) = "item-${item.id}"

    /** Vaults every ready original and returns the items whose original may be deleted. */
    suspend fun prepare(key: Vault.Key): List<Item> =
        dao.readyToSecure().filter { item ->
            check(item.recheckPassed && item.redactedMediaStoreId != null) { "item ${item.id} is not ready" }
            vault.contains(vaultId(item)) || store(key, item)
        }

    private fun store(key: Vault.Key, item: Item): Boolean {
        val hash = item.contentHash ?: return false
        val original = source.open(item.mediaStoreId) ?: return false
        return original.use { vault.store(key, vaultId(item), it, expectedSha256 = hash) } is Vault.StoreResult.Verified
    }

    /**
     * Records the user's answer to the delete dialog. An original that still exists after
     * "Allow" (the dialog result and MediaStore can disagree) is treated as not deleted.
     */
    suspend fun finish(items: List<Item>, allowed: Boolean) {
        for (item in items) {
            val current = dao.byMediaStoreId(item.mediaStoreId) ?: continue
            val stillThere = source.open(item.mediaStoreId)?.use { true } ?: false
            val gone = allowed && !stillThere
            val next = if (gone) ItemState.VAULTED else ItemState.ORIGINAL_STILL_PUBLIC
            if (current.state != next) dao.update(current.moveTo(next))
        }
    }
}
