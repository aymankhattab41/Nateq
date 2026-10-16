package com.aymankhattab.nateq.core.audio.phone

import android.media.ToneGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تخطيط ومفاتيح لوحة اتصال Lord TTS الميسّرة.
 */
class DialpadLayoutTest {

    @Test
    fun standardDialpad_containsFourRowsOfThreeKeys() {
        val rows = DialpadLayoutHelper.getStandardDialpadKeys()
        assertEquals(4, rows.size)
        for (r in rows) {
            assertEquals(3, r.size)
        }
    }

    @Test
    fun standardDialpad_containsAllDigitsAndSymbols() {
        val rows = DialpadLayoutHelper.getStandardDialpadKeys()
        val allKeys = rows.flatten()
        val digits = allKeys.map { it.digit }

        assertEquals(12, allKeys.size)
        assertTrue(digits.containsAll(listOf('1', '2', '3', '4', '5')))
        assertTrue(digits.containsAll(listOf('6', '7', '8', '9', '0')))
        assertTrue(digits.containsAll(listOf('*', '#')))
    }

    @Test
    fun standardDialpad_assignsCorrectDtmfTones() {
        val rows = DialpadLayoutHelper.getStandardDialpadKeys()
        val keyMap = rows.flatten().associateBy { it.digit }

        assertEquals(ToneGenerator.TONE_DTMF_1, keyMap['1']?.dtmfTone)
        assertEquals(ToneGenerator.TONE_DTMF_5, keyMap['5']?.dtmfTone)
        assertEquals(ToneGenerator.TONE_DTMF_9, keyMap['9']?.dtmfTone)
        assertEquals(ToneGenerator.TONE_DTMF_0, keyMap['0']?.dtmfTone)
        assertEquals(ToneGenerator.TONE_DTMF_S, keyMap['*']?.dtmfTone)
        assertEquals(ToneGenerator.TONE_DTMF_P, keyMap['#']?.dtmfTone)
    }

    @Test
    fun standardDialpad_providesArabicSpokenLabels() {
        val rows = DialpadLayoutHelper.getStandardDialpadKeys()
        val keyMap = rows.flatten().associateBy { it.digit }

        assertEquals("واحد", keyMap['1']?.spokenLabel)
        assertEquals("صفر", keyMap['0']?.spokenLabel)
        assertEquals("نجمة", keyMap['*']?.spokenLabel)
        assertEquals("مربع", keyMap['#']?.spokenLabel)
    }
}
