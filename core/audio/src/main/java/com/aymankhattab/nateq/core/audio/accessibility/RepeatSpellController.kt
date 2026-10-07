package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * تحكم إعادة النطق وهجاء الكلمات (ع٢٧-32).
 * يتيح إعادة آخر عبارة منطوقة أو هجائها حرفاً حرفاً.
 */
object RepeatSpellController {

    private val lastSpokenTextRef = AtomicReference<String?>()

    fun updateLastSpokenText(text: String?) {
        if (!text.isNullOrBlank()) {
            lastSpokenTextRef.set(text.trim())
        }
    }

    fun getLastSpokenText(): String? = lastSpokenTextRef.get()

    /**
     * تحويل النص إلى هجاء حرفاً حرفاً مفصولاً بمسافات
     */
    fun spellText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.map { char ->
            if (char.isWhitespace()) "مسافة" else char.toString()
        }.joinToString(" ")
    }

    /**
     * نطق إعادة آخر عبارة منطوقة
     */
    fun repeatLast(context: Context) {
        val last = getLastSpokenText() ?: return
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = last,
            isInterruptAllowed = true
        )
    }

    /**
     * هجاء آخر عبارة منطوقة حرفاً حرفاً
     */
    fun spellLast(context: Context) {
        val last = getLastSpokenText() ?: return
        val spelled = spellText(last)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = spelled,
            isInterruptAllowed = true
        )
    }
}
