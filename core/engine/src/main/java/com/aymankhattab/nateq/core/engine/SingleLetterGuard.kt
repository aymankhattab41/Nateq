package com.aymankhattab.nateq.core.engine

/**
 * حارس الحرف المفرد (الأمر 2):
 * يميّز النطق بالحرف الواحد (أثناء القراءة بالحرف أو لمس مفتاح في اللوحة)
 * ليُنطق باسم الحرف الصحيح («ميم»، «كاي») بدل استبداله بقيمة القاموس («متر»).
 */
object SingleLetterGuard {

    private val ARABIC_LETTER_NAMES = mapOf(
        "أ" to "ألف همزة فوق",
        "إ" to "ألف همزة تحت",
        "آ" to "ألف مدّة",
        "ا" to "ألف",
        "ء" to "همزة",
        "ؤ" to "واو همزة",
        "ئ" to "ياء همزة",
        "ب" to "باء",
        "ت" to "تاء",
        "ث" to "ثاء",
        "ج" to "جيم",
        "ح" to "حاء",
        "خ" to "خاء",
        "د" to "دال",
        "ذ" to "ذال",
        "ر" to "راء",
        "ز" to "زاي",
        "س" to "سين",
        "ش" to "شين",
        "ص" to "صاد",
        "ض" to "ضاد",
        "ط" to "طاء",
        "ظ" to "ظاء",
        "ع" to "عين",
        "غ" to "غين",
        "ف" to "فاء",
        "ق" to "قاف",
        "ك" to "كاف",
        "ل" to "لام",
        "م" to "ميم",
        "ن" to "نون",
        "ه" to "هاء",
        "و" to "واو",
        "ي" to "ياء",
        "ة" to "تاء مربوطة",
        "ى" to "ألف مقصورة",
        "لا" to "لام ألف",
        "لأ" to "لام ألف",
        "لإ" to "لام ألف",
        "لآ" to "لام ألف"
    )

    private val ENGLISH_LETTER_NAMES = mapOf(
        'a' to "ay", 'b' to "bee", 'c' to "see", 'd' to "dee",
        'e' to "ee", 'f' to "eff", 'g' to "jee", 'h' to "aitch",
        'i' to "eye", 'j' to "jay", 'k' to "kay", 'l' to "el",
        'm' to "em", 'n' to "en", 'o' to "oh", 'p' to "pee",
        'q' to "cue", 'r' to "ar", 's' to "ess", 't' to "tee",
        'u' to "you", 'v' to "vee", 'w' to "double you", 'x' to "ex",
        'y' to "why", 'z' to "zee"
    )

    /**
     * تجريد النص من المسافات والمحارف غير المرئية والحركات والتطويل
     * والنقاط الهامشية للحصول على المحتوى الحرفي الصافي.
     */
    fun clean(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            // محارف التحكم والمحارف غير المرئية وعلامات الاتجاه
            if (Character.isISOControl(ch) ||
                cp == 0x200E || cp == 0x200F || // LRM, RLM
                cp == 0x200B || cp == 0x200C || // ZWSP, ZWNJ
                cp == 0x200D || cp == 0x2060 || // ZWJ, WJ
                cp == 0xFEFF || cp == 0x00AD || // BOM, Soft hyphen
                ch.isWhitespace()
            ) continue

            // التطويل / الكشيدة
            if (cp == 0x0640) continue

            // الحركات وعلامات التشكيل
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                cp in 0x064B..0x065F || cp == 0x0670
            ) continue

            // النقطة المفردة في نهاية الرمز (مثل «م.»)
            if (ch == '.' || ch == '۔') continue

            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * هل النص منطوق حرف واحد فقط؟
     */
    fun isSingleLetterUtterance(text: String): Boolean {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) return false
        if (cleaned == "لا" || cleaned == "لأ" ||
            cleaned == "لإ" || cleaned == "لآ"
        ) {
            return true
        }
        if (cleaned.length == 1) {
            val cp = cleaned.codePointAt(0)
            return Character.isLetter(cp)
        }
        return false
    }

    /**
     * اسم الحرف الصحيح إن كان حرفاً مفرداً، أو null إن لم يكن كذلك.
     */
    fun getLetterName(text: String): String? {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) return null

        val arName = ARABIC_LETTER_NAMES[cleaned]
        if (arName != null) return arName

        if (cleaned.length == 1) {
            val lower = cleaned[0].lowercaseChar()
            val enName = ENGLISH_LETTER_NAMES[lower]
            if (enName != null) return enName

            // حرف لاتيني بحركات (مثل é/è) يُردّ للأصل إن أمكن
            val decomposed = java.text.Normalizer.normalize(
                cleaned, java.text.Normalizer.Form.NFD
            )
            val baseChar = decomposed.firstOrNull { it.isLetter() }
                ?.lowercaseChar()
            if (baseChar != null &&
                ENGLISH_LETTER_NAMES.containsKey(baseChar)
            ) {
                return ENGLISH_LETTER_NAMES[baseChar]
            }
        }

        return null
    }

    /**
     * يحسم نطق الحرف المفرد وفق النمط المحدد:
     * يعيد null إن لم يكن النص حرفاً مفرداً أو كان النمط "dictionary"
     * (فيواصل المستدعي تطبيق القاموس والخطوات الدلالية)،
     * أو يعيد اسم الحرف في نمط "name"، أو النص كما هو في نمط "as_written".
     */
    fun resolve(text: String, singleLetterMode: String = "name"): String? {
        if (!isSingleLetterUtterance(text) ||
            singleLetterMode == "dictionary"
        ) {
            return null
        }
        return if (singleLetterMode == "name") {
            getLetterName(text) ?: text
        } else {
            text
        }
    }
}
