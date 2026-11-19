package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * نتيجة حسم إعدادات الفئة الصوتية.
 *
 * الفئة بلا تجاوز صريح تتبع الإعدادات العامة (عبر دوال القراءة)،
 * ولا ترث من فئة «افتراضي» محذوفة.
 */
data class ResolvedCategoryVoice(
    val category: String,
    val engine: String?,
    val voiceId: String?,
    val speechRate: Float,
    val pitch: Float,
    val volume: Float
)

/**
 * حاسم إعدادات الفئات الصوتية.
 * دالة نقية قابلة للاختبار المستقل بجدول حالات.
 */
object CategoryResolver {

    /**
     * يستدل المحرك الصريح من اسم الصوت إن لم يكن المحرك محدداً صراحة.
     * يدعم محركات Acapela وVocalizer وSamsung وGoogle وeSpeak وMultiTTS.
     */
    fun inferEngineFromVoice(
        voiceName: String?,
        customLookup: ((String) -> String?)? = null
    ): String? {
        if (voiceName.isNullOrBlank()) return null
        customLookup?.invoke(voiceName)?.let { return it }

        val lower = voiceName.lowercase()
        return when {
            // Google voices
            lower.contains("-x-isc") || lower.contains("-x-sfg") ||
                lower.contains("-x-iom") || lower.contains("-x-iob") ||
                lower.contains("-x-iol") || lower.contains("-x-ffa") ||
                lower.contains("-x-tld") || lower.contains("-x-gpf") ||
                lower.contains("-x-jad") || lower.contains("-x-kda") ||
                lower.contains("-x-dfz") || lower.contains("-x-apa") ||
                lower.contains("-x-ear") || lower.contains("-x-wfb") ||
                lower.contains("-x-cfl") ||
                (lower.contains("-x-") && (lower.endsWith("-local") ||
                    lower.endsWith("-network"))) ||
                lower.startsWith("com.google.android.tts:") ->
                "com.google.android.tts"

            // Samsung voices
            lower.contains("-smn#") || lower.contains("#female") ||
                lower.contains("#male") ||
                lower.startsWith("com.samsung.smt:") ->
                "com.samsung.SMT"

            // Vocalizer voices (بند 1: توحيد الأسماء البديلة).
            lower.contains("-x-laila") || lower.contains("-x-maged") ||
                lower.contains("-x-mariam") || lower.contains("-x-tarek") ||
                lower.contains("-x-tariq") || lower.contains("-x-tarik") ||
                lower.contains("maged") || lower.contains("mariam") ||
                lower.contains("tarek") || lower.contains("tariq") ||
                lower.contains("tarik") || lower.contains("tarick") ||
                lower.contains("layla") ||
                VoiceIdContract.sameVoiceName(
                    VoiceIdContract.canonicalVoiceName(voiceName), "Tarik"
                ) ||
                VoiceIdContract.sameVoiceName(
                    VoiceIdContract.canonicalVoiceName(voiceName), "Laila"
                ) ||
                lower.startsWith("com.vocalizer.") ||
                lower.startsWith("com.nuance.") ->
                "com.vocalizer.tts"

            // Acapela embedded (بند 1: توحيد الأسماء البديلة).
            lower.startsWith("acapela_embedded:") ||
                lower.startsWith("acapela:") ||
                lower == "mehdi" || lower == "salma" || lower == "nizar" ||
                lower == "youssef" || lower == "leila" || lower == "layla" ||
                lower == "tarik" || lower == "tarek" || lower == "tariq" ||
                lower == "tarick" ||
                (lower.contains("(") && lower.contains(")") &&
                    (lower.contains("mehdi") || lower.contains("salma") ||
                        lower.contains("nizar") || lower.contains("youssef") ||
                        lower.contains("leila") || lower.contains("layla") ||
                        lower.contains("tarik") || lower.contains("tarek") ||
                        lower.contains("tariq") || lower.contains("tarick"))) ->
                "acapela_embedded"

            // Acapela external system engines
            lower.startsWith("com.acapelagroup.") ->
                "com.acapelagroup.android.tts"

            lower.startsWith("ara-sau-") || lower.startsWith("ara-egy-") ||
                lower.startsWith("ara-") || lower.contains("-acapela") ||
                lower.startsWith("com.acapela.") ||
                lower.startsWith("ar_") ->
                "com.acapela.tts"

            // eSpeak
            lower.startsWith("espeak:") || lower.startsWith("espeak-") ||
                lower.contains("espeak") ->
                "com.googlecode.eyesfree.espeak"

            // MultiTTS
            lower.startsWith("multitts:") ||
                lower.startsWith("org.nobody.multitts:") ->
                "org.nobody.multitts"

            else -> null
        }
    }

    /**
     * دالة نقية لحسم إعدادات الفئة.
     * لا وراثة من فئة افتراضية؛ الفئة بلا تجاوز صريح تتبع العام
     * عبر دوال القراءة في المستودع.
     */
    fun resolveCategory(
        category: String,
        prefs: CategoryVoicePrefs,
        customLookup: ((String) -> String?)? = null
    ): ResolvedCategoryVoice {
        val catRawEngine = prefs.getEngineForCategory(category)
        val catRawVoice = prefs.getPreferredVoiceIdForCategory(category)
        val catRate = prefs.getSpeechRateForCategory(category)
        val catPitch = prefs.getPitchForCategory(category)
        val catVolume = prefs.getVolumeForCategory(category)

        val resolvedEngine = when {
            !catRawEngine.isNullOrBlank() && catRawEngine != "default" ->
                catRawEngine
            !catRawVoice.isNullOrBlank() && catRawVoice != "default" ->
                inferEngineFromVoice(catRawVoice, customLookup)
            else -> null
        }

        val resolvedVoice = when {
            !catRawVoice.isNullOrBlank() && catRawVoice != "default" ->
                catRawVoice
            else -> null
        }

        return ResolvedCategoryVoice(
            category = category,
            engine = resolvedEngine,
            voiceId = resolvedVoice,
            speechRate = catRate,
            pitch = catPitch,
            volume = catVolume
        )
    }
}
