package com.aymankhattab.nateq.core.audio.accessibility

import com.aymankhattab.nateq.util.LocaleUtils

/**
 * قارئ ومستخرج رموز التحقق OTP (ع٢٧-39).
 * يستخرج رموز التحقق (4 إلى 8 أرقام) وينطقها رقماً رقماً بوضوح.
 */
object OtpReader {

    private val OTP_KEYWORD_PATTERN = Regex(
        """(?i)(?:code|otp|رمز|تحقق|كود|pin|verification|تأیید|تایید|کد)""" +
            """[^0-9]{0,30}?(?:G-)?""" +
            """(?<![0-9])([0-9](?:[- ]?[0-9]){3,7})(?![0-9])"""
    )

    private val STANDALONE_OTP_PATTERN = Regex(
        """(?<![0-9])([0-9](?:[- ]?[0-9]){3,7})(?![0-9])"""
    )

    private val OTP_PATTERNS = listOf(
        OTP_KEYWORD_PATTERN,
        STANDALONE_OTP_PATTERN
    )

    /**
     * استخراج رمز التحقق من النص إن وجد مع تطبيع الأرقام
     * الهندية والفارسية إلى أرقام قياسية دون المساس بترتيبها
     * أو بالصفر البادئ.
     */
    fun extractOtp(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normalized = LocaleUtils.normalizeIndicDigits(text)
        for (pattern in OTP_PATTERNS) {
            val match = pattern.find(normalized)
            if (match != null) {
                val group = if (match.groupValues.size > 1) {
                    match.groupValues[1]
                } else {
                    match.groupValues[0]
                }
                val digits = group.filter { it.isDigit() }
                if (digits.length in 4..8) {
                    return digits
                }
            }
        }
        return null
    }

    /**
     * تحويل الرمز إلى صيغة مقروءة بوضوح رقماً رقماً مع الحفاظ
     * على الصفر البادئ وترتيب الخانات.
     */
    fun formatOtpForSpeech(otp: String): String {
        val normalized = LocaleUtils.normalizeIndicDigits(otp)
        return normalized.filter { it.isDigit() }
            .map { it.toString() }
            .joinToString(" ")
    }
}
