package com.sudo.securegallery.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One gallery image the app has indexed (§8.4).
 *
 * Holds only what §8.4 allows: the MediaStore ID, content hash, state, class, confidences,
 * box coordinates, field types and retry count. There is no column for OCR text or field
 * values; `ItemSchemaTest` fails if one is added.
 *
 * @property dateModified MediaStore `DATE_MODIFIED` (seconds) when the item was indexed, used to
 *   tell whether the file changed since.
 * @property contentHash SHA-256 of the file bytes as lowercase hex, or null until hashed.
 * @property cardCorners the detected card's corners, or null until detected (#16).
 * @property redactedMediaStoreId the MediaStore ID of the app's redacted copy, or null until it
 *   is saved (#18).
 * @property recheckPassed true once the saved copy passed the on-device recheck (§8.5, #19).
 *   Only such items may have their original deleted (#22).
 * @property corrections how often the user corrected this item (a box changed, or "not
 *   sensitive"), for the correction-rate metric (§8.4). A count only, no content.
 * @property retryCount failed attempts while no copy exists; once a copy is saved, the number
 *   of recheck retries with larger boxes (0 to 2, 3 when it ended in a whole-image fill).
 */
@Entity(tableName = "items", indices = [Index(value = ["media_store_id"], unique = true)])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "state") val state: ItemState = ItemState.PENDING,
    @ColumnInfo(name = "document_class") val documentClass: DocumentClass? = null,
    @ColumnInfo(name = "document_confidence") val documentConfidence: Float? = null,
    @ColumnInfo(name = "boxes") val boxes: List<RedactionBox> = emptyList(),
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "card_corners") val cardCorners: CardCorners? = null,
    @ColumnInfo(name = "redacted_media_store_id") val redactedMediaStoreId: Long? = null,
    @ColumnInfo(name = "recheck_passed", defaultValue = "0") val recheckPassed: Boolean = false,
    @ColumnInfo(name = "corrections", defaultValue = "0") val corrections: Int = 0,
) {
    /** Move to [next], or throw if §8.4 does not allow that step. */
    fun moveTo(next: ItemState): Item {
        check(state.canMoveTo(next)) { "cannot move from $state to $next" }
        return copy(state = next)
    }
}
