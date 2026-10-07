package com.aymankhattab.nateq.core.data

/**
 * نتيجة حسم إعدادات الفئة الصوتية (ع٢٧-A2).
 *
 * عندما تكون فئة على «افتراضي» فإنها ترث من فئة default المحرك والصوت
 * والسرعة والنبرة والمستوى معاً، لا المحرك وحده.
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
 * حاسم إعدادات الفئات الصوتية (ع٢٧-A2).
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

            // Vocalizer voices
            lower.contains("-x-laila") || lower.contains("-x-maged") ||
                lower.contains("-x-mariam") || lower.contains("-x-tarek") ||
                lower.contains("maged") || lower.contains("mariam") ||
                lower.contains("tarek") ||
                lower.startsWith("com.vocalizer.") ||
                lower.startsWith("com.nuance.") ->
                "com.vocalizer.tts"

            // Acapela voices (e.g. ara-SAU-Mehdi, ara-EGY-Salma...)
            lower.startsWith("ara-sau-") || lower.startsWith("ara-egy-") ||
                lower.startsWith("ara-") || lower.contains("-acapela") ||
                lower.startsWith("com.acapela.") ->
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
     * دالة نقية لحسم إعدادات الفئة (ع٢٧-A2).
     */
    fun resolveCategory(
        category: String,
        prefs: CategoryVoicePrefs,
        customLookup: ((String) -> String?)? = null
    ): ResolvedCategoryVoice {
        val defaultCategoryKey = SettingsRepository.VOICE_CATEGORY_DEFAULT
        val isDefault = category == defaultCategoryKey

        val defaultRawEngine = prefs.getEngineForCategory(defaultCategoryKey)
        val defaultRawVoice =
            prefs.getPreferredVoiceIdForCategory(defaultCategoryKey)
        val defaultRate = prefs.getSpeechRateForCategory(defaultCategoryKey)
        val defaultPitch = prefs.getPitchForCategory(defaultCategoryKey)
        val defaultVolume = prefs.getVolumeForCategory(defaultCategoryKey)

        val defaultResolvedEngine = defaultRawEngine
            ?.takeIf { !it.isNullOrBlank() && it != "default" }
            ?: inferEngineFromVoice(defaultRawVoice, customLookup)

        val defaultResolvedVoice = defaultRawVoice
            ?.takeIf { !it.isNullOrBlank() && it != "default" }

        val defaultResolved = ResolvedCategoryVoice(
            category = defaultCategoryKey,
            engine = defaultResolvedEngine,
            voiceId = defaultResolvedVoice,
            speechRate = defaultRate,
            pitch = defaultPitch,
            volume = defaultVolume
        )

        if (isDefault) {
            return defaultResolved
        }

        val catRawEngine = prefs.getEngineForCategory(category)
        val catRawVoice = prefs.getPreferredVoiceIdForCategory(category)
        val catRate = prefs.getSpeechRateForCategory(category)
        val catPitch = prefs.getPitchForCategory(category)
        val catVolume = prefs.getVolumeForCategory(category)

        val hasCustomRate = prefs.hasExplicitSpeechRateForCategory(category)
        val hasCustomPitch = prefs.hasExplicitPitchForCategory(category)
        val hasCustomVolume = prefs.hasExplicitVolumeForCategory(category)

        val resolvedEngine = when {
            !catRawEngine.isNullOrBlank() && catRawEngine != "default" ->
                catRawEngine
            !catRawVoice.isNullOrBlank() && catRawVoice != "default" ->
                inferEngineFromVoice(catRawVoice, customLookup)
                    ?: defaultResolved.engine
            else -> defaultResolved.engine
        }

        val resolvedVoice = when {
            !catRawVoice.isNullOrBlank() && catRawVoice != "default" ->
                catRawVoice
            else -> defaultResolved.voiceId
        }

        val resolvedRate =
            if (hasCustomRate) catRate else defaultResolved.speechRate
        val resolvedPitch =
            if (hasCustomPitch) catPitch else defaultResolved.pitch
        val resolvedVolume =
            if (hasCustomVolume) catVolume else defaultResolved.volume

        return ResolvedCategoryVoice(
            category = category,
            engine = resolvedEngine,
            voiceId = resolvedVoice,
            speechRate = resolvedRate,
            pitch = resolvedPitch,
            volume = resolvedVolume
        )
    }
}
