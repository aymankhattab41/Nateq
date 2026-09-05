package com.aymankhattab.nateq.providers

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
     * @param onAudioChunk يُستدعى بكل جزء صوتي جاهز (PCM 16-bit)
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onAudioChunk: (ByteArray) -> Unit,
        enginePackage: String? = null,
        voiceLocale: Locale? = null
    )
}

/** وصف موحّد لأي صوت من أي مزود */
data class VoiceDescriptor(
    val id: String,
    val providerId: String,
    val displayName: String,
    val locale: Locale
)
