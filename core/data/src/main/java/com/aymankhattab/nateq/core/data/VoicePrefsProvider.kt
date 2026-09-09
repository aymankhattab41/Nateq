package com.aymankhattab.nateq.core.data

/**
 * موفِّر تفضيلات الأصوات/النُطق التي تحتاجها طبقة الصوت (مزوّدو الأصوات
 * والمتحدث) — يفصلها عن مستودع الإعدادات لتفكيك الوحدات (البند 4).
 * ينفّذها SettingsRepository.
 */
interface VoicePrefsProvider {

    /** حزمة محرك TTS الذي اختاره المستخدم (null = اختيار تلقائي). */
    fun getSelectedEnginePackage(): String?

    /** محرك TTS الصريح للغةٍ معيّنة (null = بلا تفضيل لغة) — يسري في
     *  النطق العام حتى مع تعطيل التحويل التلقائي. */
    fun getEngineForLanguage(languageTag: String): String?

    /** صوت المحرك الصريح (داخل محرك اللغة) للغةٍ معيّنة، null إن لم يُحدَّد. */
    fun getVoiceForLanguage(languageTag: String): String?

    /** الصوت المفضّل لمجموعة صوتية معيّنة (مثل VOICE_CATEGORY_EMOJI). */
    fun getPreferredVoiceIdForCategory(category: String): String?

    /** معدل الكلام لمجموعة صوتية معيّنة (نسبة من الطبيعي). */
    fun getSpeechRateForCategory(category: String): Float

    /** درجة الصوت لمجموعة صوتية معيّنة (نصف نغمة). */
    fun getPitchForCategory(category: String): Float

    /** مستوى الصوت لمجموعة صوتية معيّنة (0..1). */
    fun getVolumeForCategory(category: String): Float
}