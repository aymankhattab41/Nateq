package com.aymankhattab.nateq.core.engine

/**
 * إعدادات النُطق التي يحتاجها خط إنتاج معالجة النص (pipeline) — يفصلها عن
 * مستودع الإعدادات مباشرةً لتفكيك الوحدات
 * (البند 4). ينفّذها SettingsRepository.
 */
interface SynthesisConfig {

    /** تفعيل التحويل إلى التقويم الهجري في التواريخ. */
    fun isHijriDateEnabled(): Boolean

    /** تفعيل نطق أسماء الإيموجي قبل إزالتها من النص. */
    fun isEmojiPronunciationEnabled(): Boolean

    /** مستوى نطق علامات الترقيم والرموز: 0 لا شيء، 1 البعض، 2 الكل. */
    fun getPunctuationLevel(): Int

    /** تفعيل التهجئة الذكية ونطق التشكيل عند التنقل الحرفي في TalkBack. */
    fun isSmartSpellingEnabled(): Boolean
}