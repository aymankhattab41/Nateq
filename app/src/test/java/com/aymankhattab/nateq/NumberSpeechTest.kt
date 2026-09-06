package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.NumberSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات نطق الأرقام — جزء كتابي نقي يعمل عبر JVM بلا أجهزة. */
class NumberSpeechTest {

    @Test
    fun arabicWords_basicUnits() {
        assertEquals("صفر", NumberSpeech.toArabicWords(0))
        assertEquals("واحدة", NumberSpeech.toArabicWords(1))
        assertEquals("عشرون", NumberSpeech.toArabicWords(20))
        assertEquals("خمس وثلاثون", NumberSpeech.toArabicWords(35))
    }

    @Test
    fun arabicWords_beyondFiftyNine() {
        assertEquals("ثماني وستون", NumberSpeech.toArabicWords(68))
        assertEquals("تسعون", NumberSpeech.toArabicWords(90))
        assertEquals("مئة", NumberSpeech.toArabicWords(100))
    }

    @Test
    fun englishWords_beyondFiftyNine() {
        assertEquals("sixty eight", NumberSpeech.toEnglishWords(68))
        assertEquals("ninety", NumberSpeech.toEnglishWords(90))
        assertEquals("one hundred", NumberSpeech.toEnglishWords(100))
    }

    @Test
    fun formatByMode_singleDigits() {
        assertEquals("واحد اثنان ثلاثة", NumberSpeech.formatByMode(1, 123, false))
    }

    @Test
    fun formatByMode_grouping() {
        assertEquals("اثنا عشر, ثلاثة وثلاثون, خمسة وأربعون", NumberSpeech.formatByMode(2, 123345, false))
    }

    @Test
    fun formatByMode_english() {
        assertEquals("one two", NumberSpeech.formatByMode(1, 12, true))
    }

    @Test
    fun arabicWords_largeNumbers() {
        assertEquals("ألف", NumberSpeech.toArabicWords(1000))
        assertEquals("ألفان", NumberSpeech.toArabicWords(2000))
        assertEquals("ألف وواحدة", NumberSpeech.toArabicWords(1001))
        assertEquals("خمسة آلاف", NumberSpeech.toArabicWords(5000))
        assertEquals("ألف ومئة", NumberSpeech.toArabicWords(1100))
    }

    @Test
    fun arabicWords_hundreds() {
        assertEquals("مائتان", NumberSpeech.toArabicWords(200))
        assertEquals("ثلاثةمائة", NumberSpeech.toArabicWords(300))
        assertEquals("مئة وخمس", NumberSpeech.toArabicWords(105))
        assertEquals("أربعةمائة وعشر", NumberSpeech.toArabicWords(410))
    }

    @Test
    fun arabicWords_masculine() {
        assertEquals("واحد", NumberSpeech.toArabicWords(1, isFeminine = false))
        assertEquals("اثنان", NumberSpeech.toArabicWords(2, isFeminine = false))
        assertEquals("خمسة", NumberSpeech.toArabicWords(5, isFeminine = false))
        assertEquals("أحد وخمسون", NumberSpeech.toArabicWords(51, isFeminine = false))
        assertEquals("أحد عشر", NumberSpeech.toArabicWords(11, isFeminine = false))
    }

    @Test
    fun arabicWords_feminineCompound() {
        // مؤنث مركب فوق العشرات
        assertEquals("إحدى وخمسون", NumberSpeech.toArabicWords(51, isFeminine = true))
        assertEquals("اثنتان وثلاثون", NumberSpeech.toArabicWords(32, isFeminine = true))
        assertEquals("خمس وستون", NumberSpeech.toArabicWords(65, isFeminine = true))
    }

    @Test
    fun englishWords_basicNumbers() {
        assertEquals("zero", NumberSpeech.toEnglishWords(0))
        assertEquals("one", NumberSpeech.toEnglishWords(1))
        assertEquals("twelve", NumberSpeech.toEnglishWords(12))
        assertEquals("thirteen", NumberSpeech.toEnglishWords(13))
        assertEquals("twenty", NumberSpeech.toEnglishWords(20))
        assertEquals("twenty one", NumberSpeech.toEnglishWords(21))
    }

    @Test
    fun englishWords_largeNumbers() {
        assertEquals("one thousand", NumberSpeech.toEnglishWords(1000))
        assertEquals("two thousand five hundred", NumberSpeech.toEnglishWords(2500))
        assertEquals("nine thousand nine hundred ninety nine", NumberSpeech.toEnglishWords(9999))
    }

    @Test
    fun englishWords_hundreds() {
        assertEquals("one hundred", NumberSpeech.toEnglishWords(100))
        assertEquals("one hundred five", NumberSpeech.toEnglishWords(105))
        assertEquals("three hundred forty one", NumberSpeech.toEnglishWords(341))
    }

    @Test
    fun formatByMode_groupingWithLeadingGroup() {
        // ungrouped تقسيم المجموعات مع مجموعة أولى أصغر (123 → 1, 23)
        assertEquals("واحد, ثلاثة وعشرون", NumberSpeech.formatByMode(2, 123, false))
        assertEquals("one, twenty three", NumberSpeech.formatByMode(2, 123, true))
    }

    @Test
    fun formatByMode_negativeNumbers() {
        assertEquals("سالب واحد", NumberSpeech.formatByMode(1, -1, false))
        assertEquals("negative one", NumberSpeech.formatByMode(1, -1, true))
    }

    @Test
    fun formatByMode_largeGrouping() {
        assertEquals("مائة وثلاثة وعشرون, مائة وثلاثة وعشرون", NumberSpeech.formatByMode(3, 123123, false))
    }
}