package com.sudo.securegallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    /** Adds items not yet indexed; images already in the table are left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(items: List<Item>): List<Long>

    @Update
    suspend fun update(item: Item)

    @Query("SELECT * FROM items WHERE media_store_id = :mediaStoreId")
    suspend fun byMediaStoreId(mediaStoreId: Long): Item?

    /** The next indexed item still waiting to be processed, oldest first. */
    @Query("SELECT * FROM items WHERE content_hash IS NULL AND state = 'PENDING' ORDER BY id LIMIT 1")
    suspend fun nextUnhashed(): Item?

    /** Hashed items the detector has not seen yet (detected ones have a class or left PENDING). */
    @Query("SELECT * FROM items WHERE state = 'PENDING' AND content_hash IS NOT NULL AND document_class IS NULL ORDER BY id")
    suspend fun awaitingDetection(): List<Item>

    /** Detected items still waiting for field localization (#17). */
    @Query("SELECT * FROM items WHERE state = 'PENDING' AND document_class IS NOT NULL ORDER BY id")
    suspend fun awaitingLocalization(): List<Item>

    /** Localized items whose redacted copy is not saved yet (#18). */
    @Query("SELECT * FROM items WHERE state IN ('REDACTED', 'NEEDS_CHECK') AND redacted_media_store_id IS NULL ORDER BY id")
    suspend fun awaitingRedaction(): List<Item>

    /**
     * Items whose original may now be vaulted and deleted (#22): a saved redacted copy that
     * passed the recheck (#19). Items whose deletion the user denied come back for a retry.
     */
    @Query(
        "SELECT * FROM items WHERE state IN ('REDACTED', 'NEEDS_CHECK', 'ORIGINAL_STILL_PUBLIC') " +
            "AND redacted_media_store_id IS NOT NULL AND recheck_passed = 1 ORDER BY id",
    )
    suspend fun readyToSecure(): List<Item>

    @Query(
        "SELECT COUNT(*) FROM items WHERE state IN ('REDACTED', 'NEEDS_CHECK', 'ORIGINAL_STILL_PUBLIC') " +
            "AND redacted_media_store_id IS NOT NULL AND recheck_passed = 1",
    )
    fun countReadyToSecure(): Flow<Int>

    @Query("SELECT COUNT(*) FROM items")
    fun countAll(): Flow<Int>

    /** Items the scan has finished with: hashed, or given up on because the file went away. */
    @Query("SELECT COUNT(*) FROM items WHERE content_hash IS NOT NULL OR state != 'PENDING'")
    fun countProcessed(): Flow<Int>
}
