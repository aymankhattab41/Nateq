package com.aymankhattab.nateq.core.audio.accessibility

import com.aymankhattab.nateq.util.LocaleUtils

/**
 * سياسة إدراج النصّ في سجلّ «ما قيل» (ع٤١-ب4.1) — منطقٌ نقيّ.
 * تُستثنى: المكالمات، ورموز التحقق (OTP) بكافة أشكالها
 * (أرقام متصلة أو مفصولة أو تمثيل كلمي)، وأي نصوص حساسة صريحة.
 */
object SpeechHistoryPolicy {

    /** نطاق طول رمز التحقق (4–8 خانات). */
    private const val OTP_MIN = 4
    private const val OTP_MAX = 8

    private val ARABIC_DIGIT_WORDS = setOf(
        "صفر",
        "واحد", "واحدة",
        "اثنان", "اثنين", "إثنان", "إثنين",
        "ثلاثة", "ثلاث",
        "أربعة", "اربعة", "أربع", "اربع",
        "خمسة", "خمس",
        "ستة", "ست",
        "سبعة", "سبع",
        "ثمانية", "ثماني", "ثمان",
        "تسعة", "تسع"
    )

    private val ENGLISH_DIGIT_WORDS = setOf(
        "zero", "one", "two", "three", "four",
        "five", "six", "seven", "eight", "nine"
    )

    private val TASHKEEL_REGEX = Regex("""[\u064B-\u065F\u0670]""")

    fun shouldRecord(
        text: String?,
        isCall: Boolean,
        isSensitive: Boolean = false
    ): Boolean {
        if (text.isNullOrBlank()) return false
        if (isCall) return false
        if (isSensitive) return false
        return !looksLikeOtp(text.trim())
    }

    /**
     * هل النص يشبه رمز تحقق (OTP)؟
     * يفحص:
     * 1. كود تحقق صريح في سياق رسالة عبر [LocaleUtils.containsOtp].
     * 2. أرقام متصلة أو مفصولة بمسافات أو شرطات (4 إلى 8 خانات).
     * 3. أرقام هندية/فارسية متصلة أو مفصولة.
     * 4. تمثيل كلمي للأرقام خانة خانة (عربي أو إنجليزي) بطول 4 إلى 8 خانات.
     */
    fun looksLikeOtp(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // 1. فحص كود تحقق صريح في سياق رسالة
        if (LocaleUtils.containsOtp(trimmed)) {
            return true
        }

        // 2. فحص الأرقام القياسية والهندية/الفارسية (متصلة أو مفصولة)
        val normalized = LocaleUtils.normalizeIndicDigits(trimmed)
        val digits = normalized.filter { it.isDigit() }
        if (digits.length in OTP_MIN..OTP_MAX) {
            val allAllowed = normalized.all {
                it.isDigit() || it.isWhitespace() || it == '-'
            }
            if (allAllowed) {
                return true
            }
        }

        // 3. فحص التمثيل الكلمي (عربي أو إنجليزي)
        val tokens = trimmed.split(Regex("""[\s,،\-]+"""))
            .filter { it.isNotBlank() }
        if (tokens.size in OTP_MIN..OTP_MAX) {
            val allDigitWords = tokens.all { token ->
                val clean = token.replace(TASHKEEL_REGEX, "").lowercase()
                clean in ARABIC_DIGIT_WORDS || clean in ENGLISH_DIGIT_WORDS
            }
            if (allDigitWords) {
                return true
            }
        }

        return false
    }
}
