package com.sudo.securegallery.detect

import android.content.res.AssetManager
import com.sudo.securegallery.data.FieldType
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * One matched text field result in image coordinates (§8.2, §8.6).
 *
 * Never holds the recognized plaintext, preserving privacy standards (§8.4).
 *
 * @property fieldType Type of field recognized by rule regex.
 * @property left Bounding box left coordinate.
 * @property top Bounding box top coordinate.
 * @property right Bounding box right coordinate.
 * @property bottom Bounding box bottom coordinate.
 */
data class MatchedField(
    val fieldType: FieldType,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Four corners clockwise from top-left. */
    fun toCorners(): List<Float> = listOf(
        left, top,
        right, top,
        right, bottom,
        left, bottom,
    )
}

/**
 * An individual regex rule for identifying a sensitive field (§8.2, §8.6).
 *
 * @property fieldType Target sensitive field category.
 * @property pattern Compiled regular expression pattern.
 * @property luhn Whether Luhn checksum validation must pass (payment cards).
 * @property skipIfExcluded True if line containing any excluded word must be ignored.
 * @property extendNextLine True if the immediately following line in the paragraph is appended.
 */
data class FieldRule(
    val fieldType: FieldType,
    val pattern: Pattern,
    val luhn: Boolean = false,
    val skipIfExcluded: Boolean = false,
    val extendNextLine: Boolean = false,
)

/**
 * Container for shared OCR field rules loaded from `templates/rules/fields.json`.
 *
 * Shared between the desktop validation harness (D10) and on-device localization.
 *
 * @property version Rules format version.
 * @property excludeWords Words indicating non-sensitive card headers, emblems or banks.
 * @property rules List of compiled field matching rules.
 */
data class FieldRules(
    val version: Int,
    val excludeWords: Set<String>,
    val rules: List<FieldRule>,
) {
    /**
     * Matches OCR text lines against all rules, returning bounding boxes of matched fields.
     *
     * @param lines Structured lines of OCR words with bounding box coordinates.
     * @param pad Padding pixels added around each matched OCR box in coordinates of the lines.
     * @return List of matched field locations without sensitive text.
     */
    fun match(lines: List<OcrLine>, pad: Float = DEFAULT_PAD): List<MatchedField> {
        val matches = mutableListOf<MatchedField>()

        for ((idx, line) in lines.withIndex()) {
            if (line.words.isEmpty()) continue

            // Establish contiguous character offsets for each word in the line.
            var offset = 0
            val wordsWithOffsets = line.words.map { word ->
                val w = word.copy(start = offset)
                offset += word.text.length + 1
                w
            }
            val lineText = wordsWithOffsets.joinToString(" ") { it.text }.uppercase()

            val tokens = lineText.split("\\s+".toRegex())
            val hasExcluded = tokens.any { token ->
                token.trim(',', '.').uppercase() in excludeWords
            }

            for (rule in rules) {
                if (rule.skipIfExcluded && hasExcluded) continue

                val matcher = rule.pattern.matcher(lineText)
                while (matcher.find()) {
                    val matchStr = matcher.group()
                    if (rule.luhn && !isLuhnValid(matchStr)) continue

                    val start = matcher.start()
                    val end = matcher.end()

                    val hitWords = wordsWithOffsets.filter { w ->
                        w.start < end && (w.start + w.text.length) > start
                    }.toMutableList()

                    if (hitWords.isEmpty()) continue

                    // If rule extends to the next line (e.g. multi-line street address).
                    if (rule.extendNextLine && idx + 1 < lines.size) {
                        val nextLine = lines[idx + 1]
                        if (nextLine.blockIndex == line.blockIndex) {
                            hitWords.addAll(nextLine.words)
                        }
                    }

                    val minLeft = hitWords.minOf { it.left } - pad
                    val minTop = hitWords.minOf { it.top } - pad
                    val maxRight = hitWords.maxOf { it.right } + pad
                    val maxBottom = hitWords.maxOf { it.bottom } + pad

                    matches.add(
                        MatchedField(
                            fieldType = rule.fieldType,
                            left = minLeft,
                            top = minTop,
                            right = maxRight,
                            bottom = maxBottom,
                        ),
                    )
                }
            }
        }
        return matches
    }

    companion object {
        const val DEFAULT_PAD = 4f

        /**
         * Validates payment card numbers using the standard ISO/IEC 7812 Luhn checksum.
         *
         * @param cardNumber Card number string with optional spaces or dashes.
         * @return True if valid according to Mod 10 Luhn algorithm.
         */
        fun isLuhnValid(cardNumber: String): Boolean {
            val digits = cardNumber.filter { it.isDigit() }.map { it.digitToInt() }
            if (digits.isEmpty()) return false
            var total = 0
            for ((i, digit) in digits.reversed().withIndex()) {
                if (i % 2 == 1) {
                    val doubled = digit * 2
                    total += if (doubled > 9) doubled - 9 else doubled
                } else {
                    total += digit
                }
            }
            return total % 10 == 0
        }

        /**
         * Parses [FieldRules] from the JSON content of `templates/rules/fields.json`.
         *
         * @param jsonString Raw JSON content.
         * @return Compiled [FieldRules].
         */
        fun parse(jsonString: String): FieldRules {
            val root = JSONObject(jsonString)
            val version = root.optInt("version", 1)
            val excludeArray = root.optJSONArray("exclude_words")
            val excludeWords = mutableSetOf<String>()
            if (excludeArray != null) {
                for (i in 0 until excludeArray.length()) {
                    excludeWords.add(excludeArray.getString(i).uppercase())
                }
            }

            val rulesArray = root.getJSONArray("rules")
            val rules = mutableListOf<FieldRule>()
            for (i in 0 until rulesArray.length()) {
                val rObj = rulesArray.getJSONObject(i)
                val typeStr = rObj.getString("field_type")
                val fieldType = try {
                    FieldType.fromId(typeStr)
                } catch (_: NoSuchElementException) {
                    null
                } ?: continue

                val patternStr = rObj.getString("pattern")
                val luhn = rObj.optString("validate") == "luhn"
                val skipIfExcluded = rObj.optBoolean("skip_if_excluded_word", false)
                val extendNextLine = rObj.optBoolean("extend_next_line", false)

                rules.add(
                    FieldRule(
                        fieldType = fieldType,
                        pattern = Pattern.compile(patternStr),
                        luhn = luhn,
                        skipIfExcluded = skipIfExcluded,
                        extendNextLine = extendNextLine,
                    ),
                )
            }
            return FieldRules(version, excludeWords, rules)
        }

        /**
         * Loads [FieldRules] from the application assets.
         *
         * @param assets Android [AssetManager].
         * @param path Asset relative path, checking fallback paths if relocated.
         * @return Parsed [FieldRules].
         */
        fun load(assets: AssetManager, path: String = "templates/rules/fields.json"): FieldRules {
            val candidatePaths = listOf(path, "rules/fields.json", "fields.json")
            for (p in candidatePaths) {
                try {
                    assets.open(p).use { input ->
                        return parse(input.bufferedReader().use { it.readText() })
                    }
                } catch (_: Exception) {
                    // Try next path
                }
            }
            throw IllegalStateException("Field rules not found in assets: $path")
        }

        /**
         * Loads [FieldRules] from a file on disk (for JVM tests).
         *
         * @param file JSON file on disk.
         * @return Parsed [FieldRules].
         */
        fun load(file: File): FieldRules = parse(file.readText(Charsets.UTF_8))
    }
}
