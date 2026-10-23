package com.sudo.securegallery.detect

import com.sudo.securegallery.data.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for shared OCR field rules (§8.2, §8.6) and regex patterns.
 *
 * Verifies that the regex rules loaded from `templates/rules/fields.json` match
 * expected Philippine ID and card patterns identically to desktop `core.py`.
 */
class FieldRulesTest {

    private lateinit var rules: FieldRules

    @Before
    fun setUp() {
        val rulesFile = File("../../templates/rules/fields.json").let {
            if (it.exists()) it else File("templates/rules/fields.json")
        }
        rules = FieldRules.load(rulesFile)
    }

    @Test
    fun parsesRulesAndExcludeWords() {
        assertTrue(rules.excludeWords.contains("REPUBLIC"))
        assertTrue(rules.excludeWords.contains("EPHILID"))
        assertTrue(rules.excludeWords.contains("VISA"))
        assertTrue(rules.rules.any { it.fieldType == FieldType.PCN })
        assertTrue(rules.rules.any { it.fieldType == FieldType.CARD_NUMBER })
        assertTrue(rules.rules.any { it.fieldType == FieldType.LICENSE_NUMBER })
    }

    @Test
    fun pcnMatchesValidFourDigitGroups() {
        val line = OcrLine(
            text = "PCN: 1234-5678-9012-3456",
            words = listOf(
                OcrWord("PCN:", 0f, 0f, 30f, 20f),
                OcrWord("1234-5678-9012-3456", 35f, 0f, 150f, 20f),
            ),
        )
        val matches = rules.match(listOf(line))
        assertEquals(1, matches.count { it.fieldType == FieldType.PCN })
    }

    @Test
    fun luhnValidationAcceptsValidCardsAndRejectsInvalid() {
        // Standard published test PAN (Adyen / Stripe Visa)
        assertTrue(FieldRules.isLuhnValid("4111 1111 1111 1111"))
        assertTrue(FieldRules.isLuhnValid("4242-4242-4242-4242"))
        assertTrue(FieldRules.isLuhnValid("5555555555554444"))

        // Tampered check digit
        assertFalse(FieldRules.isLuhnValid("4111 1111 1111 1112"))
        assertFalse(FieldRules.isLuhnValid("4242-4242-4242-4240"))
        assertFalse(FieldRules.isLuhnValid(""))
    }

    @Test
    fun cardNumberRuleRequiresValidLuhn() {
        val validLine = OcrLine(
            text = "CARD 4111 1111 1111 1111",
            words = listOf(
                OcrWord("CARD", 0f, 0f, 30f, 20f),
                OcrWord("4111", 35f, 0f, 60f, 20f),
                OcrWord("1111", 65f, 0f, 90f, 20f),
                OcrWord("1111", 95f, 0f, 120f, 20f),
                OcrWord("1111", 125f, 0f, 150f, 20f),
            ),
        )
        val invalidLine = OcrLine(
            text = "CARD 4111 1111 1111 1112",
            words = listOf(
                OcrWord("CARD", 0f, 0f, 30f, 20f),
                OcrWord("4111", 35f, 0f, 60f, 20f),
                OcrWord("1111", 65f, 0f, 90f, 20f),
                OcrWord("1111", 95f, 0f, 120f, 20f),
                OcrWord("1112", 125f, 0f, 150f, 20f),
            ),
        )

        val validMatches = rules.match(listOf(validLine))
        assertEquals(1, validMatches.count { it.fieldType == FieldType.CARD_NUMBER })

        val invalidMatches = rules.match(listOf(invalidLine))
        assertEquals(0, invalidMatches.count { it.fieldType == FieldType.CARD_NUMBER })
    }

    @Test
    fun ltoLicenseNumberMatchesPattern() {
        val line = OcrLine(
            text = "NO. N01-23-456789",
            words = listOf(
                OcrWord("NO.", 0f, 0f, 20f, 20f),
                OcrWord("N01-23-456789", 25f, 0f, 120f, 20f),
            ),
        )
        val matches = rules.match(listOf(line))
        assertEquals(1, matches.count { it.fieldType == FieldType.LICENSE_NUMBER })
    }

    @Test
    fun dateOfBirthMatchesBothFormats() {
        val numericLine = OcrLine(
            text = "DOB: 1995/08/24",
            words = listOf(
                OcrWord("DOB:", 0f, 0f, 30f, 20f),
                OcrWord("1995/08/24", 35f, 0f, 100f, 20f),
            ),
        )
        val textualLine = OcrLine(
            text = "AUGUST 24, 1995",
            words = listOf(
                OcrWord("AUGUST", 0f, 0f, 50f, 20f),
                OcrWord("24,", 55f, 0f, 75f, 20f),
                OcrWord("1995", 80f, 0f, 110f, 20f),
            ),
        )

        assertEquals(1, rules.match(listOf(numericLine)).count { it.fieldType == FieldType.DATE_OF_BIRTH })
        assertEquals(1, rules.match(listOf(textualLine)).count { it.fieldType == FieldType.DATE_OF_BIRTH })
    }

    @Test
    fun nameMatchingIgnoresLinesWithExcludedWords() {
        val officialHeader = OcrLine(
            text = "REPUBLIC OF THE PHILIPPINES",
            words = listOf(
                OcrWord("REPUBLIC", 0f, 0f, 60f, 20f),
                OcrWord("OF", 65f, 0f, 80f, 20f),
                OcrWord("THE", 85f, 0f, 105f, 20f),
                OcrWord("PHILIPPINES", 110f, 0f, 180f, 20f),
            ),
        )
        val realName = OcrLine(
            text = "DELA CRUZ, JUAN PEDRO",
            words = listOf(
                OcrWord("DELA", 0f, 0f, 40f, 20f),
                OcrWord("CRUZ,", 45f, 0f, 85f, 20f),
                OcrWord("JUAN", 90f, 0f, 125f, 20f),
                OcrWord("PEDRO", 130f, 0f, 175f, 20f),
            ),
        )

        assertEquals(0, rules.match(listOf(officialHeader)).count { it.fieldType == FieldType.FULL_NAME })
        assertEquals(1, rules.match(listOf(realName)).count { it.fieldType == FieldType.FULL_NAME })
    }

    @Test
    fun addressRuleExtendsToNextLineInSameBlock() {
        val line1 = OcrLine(
            text = "123 RIZAL AVENUE",
            words = listOf(
                OcrWord("123", 0f, 0f, 25f, 20f),
                OcrWord("RIZAL", 30f, 0f, 70f, 20f),
                OcrWord("AVENUE", 75f, 0f, 120f, 20f),
            ),
            blockIndex = 1,
            lineIndex = 0,
        )
        val line2 = OcrLine(
            text = "BARANGAY SAN JOSE",
            words = listOf(
                OcrWord("BARANGAY", 0f, 25f, 80f, 45f),
                OcrWord("SAN", 85f, 25f, 110f, 45f),
                OcrWord("JOSE", 115f, 25f, 150f, 45f),
            ),
            blockIndex = 1,
            lineIndex = 1,
        )

        val matches = rules.match(listOf(line1, line2))
        val addrMatch = matches.firstOrNull { it.fieldType == FieldType.ADDRESS }
        assertTrue(addrMatch != null)
        // Verify bounding box spans across line 2
        assertTrue(addrMatch!!.bottom >= 45f)
    }
}
