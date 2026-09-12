package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.SmartSpeller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبارات التهجئة الذكية (نطق الحرف المفرد مع الحركات وأسماء NATO). */
class SmartSpellerTest {

    @Test
    fun arabicPlainLetter_spelledByName() {
        assertEquals("باء", SmartSpeller.spell("ب", "ar"))
        assertEquals("ألف بهمزة", SmartSpeller.spell("أ", "ar"))
        assertEquals("ألف بهمزة", SmartSpeller.spell("إ", "ar"))
        assertEquals("ألف ممدودة", SmartSpeller.spell("آ", "ar"))
        assertEquals("ألف وصل", SmartSpeller.spell("ٱ", "ar"))
        assertEquals("واو مهموزة", SmartSpeller.spell("ؤ", "ar"))
        assertEquals("ياء مهموزة", SmartSpeller.spell("ئ", "ar"))
        assertEquals("همزة", SmartSpeller.spell("ء", "ar"))
        assertEquals("تاء مربوطة", SmartSpeller.spell("ة", "ar"))
    }

    @Test
    fun arabicLetterWithHaraka_spelledWithVowelName() {
        assertEquals("باء مفتوحة", SmartSpeller.spell("بَ", "ar"))
        assertEquals("باء مضمومة", SmartSpeller.spell("بُ", "ar"))
        assertEquals("باء ساكنة", SmartSpeller.spell("بْ", "ar"))
        assertEquals("دال مكسورة", SmartSpeller.spell("دِ", "ar"))
        // حركة بلا حرف أساس: لا تهجئة
        assertNull(SmartSpeller.spell("ِ", "ar"))
    }

    @Test
    fun doubledLetter_shaddaFirstThenVowel() {
        assertEquals("باء مشددة", SmartSpeller.spell("بّ", "ar"))
        assertEquals("باء مشددة مفتوحة", SmartSpeller.spell("بَّ", "ar"))
    }

    @Test
    fun tanween_namedCorrectly() {
        assertEquals("باء منصوبة", SmartSpeller.spell("بً", "ar"))
        assertEquals("باء مرفوعة", SmartSpeller.spell("بٌ", "ar"))
        assertEquals("باء مجرورة", SmartSpeller.spell("بٍ", "ar"))
    }

    @Test
    fun englishLowerAndUpper_natoWithCapitalPrefix() {
        assertEquals("Alpha", SmartSpeller.spell("a", "en"))
        assertEquals("Capital Alpha", SmartSpeller.spell("A", "en"))
        assertEquals("Zulu", SmartSpeller.spell("z", "en"))
        assertEquals("Capital Zulu", SmartSpeller.spell("Z", "en"))
        assertEquals("Whiskey", SmartSpeller.spell("w", "en"))
    }

    @Test
    fun nonLetterOrMultiChar_notSpelled() {
        // غير هجائي أو أطول من حرف واحد: يمر في المسار العادي
        assertNull(SmartSpeller.spell("", "ar"))
        assertNull(SmartSpeller.spell("1", "ar"))
        assertNull(SmartSpeller.spell(",", "ar"))
        assertNull(SmartSpeller.spell("ab", "en"))
        assertNull(SmartSpeller.spell("مرحبا", "ar"))
        assertNull(SmartSpeller.spell("😊", "ar"))
    }

    @Test
    fun arabicLetterInEnglishContext_stillSpelledArabic() {
        assertEquals("باء", SmartSpeller.spell("ب", "en"))
    }
}