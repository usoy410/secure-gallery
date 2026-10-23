package com.sudo.securegallery.localize

import android.content.res.AssetManager
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.detect.Corner
import org.json.JSONObject

/** One sensitive field of a template, as a polygon in unit-square card space. */
data class TemplateField(val type: FieldType, val polygon: List<Corner>)

/**
 * One card design variant from a JSON file in `templates/` (#3), keeping only its sensitive fields.
 * The APK gets the same files the desktop harness reads, copied at build time.
 */
data class CardTemplate(
    val id: String,
    val documentClass: DocumentClass,
    val aspectRatio: Float,
    val fields: List<TemplateField>,
) {
    /**
     * The field's polygon in unit-square card space, widened if it is a picture field. The
     * margin is [PICTURE_MARGIN] of the card width on all four sides; in unit-square units
     * that is [PICTURE_MARGIN] across and [PICTURE_MARGIN] * [aspectRatio] down.
     */
    fun paddedPolygon(field: TemplateField): List<Corner> {
        if (field.type !in PICTURE_FIELDS) return field.polygon
        val (left, top, right, bottom) = Geometry.bounds(field.polygon).toList()
        val mx = PICTURE_MARGIN
        val my = PICTURE_MARGIN * aspectRatio
        return Geometry.rectangle(left - mx, top - my, right + mx, bottom + my)
    }

    companion object {
        /**
         * Faces, QR codes and signatures are matched by position only, so their layout box
         * gets a margin of 3% of the card width (§8.6, #54). Text fields need none.
         */
        val PICTURE_FIELDS = setOf(FieldType.FACE_PHOTO, FieldType.QR_CODE, FieldType.SIGNATURE)
        const val PICTURE_MARGIN = 0.03f

        const val ASSET_DIR = "templates"

        fun parse(json: String): CardTemplate {
            val root = JSONObject(json)
            val fields = root.getJSONArray("fields")
            return CardTemplate(
                id = root.getString("id"),
                documentClass = DocumentClass.fromId(root.getString("document_class")),
                aspectRatio = root.optDouble("aspect_ratio", 1.0).toFloat(),
                fields = (0 until fields.length()).map(fields::getJSONObject)
                    .filter { it.getBoolean("sensitive") }
                    .map { field ->
                        val polygon = field.getJSONArray("polygon")
                        TemplateField(
                            type = FieldType.fromId(field.getString("type")),
                            polygon = (0 until polygon.length()).map { i ->
                                val p = polygon.getJSONArray(i)
                                Corner(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                            },
                        )
                    },
            )
        }

        /** Every template bundled in the APK, sorted by file name like the desktop harness. */
        fun loadAll(assets: AssetManager): List<CardTemplate> =
            assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.sorted().map { name ->
                parse(assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() })
            }
    }
}
