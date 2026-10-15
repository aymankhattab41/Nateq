package com.aymankhattab.nateq.core.audio.accessibility

/**
 * حزم محددات العقد الخاصة بكل تطبيق (ع٢٧-22، ع٢٧-23).
 * تحدد معرفات العناصر وقواعد استخراج الرسائل في تطبيقات المراسلة.
 */
object SelectorPacks {

    // الكلمات الدلالية لزر «قراءة المزيد» بعدة لغات — قائمة صارمة (ع٤٣-1)
    val READ_MORE_KEYWORDS = listOf(
        // Arabic
        "قراءة المزيد",
        "عرض المزيد",
        "إظهار المزيد",
        "المزيد",
        "تابع القراءة",
        "متابعة القراءة",
        "اقرأ المزيد",
        // English
        "read more",
        "show more",
        "see more",
        "more",
        "view more",
        // French
        "lire la suite",
        "voir plus",
        "afficher plus",
        // German
        "mehr anzeigen",
        "weiterlesen",
        "mehr",
        // Spanish
        "leer más",
        "ver más",
        "mostrar más"
    )

    // كلمات القائمة السوداء لمنع الضغط الخطأ على عناصر مثل "More options"
    val DISALLOWED_KEYWORDS = listOf(
        "options",
        "option",
        "خيارات",
        "خيار",
        "settings",
        "إعدادات",
        "menu",
        "قائمة",
        "share",
        "مشاركة",
        "search",
        "بحث",
        "details",
        "تفاصيل",
        "info",
        "معلومات",
        "help",
        "مساعدة"
    )

    // لواحق أدوار TalkBack الشائعة في نهاية نصوص العناصر المنطوقة (ع٤٣-1)
    private val TALKBACK_ROLE_SUFFIXES = listOf(
        "، زر إمكانية الوصول",
        " زر إمكانية الوصول",
        "، زر",
        " زر",
        "، مفتاح تحويل",
        "مزدوج النقر للتفعيل",
        "انقر نقراً مزدوجاً للتفعيل",
        "اضغط مرتين للتفعيل",
        ", accessibility button",
        " accessibility button",
        ", button",
        " button",
        ", switch",
        "double-tap to activate",
        "double tap to activate",
        "tap to activate"
    )

    // بيانات وحالات الرسائل الملحقة بنهاية النصوص المنطوقة (ع٤٣-1)
    private val MESSAGE_METADATA_SUFFIXES = listOf(
        "تم الإرسال",
        "تم التسليم",
        "تمت القراءة",
        "تم استلامها",
        "تم الاستماع إليها",
        "قُرئت",
        "جارٍ الإرسال",
        "معدلة",
        "معدّلة",
        "sent",
        "delivered",
        "read",
        "edited",
        "pending",
        "ص",
        "م",
        "صباحا",
        "صباحاً",
        "مساء",
        "مساءً",
        "am",
        "pm"
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

    // معرّفات عقد الرسائل المعروفة (الجزء الأخير من viewIdResourceName)
    val MESSAGE_VIEW_IDS = listOf(
        "message_text",
        "chat_message_text",
        "text_message",
        "message_body",
        "bubble"
    )

    // علامات اتجاه النصوص والمسافات الصفرية في يونيكود (ع٤٣-1)
    private val BIDI_FORMAT_CHARS = charArrayOf(
        '\u200E', '\u200F', '\u061C', '\u200B', '\u200C', '\u200D', '\uFEFF',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069'
    )

    private const val STRIP_CHARS = " \u200E\u200F\u061C\u200B\u200C\u200D" +
        "\uFEFF\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069" +
        ".…-><()[]{}:،,-_\"'«»"

    private const val BIDI_AND_SPACES = " \t\r\n\u200E\u200F\u061C\u200B" +
        "\u200C\u200D\uFEFF\u202A\u202B\u202C\u202D" +
        "\u202E\u2066\u2067\u2068\u2069"

    /** تنظيف النص من محارف التوجيه والمسافات الصفرية في يونيكود (ع٤٣-1). */
    fun cleanBidi(text: CharSequence?): String {
        if (text == null) return ""
        return text.filterNot { it in BIDI_FORMAT_CHARS }.toString()
    }

    /**
     * هل معرّف العقدة عقدة رسالة دردشة؟ (ع٤١-ب5)
     * يقارن الجزء الأخير بعد «/» من `viewIdResourceName` بالمعرّفات المعروفة.
     */
    fun isMessageViewId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val tail = id.substringAfterLast('/', "").lowercase()
        if (tail.isEmpty()) return false
        return MESSAGE_VIEW_IDS.any { tail == it }
    }

    /**
     * هل النص يمثل زر «قراءة المزيد»؟ (ع٤٣-1: قائمة صارمة)
     * يستبعد الكلمات السوداء مثل "More options" ويشترط المطابقة الصارمة
     * بعد تنظيف النص من علامات التوجيه والمسافات الصفرية.
     */
    fun isReadMoreTrigger(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = cleanBidi(text).trim().lowercase()

        // استبعاد أي نص يحوي كلمات سوداء (خيارات/إعدادات/قائمة...)
        if (DISALLOWED_KEYWORDS.any { clean.contains(it) }) {
            return false
        }

        // مطابقة دقيقة أولاً
        if (READ_MORE_KEYWORDS.any { clean == it }) {
            return true
        }

        // إزالة علامات الترقيم والأقواس ومحارف التوجيه المحيطة
        val stripped = clean.trim { it in STRIP_CHARS }
        return READ_MORE_KEYWORDS.any { stripped == it }
    }

    /**
     * هل النص ينتهي بنمط اقتطاع يدل على وجود «قراءة المزيد»؟ — ع٢٩-12، ع٤٣-1
     * يجرّد لواحق TalkBack والبيانات الوصفية للرسائل (الوقت والحالة) قبل الفحص.
     */
    fun looksTruncated(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        var clean = cleanBidi(text).trim()

        // ع٤٣-1: تجريد لواحق أدوار TalkBack والبيانات الوصفية المتتالية
        var stripped = true
        while (stripped && clean.isNotEmpty()) {
            stripped = false
            // 1. لواحق أدوار TalkBack (، زر / , button / إلخ)
            for (suffix in TALKBACK_ROLE_SUFFIXES) {
                val cleanSuffix = cleanBidi(suffix).trim()
                if (clean.endsWith(cleanSuffix, ignoreCase = true)) {
                    clean = clean.substring(
                        0,
                        clean.length - cleanSuffix.length
                    ).trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                    stripped = true
                    break
                }
            }
            if (stripped) continue

            // 2. الكلمات الوصفية وحالات الإرسال والتوقيت
            for (meta in MESSAGE_METADATA_SUFFIXES) {
                val cleanMeta = cleanBidi(meta).trim()
                if (clean.endsWith(cleanMeta, ignoreCase = true)) {
                    clean = clean.substring(
                        0,
                        clean.length - cleanMeta.length
                    ).trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                    stripped = true
                    break
                }
            }
            if (stripped) continue

            // 3. أرقام الوقت والتاريخ في النهاية (مثل 1:46 أو ١:٤٦)
            val timeMatch = Regex("""[0-9٠-٩]+[:/][0-9٠-٩]+$""").find(clean)
            if (timeMatch != null) {
                clean = clean.substring(0, timeMatch.range.first)
                    .trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                stripped = true
                continue
            }

            // 4. أرقام منفردة في النهاية
            val numMatch = Regex("""\b[0-9٠-٩]+$""").find(clean)
            if (numMatch != null && clean.length - numMatch.range.first <= 5) {
                clean = clean.substring(0, numMatch.range.first)
                    .trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                stripped = true
                continue
            }
        }

        val tailClean = clean.trim { it in BIDI_AND_SPACES }

        // فحص أنماط الاقتطاع المباشرة
        if (TRUNCATION_PATTERNS.any {
                tailClean.endsWith(it, ignoreCase = true)
            }
        ) {
            return true
        }

        // فحص ما إذا كان ذيل النص (آخر كلمتين) يمثل مفتاح قراءة المزيد
        val tailWords = tailClean.takeLast(60)
            .split(' ', '،', ',', '\n')
            .map { it.trim { c -> c in STRIP_CHARS } }
            .filter { it.isNotBlank() }
        val lastWord = tailWords.lastOrNull()?.lowercase()
        val lastTwoWords = if (tailWords.size >= 2) {
            val w1 = tailWords[tailWords.size - 2]
            val w2 = tailWords[tailWords.size - 1]
            "$w1 $w2".lowercase()
        } else null

        if (lastWord != null && READ_MORE_KEYWORDS.any { it == lastWord }) {
            return true
        }
        if (lastTwoWords != null &&
            READ_MORE_KEYWORDS.any { it == lastTwoWords }
        ) {
            return true
        }

        return false
    }

    /**
     * ع٤٣-1: فحص تطابق مرن بين نص قارئ الشاشة ونص عقدة الرسالة.
     * يتجاوز اسم المرسل والوقت وأدوار TalkBack دون اشتراط أول 40 حرفاً حرفياً.
     */
    fun isTextMatchingMessage(
        spokenText: String?,
        nodeText: String?
    ): Boolean {
        if (spokenText.isNullOrBlank() || nodeText.isNullOrBlank()) {
            return false
        }
        val sClean = cleanBidi(spokenText).trim().lowercase()
        val nClean = cleanBidi(nodeText).trim().lowercase()

        // احتواء متبادل مباشر
        if (sClean.contains(nClean) || nClean.contains(sClean)) {
            return true
        }

        // تقسيم إلى كلمات وتصفية الكلمات القصيرة
        val sWords = sClean.split("\\s+".toRegex())
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 3 }
        val nWords = nClean.split("\\s+".toRegex())
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 3 }

        if (sWords.isEmpty() || nWords.isEmpty()) {
            val len = minOf(15, sClean.length, nClean.length)
            return len > 0 &&
                sClean.substring(0, len) == nClean.substring(0, len)
        }

        // فحص عدد الكلمات المشتركة ونسبتها
        val common = sWords.intersect(nWords.toSet()).size
        val minCount = minOf(sWords.size, nWords.size)
        if (common >= 2 && (common.toDouble() / minCount) >= 0.35) {
            return true
        }

        // فحص وجود 3 كلمات متتالية مشتركة (trigram)
        val nJoined = nWords.joinToString(" ")
        for (i in 0..sWords.size - 3) {
            val trigram = "${sWords[i]} ${sWords[i + 1]} ${sWords[i + 2]}"
            if (nJoined.contains(trigram)) return true
        }

        return false
    }
}
