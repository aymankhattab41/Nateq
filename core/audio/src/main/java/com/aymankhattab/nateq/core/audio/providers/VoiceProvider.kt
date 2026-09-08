package com.aymankhattab.nateq.core.audio.providers

import java.util.Locale

/**
 * الواجهة (Interface) الموحّدة لأي مزود صوت.
 * أي مزود جديد تضيفه مستقبلاً (Amazon Polly, IBM Watson, ...)
 * لازم يطبّق هذه الواجهة فقط، وهيشتغل تلقائيًا مع باقي النظام
 * بدون أي تعديل في NateqTtsService.
 */
interface VoiceProvider {

    /** معرف فريد للمزود، يُستخدم داخليًا وفي الإعدادات */
    val providerId: String

    /** اسم المزود المعروض للمستخدم (يُقرأ عبر قارئ الشاشة) */
    val displayName: String

    /** هل المزود جاهز للعمل الآن */
    fun isConfigured(): Boolean

    /** قائمة الأصوات المتاحة من هذا المزود لهذه اللغة */
    suspend fun listVoices(locale: Locale): List<VoiceDescriptor>

    /**
     * تخليق الصوت الفعلي.
     * @param text النص المطلوب نطقه
     * @param voice الصوت المختار من [listVoices]
     * @param speechRate سرعة النطق (1.0 = طبيعي، 0.5 = نصف السرعة، 2.0 = ضعف السرعة)
     * @param pitch نبرة الصوت (1.0 = طبيعي، 0.5 = منخفضة، 2.0 = عالية)
     * @param volume مستوى الصوت (0.0 = صامت، 1.0 = كامل)
     * @param onFormatInfo يُبلّغ قبل أول شريحة بمعدل العينات (Hz) وعدد القنوات
     *                     الفعليين لموقع WAV الذي ينتجه المحرك، ليُمرَّرا كاملين
     *                     لـ callback.start() بدل قيمة ثابتة (كانت 22050 دائماً)
     * @param onAudioChunk يُستدعى بكل جزء صوتي جاهز (PCM 16-bit). يمرر الشريحة
     *                     وطولها الصالح الصريح (ByteArray, Int) — فقد تأتي
     *                     الشريحة من مسبح مُعاد الاستخدام بحجم مصفوفة أكبر من
     *                     بياناته الفعلية، فطول البيانات الحقيقي هو [Int] لا
     *                     مصفوفة[i].size. البيانات صالحة خلال مدة الاستدعاء فقط
     *                     (يُنسخها المتلقّي فوراً ولا يمسك بمرجع المصفوفة).
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit = { _: Int, _: Int -> },
        onAudioChunk: (ByteArray, Int) -> Unit,
        enginePackage: String? = null,
        voiceLocale: Locale? = null,
        desiredVoiceName: String? = null
    )

    /**
     * إغلاق نهائي لاتصال المزوّد وتحرير موارده (محرك TTS ورابط الـ Binder IPC
     * إلى المحرك الخارجي). يُستدعى من [NateqTtsService.onDestroy] حتى لا يبقى
     * الاتصال معلقاً في النظام بعد تدمير الخدمة. افتراضية فارغة (Unit) للمزودين
     * غير الضروريين كي لا يُجبر أي مُنفّذ مستقبلي على تنفيذها.
     */
    fun shutdown() = Unit
}

/** وصف موحّد لأي صوت من أي مزود */
data class VoiceDescriptor(
    val id: String,
    val providerId: String,
    val displayName: String,
    val locale: Locale
)
