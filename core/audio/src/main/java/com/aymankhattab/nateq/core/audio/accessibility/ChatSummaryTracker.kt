package com.aymankhattab.nateq.core.audio.accessibility

/**
 * ملخّص محادثة محسوب لزيارةٍ واحدة (ع٤١-ب5).
 *
 * @param messageCount عدد الرسائل الجديدة.
 * @param senders مرسلو الرسائل الجديدة (قد يتكرّر الاسم فيُستبعد لاحقاً).
 * @param fingerprints بصمات الرسائل المحسوبة لهذا الملخص (البند 16).
 */
data class ChatSummary(
    val messageCount: Int,
    val senders: List<String>,
    internal val fingerprints: List<Int> = emptyList()
)

/**
 * متتبّع ملخّصات المحادثات (ع٤١-ب5).
 * يحتفظ في الذاكرة فقط ببصمات الرسائل المقروءة لكل محادثة، ويحسب
 * الجديد عند كل زيارة، مع تثبيط الإعلان لكل محادثة (افتراضياً 30ث)
 * حتى لا يتكرّر الملخّص عند الرجوع السريع.
 *
 * - لا يثبت بصمات الرسائل إلا عند قبول الملخص وعدم تثبيطه (البند 16).
 * - يمنع الإعلان المزدوج من الزيارات المتزامنة.
 * - يدعم إلغاء التثبيت (rollback) عند إخفاق مسار الإعلان.
 * - لا يُخزَّن شيء على القرص، ويُمسح بالكامل عند إطفاء الشاشة.
 */
class ChatSummaryTracker(
    private val throttleMs: Long = DEFAULT_THROTTLE_MS
) {

    private val seenByChat = HashMap<String, MutableSet<Int>>()
    private val lastAnnouncedAt = HashMap<String, Long>()

    /**
     * يسجّل زيارةً لمحادثة ويعيد الملخّص إن وُجدت رسائل جديدة وغير
     * مثبَّطة، وإلا null.
     */
    @Synchronized
    fun onVisit(
        chatKey: String,
        messages: List<ChatMessage>,
        nowMs: Long
    ): ChatSummary? {
        if (chatKey.isBlank() || messages.isEmpty()) return null

        val last = lastAnnouncedAt[chatKey]
        if (last != null && nowMs - last < throttleMs) {
            // التثبيط نشط: لا نعدل البصمات المقروءة حتى لا تضيع الرسائل للأبد
            return null
        }

        val seen = seenByChat.getOrPut(chatKey) { HashSet() }
        val fresh = messages.filter { !seen.contains(it.fingerprint()) }
        if (fresh.isEmpty()) return null

        for (msg in fresh) {
            seen.add(msg.fingerprint())
        }
        trim(seen)
        lastAnnouncedAt[chatKey] = nowMs

        return ChatSummary(
            messageCount = fresh.size,
            senders = fresh.mapNotNull {
                it.sender?.takeIf { s -> s.isNotBlank() }
            },
            fingerprints = fresh.map { it.fingerprint() }
        )
    }

    /**
     * إلغاء تثبيت ملخص عند تعثر أو إلغاء الإعلان لإعادته في زيارة لاحقة.
     */
    @Synchronized
    fun rollback(chatKey: String, summary: ChatSummary) {
        val seen = seenByChat[chatKey] ?: return
        seen.removeAll(summary.fingerprints.toSet())
        lastAnnouncedAt.remove(chatKey)
    }

    /** مسح كل الحالة (عند إطفاء الشاشة أو توقّف الخدمة). */
    @Synchronized
    fun clear() {
        seenByChat.clear()
        lastAnnouncedAt.clear()
    }

    private fun trim(seen: MutableSet<Int>) {
        if (seen.size > MAX_SEEN_PER_CHAT) {
            val iterator = seen.iterator()
            var removeCount = seen.size - MAX_SEEN_PER_CHAT
            while (iterator.hasNext() && removeCount > 0) {
                iterator.next()
                iterator.remove()
                removeCount--
            }
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 30_000L
        private const val MAX_SEEN_PER_CHAT = 200
    }
}
