package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * تنبيه الأخطاء الإملائية أثناء الكتابة (ع٥٦-أمر 12).
 * يكشف المسافة المزدوجة وينبه عند كتابة كلمة بها خطأ إملائي بمعدل منضبط.
 */
object TypoChecker {

    private const val MIN_ALERT_INTERVAL_MS = 1000L
    private var lastAlertTime = 0L

    private val URL_OR_EMAIL_REGEX =
        Regex("""(https?://|www\.|\S+@\S+\.\S+)""", RegexOption.IGNORE_CASE)
    private val DIGITS_ONLY_REGEX = Regex("""^[\d\s\p{Punct}]+$""")

    fun isEligibleWord(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.length <= 2) return false
        if (trimmed.matches(DIGITS_ONLY_REGEX)) return false
        if (URL_OR_EMAIL_REGEX.containsMatchIn(trimmed)) return false
        return true
    }

    fun checkDoubleSpace(
        context: Context,
        text: CharSequence?
    ): Boolean {
        val s = text?.toString().orEmpty()
        if (s.endsWith("  ")) {
            val now = SystemClock.uptimeMillis()
            if (lastAlertTime == 0L ||
                now - lastAlertTime >= MIN_ALERT_INTERVAL_MS
            ) {
                lastAlertTime = now
                val msg = context.getString(R.string.two_spaces)
                A11ySpeechCoordinator.speakAnnouncement(context, msg)
                return true
            }
        }
        return false
    }

    fun notifyTypo(context: Context, word: String) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_typo_whisper")) return
        if (!isEligibleWord(word)) return

        val now = SystemClock.uptimeMillis()
        if (lastAlertTime != 0L &&
            now - lastAlertTime < MIN_ALERT_INTERVAL_MS
        ) return
        lastAlertTime = now

        val useVoice = repo.isExtraEnabled("extra_typo_voice_alert")
        if (useVoice) {
            val msg = context.getString(R.string.spelling_error)
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
        } else {
            ScrollCuePlayer.playTone(0, 0.4f)
        }
    }

    fun resetForTesting() {
        lastAlertTime = 0L
    }
}
