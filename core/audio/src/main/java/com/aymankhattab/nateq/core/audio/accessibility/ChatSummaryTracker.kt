package com.aymankhattab.nateq.core.audio.accessibility

/**
 * ملخّص محادثة محسوب لزيارةٍ واحدة (ع٤١-ب5).
 *
 * @param messageCount عدد الرسائل الجديدة.
 * @param senders مرسلو الرسائل الجديدة (قد يتكرّر الاسم فيُستبعد لاحقاً).
 */
data class ChatSummary(
    val messageCount: Int,
    val senders: List<String>
)

/**
 * متتبّع ملخّصات المحادثات (ع٤١-ب5).
 * يحتفظ في الذاكرة فقط ببصمات الرسائل المقروءة لكل محادثة، ويحسب
 * الجديد عند كل زيارة، مع تثبيط الإعلان لكل محادثة (افتراضياً 30ث)
 * حتى لا يتكرّر الملخّص عند الرجوع السريع.
 *
 * لا يُخزَّن شيء على القرص، ويُمسح بالكامل عند إطفاء الشاشة.
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
    fun onVisit(
        chatKey: String,
        messages: List<ChatMessage>,
        nowMs: Long
    ): ChatSummary? {
        if (chatKey.isBlank() || messages.isEmpty()) return null
        val seen = seenByChat.getOrPut(chatKey) { HashSet() }
        val fresh = messages.filter { seen.add(it.fingerprint()) }
        trim(seen)
        if (fresh.isEmpty()) return null

        val last = lastAnnouncedAt[chatKey]
        if (last != null && nowMs - last < throttleMs) {
            return null
        }
        lastAnnouncedAt[chatKey] = nowMs
        return ChatSummary(
            messageCount = fresh.size,
            senders = fresh.mapNotNull {
                it.sender?.takeIf { s -> s.isNotBlank() }
            }
        )
    }

    /** مسح كل الحالة (عند إطفاء الشاشة أو توقّف الخدمة). */
    fun clear() {
        seenByChat.clear()
        lastAnnouncedAt.clear()
    }

    private fun trim(seen: MutableSet<Int>) {
        if (seen.size <= MAX_SEEN_PER_CHAT) return
        val iterator = seen.iterator()
        var removed = 0
        val target = seen.size - MAX_SEEN_PER_CHAT
        while (iterator.hasNext() && removed < target) {
            iterator.next()
            iterator.remove()
            removed++
        }
    }

    companion object {
        /** تثبيط الإعلان عن نفس المحادثة (ع٤١-ب5.2). */
        const val DEFAULT_THROTTLE_MS = 30_000L

        /** سقف بصمات الرسائل المحفوظة لكل محادثة. */
        const val MAX_SEEN_PER_CHAT = 500
    }
}
