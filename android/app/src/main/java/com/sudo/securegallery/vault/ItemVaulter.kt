package com.sudo.securegallery.vault

import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemDao
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.scan.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pipeline step 1 and 2 of §8.2: encrypt an item's original into the vault and verify it.
 * If anything fails, the vault file is gone, the item becomes FAILED, and the original stays
 * in the gallery untouched. Nothing here ever deletes or changes the original.
 */
class ItemVaulter(
    private val vault: Vault,
    private val source: ImageSource,
    private val dao: ItemDao,
) {
    suspend fun vault(key: Vault.Key, item: Item): Vault.StoreResult = withContext(Dispatchers.IO) {
        val result = source.open(item.mediaStoreId)?.use { original ->
            vault.store(key, fileId(item), original, item.contentHash)
        } ?: Vault.StoreResult.Failed("original is missing")
        if (result is Vault.StoreResult.Failed && item.state.canMoveTo(ItemState.FAILED)) {
            dao.update(item.moveTo(ItemState.FAILED))
        }
        result
    }

    companion object {
        /** Vault files are named by the item's row ID, never by anything from the image. */
        fun fileId(item: Item): String = "item-${item.id}"
    }
}
