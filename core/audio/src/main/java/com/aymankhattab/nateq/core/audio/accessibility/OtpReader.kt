package com.aymankhattab.nateq.core.audio.accessibility

/**
 * قارئ ومستخرج رموز التحقق OTP (ع٢٧-39).
 * يستخرج رموز التحقق (4 إلى 8 أرقام) وينطقها رقماً رقماً بوضوح.
 */
object OtpReader {

    private val OTP_PATTERNS = listOf(
        Regex(
            """(?i)(?:code|otp|رمز|تحقق|كود|pin|verification)""" +
                """[^\d]{0,10}(\d{4,8})"""
        ),
        Regex("""\b(\d{4,8})\b""")
    )

    /**
     * استخراج رمز التحقق من النص إن وجد
     */
    fun extractOtp(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (pattern in OTP_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val group = if (match.groupValues.size > 1) {
                    match.groupValues[1]
                } else {
                    match.groupValues[0]
                }
                if (group.length in 4..8) {
                    return group
                }
            }
        }
        return null
    }

    /**
     * تحويل الرمز إلى صيغة مقروءة بوضوح رقماً رقماً
     */
    fun formatOtpForSpeech(otp: String): String {
        return otp.map { it.toString() }.joinToString(" ")
    }
}
