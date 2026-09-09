package com.aymankhattab.nateq.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات منطق Controllers B: تطبيع الأرقام وحدود معامل السرعة/النبرة. */
class ControllersBLogicTest {

    @Test
    fun normalizeDigits_arabicIndic() {
        assertEquals("5", "٥".normalizeDigits())
        assertEquals("10", "١٠".normalizeDigits())
        assertEquals("2300", "٢٣٠٠".normalizeDigits())
        assertEquals("0", "٠".normalizeDigits())
    }

    @Test
    fun normalizeDigits_persianUrdu() {
        assertEquals("5", "۵".normalizeDigits())
        assertEquals("10", "۱۰".normalizeDigits())
        assertEquals("2300", "۲۳۰۰".normalizeDigits())
    }

    @Test
    fun normalizeDigits_mixedAsciiAndSymbols() {
        assertEquals("12:30", "١٢:٣٠".normalizeDigits())
        assertEquals("7", "7".normalizeDigits())
        assertEquals("a1b", "a1b".normalizeDigits())
        assertEquals("23", "۲3".normalizeDigits())
    }

    @Test
    fun normalizeDigits_empty() {
        assertEquals("", "".normalizeDigits())
    }

    @Test
    fun speedFactor_clampsBelowMin() {
        assertEquals(MIN_SPEED_PITCH_FACTOR, 0.speedFactor(), 0f)
        assertEquals(MIN_SPEED_PITCH_FACTOR, 10.speedFactor(), 0f)
        assertEquals(MIN_SPEED_PITCH_FACTOR, 25.speedFactor(), 0f)
    }

    @Test
    fun speedFactor_preservesNormalAndHighValues() {
        assertEquals(0.5f, 50.speedFactor(), 0f)
        assertEquals(1.0f, 100.speedFactor(), 0f)
        assertEquals(1.5f, 150.speedFactor(), 0f)
        assertEquals(2.0f, 200.speedFactor(), 0f)
    }
}