package com.aymankhattab.nateq.core.audio.keyboard

import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة تخطيط ومفاتيح لوحة مفاتيح Lord TTS الميسّرة.
 */
class KeyboardLayoutHelperTest {

    @Test
    fun getArabicRows_returnsFiveRowsWithCorrectActions() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        assertEquals(5, rows.size)

        // الصف الأول يبدأ بـ ض (11 حرفاً)
        assertEquals("ض", rows[0].first().label)
        assertEquals(11, rows[0].size)

        // الصف الثاني 11 حرفاً
        assertEquals(11, rows[1].size)

        // الصف الثالث همزات 6
        assertEquals(6, rows[2].size)
        assertEquals(
            listOf("أ", "إ", "آ", "ؤ", "ئ", "ء"),
            rows[2].map { it.label }
        )

        // الصف الرابع يحتوي 8 حروف ومفتاح الحذف
        val backspaceKey = rows[3].find {
            it.type == KeyType.ACTION_BACKSPACE
        }
        assertNotNull(backspaceKey)
        assertEquals("حذف", backspaceKey?.spokenText)

        // الصف الخامس يحتوي مفاتيح المسافة والإدخال واللغات والرموز والتشكيل
        val spaceKey = rows[4].find { it.type == KeyType.ACTION_SPACE }
        assertNotNull(spaceKey)
        val enterKey = rows[4].find { it.type == KeyType.ACTION_ENTER }
        assertNotNull(enterKey)
        val langKey = rows[4].find { it.type == KeyType.ACTION_SWITCH_LANG }
        assertNotNull(langKey)
        val symbolsKey = rows[4].find { it.type == KeyType.ACTION_SYMBOLS }
        assertNotNull(symbolsKey)
        val tashkeelKey = rows[4].find { it.type == KeyType.ACTION_TASHKEEL }
        assertNotNull(tashkeelKey)
    }

    @Test
    fun arabicCharacters_haveUniqueSpokenTexts() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val charKeys = rows.flatten().filter {
            it.type == KeyType.CHARACTER
        }
        val spokenTexts = charKeys.map { it.spokenText }
        val uniqueSpoken = spokenTexts.toSet()
        assertEquals(spokenTexts.size, uniqueSpoken.size)
    }

    @Test
    fun getArabicRows_hasPhoneticDescriptionsForArabicLetters() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val alef = rows.flatten().find { it.label == "ا" }
        assertNotNull(alef)
        assertEquals("ألف - أسد", alef?.phonetic)

        val baa = rows.flatten().find { it.label == "ب" }
        assertNotNull(baa)
        assertEquals("باء - بطة", baa?.phonetic)
    }

    @Test
    fun getEnglishRows_supportsLowerAndUpperCases() {
        val lowerRows = KeyboardLayoutHelper.getEnglishRows(isCaps = false)
        val qLower = lowerRows[0].first()
        assertEquals("q", qLower.label)
        assertEquals("Alpha", lowerRows.flatten().find {
            it.label == "a"
        }?.phonetic)

        val upperRows = KeyboardLayoutHelper.getEnglishRows(isCaps = true)
        val qUpper = upperRows[0].first()
        assertEquals("Q", qUpper.label)
        assertEquals("Alpha", upperRows.flatten().find {
            it.label == "A"
        }?.phonetic)
    }

    @Test
    fun getSymbolsRows_containsDigitsAndSpokenPunctuation() {
        val symbolRows = KeyboardLayoutHelper.getSymbolsRows()
        assertEquals(4, symbolRows.size)

        // الصف الأول يحتوي الأرقام من 1 إلى 0
        val digits = symbolRows[0].map { it.label }
        assertTrue(digits.contains("1"))
        assertTrue(digits.contains("0"))

        // الرموز لها أسماء نطق واضحة
        val atSymbol = symbolRows.flatten().find { it.label == "@" }
        assertNotNull(atSymbol)
        assertEquals("آت", atSymbol?.spokenText)

        val questionSymbol = symbolRows.flatten().find { it.label == "?" }
        assertNotNull(questionSymbol)
        assertEquals("علامة استفهام", questionSymbol?.spokenText)
    }

    @Test
    fun getTashkeelRow_containsAllStandardArabicDiacritics() {
        val tashkeel = KeyboardLayoutHelper.getTashkeelRow()
        assertTrue(tashkeel.size >= 8)

        val fatha = tashkeel.find { it.label == "َ" }
        assertNotNull(fatha)
        assertEquals("فتحة", fatha?.spokenText)

        val shadda = tashkeel.find { it.label == "ّ" }
        assertNotNull(shadda)
        assertEquals("شدة", shadda?.spokenText)

        val sukun = tashkeel.find { it.label == "ْ" }
        assertNotNull(sukun)
        assertEquals("سكون", sukun?.spokenText)
    }
}
