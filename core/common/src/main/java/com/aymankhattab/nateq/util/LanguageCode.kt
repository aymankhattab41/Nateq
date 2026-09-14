package com.aymankhattab.nateq.util

/**
 * أكواد اللغات الثابتة الدلالية في المشروع (بند 2) — تحل محل السلاسل السحرية
 * المتكررة `"ar"` و`"en"` في مسارات النطق والترجمة. اللغات المكتشفة ديناميكياً
 * من المحركات تبقى سلاسل (fr/de/…) لأنها حالات غير محدودة بلا ثابت.
 *
 * كل حالة تحمل قيمة طابع BCP-47 حقيقية [tag] تُمرَّر لأي واجهة تتطلب سلسلة
 * (احتياطيّة حارس): `Locale.forLanguageTag`, مفتاح JSON, ... إلخ.
 */
enum class LanguageCode(val tag: String) {
    /** العربية — اللغة الأساسية للتطبيق. */
    AR("ar"),

    /** الإنجليزية — لغة الترجمة/السقوط لسائر الكتابات. */
    EN("en");

    companion object {
        // **بند 6.1:** البادئة الصارمة بدل `startsWith("ar")` المتسامح الذي
        // كان يقبل "arise"/"aroma"… فأُقرئَ نصّي لأشياء ليست عربية. القبول
        // حصراً للطابع العربي "ar"/"ara" (أو امتدادهما ar-EG/ar_EG)، لا أي
        // "ar*" أخرى (aro، ary، arz…). المثلُ للإنجليزية "en"/"eng".
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
    }
}
