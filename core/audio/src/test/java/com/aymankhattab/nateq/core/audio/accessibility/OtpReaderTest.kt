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
        assertEquals(
            "12345678",
            OtpReader.extractOtp("رمز التحقق ١٢٣٤-٥٦٧٨")
        )
        assertEquals(
            "12345678",
            OtpReader.extractOtp("رمز التحقق ١٢٣٤ ٥٦٧٨")
        )
        assertEquals(
            "01234567",
            OtpReader.extractOtp("رمز التحقق: ٠١٢٣-٤٥٦٧")
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
        assertEquals(
            "12345678",
            OtpReader.extractOtp("کد تأیید ۱۲۳۴-۵۶۷۸")
        )
        assertEquals(
            "12345678",
            OtpReader.extractOtp("کد تأیید ۱۲۳۴ ۵۶۷۸")
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp("کد تایید ۱۲۳-۴۵۶")
        )
        assertEquals(
            "01234567",
            OtpReader.extractOtp("کد تایید: ۰۱۲۳-۴۵۶۷")
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
        assertEquals(
            "123456",
            OtpReader.extractOtp("G-123-456 is your code")
        )
    }

    @Test
    fun extractOtp_hyphenAndSpaceSeparatedCodes() {
        // فحص الرموز المفصولة بشرطة أو مسافة بصيغة مستقلة أو مع كلمة
        assertEquals("12345678", OtpReader.extractOtp("1234-5678"))
        assertEquals("12345678", OtpReader.extractOtp("1234 5678"))
        assertEquals("123456", OtpReader.extractOtp("123-456"))
        assertEquals("01234567", OtpReader.extractOtp("0123-4567"))
        assertEquals("012345", OtpReader.extractOtp("012-345"))
        assertEquals("01234567", OtpReader.extractOtp("0123 4567"))
        assertEquals("123456", OtpReader.extractOtp("12-34-56"))
        assertEquals("123456", OtpReader.extractOtp("12 34 56"))
    }

    @Test
    fun extractOtp_datesAndPhoneNumbersExcluded() {
        // أرقام التواريخ لا تُستخرج كرمز تحقق
        assertNull(OtpReader.extractOtp("10-23-1987"))
        assertNull(OtpReader.extractOtp("2024-11-25"))
        assertNull(OtpReader.extractOtp("التاريخ 10-23-1987"))
        assertNull(OtpReader.extractOtp("بتاريخ 2024-11-25"))
        assertNull(OtpReader.extractOtp("date: 2024-11-25"))

        // أرقام الهاتف وسياق الهاتف والمبالغ لا تُستخرج
        assertNull(OtpReader.extractOtp("010-1234-5678"))
        assertNull(OtpReader.extractOtp("هاتف 123-4567"))
        assertNull(OtpReader.extractOtp("اتصل على 123-4567"))
        assertNull(OtpReader.extractOtp("المبلغ 123-456"))
    }

    @Test
    fun extractOtp_mixedContextExtractsOtpIgnoresDateAndPhone() {
        assertEquals(
            "12345678",
            OtpReader.extractOtp(
                "رمز التحقق هو 1234-5678 لطلبك بتاريخ 2024-11-25"
            )
        )
        assertEquals(
            "123456",
            OtpReader.extractOtp(
                "اتصل على 010-1234-5678 أو استخدم رمز التحقق 123-456"
            )
        )
    }

    @Test
    fun extractOtp_boundariesAndInvalid() {
        assertNull(OtpReader.extractOtp("مرحبا بك في تطبيقنا"))
        assertNull(OtpReader.extractOtp("رمز التحقق 123"))
        assertNull(OtpReader.extractOtp("رمز التحقق 123456789"))
        assertNull(OtpReader.extractOtp("رمز التحقق 1234-56789"))
        assertNull(OtpReader.extractOtp("رمز التحقق 12-3"))
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
        assertEquals(
            "1 2 3 4 5 6 7 8",
            OtpReader.formatOtpForSpeech("1234-5678")
        )
        assertEquals(
            "0 1 2 3 4 5 6 7",
            OtpReader.formatOtpForSpeech("0123-4567")
        )
    }
}
