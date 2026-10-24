package com.sudo.securegallery.secure

import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.vault.Vault

/**
 * Deleting a photo from the viewer (#77): the redacted copy, the original if it is still public,
 * and the vault file. The public files go through one `createDeleteRequest` dialog, so Android
 * asks the user itself. The vault file and the item go only once that dialog removed every
 * public file; a denied dialog deletes nothing. Nothing brings the photo back afterwards.
 */
class DeletePhoto(
    private val dao: ItemDao,
    private val vault: Vault,
    private val gallery: RestoreOriginal.Gallery,
    private val vaultId: (Item) -> String,
) {
    /** The MediaStore IDs the system dialog must delete: the copy, and the original if still public. */
    fun publicFiles(item: Item): List<Long> = listOfNotNull(
        item.redactedMediaStoreId?.takeIf(gallery::exists),
        item.mediaStoreId.takeIf { item.state != ItemState.VAULTED && gallery.exists(it) },
    )

    /** After the dialog: true if the photo is gone everywhere, false if anything was kept. */
    suspend fun finish(item: Item, allowed: Boolean): Boolean {
        if (!allowed || publicFiles(item).isNotEmpty()) return false
        vault.delete(vaultId(item))
        dao.delete(item)
        return true
    }
}
