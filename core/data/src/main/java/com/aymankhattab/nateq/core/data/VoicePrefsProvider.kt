package com.aymankhattab.nateq.core.data

/**
 * موفِّر تفضيلات الأصوات/النُطق التي تحتاجها طبقة الصوت (مزوّدو الأصوات
 * والمتحدث) — يفصلها عن مستودع الإعدادات لتفكيك الوحدات (البند 4).
 * ينفّذها SettingsRepository.
 */
interface VoicePrefsProvider {

    /** حزمة محرك TTS الذي اختاره المستخدم (null = اختيار تلقائي). */
    fun getSelectedEnginePackage(): String?

    /** الصوت المفضّل لمجموعة صوتية معيّنة (مثل VOICE_CATEGORY_EMOJI). */
    fun getPreferredVoiceIdForCategory(category: String): String?

    /** معدل الكلام لمجموعة صوتية معيّنة (نسبة من الطبيعي). */
    fun getSpeechRateForCategory(category: String): Float

    /** درجة الصوت لمجموعة صوتية معيّنة (نصف نغمة). */
    fun getPitchForCategory(category: String): Float

    /** مستوى الصوت لمجموعة صوتية معيّنة (0..1). */
    fun getVolumeForCategory(category: String): Float
}