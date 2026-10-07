package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpReaderTest {

    @Test
    fun `extractOtp finds verification code accurately`() {
        assertEquals(
            "123456",
            OtpReader.extractOtp("رمز التحقق الخاص بك هو 123456 لا تشاركه")
        )
        assertEquals(
            "9876",
            OtpReader.extractOtp("Your verification code is 9876")
        )
        assertNull(
            OtpReader.extractOtp("مرحبا بك في تطبيقنا")
        )
    }

    @Test
    fun `formatOtpForSpeech separates digits for clear pronunciation`() {
        assertEquals("1 2 3 4", OtpReader.formatOtpForSpeech("1234"))
        assertEquals("9 8 7 6 5", OtpReader.formatOtpForSpeech("98765"))
    }
}
