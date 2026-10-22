package com.sudo.securegallery.data

import androidx.room.TypeConverter

/**
 * Room converters. Boxes are stored as `type:x1,y1,...,x4,y4:confidence` joined by `;`.
 * Decoding goes through [FieldType.fromId] and number parsing, so the column cannot carry
 * free text even if a caller tried.
 */
class Converters {
    @TypeConverter
    fun stateToName(state: ItemState): String = state.name

    @TypeConverter
    fun nameToState(name: String): ItemState = ItemState.valueOf(name)

    @TypeConverter
    fun classToId(documentClass: DocumentClass?): String? = documentClass?.id

    @TypeConverter
    fun idToClass(id: String?): DocumentClass? = id?.let(DocumentClass::fromId)

    @TypeConverter
    fun boxesToString(boxes: List<RedactionBox>): String = encodeBoxes(boxes)

    @TypeConverter
    fun stringToBoxes(encoded: String): List<RedactionBox> = decodeBoxes(encoded)

    companion object {
        fun encodeBoxes(boxes: List<RedactionBox>): String = boxes.joinToString(";") { box ->
            "${box.fieldType.id}:${box.corners.joinToString(",")}:${box.confidence}"
        }

        fun decodeBoxes(encoded: String): List<RedactionBox> {
            if (encoded.isEmpty()) return emptyList()
            return encoded.split(";").map { part ->
                val (type, corners, confidence) = part.split(":")
                RedactionBox(
                    fieldType = FieldType.fromId(type),
                    corners = corners.split(",").map(String::toFloat),
                    confidence = confidence.toFloat(),
                )
            }
        }
    }
}
