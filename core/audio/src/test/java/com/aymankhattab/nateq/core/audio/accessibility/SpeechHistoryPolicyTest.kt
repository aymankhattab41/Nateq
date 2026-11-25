package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechHistoryPolicyTest {

    @Test
    fun shouldRecord_rejectsBlankNullCallOrSensitive() {
        assertFalse(SpeechHistoryPolicy.shouldRecord(null, isCall = false))
        assertFalse(SpeechHistoryPolicy.shouldRecord("   ", isCall = false))
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("نص عادي", isCall = true)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "نص عادي", isCall = false, isSensitive = true
            )
        )
    }

    @Test
    fun shouldRecord_rejectsPureDigitsOtp() {
        assertFalse(SpeechHistoryPolicy.shouldRecord("1234", isCall = false))
        assertFalse(SpeechHistoryPolicy.shouldRecord("123456", isCall = false))
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("12345678", isCall = false)
        )
    }

    @Test
    fun shouldRecord_rejectsSpaceAndHyphenSeparatedDigits() {
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("1 2 3 4 5 6", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("0 1 2 3 4 5", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("123-456", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("1234-5678", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("1234 5678", isCall = false)
        )
    }

    @Test
    fun shouldRecord_rejectsArabicIndicAndPersianDigits() {
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("١٢٣٤٥٦", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("١ ٢ ٣ ٤ ٥ ٦", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("۰۱۲۳۴۵", isCall = false)
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord("۰ ۱ ۲ ۳ ۴ ۵", isCall = false)
        )
    }

    @Test
    fun shouldRecord_rejectsArabicWordRepresentation() {
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "واحد اثنان ثلاثة أربعة خمسة ستة",
                isCall = false
            )
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "صفر واحد اثنين ثلاثة اربعة خمسة",
                isCall = false
            )
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "سبعة ثمانية تسعة واحد",
                isCall = false
            )
        )
    }

    @Test
    fun shouldRecord_rejectsEnglishWordRepresentation() {
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "one two three four five six",
                isCall = false
            )
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "zero one two three four five",
                isCall = false
            )
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "nine eight seven six",
                isCall = false
            )
        )
    }

    @Test
    fun shouldRecord_rejectsOtpMessageWithKeyword() {
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "رمز التحقق 123456",
                isCall = false
            )
        )
        assertFalse(
            SpeechHistoryPolicy.shouldRecord(
                "Your verification code is 123456",
                isCall = false
            )
        )
    }

    @Test
    fun shouldRecord_acceptsNormalNonSensitiveSpeech() {
        assertTrue(
            SpeechHistoryPolicy.shouldRecord(
                "رسالة جديدة من محمد",
                isCall = false
            )
        )
        assertTrue(
            SpeechHistoryPolicy.shouldRecord(
                "البطارية 20 بالمئة",
                isCall = false
            )
        )
        assertTrue(
            SpeechHistoryPolicy.shouldRecord(
                "الساعة الخامسة مساء",
                isCall = false
            )
        )
        assertTrue(
            SpeechHistoryPolicy.shouldRecord(
                "كتاب جديد",
                isCall = false
            )
        )
    }
}
