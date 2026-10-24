package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * قارئ الدردشة الحية (ع٢٧-24).
 * يقرأ الرسائل الجديدة الواردة داخل نافذة الدردشة المفتوحة دون تكرار.
 */
class ChatLiveReader private constructor() {

    private val seenMessageHashes =
        ConcurrentHashMap.newKeySet<Int>()
    private val historyOrder = CopyOnWriteArrayList<Int>()

    private var lastSender: String? = null

    /**
     * معالجة نص رسالة جديدة وقراءتها إذا لم تُقرأ مسبقاً
     */
    fun processIncomingMessage(
        context: Context,
        sender: String?,
        messageBody: String?,
        packageName: String?
    ): Boolean {
        if (messageBody.isNullOrBlank()) return false

        val hash = (sender.orEmpty() + ":" + messageBody.trim()).hashCode()
        if (seenMessageHashes.contains(hash)) {
            return false
        }

        // إضافة للذاكرة مع الحفاظ على سعة 100 رسالة
        seenMessageHashes.add(hash)
        historyOrder.add(hash)
        if (historyOrder.size > MAX_SEEN_MESSAGES) {
            val oldest = historyOrder.removeAt(0)
            seenMessageHashes.remove(oldest)
        }

        // صياغة النص المقروء
        val textToSpeak = if (!sender.isNullOrBlank() && sender != lastSender) {
            lastSender = sender
            "$sender: $messageBody"
        } else {
            messageBody
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
            // ع٤١-ب10/ب11: صوت جهة الاتصال (باسم المرسِل) وملف التطبيق
            // الأمامي يتقدّمان على إعدادات الفئة.
            val voice = A11yVoiceResolver.resolve(
                context = context,
                settings = repo,
                packageName = packageName,
                senderName = sender
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

    fun clearHistory() {
        seenMessageHashes.clear()
        historyOrder.clear()
        lastSender = null
    }

    companion object {
        private const val MAX_SEEN_MESSAGES = 100

        @Volatile
        private var instance: ChatLiveReader? = null

        fun getInstance(): ChatLiveReader {
            return instance ?: synchronized(this) {
                instance ?: ChatLiveReader().also { instance = it }
            }
        }
    }
}
