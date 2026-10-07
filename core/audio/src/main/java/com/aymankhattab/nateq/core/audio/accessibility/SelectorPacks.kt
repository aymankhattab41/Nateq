package com.aymankhattab.nateq.core.audio.accessibility

/**
 * حزم محددات العقد الخاصة بكل تطبيق (ع٢٧-22، ع٢٧-23).
 * تحدد معرفات العناصر وقواعد استخراج الرسائل في تطبيقات المراسلة.
 */
object SelectorPacks {

    // الكلمات الدلالية لزر «قراءة المزيد» بعدة لغات
    val READ_MORE_KEYWORDS = listOf(
        "قراءة المزيد",
        "عرض المزيد",
        "المزيد",
        "read more",
        "show more",
        "more",
        "lire la suite",
        "mehr anzeigen"
    )

    // معرفات الحقول المشهورة في واتساب
    val WHATSAPP_MESSAGE_TEXT_IDS = listOf(
        "message_text",
        "caption",
        "quoted_text"
    )

    // معرفات الحقول المشهورة في تلغرام
    val TELEGRAM_MESSAGE_TEXT_IDS = listOf(
        "message_text",
        "chat_message_text"
    )

    /**
     * هل النص يمثل زر «قراءة المزيد»؟
     */
    fun isReadMoreTrigger(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = text.toString().trim().lowercase()
        return READ_MORE_KEYWORDS.any { clean == it || clean.contains(it) }
    }
}
