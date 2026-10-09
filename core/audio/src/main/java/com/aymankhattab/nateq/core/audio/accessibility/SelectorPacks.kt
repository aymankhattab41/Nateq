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

    // أنماط النص المقتطع (نهاية النص تدل على وجود «قراءة المزيد») — ع٢٩-12
    val TRUNCATION_PATTERNS = listOf(
        "…",           // U+2026 horizontal ellipsis
        "...",         // ثلاث نقاط ASCII
        "… المزيد",     // Arabic
        "... المزيد",   // Arabic with ASCII dots
        "قراءة المزيد",  // Arabic explicit
        "See more",    // English
        "Show more",   // English
        "Read more",   // English
        "Lire la suite", // French
        "Mehr anzeigen"  // German
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

    /**
     * هل النص ينتهي بنمط اقتطاع يدل على وجود «قراءة المزيد»؟ — ع٢٩-12
     */
    fun looksTruncated(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = text.trim()
        return TRUNCATION_PATTERNS.any { clean.endsWith(it) }
    }
}
