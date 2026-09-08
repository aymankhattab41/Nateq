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
        /** هل السلسلة/طابع اللغة يمثل العربية؟ (مطابقة بادئة متسامحة مع الحالة). */
        fun isArabic(languageTag: String): Boolean =
            languageTag.startsWith(AR.tag, ignoreCase = true)

        /** هل السلسلة/طابع اللغة يمثل الإنجليزية؟ (مطابقة بادئة متسامحة مع الحالة). */
        fun isEnglish(languageTag: String): Boolean =
            languageTag.startsWith(EN.tag, ignoreCase = true)

        /** يحلّ سلسلةً إلى ثابت معروف إن طابق أحدها، وإلا null (للغات المكتشفة). */
        fun fromTagOrNull(languageTag: String): LanguageCode? =
            entries.firstOrNull { it.tag.equals(languageTag, ignoreCase = true) }
    }
}
