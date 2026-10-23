package com.sudo.securegallery.localize

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.FieldType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Field-rule matching (issue #17). The rules are the bundled `templates/rules/fields.json`, and
 * the cases in `validation/localize/tests/rule_cases.json` are the ones the Python harness checks
 * too, so the app finds the same fields as the desktop reference.
 */
@RunWith(AndroidJUnit4::class)
class FieldRulesTest {
    private val rules = FieldRules.load(ApplicationProvider.getApplicationContext<Context>().assets)

    @Test
    fun matchesTheDesktopHarnessOnEveryCase() {
        val cases = JSONObject(repoFile("validation/localize/tests/rule_cases.json").readText()).getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val lines = case.getJSONArray("lines").let { arr ->
                (0 until arr.length()).map(arr::getJSONObject).map { line ->
                    val words = line.getJSONArray("words")
                    OcrLine(
                        block = line.getInt("block"),
                        line = line.getInt("line"),
                        words = (0 until words.length()).map(words::getJSONArray).map { w ->
                            OcrWord(w.getString(0), w.getDouble(1).toFloat(), w.getDouble(2).toFloat(), w.getDouble(3).toFloat(), w.getDouble(4).toFloat())
                        },
                    )
                }
            }
            val expected = case.getJSONArray("expected").let { arr ->
                (0 until arr.length()).map(arr::getJSONObject).map { e ->
                    val box = e.getJSONArray("box")
                    FieldType.fromId(e.getString("field_type")) to List(4) { box.getDouble(it).toFloat() }
                }
            }
            val found = rules.match(lines).map { it.fieldType to Geometry.bounds(it.polygon).toList() }
            assertEquals(case.getString("name"), expected, found)
        }
    }

    @Test
    fun luhnCheckMatchesPublishedTestNumbers() {
        assertTrue(FieldRules.isLuhnValid("4111 1111 1111 1111"))
        assertTrue(FieldRules.isLuhnValid("5555-5555-5555-4444"))
        assertFalse(FieldRules.isLuhnValid("4111 1111 1111 1112"))
        assertFalse(FieldRules.isLuhnValid(""))
    }

    @Test
    fun ocrResultsNeverPrintTheirText() {
        val word = OcrWord("DELA CRUZ", 1f, 2f, 3f, 4f)
        val line = OcrLine(0, 0, listOf(word))
        assertFalse(word.toString().contains("DELA"))
        assertFalse(line.toString().contains("DELA"))
    }

    @Test
    fun everyBundledRuleNamesAKnownField() {
        assertEquals(10, rules.rules.size)
        assertTrue("VISA" in rules.excludeWords)
    }

    companion object {
        /** A file in the repository, found from the module's working directory. */
        fun repoFile(path: String): File {
            var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
            while (dir != null) {
                val file = File(dir, path)
                if (file.exists()) return file
                dir = dir.parentFile
            }
            error("$path not found above ${System.getProperty("user.dir")}")
        }
    }
}
