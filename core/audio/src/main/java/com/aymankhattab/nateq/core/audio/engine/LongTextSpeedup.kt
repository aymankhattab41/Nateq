package com.aymankhattab.nateq.core.audio.engine

/**
 * Long text speedup calculation.
 */
object LongTextSpeedup {
    const val THRESHOLD_CHARS = 300
    const val RAMP_CHARS = 1000
    const val FULL_SPEEDUP_CHARS = THRESHOLD_CHARS + RAMP_CHARS
    const val MAX_SPEEDUP_RATIO = 0.15f

    fun multiplier(charCount: Int, enabled: Boolean): Float {
        if (!enabled || charCount <= THRESHOLD_CHARS) {
            return 1.0f
        }
        val excess = (charCount - THRESHOLD_CHARS).toFloat()
        val progress = (excess / RAMP_CHARS).coerceIn(0.0f, 1.0f)
        return 1.0f + (progress * MAX_SPEEDUP_RATIO)
    }

    fun applySpeedup(
        baseRate: Float,
        charCount: Int,
        enabled: Boolean
    ): Float {
        return baseRate * multiplier(charCount, enabled)
    }
}