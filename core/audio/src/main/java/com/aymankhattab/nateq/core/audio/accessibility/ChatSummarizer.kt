package com.aymankhattab.nateq.core.audio.accessibility

/**
 * ملخص محادثات الدردشة (ع٢٧-25).
 * يلخّص عدد الرسائل الواردة وأسماء المرسلين في جملة واحدة واضحة ومقتضبة.
 */
object ChatSummarizer {

    fun summarize(senders: List<String>, messageCount: Int): String {
        if (messageCount <= 0) return ""
        val uniqueSenders = senders.filter { it.isNotBlank() }.distinct()

        return when {
            uniqueSenders.isEmpty() -> {
                "$messageCount رسائل جديدة"
            }
            uniqueSenders.size == 1 -> {
                "$messageCount رسائل جديدة من ${uniqueSenders.first()}"
            }
            uniqueSenders.size == 2 -> {
                val s1 = uniqueSenders[0]
                val s2 = uniqueSenders[1]
                "$messageCount رسائل من $s1 و $s2"
            }
            else -> {
                val s1 = uniqueSenders[0]
                val s2 = uniqueSenders[1]
                val others = uniqueSenders.size - 2
                "$messageCount رسائل من $s1، $s2 و $others آخرين"
            }
        }
    }
}
