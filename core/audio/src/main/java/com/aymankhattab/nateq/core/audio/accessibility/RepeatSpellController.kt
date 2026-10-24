package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تحكم إعادة النطق وهجاء الكلمات وسجلّ «ما قيل» (ع٢٧-32، ع٤١-ب4).
 * - سجلٌّ حلقيّ بسعة [MAX_ENTRIES] (نص + وقت + مصدر) في الذاكرة فقط.
 * - هجاءٌ بأسماء الحروف/الأرقام/الرموز عبر [SpellTable]، مع أولوية القاموس.
 */
object RepeatSpellController {

    const val MAX_ENTRIES = 20

    /** من أين جاء النص المسجَّل. */
    enum class Source { READER, ANNOUNCEMENT }

    data class Entry(
        val text: String,
        val timestampMs: Long,
        val source: Source
    )

    private val lock = Any()

    /** الأقدم أولاً؛ الأحدث آخراً. */
    private val entries = ArrayDeque<Entry>()

    /** يمنع تسجيل نطق أدوات «كرّر/هجِّ/السجل» نفسها فتلوّث التاريخ. */
    private val recordingSuppressed = AtomicBoolean(false)

    fun updateLastSpokenText(
        text: String?,
        source: Source = Source.ANNOUNCEMENT,
        isCall: Boolean = false
    ) {
        if (recordingSuppressed.get()) return
        if (!SpeechHistoryPolicy.shouldRecord(text, isCall)) return
        val trimmed = text!!.trim()
        synchronized(lock) {
            entries.addLast(
                Entry(trimmed, System.currentTimeMillis(), source)
            )
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun getLastSpokenText(): String? =
        synchronized(lock) { entries.lastOrNull()?.text }

    /** السجلّ من الأحدث إلى الأقدم. */
    fun history(): List<Entry> =
        synchronized(lock) { entries.toList().asReversed() }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** يمنع تسجيل ما يُنطق داخل [block] (نطقُ الأدوات لا يُسجَّل). */
    private inline fun withoutRecording(block: () -> Unit) {
        recordingSuppressed.set(true)
        try {
            block()
        } finally {
            recordingSuppressed.set(false)
        }
    }

    /**
     * هجاء النص خانةً خانة بأسماء الحروف (الجولة 53 - الأمر 4).
     */
    fun spellText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.map { char -> spellChar(char) }
            .joinToString(" ")
    }

    private fun spellChar(char: Char): String {
        if (char.isWhitespace()) return "مسافة"
        return SpellTable.nameOf(char)
    }

    /** نطق إعادة آخر عبارة منطوقة. */
    fun repeatLast(context: Context) {
        val last = getLastSpokenText() ?: return
        speak(context, last, SpeechEntryPoints.Source.REPEAT_LAST)
    }

    /** هجاء آخر عبارة منطوقة بأسماء الحروف وليس بالحرف ذاته. */
    fun spellLast(context: Context) {
        val last = getLastSpokenText() ?: return
        speak(context, spellText(last), SpeechEntryPoints.Source.SPELL_LAST)
    }

    /** «اقرأ ما قبله»: يرجع في السجل إلى المدخل السابق. */
    fun repeatPrevious(context: Context) {
        val previous = history().getOrNull(1)?.text
        if (previous == null) {
            speak(
                context,
                "لا يوجد ما قبله",
                SpeechEntryPoints.Source.REPEAT_LAST
            )
        } else {
            speak(context, previous, SpeechEntryPoints.Source.REPEAT_LAST)
        }
    }

    /** «اقرأ السجل»: كل ما قيل من الأحدث إلى الأقدم. */
    fun announceHistory(context: Context) {
        val items = history().map { it.text }
        val text = if (items.isEmpty()) "السجل فارغ"
        else items.joinToString("، ")
        speak(context, text, SpeechEntryPoints.Source.REPEAT_LAST)
    }

    private fun speak(
        context: Context,
        text: String,
        source: SpeechEntryPoints.Source
    ) {
        withoutRecording {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = text,
                isInterruptAllowed = true,
                source = source
            )
        }
    }
}
