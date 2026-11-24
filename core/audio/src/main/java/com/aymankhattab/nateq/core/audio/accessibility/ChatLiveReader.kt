package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
import java.util.concurrent.CopyOnWriteArrayList

/**
 * قارئ الدردشة الحية (ع٢٧-24، ع٤١-ب5).
 * يقرأ الرسائل الجديدة الواردة داخل نافذة الدردشة المفتوحة دون تكرار.
 * يعتمد على بصمات مركبة (محادثة + مرسل + نص + نافذة صلاحية) مع
 * تنظيف دوري وحد أقصى وسجل مرتب، دون نطق أسماء الحزم.
 */
class ChatLiveReader private constructor() {

    internal data class MessageFingerprint(
        val conversationId: String,
        val sender: String?,
        val normalizedBody: String,
        val timestamp: Long
    )

    private val history = CopyOnWriteArrayList<MessageFingerprint>()
    private var lastSender: String? = null

    /**
     * معالجة نص رسالة جديدة وقراءتها إذا لم تُقرأ مسبقاً
     */
    fun processIncomingMessage(
        context: Context,
        sender: String?,
        messageBody: String?,
        packageName: String?,
        conversationId: String? = null
    ): Boolean {
        if (messageBody.isNullOrBlank()) return false

        val cleanSender = sanitizeSender(sender, packageName)
        val cleanBody = cleanBidiAndNoise(messageBody)
        if (cleanBody.isBlank()) return false

        val convId = conversationId?.trim()?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: "unknown_chat"

        val now = SystemClock.uptimeMillis()
        pruneExpired(now)

        val isDuplicate = history.any { entry ->
            entry.conversationId == convId &&
                entry.sender == cleanSender &&
                entry.normalizedBody == cleanBody &&
                (now - entry.timestamp <= DEDUP_WINDOW_MS)
        }
        if (isDuplicate) return false

        history.add(
            MessageFingerprint(convId, cleanSender, cleanBody, now)
        )
        if (history.size > MAX_SEEN_MESSAGES) {
            history.removeAt(0)
        }

        val textToSpeak = if (!cleanSender.isNullOrBlank() &&
            cleanSender != lastSender
        ) {
            lastSender = cleanSender
            "$cleanSender: $cleanBody"
        } else {
            cleanBody
        }

        val repo = com.aymankhattab.nateq.core.data.SettingsRepository
            .create(context)
        val isFeatureActive = FeatureFlags.isActive(
            FeatureFlags.FEATURE_CHAT_LIVE_READING,
            packageName = packageName,
            context = context,
            settings = repo
        )

        if (isFeatureActive) {
            val voice = A11yVoiceResolver.resolve(
                context = context,
                settings = repo,
                packageName = packageName,
                senderName = cleanSender
            )
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = textToSpeak,
                isInterruptAllowed = false,
                voiceId = voice?.voiceId,
                engineOverride = voice?.engine,
                speechRate = voice?.speechRate,
                pitch = voice?.pitch,
                volume = voice?.volume,
                source = SpeechEntryPoints.Source.CHAT_LIVE
            )
            return true
        }

        return false
    }

    private fun sanitizeSender(
        sender: String?,
        packageName: String?
    ): String? {
        val s = sender?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s == packageName) return null
        if (s.startsWith("com.") || s.startsWith("android.")) return null
        return s
    }

    private fun cleanBidiAndNoise(text: String): String {
        return text.replace(
            Regex("[\u200B-\u200F\u202A-\u202E\u061C]"),
            ""
        ).trim()
    }

    private fun pruneExpired(now: Long) {
        history.removeAll { now - it.timestamp > DEDUP_WINDOW_MS }
    }

    fun clearHistory() {
        history.clear()
        lastSender = null
    }

    companion object {
        private const val MAX_SEEN_MESSAGES = 100
        const val DEDUP_WINDOW_MS = 60_000L

        @Volatile
        private var instance: ChatLiveReader? = null

        fun getInstance(): ChatLiveReader {
            return instance ?: synchronized(this) {
                instance ?: ChatLiveReader().also { instance = it }
            }
        }
    }
}
