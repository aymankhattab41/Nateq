package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * فاحص الرسائل الصوتية (ع٥٦-أمر 21).
 * يستخرج مدة الرسالة الصوتية وحالة الاستماع في واتساب وتيليجرام
 * وينطقها بتنسيق واضح.
 */
object VoiceNoteInspector {

    private val VOICE_NOTE_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    private val DURATION_REGEX = Regex("""(?:\b\d{1,2}:\d{2}\b)""")

    data class VoiceNoteInfo(
        val duration: String,
        val isPlayed: Boolean
    )

    fun isVoiceNoteNode(
        packageName: String?,
        node: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName !in VOICE_NOTE_PACKAGES) return false
        if (node == null) return false

        val text = node.text?.toString().orEmpty()
        val cd = node.contentDescription?.toString().orEmpty()
        val full = "$text $cd".lowercase()

        return full.contains("رسالة صوتية") ||
            full.contains("voice message") ||
            full.contains("voice note") ||
            DURATION_REGEX.containsMatchIn(full)
    }

    fun parseVoiceNote(node: AccessibilityNodeInfo?): VoiceNoteInfo? {
        if (node == null) return null
        val text = node.text?.toString().orEmpty()
        val cd = node.contentDescription?.toString().orEmpty()
        val combined = "$text $cd"

        val durationMatch = DURATION_REGEX.find(combined)?.value ?: "0:00"

        val lower = combined.lowercase()
        val isPlayed = lower.contains("تم الاستماع") ||
            lower.contains("played") ||
            lower.contains("listened")

        return VoiceNoteInfo(durationMatch, isPlayed)
    }

    fun inspectAndAnnounce(
        context: Context,
        packageName: String?,
        node: AccessibilityNodeInfo?
    ): Boolean {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_voice_note_inspector")) return false
        if (!isVoiceNoteNode(packageName, node)) return false

        val info = parseVoiceNote(node) ?: return false
        val stateStr = if (info.isPlayed) {
            context.getString(R.string.voice_note_played)
        } else {
            context.getString(R.string.voice_note_unplayed)
        }

        val speech = context.getString(
            R.string.voice_note_desc,
            info.duration,
            stateStr
        )
        A11ySpeechCoordinator.speakAnnouncement(context, speech)
        return true
    }
}
