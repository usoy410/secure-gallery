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

    @Query("SELECT COUNT(*) FROM items")
    fun countAll(): Flow<Int>

    /** Items the scan has finished with: hashed, or given up on because the file went away. */
    @Query("SELECT COUNT(*) FROM items WHERE content_hash IS NOT NULL OR state != 'PENDING'")
    fun countProcessed(): Flow<Int>
}
