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
        assertEquals("ثمانية وستون", NumberSpeech.toArabicWords(68))
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
        assertEquals("واحدة اثنتين ثلاث", NumberSpeech.formatByMode(1, 123, false))
    }

    @Test
    fun formatByMode_grouping() {
        assertEquals("اثنتي عشرة, ثلاث وثلاثون, خمس وأربعون", NumberSpeech.formatByMode(2, 123345, false))
    }

    @Test
    fun formatByMode_english() {
        assertEquals("one two", NumberSpeech.formatByMode(1, 12, true))
    }
}