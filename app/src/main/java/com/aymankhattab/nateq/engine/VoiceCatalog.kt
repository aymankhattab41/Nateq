package com.aymankhattab.nateq.engine

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.aymankhattab.nateq.providers.VoiceDescriptor
import com.aymankhattab.nateq.providers.VoiceProvider
import java.util.Locale

/**
 * يجمّع كل الأصوات المتاحة من كل المزودين (النشطين/المُهيّئين فقط)
 * في قائمة واحدة موحّدة، ليعرضها النظام وقارئ الشاشة للمستخدم.
 */
class VoiceCatalog(private val providers: List<VoiceProvider>) {

    suspend fun allAvailableVoices(locale: Locale): List<VoiceDescriptor> =
        providers
            .filter { it.isConfigured() }
            .flatMap { it.listVoices(locale) }

    fun findProvider(providerId: String): VoiceProvider? =
        providers.find { it.providerId == providerId }

    /**
     * اللغات المدعومة إجمالاً (تُستخدم في onIsLanguageAvailable).
     * مبسّطة إلى لغتين فقط كما طلب المستخدم: "العربية" و"الإنجليزية"،
     * وتندرج كل اللهجات/البلدان المشتقة (مصر/سعودية/إمارات، أمريكا/بريطانيا)
     * تحت لغته الأم كصوتٍ واحد.
     */
    fun supportedLocales(): List<Locale> = listOf(
        Locale.forLanguageTag("ar"), // العربية (تشمل لهجات مصر/السعودية/الإمارات)
        Locale.forLanguageTag("en")  // الإنجليزية (تشمل أمريكا/بريطانيا وأخرى)
    )

    /**
     * قائمة الأصوات (android.speech.tts.Voice) المُعلنة للنظام.
     * هذا هو المصدر الوحيد الذي يقرأ منه النظام في شاشة
     * "تعيين الصوت الخاص بلغة النص المنطوق" (onGetVoices).
     * تُشتق من اللغات المدعومة فعلياً حتى لا تظهر أصوات لا تُنطق.
     *
     * ملاحظة مهمة (سامسونج/بعض المَشغلين): الأصوات يجب أن تُعلن صراحةً
     * بـ KEY_FEATURE_EMBEDDED_SYNTHESIS (offline) وإلا تُصفَّى وتُهمَل من
     * قائمة اللغات. ولا يُضاف KEY_FEATURE_NOT_INSTALLED أبداً.
     *
     * الثابت معيَّن deprecated في المنصة الحديثة (لأن التخليق المدمج صار
     * افتراضياً)، لكن إزالته تفاقم تصفية سامسونج لقائمة الأصوات، لذا نحتفظ
     * به مع كتم تحذير الإهمال المحدَّد.
     */
    @Suppress("DEPRECATION")
    private val offlineFeature = setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    private fun voiceNameFor(locale: Locale): String {
        // أسماء الأصوات المعلنة في tts_engine.xml هي "ar-local"/"en-local"،
        // وهي نفسها المعرّفات التي يخزنها تطبيقنا في الإعدادات (بطارية/رسائل/متصل/فئات)
        // والمعرّفات التي يُنتجها SystemVoiceProvider.listVoices(). لذلك يجب أن تطابق
        // onGetVoices هنا هذه الأسماء بالضبط — وإلا يفشل الإبقاء على اختيار الصوت في
        // شاشة سامسونج (رفض findIndexOfValue) ويضيع voiceId في كل عمليات البحث
        // `voices.find { it.id == name }`. الـ locale يبقى ar/en لكل منهما.
        return when (locale.language.lowercase(java.util.Locale.ROOT)) {
            "ar" -> "ar-local"
            "en" -> "en-local"
            else -> "${locale.language.lowercase(java.util.Locale.ROOT)}-local"
        }
    }

    fun supportedVoices(): List<Voice> =
        supportedLocales().map { locale ->
            Voice(
                voiceNameFor(locale),
                locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_LOW,
                false, // requiresNetworkConnection = false (محلي بالكامل)
                offlineFeature
            )
        }

    /** معرّف الصوت الافتراضي للغة (يُطابق أسماء supportedVoices) */
    fun defaultVoiceNameForLanguage(language: String): String? =
        supportedLocales()
            .firstOrNull { it.language == language }
            ?.let { voiceNameFor(it) }
}
