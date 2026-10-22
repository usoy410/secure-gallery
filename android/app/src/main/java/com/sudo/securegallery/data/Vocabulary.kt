package com.sudo.securegallery.data

/** Supported document classes (§8.1), matching `document_class` in `templates/schema.json`. */
enum class DocumentClass(val id: String) {
    PHILSYS("philsys"),
    LTO("lto"),
    PAYMENT_CARD("payment_card");

    companion object {
        fun fromId(id: String): DocumentClass = entries.first { it.id == id }
    }
}

/**
 * Sensitive field types from the card templates. The database stores only these names,
 * never what the field says, so it cannot hold OCR text or field values (§8.4).
 */
enum class FieldType(val id: String) {
    ADDRESS("address"),
    CARD_NUMBER("card_number"),
    CARDHOLDER_NAME("cardholder_name"),
    CVV("cvv"),
    DATE_OF_BIRTH("date_of_birth"),
    EXPIRY_DATE("expiry_date"),
    FACE_PHOTO("face_photo"),
    FULL_NAME("full_name"),
    LICENSE_NUMBER("license_number"),
    NAME("name"),
    PCN("pcn"),
    QR_CODE("qr_code"),
    SIGNATURE("signature"),
    /** Whole-document fallback box (§8.2). */
    WHOLE_DOCUMENT("whole_document");

    companion object {
        fun fromId(id: String): FieldType = entries.first { it.id == id }
    }
}

/**
 * One redaction box in original-image pixels: four corners (x, y), clockwise from top-left.
 *
 * @property confidence detector or OCR confidence for this box, 0 to 1.
 */
data class RedactionBox(
    val fieldType: FieldType,
    val corners: List<Float>,
    val confidence: Float,
) {
    init {
        require(corners.size == 8) { "a box has 4 corners (8 numbers)" }
        require(confidence in 0f..1f) { "confidence must be between 0 and 1" }
    }
}
