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
}
