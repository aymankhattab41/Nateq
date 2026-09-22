package com.aymankhattab.nateq.core.engine

/**
 * إعدادات النُطق التي يحتاجها خط إنتاج معالجة النص (pipeline) — يفصلها عن
 * مستودع الإعدادات مباشرةً لتفكيك الوحدات
 * (البند 4). ينفّذها SettingsRepository.
 */
interface SynthesisConfig {

    /** تفعيل نطق أسماء الإيموجي قبل إزالتها من النص. */
    fun isEmojiPronunciationEnabled(): Boolean

    /** مستوى نطق علامات الترقيم والرموز: 0 لا شيء، 1 البعض، 2 الكل. */
    fun getPunctuationLevel(): Int


    /**
     * الحفاظ على تشكيل النصوص العربية المُرسلة للمحرك: التجريد الداخلي
     * (للمطابقة مع القواميس والأنماط) يبقى قائماً، لكن الكلمات الأصلية
     * غير المتحوّلة تُعاد بتشكيلها إلى المحرك — فتنطق الحركات/الشدة
     * بوضوح لدى المحركات العربية التي تفهم التشكيل (بند 1.7).
     */
    fun isTashkeelPreserved(): Boolean

    /** لغة نطق الأرقام: "ar" أو "en". */
    fun getNumberReadingLanguage(): String = "ar"
}