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

    // كلمات دلالية إضافية لإجراءات التوسيع وقراءة المزيد (ع٤٣-3)
    val EXPAND_ACTION_KEYWORDS = listOf(
        "توسيع",
        "توسعة",
        "عرض بالكامل",
        "إظهار بالكامل",
        "عرض كامل",
        "إظهار كامل",
        "عرض النص كاملا",
        "عرض النص كامل",
        "عرض الرسالة كاملة",
        "expand",
        "show full",
        "read full",
        "view full"
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

    private val TIME_SUFFIX_REGEX =
        Regex("""[0-9٠-٩]+[:/][0-9٠-٩]+$""")
    private val NUM_SUFFIX_REGEX = Regex("""\b[0-9٠-٩]+$""")
    private val WHITESPACE_REGEX = Regex("\\s+")

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
     * هل النص يطابق قائمة الكلمات الصارمة لزر قراءة المزيد؟
     */
    fun isReadMoreText(text: CharSequence?): Boolean = isReadMoreTrigger(text)

    /**
     * هل اسم الإجراء (Action label) يمثل إجراء توسيع / قراءة المزيد؟ (ع٤٣-3)
     * يفحص المطابقة الحرفية والجزئية مع استبعاد الكلمات المحظورة.
     */
    fun isReadMoreActionLabel(label: CharSequence?): Boolean {
        if (label.isNullOrBlank()) return false
        val clean = cleanBidi(label).trim().lowercase()

        // استبعاد أي نص يحوي كلمات سوداء (خيارات/إعدادات/قائمة...)
        if (DISALLOWED_KEYWORDS.any { clean.contains(it) }) {
            return false
        }

        // 1. مطابقة دقيقة أولاً
        if (isReadMoreTrigger(clean)) {
            return true
        }

        // 2. فحص كلمات التوسيع الصريحة
        if (EXPAND_ACTION_KEYWORDS.any { clean.contains(it) }) {
            return true
        }

        // 3. فحص كلمات قراءة المزيد في سياق اسم الإجراء
        for (kw in READ_MORE_KEYWORDS) {
            if (kw == "المزيد" || kw == "more") {
                if (clean == kw ||
                    clean.startsWith("$kw ") ||
                    clean.endsWith(" $kw") ||
                    clean.contains(" $kw ")
                ) {
                    if (clean.length <= 30) return true
                }
            } else if (clean.contains(kw)) {
                return true
            }
        }

        return false
    }

    /**
     * هل النص/الوصف يُمثّل تحكّماً صريحاً للتوسعة/«قراءة المزيد»؟
     * تسمية تحكّم قصيرة وصريحة فقط (زر/إجراء/ClickableSpan)، لا محتوى
     * رسالة يضم العبارة ضمن نصه. يُحظر الاعتماد على الطول/الاقتطاع وحدهما.
     */
    fun isExplicitExpandText(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = cleanBidi(text).trim().lowercase()
        if (clean.isEmpty()) return false
        if (DISALLOWED_KEYWORDS.any { clean.contains(it) }) return false
        if (clean.length > 60) return false

        val stripped = clean.trim { it in STRIP_CHARS }

        // مطابقة تامة للكلمة المفتاحية = تحكّم صريح.
        if (matchesExactReadMore(clean, stripped)) return true

        // مطابقة لكون النص تسمية تحكّم (الكلمة المفتاحية هي جوهر النص).
        if (isControlLabelMatch(clean, stripped)) return true

        return false
    }

    private fun matchesExactReadMore(clean: String, stripped: String): Boolean {
        return READ_MORE_KEYWORDS.any { kw ->
            clean == kw || stripped == kw
        } || EXPAND_ACTION_KEYWORDS.any { kw ->
            val k = cleanBidi(kw).trim().lowercase()
            clean == k || stripped == k
        }
    }

    private fun isControlLabelMatch(
        clean: String,
        stripped: String
    ): Boolean {
        // ابحث عن كلمة مفتاحية تشكّل جوهر التسمية (الباقي زخرفة فقط).
        for (kw in READ_MORE_KEYWORDS) {
            if (kw == "المزيد" || kw == "more") {
                if (hasCoreKeyword(clean, stripped, kw, requireWordBoundary = true)) {
                    return true
                }
            } else if (hasCoreKeyword(clean, stripped, kw, requireWordBoundary = false)) {
                return true
            }
        }
        for (kw in EXPAND_ACTION_KEYWORDS) {
            val k = cleanBidi(kw).trim().lowercase()
            if (k.isEmpty()) continue
            if (hasCoreKeyword(clean, stripped, k, requireWordBoundary = false)) {
                return true
            }
        }
        return false
    }

    private fun hasCoreKeyword(
        clean: String,
        stripped: String,
        keyword: String,
        requireWordBoundary: Boolean
    ): Boolean {
        val candidates = linkedSetOf(clean, stripped)
        for (text in candidates) {
            if (!text.contains(keyword)) continue
            if (requireWordBoundary && !hasWordBoundary(text, keyword)) continue
            val remainder = text.replaceFirst(keyword, "")
                .trim { it in STRIP_CHARS || it.isWhitespace() }
            // الباقي بعد إزالة الكلمة المفتاحية يجب أن يكون زخرفة قصيرة فقط
            // (أسهم/نقاط/رموز) حتى لا نطابق محتوى رسالة.
            if (remainder.length <= 6) return true
        }
        return false
    }

    private fun hasWordBoundary(text: String, keyword: String): Boolean {
        val idx = text.indexOf(keyword)
        if (idx < 0) return false
        val beforeOk = idx == 0 || text[idx - 1].isWhitespace() ||
            text[idx - 1] in STRIP_CHARS
        val afterIdx = idx + keyword.length
        val afterOk = afterIdx >= text.length ||
            text[afterIdx].isWhitespace() || text[afterIdx] in STRIP_CHARS
        return beforeOk && afterOk
    }

    /**
     * هل يحتوي النص على مؤشر اقتطاع بصري صريح (علامات حذف …/...)
     * بعد تنظيف لاحقات TalkBack/البيانات الوصفية؟
     */
    fun hasTruncationHint(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val tail = cleanedTailForInspection(text)
        if (tail.endsWith("…") || tail.endsWith("...")) return true
        // أنماط اقتطاع تنتهي بعلامات حذف صريحة
        val ellipsisPatterns = listOf(
            "… المزيد", "... المزيد"
        )
        return ellipsisPatterns.any {
            tail.endsWith(it, ignoreCase = true)
        }
    }

    /**
     * هل يحتوي النص على محفّز/عبارة توسعة صريحة (مثل «قراءة المزيد»/
     * «عرض المزيد»/show more) بعد تنظيف لاحقات TalkBack/البيانات؟
     * لا يشمل علامات الحذف وحدها.
     */
    fun hasExplicitExpandCue(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val tail = cleanedTailForInspection(text)
        val lower = tail.lowercase()
        if (DISALLOWED_KEYWORDS.any { lower.contains(it) }) return false

        val explicitCuePatterns = listOf(
            "قراءة المزيد", "عرض المزيد", "إظهار المزيد",
            "تابع القراءة", "متابعة القراءة", "اقرأ المزيد",
            "read more", "show more", "see more", "view more",
            "lire la suite", "voir plus", "afficher plus",
            "mehr anzeigen", "weiterlesen",
            "leer más", "ver más", "mostrar más"
        )
        if (explicitCuePatterns.any { lower.endsWith(it) }) return true
        if (explicitCuePatterns.any { lower.contains(it) }) return true

        if (EXPAND_ACTION_KEYWORDS.any { kw ->
                lower.contains(cleanBidi(kw).trim().lowercase())
            }
        ) {
            return true
        }

        // كلمة «المزيد»/more كذيل صريح قصير (دون كلمات محظورة)
        val tailWords = lower.takeLast(60)
            .split(' ', '،', ',', '\n')
            .map { it.trim { c -> c in STRIP_CHARS } }
            .filter { it.isNotBlank() }
        val last = tailWords.lastOrNull()
        if (last == "المزيد" || last == "more") return true
        if (tailWords.size >= 2) {
            val lastTwo = "${tailWords[tailWords.size - 2]} ${tailWords.last()}"
            if (explicitCuePatterns.any { lastTwo == it }) return true
        }
        return false
    }

    private fun cleanedTailForInspection(text: String): String {
        var clean = cleanBidi(text).trim()
        var stripped = true
        while (stripped && clean.isNotEmpty()) {
            stripped = false
            for (suffix in TALKBACK_ROLE_SUFFIXES) {
                val cleanSuffix = cleanBidi(suffix).trim()
                if (clean.endsWith(cleanSuffix, ignoreCase = true)) {
                    clean = clean.substring(
                        0, clean.length - cleanSuffix.length
                    ).trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                    stripped = true
                    break
                }
            }
            if (stripped) continue
            for (meta in MESSAGE_METADATA_SUFFIXES) {
                val cleanMeta = cleanBidi(meta).trim()
                if (clean.endsWith(cleanMeta, ignoreCase = true)) {
                    clean = clean.substring(
                        0, clean.length - cleanMeta.length
                    ).trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                    stripped = true
                    break
                }
            }
            if (stripped) continue
            val timeMatch = TIME_SUFFIX_REGEX.find(clean)
            if (timeMatch != null) {
                clean = clean.substring(0, timeMatch.range.first)
                    .trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                stripped = true
                continue
            }
            val numMatch = NUM_SUFFIX_REGEX.find(clean)
            if (numMatch != null &&
                clean.length - numMatch.range.first <= 5
            ) {
                clean = clean.substring(0, numMatch.range.first)
                    .trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                stripped = true
                continue
            }
        }
        return clean.trim { it in BIDI_AND_SPACES }
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
            val timeMatch = TIME_SUFFIX_REGEX.find(clean)
            if (timeMatch != null) {
                clean = clean.substring(0, timeMatch.range.first)
                    .trim { it in BIDI_AND_SPACES || it in ",،;؛•-_| " }
                stripped = true
                continue
            }

            // 4. أرقام منفردة في النهاية
            val numMatch = NUM_SUFFIX_REGEX.find(clean)
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
        val sWords = sClean.split(WHITESPACE_REGEX)
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 3 }
        val nWords = nClean.split(WHITESPACE_REGEX)
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

    /**
     * بند ١٥: استمرارية بادئة النص — يتحقق أن بداية النص المنطوق ما زالت
     * موجودة في بداية نص العقدة (لا مجرد كلمات متفرقة في الذيل)، حتى لو
     * كان النص مقتطعاً. يُستخدم حين يُعطَّل فحص التطابق العام بسبب الاقتطاع
     * كي لا تُفتح أو تُقرأ رسالة أخرى تشترك معها في كلمات الذيل فقط.
     */
    fun hasPrefixContinuity(
        spokenText: String?,
        nodeText: String?
    ): Boolean {
        if (spokenText.isNullOrBlank() || nodeText.isNullOrBlank()) {
            return false
        }
        val sWords = meaningfulWords(spokenText)
        val nWords = meaningfulWords(nodeText)
        if (sWords.isEmpty() || nWords.isEmpty()) return false
        // أول كلمتين من النص المنطوق يجب أن تظهرا ضمن أول ٨ كلمات
        // للعقدة بنفس الترتيب — هذا يمنع قراءة رسالة أخرى تشترك
        // معها ببعض الكلمات في الذيل فقط.
        val anchor = sWords.take(2)
        val head = nWords.take(8)
        var cursor = 0
        for (word in anchor) {
            val found = (cursor until head.size).firstOrNull { i ->
                head[i] == word || head[i].startsWith(word) ||
                    word.startsWith(head[i])
            } ?: return false
            cursor = found + 1
        }
        return true
    }

    private fun meaningfulWords(text: String): List<String> =
        cleanBidi(text).trim().lowercase()
            .split(WHITESPACE_REGEX)
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 3 }
}
