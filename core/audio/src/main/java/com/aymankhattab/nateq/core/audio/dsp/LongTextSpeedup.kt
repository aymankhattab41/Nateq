package com.aymankhattab.nateq.core.audio.dsp

import kotlin.math.min

/**
 * التسريع التكيفي للنصوص الطويلة (ع٢٧-63).
 * يزيد سرعة النطق تدريجياً وبسلاسة للنصوص التي تتجاوز 300 حرف إذا فُعّل الخيار.
 */
object LongTextSpeedup {

    const val THRESHOLD_CHARS = 300
    const val MAX_BOOST = 0.25f // زيادة أقصاها 25%

    /**
     * حساب معامل السرعة التكيفي بناءً على طول النص
     */
    fun calculateSpeedMultiplier(
        charCount: Int,
        isEnabled: Boolean
    ): Float {
        if (!isEnabled || charCount <= THRESHOLD_CHARS) {
            return 1.0f
        }

        val extraChars = charCount - THRESHOLD_CHARS
        // زيادة بنسبة 5% لكل 200 حرف إضافي حتى السقف الأقصى
        val boost = min(MAX_BOOST, (extraChars / 200f) * 0.05f)
        return 1.0f + boost
    }
}
