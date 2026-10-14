package com.aymankhattab.nateq.util

/**
 * أكواد اللغات الثابتة الدلالية في المشروع (بند 2) — تحل محل السلاسل
 * السحرية المتكررة `"ar"` و`"en"` في مسارات النطق والترجمة. اللغات
 * المكتشفة ديناميكياً من المحركات تبقى سلاسل (fr/de/…) لأنها حالات
 * غير محدودة بلا ثابت.
 *
 * كل حالة تحمل قيمة طابع BCP-47 حقيقية [tag] تُمرَّر لأي واجهة تتطلب
 * سلسلة (احتياطيّة حارس): `Locale.forLanguageTag`, مفتاح JSON, إلخ.
 */
enum class LanguageCode(val tag: String) {
    /** العربية — اللغة الأساسية للتطبيق. */
    AR("ar"),

    /** الإنجليزية — لغة الترجمة/السقوط لسائر الكتابات. */
    EN("en");

    companion object {
        // **بند 6.1:** البادئة الصارمة بدل `startsWith("ar")` المتسامح
        // الذي كان يقبل "arise"/"aroma"… فأُقرئَ نصّي لأشياء ليست عربية.
        // القبول حصراً للطابع العربي "ar"/"ara" (أو امتدادهما ar-EG)،
        // لا أي "ar*" أخرى (aro، ary، arz…). المثلُ للإنجليزية "en".
        private val ARABIC_PATTERN =
            Regex("""(?i)^(ar|ara)([-_][a-z0-9]*)?$""")
        private val ENGLISH_PATTERN =
            Regex("""(?i)^(en|eng)([-_][a-z0-9]*)?$""")

        /** هل السلسلة/طابع اللغة يمثل العربية؟ (مطابقة صارمة
         *  لـ ar|ara وامتداداتهما مع تجاهل حالة الأحرف). */
        fun isArabic(languageTag: String): Boolean =
            ARABIC_PATTERN.matches(languageTag)

        /** هل السلسلة/طابع اللغة يمثل الإنجليزية؟ (مطابقة صارمة
         *  لـ en|eng وامتداداتهما مع تجاهل حالة الأحرف). */
        fun isEnglish(languageTag: String): Boolean =
            ENGLISH_PATTERN.matches(languageTag)

        /** يحلّ سلسلةً إلى ثابت معروف إن طابق أحدها، وإلا null
         *  (للغات المكتشفة). */
        fun fromTagOrNull(languageTag: String): LanguageCode? =
            entries.firstOrNull {
                it.tag.equals(languageTag, ignoreCase = true)
            }

        /** هل ينتمي الوسمان لنفس العائلة اللغوية؟ (ar == ar-SA). */
        fun isSameFamily(tag1: String?, tag2: String?): Boolean {
            if (tag1 == null && tag2 == null) return true
            if (tag1 == null || tag2 == null) return false
            if (tag1.equals(tag2, ignoreCase = true)) return true
            if (isArabic(tag1) && isArabic(tag2)) return true
            if (isEnglish(tag1) && isEnglish(tag2)) return true
            val b1 = tag1.substringBefore('-')
                .substringBefore('_').trim().lowercase()
            val b2 = tag2.substringBefore('-')
                .substringBefore('_').trim().lowercase()
            return b1.isNotEmpty() && b1 == b2
        }
    }
}
