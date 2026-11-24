package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpReaderTest {

    @Test
    fun extractOtp_arabicIndicDigits() {
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق ١٢٣٤٥٦")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق الخاص بك هو ١٢٣٤٥٦ لا تشاركه")
        )
        assertEquals(
            "012345",
            OtpReader.extractOtp("رمز التحقق: ٠١٢٣٤٥")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق ١٢٣-٤٥٦")
        )
    }

    @Test
    fun extractOtp_persianDigits() {
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق ۱۲۳۴۵۶")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("کد تأیید شما ۱۲۳۴۵۶ است")
        )
        assertEquals(
            "012345",
            OtpReader.extractOtp("کد تایید: ۰۱۲۳۴۵")
        )
    }

    @Test
    fun extractOtp_latinDigits() {
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق الخاص بك هو 123456 لا تشاركه")
        )
        assertEquals(
            "012345",
            OtpReader.extractOtp("رمز التحقق: 012345")
        )
        assertEquals(
            "9876",
            OtpReader.extractOtp("Your verification code is 9876")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("Your verification code is 123-456")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("G-123456 is your code")
        )
    }

    @Test
    fun extractOtp_boundariesAndInvalid() {
        assertNull(OtpReader.extractOtp("مرحبا بك في تطبيقنا"))
        assertNull(OtpReader.extractOtp("رمز التحقق 123"))
        assertNull(OtpReader.extractOtp("رمز التحقق 123456789"))
        assertNull(OtpReader.extractOtp("رمز التحقق ١٢٣"))
        assertNull(OtpReader.extractOtp("رمز التحقق ١٢٣٤٥٦٧٨٩"))
        assertNull(OtpReader.extractOtp(""))
        assertNull(OtpReader.extractOtp(null))
    }

    @Test
    fun formatOtpForSpeech_separatesDigitsAndPreservesLeadingZero() {
        assertEquals("0 1 2 3 4 5", OtpReader.formatOtpForSpeech("012345"))
        assertEquals("1 2 3 4 5 6", OtpReader.formatOtpForSpeech("123456"))
        assertEquals("1 2 3 4 5 6", OtpReader.formatOtpForSpeech("١٢٣٤٥٦"))
        assertEquals("0 1 2 3 4 5", OtpReader.formatOtpForSpeech("۰۱۲۳۴۵"))
        assertEquals("1 2 3 4", OtpReader.formatOtpForSpeech("1234"))
        assertEquals("9 8 7 6 5", OtpReader.formatOtpForSpeech("98765"))
    }
}
