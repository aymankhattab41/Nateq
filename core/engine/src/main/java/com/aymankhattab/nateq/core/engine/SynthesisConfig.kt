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
}