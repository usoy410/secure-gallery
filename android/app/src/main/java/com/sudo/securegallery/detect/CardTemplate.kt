package com.sudo.securegallery.detect

import android.content.res.AssetManager
import android.graphics.PointF
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * A sensitive or structural field region inside a card template (§8.1, §8.6).
 *
 * @property name Human-readable field title from the template schema.
 * @property type Specific [FieldType] identifying this field category.
 * @property sensitive True if the field must be covered by a redaction box.
 * @property polygon Normalized coordinates in unit-square [0, 1] space.
 */
data class TemplateField(
    val name: String,
    val type: FieldType,
    val sensitive: Boolean,
    val polygon: List<PointF>,
)

/**
 * Card template defining normalized field layouts for a document variant (§8.2, §8.6).
 *
 * Templates are shared directly between the synthetic generator and on-device localization.
 *
 * @property id Unique variant identifier (e.g. `philsys-card`, `lto-dl-current`).
 * @property name Full descriptive name of the document variant.
 * @property documentClass Target [DocumentClass] grouping this template.
 * @property aspectRatio Card width divided by height (standard ID-1 is ~1.5858).
 * @property fields All normalized field polygons defined in the template.
 */
data class CardTemplate(
    val id: String,
    val name: String,
    val documentClass: DocumentClass,
    val aspectRatio: Float,
    val fields: List<TemplateField>,
) {
    /**
     * Returns sensitive fields only, with picture fields padded per SECURE_GALLERY.md §8.6.
     *
     * Faces, QR codes and signatures have no textual cues for OCR refinement, so their layout
     * boxes receive a 3% card width margin on every side to account for physical print drift (#54).
     */
    fun sensitiveFieldsWithPadding(): List<TemplateField> = fields
        .filter { it.sensitive }
        .map { field ->
            if (field.type in PICTURE_FIELDS) {
                field.copy(polygon = padPolygon(field.polygon, aspectRatio))
            } else {
                field
            }
        }

    companion object {
        /** Fields identified by position only; padded by [PICTURE_MARGIN] of the card width. */
        val PICTURE_FIELDS = setOf(
            FieldType.FACE_PHOTO,
            FieldType.QR_CODE,
            FieldType.SIGNATURE,
        )

        /** Margin added to picture fields on all four sides (3% card width). */
        const val PICTURE_MARGIN = 0.03f

        /**
         * Widens a unit-square polygon by 3% across and `3% * aspectRatio` down.
         *
         * @param polygon Unit-square polygon coordinates.
         * @param aspectRatio Ratio of card width to height.
         * @return Padded rectangular polygon in unit-square coordinates.
         */
        fun padPolygon(polygon: List<PointF>, aspectRatio: Float): List<PointF> {
            val xs = polygon.map { it.x }
            val ys = polygon.map { it.y }
            val mx = PICTURE_MARGIN
            val my = PICTURE_MARGIN * aspectRatio
            val left = xs.minOrNull() ?: 0f - mx
            val top = ys.minOrNull() ?: 0f - my
            val right = xs.maxOrNull() ?: 1f + mx
            val bottom = ys.maxOrNull() ?: 1f + my
            return listOf(
                PointF(left, top),
                PointF(right, top),
                PointF(right, bottom),
                PointF(left, bottom),
            )
        }

        /**
         * Parses a [CardTemplate] from a raw JSON string.
         *
         * @param jsonString Serialized template JSON matching `templates/schema.json`.
         * @return Parsed [CardTemplate].
         */
        fun parse(jsonString: String): CardTemplate {
            val root = JSONObject(jsonString)
            val id = root.getString("id")
            val name = root.getString("name")
            val docClass = DocumentClass.fromId(root.getString("document_class"))
            val aspectRatio = root.optDouble("aspect_ratio", 1.5858).toFloat()
            val fieldsArray = root.getJSONArray("fields")

            val fields = mutableListOf<TemplateField>()
            for (i in 0 until fieldsArray.length()) {
                val fObj = fieldsArray.getJSONObject(i)
                val typeStr = fObj.getString("type")
                val sensitive = fObj.optBoolean("sensitive", false)
                // Non-sensitive fields might not exist in FieldType enum (e.g. headings, bank_logo).
                val fieldType = try {
                    FieldType.fromId(typeStr)
                } catch (_: NoSuchElementException) {
                    null
                }

                val polyArray = fObj.getJSONArray("polygon")
                val polyPoints = mutableListOf<PointF>()
                for (j in 0 until polyArray.length()) {
                    val pt = polyArray.getJSONArray(j)
                    polyPoints.add(PointF(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
                }

                if (fieldType != null) {
                    fields.add(
                        TemplateField(
                            name = fObj.optString("name", typeStr),
                            type = fieldType,
                            sensitive = sensitive,
                            polygon = polyPoints,
                        ),
                    )
                }
            }
            return CardTemplate(id, name, docClass, aspectRatio, fields)
        }

        /**
         * Loads all template JSON files discovered under the specified asset directory.
         *
         * @param assets Android [AssetManager].
         * @param dir Directory name in assets containing card templates.
         * @return List of parsed [CardTemplate] objects.
         */
        fun loadAll(assets: AssetManager, dir: String = "templates"): List<CardTemplate> {
            val files = assets.list(dir) ?: return emptyList()
            return files
                .filter { it.endsWith(".json") && it != "schema.json" }
                .map { fileName ->
                    assets.open("$dir/$fileName").use { input ->
                        parse(input.bufferedReader().use { it.readText() })
                    }
                }
        }

        /**
         * Loads all templates from a local directory on disk (for JVM unit tests).
         *
         * @param dir Directory containing template JSON files.
         * @return List of parsed [CardTemplate] objects.
         */
        fun loadAll(dir: File): List<CardTemplate> {
            val files = dir.listFiles { file -> file.extension == "json" && file.name != "schema.json" }
                ?: return emptyList()
            return files.map { parse(it.readText(Charsets.UTF_8)) }
        }
    }
}
