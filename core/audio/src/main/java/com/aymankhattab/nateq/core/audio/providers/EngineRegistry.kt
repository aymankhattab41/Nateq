package com.aymankhattab.nateq.core.audio.providers

/**
 * سجلّ محركات TTS: مركز القرار النقي لاختيار المحرك الأفضل المثبّت،
 * والاحتياط بعد الفشل، وسلسلة المحركات القادرة على نطق لغةٍ معيّنة.
 *
 * كل دوال هذا الكائن نقية (بلا Context) وقابلة للاختبار الآلي مباشرةً؛
 * الاستعلامات المعتمدة على النظام (المثبّتة في الجهاز) تبقى في
 * [EnginePicker] الذي يفوض قرارات الاختيار إلى هنا ليتّحد منطق القرار
 * في مكان واحد عبر التطبيق كله (المتحدث العام ومديري الإعلانات).
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