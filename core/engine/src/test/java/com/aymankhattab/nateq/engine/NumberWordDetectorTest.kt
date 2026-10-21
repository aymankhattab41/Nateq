package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.core.engine.EngineMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberWordDetectorTest {

    @Test
    fun testDetectArabicNumbers() {
        val input = "خمسة من سبعة"
        val marked = NumberWordDetector.detectAndMark(input, "ar")
        val expected = "${EngineMarkers.NUMBER_MARK_START}خمسة" +
            "${EngineMarkers.NUMBER_MARK_END} من " +
            "${EngineMarkers.NUMBER_MARK_START}سبعة" +
            "${EngineMarkers.NUMBER_MARK_END}"
        assertEquals(expected, marked)
    }

    @Test
    fun testDetectCompoundArabicNumber() {
        val input = "خمسة وعشرون جنيهاً"
        val marked = NumberWordDetector.detectAndMark(input, "ar")
        val expected = "${EngineMarkers.NUMBER_MARK_START}خمسة وعشرون" +
            "${EngineMarkers.NUMBER_MARK_END} جنيهاً"
        assertEquals(expected, marked)
    }

    @Test
    fun testDetectEnglishNumbers() {
        val input = "five of seven"
        val marked = NumberWordDetector.detectAndMark(input, "en")
        val expected = "${EngineMarkers.NUMBER_MARK_START}five" +
            "${EngineMarkers.NUMBER_MARK_END} of " +
            "${EngineMarkers.NUMBER_MARK_START}seven" +
            "${EngineMarkers.NUMBER_MARK_END}"
        assertEquals(expected, marked)
    }

    @Test
    fun testExclusionsNotMarked() {
        val input = "عبد الواحد no one someone"
        val marked = NumberWordDetector.detectAndMark(input, "ar")
        assertFalse(marked.contains(EngineMarkers.NUMBER_MARK_START))
        assertFalse(marked.contains(EngineMarkers.NUMBER_MARK_END))
    }

    @Test
    fun testAlreadyMarkedNotDoubleMarked() {
        val input = "${EngineMarkers.NUMBER_MARK_START}خمسة" +
            "${EngineMarkers.NUMBER_MARK_END}"
        val marked = NumberWordDetector.detectAndMark(input, "ar")
        assertEquals(input, marked)
    }

    @Test
    fun testHasNumberWord() {
        val noNumbers = "نص عادي بدون أرقام إطلاقاً"
        assertFalse(NumberWordDetector.hasNumberWord(noNumbers))
        assertTrue(NumberWordDetector.hasNumberWord("خمسة عشر يوماً"))
        assertTrue(NumberWordDetector.hasNumberWord("twenty days"))
        assertFalse(NumberWordDetector.hasNumberWord("hello world store"))
    }

    @Test
    fun testTextWithoutNumbersIsFast() {
        val nonNumberText = "هذا نص عادي جداً للاختبار والتأكد من سرعة " +
            "الفحص الخطي قبل تشغيل التعابير المنتظمة المعقدة في كاشف الأعداد " +
            "بالحروف حتى نوفر البطارية والأداء على الأجهزة المختلفة بشكل " +
            "ملحوظ وفعال ومؤكد دائماً دون أي تباطؤ."
        assertTrue(nonNumberText.length >= 200)

        // Warmup JIT
        repeat(100) {
            NumberWordDetector.detectAndMark(nonNumberText, "ar")
        }

        val iterations = 500
        val start = System.nanoTime()
        repeat(iterations) {
            NumberWordDetector.detectAndMark(nonNumberText, "ar")
        }
        val elapsedNanos = System.nanoTime() - start
        val avgMs = (elapsedNanos.toDouble() / iterations) / 1_000_000.0
        assertTrue(
            "Average time per call ($avgMs ms) should be < 0.2ms",
            avgMs < 0.2
        )
    }
}
