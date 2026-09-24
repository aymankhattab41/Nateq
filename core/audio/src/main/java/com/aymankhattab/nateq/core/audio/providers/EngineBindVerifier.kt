package com.aymankhattab.nateq.core.audio.providers

/**
 * قرارات التحقق من ربط المحرك بعد onInit — منطق نقي قابل للاختبار الآلي.
 *
 * قد تربط مشخّصات (مثل سامسونج على Android 10) محرك النظام الافتراضي
 * (جوجل) على الرغم من طلب حزمة صريحة في المنشئ الثلاثي
 * [android.speech.tts.TextToSpeech]. كشف الحزمة المرتبطة مباشرةً عبر
 * [android.speech.tts.TextToSpeech.getEngine] غير ممكن — الطريقة زالت من
 * SDK المعتمد — فيُعوَّض بكشف هوية المحرك من أسماء أصواته
 * ([android.speech.tts.TextToSpeech.getVoices]) التي تحمل محركاتُ Google
 * نمطاً مميزاً ([GOOGLE_VOICE_MARKERS]) لا تحمله eSpeak/SVOX/سامسونج.
 *
 * عند اكتشاف محرك دخيل يُسقط [SystemVoiceProvider] المثيل بدل إخراج صوت
 * محركٍ غير المختار، مع إعادة ربط واحدة [shouldRetryRebind] قبل الإعلان
 * الصريح عن فشل المحرك.
 */
internal object EngineBindVerifier {

    /** أقصى عدد محاولات إعادة ربط عند اكتشاف محرك دخيل. */
    const val MAX_REBIND_ATTEMPTS = 1

    /** وسم أسماء أصوات Google (مثل "ar-x-isc#female_2-local") — أسماء
     *  أصوات المحركات الأخرى (eSpeak/SVOX/سامسونج) لا تحمله. */
    private val GOOGLE_VOICE_MARKERS: List<String> = listOf("-x-", "#")

    private const val GOOGLE_ENGINE_PACKAGE = "com.google.android.tts"

    /** هل الحزمة المطلوبة [requestedEngine] هي محرك جوجل نفسه؟ لا كشف
     *  دخيل عندما يطلب المستخدم جوجل عمداً. */
    fun isGoogleEngine(requestedEngine: String): Boolean =
        requestedEngine == GOOGLE_ENGINE_PACKAGE

    /** هل أصوات المحرك المربوط فعلياً [actualVoiceNames] تحمل وسم Google
     *  بينما نطلب محركاً غير جوجل؟ — ربطٌ دخيل يجب رفضه (جوجل بدل المختار).
     *  أصوات فارغة/غير حاسمة تُعتمد (لا نرفض بلا دليل). */
    fun isWronglyBoundToGoogle(
        requestedEngine: String,
        actualVoiceNames: List<String>
    ): Boolean {
        if (isGoogleEngine(requestedEngine)) return false
        return actualVoiceNames.any { name ->
            GOOGLE_VOICE_MARKERS.any { name.contains(it) }
        }
    }

    /** هل نعيد محاولة الربط بعد [rebindAttempts] محاولةً سابقة؟ محاولة
     *  واحدة إضافية فقط قبل الاستسلام للإعلان الصريح. */
    fun shouldRetryRebind(rebindAttempts: Int): Boolean =
        rebindAttempts < MAX_REBIND_ATTEMPTS
}