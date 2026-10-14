package com.aymankhattab.nateq.core.audio.accessibility

/**
 * سياسة إدراج النصّ في سجلّ «ما قيل» (ع٤١-ب4.1) — منطقٌ نقيّ.
 * تُستثنى: المكالمات، والرموز التي تشبه OTP (أرقامٌ بحتة قصيرة).
 */
object SpeechHistoryPolicy {

    /** أطول رمز تحقّقٍ نعتبره OTP (4–8 خانات رقمية بحتة). */
    private const val OTP_MIN = 4
    private const val OTP_MAX = 8

    fun shouldRecord(text: String?, isCall: Boolean): Boolean {
        if (text.isNullOrBlank()) return false
        if (isCall) return false
        return !looksLikeOtp(text.trim())
    }

    /** رمزٌ رقميّ بحت بطول 4–8 خانات ⇒ يُرجَّح أنه رمز تحقّق. */
    fun looksLikeOtp(text: String): Boolean {
        if (text.length !in OTP_MIN..OTP_MAX) return false
        return text.all { it.isDigit() }
    }
}
