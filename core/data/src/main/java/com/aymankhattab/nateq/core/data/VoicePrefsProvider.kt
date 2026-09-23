package com.aymankhattab.nateq.core.data

/**
 * موفِّر تفضيلات الأصوات/النُطق التي تحتاجها طبقة الصوت (مزوّدو الأصوات
 * والمتحدث) — يفصلها عن مستودع الإعدادات لتفكيك الوحدات (البند 4).
 * ينفّذها SettingsRepository.
 */
interface VoicePrefsProvider {

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

    /** معايرة RMS المحفوظة لمحرك (كسب التطبيع المستقر) — null إن لم توجد
     *  (بند الأوامر د.3.3). الافتراضي بلا ثبات لموفّرٍ لا يدعم الحفظ. */
    fun getEngineRmsCalibration(enginePackage: String): Float? = null

    /** حفظ معايرة RMS لمحرك — الافتراضي بلا أثر. */
    fun saveEngineRmsCalibration(enginePackage: String, gain: Float) {}

    /** مسح معايرة RMS لمحرك (إعادة معايرة) — الافتراضي بلا أثر. */
    fun clearEngineRmsCalibration(enginePackage: String) {}

    /** كسب معادِل الصوت (EQ) المخصص لمحركٍ معيّن (بالديسيبل لكل نطاق). */
    fun getEngineEqualizerGains(enginePackage: String): FloatArray? = null

    /** حفظ كسب معادِل الصوت لمحرك. */
    fun saveEngineEqualizerGains(
        enginePackage: String,
        gains: FloatArray
    ) {}

    /** مسح كسب معادِل الصوت لمحرك. */
    fun clearEngineEqualizerGains(enginePackage: String) {}
}