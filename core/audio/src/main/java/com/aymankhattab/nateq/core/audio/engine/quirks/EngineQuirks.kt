package com.aymankhattab.nateq.core.audio.engine.quirks

import java.util.Locale

/**
 * سياسة التعامل مع التشكيل (الحركات) للمحرك.
 */
enum class TashkeelPolicy {
    /** غير معروف — يُعامل افتراضياً كحفظ التشكيل. */
    UNKNOWN,
    /** المحرك يدعم التشكيل بالكامل — يُمرّر النص مشكلاً. */
    KEEP_TASHKEEL,
    /** المحرك لا يدعم التشكيل — يُجرد النص من الحركات قبل النطق. */
    STRIP_TASHKEEL,
    /** المحرك يدعم التشكيل جزئياً — يُمرر الحركات الأساسية فقط. */
    PARTIAL_TASHKEEL
}

/**
 * خصائص محرك TTS المعروفة — كائن بيانات نقي [EngineQuirks]
 * ينقل الاستثناءات المتناثرة في التعليقات إلى إعداد منظم.
 * يُستعلم عبر [EngineQuirks.forPackage] في مسار النطق.
 */
data class EngineQuirks(
    /** حزمة المحرك (مثل "es.codefactory.vocalizertts") */
    val packageName: String,

    /** هل يحتاج هذا المحرك استطلاع جاهزية (warmup polling) قبل أول نطق؟
     *  Vocalizer: true — قد يعيد SUCCESS قبل أن تكون voices جاهزة. */
    val needsWarmupPolling: Boolean = false,

    /** هل يرفض هذا المحرك Locale مجرداً (مثل Locale("ar")) ويطلب إقليماً؟ */
    val requiresLocaleWithRegion: Boolean = false,

    /** هل يرفض المحرك setVoice بصمت لبعض الأصوات ويحتاج fallback؟ */
    val rejectsVoiceSilently: Boolean = false,

    /** هل يحتاج المحرك setLanguage قبل setVoice حتى لا يرفض الصوت؟ */
    val needsSetLanguageBeforeSetVoice: Boolean = false,

    /**
     * سقف سرعة النطق الآمن لهذا المحرك (بند 2.4) — فوقه قد يصمت بلا
     * onDone/onError.
     */
    val maxSafeSpeechRate: Float = 2.5f,

    /** سياسة التشكيل لهذا المحرك — UNKNOWN مؤقتاً. */
    val tashkeelPolicy: TashkeelPolicy = TashkeelPolicy.UNKNOWN,

    /** لغات معروفة بأنها مشكلة لهذا المحرك (تتطلب fallback locale). */
    val problematicLanguages: Set<String> = emptySet(),

    /** أسماء أصوات معروفة بأنها مشكلة لهذا المحرك. */
    val problematicVoiceNames: Set<String> = emptySet(),
) {
    /**
     * يُعيد [EngineQuirks] للحزمة المعطاة، أو كائن افتراضي إن لم توجد قواعد.
     */
    companion object {
        private val QUIRKS_DB: Map<String, EngineQuirks> = mapOf(
            // es.codefactory.vocalizertts — Vocalizer TTS
            "es.codefactory.vocalizertts" to EngineQuirks(
                packageName = "es.codefactory.vocalizertts",
                needsWarmupPolling = true,
                requiresLocaleWithRegion = true,
                rejectsVoiceSilently = true,
                needsSetLanguageBeforeSetVoice = true,
                maxSafeSpeechRate = 2.5f,
                tashkeelPolicy = TashkeelPolicy.UNKNOWN,
                problematicLanguages = setOf("ar"),
                problematicVoiceNames = emptySet()
            )
        )

        fun forPackage(packageName: String?): EngineQuirks =
            packageName?.let { QUIRKS_DB[it] }
                ?: EngineQuirks(packageName = packageName.orEmpty())
    }
}