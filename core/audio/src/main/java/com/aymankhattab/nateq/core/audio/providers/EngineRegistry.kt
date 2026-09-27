package com.aymankhattab.nateq.core.audio.providers

/**
 * سجلّ محركات TTS: قرار نقي واحد فقط — هل الحزمة قارئُ شاشةٍ يجب
 * استثناؤه من المساهمة باللغات المكتشفة؟
 *
 * دالة هذا الكائن نقية (بلا Context) وقابلة للاختبار الآلي مباشرةً؛
 * اكتشاف المحركات المثبّتة يبقى في [EnginePicker]، والحسم النهائي للمحرك
 * عند النطق يتم في [AnnouncementSpeaker] و [SystemVoiceProvider].
 */
object EngineRegistry {
    /** قارئات الشاشة التي تُستثنى من المساهمة باللغات المكتشفة: لا تُنتج صوتاً
     *  عبر TextToSpeech.synthesize القياسي فتجعل المستخدم بلا صوت. تبقى ظاهرة
     *  في واجهة المحركات للاختيار اليدوي الصريح إن رغب المستخدم. */
    private val screenReaderPackages = setOf(
        "com.google.android.marvin.talkback",
        "com.samsung.accessibility",
        "com.nirenr.talkman"
    )

    /** هل الحزمة قارئ شاشة؟ */
    fun isScreenReader(packageName: String): Boolean {
        return packageName in screenReaderPackages
    }
}