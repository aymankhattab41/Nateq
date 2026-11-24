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
            """(?<![0-9]|(?:[0-9][- ]))([0-9](?:[- ]?[0-9]){3,7})""" +
            """(?!(?:[- ]?[0-9]))"""
    )

    private val STANDALONE_OTP_PATTERN = Regex(
        """(?:G-)?(?<![0-9]|(?:[0-9][- ]))""" +
            """([0-9](?:[- ]?[0-9]){3,7})""" +
            """(?!(?:[- ]?[0-9]))"""
    )

    private val OTP_PATTERNS = listOf(
        OTP_KEYWORD_PATTERN,
        STANDALONE_OTP_PATTERN
    )

    private val DATE_STRUCTURE = Regex(
        """^(?:(?:19|20)\d{2}[- /]\d{1,2}[- /]\d{1,2}|""" +
            """\d{1,2}[- /]\d{1,2}[- /](?:19|20)\d{2})$"""
    )

    private val NON_OTP_KEYWORD = Regex(
        """(?i)(?:تاريخ|بتاريخ|date|هاتف|الهاتف|تليفون|جوال|موبايل|""" +
            """اتصل|phone|tel|call|mobile|المبلغ|مبلغ|سعر|السعر|""" +
            """amount|price|total)"""
    )

    private val OTP_KEYWORD_CHECK = Regex(
        """(?i)(?:code|otp|رمز|تحقق|كود|pin|verification|تأیید|تایید|کد)"""
    )

    /**
     * استخراج رمز التحقق من النص إن وجد مع تطبيع الأرقام
     * الهندية والفارسية إلى أرقام قياسية، وتجميع الأجزاء المفصولة
     * بشرطة أو مسافة مفردة دون المساس بترتيبها أو بالصفر البادئ،
     * مع استبعاد أرقام التواريخ والهواتف والمبالغ غير المتعلقة بالتحقق.
     */
    fun extractOtp(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normalized = LocaleUtils.normalizeIndicDigits(text)
        val hasOtpKeyword = OTP_KEYWORD_CHECK.containsMatchIn(normalized)

        for (pattern in OTP_PATTERNS) {
            val matches = pattern.findAll(normalized)
            for (match in matches) {
                val group = if (match.groupValues.size > 1) {
                    match.groupValues[1]
                } else {
                    match.groupValues[0]
                }
                val digits = group.filter { it.isDigit() }
                if (digits.length !in 4..8) continue

                // استبعاد بنية التواريخ مثل 10-23-1987 أو 2024-11-25
                if (DATE_STRUCTURE.matches(group)) continue

                // استبعاد الأرقام في سياق الهاتف أو التاريخ أو المبلغ
                // عند غياب كلمة تحقق صريحة
                if (!hasOtpKeyword) {
                    val prefix = normalized.substring(
                        0, match.range.first
                    ).trimEnd()
                    val window = prefix.takeLast(30)
                    if (NON_OTP_KEYWORD.containsMatchIn(window)) {
                        continue
                    }
                }

                return digits
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
