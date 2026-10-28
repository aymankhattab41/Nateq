package com.aymankhattab.nateq.core.audio.engine

/**
 * سياسة تسريع النطق الديناميكي للنصوص الطويلة (الجولة 56 - إضافات 5 - أمر 4).
 * تسرع قراءة النصوص الطويلة خطياً حتى سقف +20% دون تعديل الأرقام والإيموجي.
 */
object DynamicRatePolicy {

    const val DEFAULT_MAX_BOOST_PERCENT = 0.20f
    const val MAX_SAFE_TTS_RATE = 4.0f

    /**
     * حساب معامل السرعة بناءً على طول النص ودوره.
     */
    fun calculateMultiplier(
        textLength: Int,
        role: SpeechRole,
        isEnabled: Boolean,
        maxBoostPercent: Float = DEFAULT_MAX_BOOST_PERCENT
    ): Float {
        if (!isEnabled || role != SpeechRole.TEXT) {
            return 1.0f
        }

        val factor = when {
            textLength <= 80 -> 1.0f
            textLength <= 300 -> {
                val fraction = (textLength - 80).toFloat() / (300 - 80)
                1.0f + fraction * 0.08f
            }
            textLength <= 800 -> {
                val fraction = (textLength - 300).toFloat() / (800 - 300)
                1.08f + fraction * 0.07f
            }
            else -> {
                val extra = (textLength - 800).toFloat() / 1000f * 0.05f
                1.15f + extra
            }
        }

        val maxMultiplier = 1.0f + maxBoostPercent.coerceIn(0.05f, 0.20f)
        return factor.coerceIn(1.0f, maxMultiplier)
    }

    /**
     * تطبيق المعامل على السرعة الأساسية مع مراعاة السقف الآمن.
     */
    fun apply(
        baseRate: Float,
        textLength: Int,
        role: SpeechRole,
        isEnabled: Boolean,
        maxBoostPercent: Float = DEFAULT_MAX_BOOST_PERCENT,
        maxSafeRate: Float = MAX_SAFE_TTS_RATE
    ): Float {
        val multiplier = calculateMultiplier(
            textLength = textLength,
            role = role,
            isEnabled = isEnabled,
            maxBoostPercent = maxBoostPercent
        )
        return (baseRate * multiplier).coerceIn(0.1f, maxSafeRate)
    }
}
