package com.aymankhattab.nateq.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * موازن مكاني وتوزيع ستيريو (ع٢٧-55، ع٢٧-56).
 * يحوّل العينات الأحادية Mono إلى ستيريو Stereo مع التحكم في موضع الصوت (Pan).
 */
object SpatialPanner {

    /**
     * تحويل عينات أحادية Mono إلى ستيريو Stereo مع موازنة Pan
     * @param monoSamples مصفوفة العينات الأحادية
     * @param pan قيمة التوزيع (-1.0 يسار، 0.0 وسط، +1.0 يمين)
     * @return مصفوفة ستيريو بحجم ضعف المصفوفة الأصلية (L, R, L, R, ...)
     */
    fun monoToStereo(
        monoSamples: ShortArray,
        pan: Float = 0.0f
    ): ShortArray {
        val clampedPan = pan.coerceIn(-1.0f, 1.0f)
        val angle = ((clampedPan + 1.0) * (PI / 4.0))
        val leftGain = cos(angle)
        val rightGain = sin(angle)

        val stereo = ShortArray(monoSamples.size * 2)
        var stereoIndex = 0

        for (sample in monoSamples) {
            val left = (sample * leftGain).toInt().coerceIn(-32768, 32767)
            val right = (sample * rightGain).toInt().coerceIn(-32768, 32767)

            stereo[stereoIndex++] = left.toShort()
            stereo[stereoIndex++] = right.toShort()
        }

        return stereo
    }
}
