package com.sudo.securegallery.localize

import android.content.res.AssetManager
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.detect.Corner
import org.json.JSONObject

/**
 * One recognized word and its box in the image OCR read. The text lives in memory only: it is
 * never stored, logged or put in an exception (§8.4), and [toString] leaves it out.
 */
class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Character offset of this word in its line, set by [FieldRules.orderLines]. */
    internal var start: Int = 0

    override fun toString() = "OcrWord(${text.length} chars at $left,$top,$right,$bottom)"
}

/**
 * One OCR line. [block] groups lines of one paragraph (Tesseract's block and paragraph, ML Kit's
 * text block); [line] keeps the engine's order inside it.
 */
class OcrLine(val block: Int, val line: Int, val words: List<OcrWord>) {
    override fun toString() = "OcrLine(block $block, line $line, ${words.size} words)"
}

/** A box the field rules found: which field, and its four corners in the OCR'd image. */
data class FieldBox(val fieldType: FieldType, val polygon: List<Corner>)

/**
 * The OCR-first field rules from `templates/rules/fields.json`, shared with the desktop
 * harness (#10). Matching is a port of `_ocr_lines`, `_merge_rows` and `_match_lines` in
 * `validation/localize/core.py`, so both sides find the same fields in the same text.
 */
class FieldRules(val rules: List<Rule>, val excludeWords: Set<String>) {
    class Rule(
        val fieldType: FieldType,
        val pattern: Regex,
        val luhn: Boolean,
        val skipIfExcluded: Boolean,
        val extendNextLine: Boolean,
    )

    /** Boxes for every rule match on [lines], each widened by [pad] pixels. */
    fun match(lines: List<OcrLine>, pad: Float = OCR_PAD): List<FieldBox> {
        val ordered = orderLines(lines)
        val found = mutableListOf<FieldBox>()
        for ((idx, line) in ordered.withIndex()) {
            val words = line.words
            val text = words.joinToString(" ") { it.text }.uppercase()
            val hasExcluded = text.split(WHITESPACE).any { it.trim(',', '.') in excludeWords }
            for (rule in rules) {
                if (rule.skipIfExcluded && hasExcluded) continue
                for (m in rule.pattern.findAll(text)) {
                    if (rule.luhn && !isLuhnValid(m.value)) continue
                    val end = m.range.last + 1
                    var hit = words.filter { it.start < end && it.start + it.text.length > m.range.first }
                    if (rule.extendNextLine && idx + 1 < ordered.size && ordered[idx + 1].block == line.block) {
                        hit = hit + ordered[idx + 1].words
                    }
                    if (hit.isEmpty()) continue
                    found += FieldBox(
                        rule.fieldType,
                        Geometry.rectangle(
                            hit.minOf { it.left } - pad,
                            hit.minOf { it.top } - pad,
                            hit.maxOf { it.right } + pad,
                            hit.maxOf { it.bottom } + pad,
                        ),
                    )
                }
            }
        }
        return found
    }

    /**
     * The text of every ID or card number the rules find on [lines] (§8.5 digit check). Held in
     * memory by the caller for one recheck and never stored or logged.
     */
    fun numberValues(lines: List<OcrLine>): List<String> =
        orderLines(lines).flatMap { line ->
            val text = line.words.joinToString(" ") { it.text }.uppercase()
            rules.filter { it.fieldType in NUMBER_FIELDS }.flatMap { rule ->
                rule.pattern.findAll(text).map { it.value }.filter { !rule.luhn || isLuhnValid(it) }.toList()
            }
        }

    companion object {
        /** Fields whose numbers count as leaked when 4 digits come back (§9.2). */
        val NUMBER_FIELDS = setOf(FieldType.PCN, FieldType.CARD_NUMBER, FieldType.LICENSE_NUMBER)

        /** Pixels added around each OCR box, in the space OCR read (§9.3 tunes it on val). */
        const val OCR_PAD = 4f
        const val ASSET_PATH = "templates/rules/fields.json"
        private val WHITESPACE = Regex("\\s+")

        fun parse(json: String): FieldRules {
            val root = JSONObject(json)
            val rules = root.getJSONArray("rules")
            val exclude = root.optJSONArray("exclude_words")
            return FieldRules(
                rules = (0 until rules.length()).map(rules::getJSONObject).map { r ->
                    Rule(
                        fieldType = FieldType.fromId(r.getString("field_type")),
                        pattern = Regex(r.getString("pattern")),
                        luhn = r.optString("validate") == "luhn",
                        skipIfExcluded = r.optBoolean("skip_if_excluded_word", false),
                        extendNextLine = r.optBoolean("extend_next_line", false),
                    )
                },
                excludeWords = (0 until (exclude?.length() ?: 0)).map { exclude!!.getString(it) }.toSet(),
            )
        }

        fun load(assets: AssetManager): FieldRules = parse(assets.open(ASSET_PATH).bufferedReader().use { it.readText() })

        /**
         * Lines top to bottom, words left to right, with pieces of one printed row joined (a
         * sparse-text OCR pass can split a row into several blocks, so multi-word values match
         * as one line).
         */
        fun orderLines(lines: List<OcrLine>): List<OcrLine> {
            val sorted = lines.filter { it.words.isNotEmpty() }
                .sortedWith(compareBy<OcrLine>({ l -> l.words.minOf { it.top } }, { it.block }, { it.line }))
                .map { OcrLine(it.block, it.line, it.words.sortedBy { w -> w.left }) }
            val merged = mutableListOf<OcrLine>()
            for (line in sorted) {
                val prev = merged.lastOrNull()?.words
                if (prev != null) {
                    val height = prev.maxOf { it.bottom - it.top }
                    val sameRow = kotlin.math.abs(prev.first().top - line.words.first().top) < height * 0.5f
                    val gap = line.words.first().left - prev.last().right
                    if (sameRow && gap >= 0 && gap < height * 1.5f) {
                        val first = merged.removeAt(merged.lastIndex)
                        merged += OcrLine(first.block, first.line, prev + line.words)
                        continue
                    }
                }
                merged += line
            }
            for (line in merged) {
                var offset = 0
                for (w in line.words) {
                    w.start = offset
                    offset += w.text.length + 1
                }
            }
            return merged
        }

        /** Luhn check over the digits of [number]; spaces and dashes are ignored. */
        fun isLuhnValid(number: String): Boolean {
            val digits = number.filter(Char::isDigit).map { it - '0' }
            if (digits.isEmpty()) return false
            val total = digits.reversed().withIndex().sumOf { (i, d) ->
                if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
            }
            return total % 10 == 0
        }
    }
}
