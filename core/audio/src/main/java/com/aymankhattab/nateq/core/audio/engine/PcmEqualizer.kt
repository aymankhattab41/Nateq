package com.aymankhattab.nateq.core.audio.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * معادِل صوت خفيف (3 نطاقات: جهير / وسط / حاد) لمعالجة PCM 16-bit (بند 1).
 *
 * معالج إشارة رقمي نقي (Pure Kotlin DSP) بدون أي مكتبات خارجية
 * أو ملفات native، بمعالجة عينات سريعة جداً (Direct Form II Transposed)
 * تستهلك بضعة ميكروثانية للشريحة الواحدة دون إضافة أي كمون لنطق الصوت.
 *
 * يعالج مشكلة اختلاف بصمة الصوت بين محركات TTS (مثل خشونة eSpeak).
 */
class PcmEqualizer {

    companion object {
        /** تردد قطع نطاق الجهير (Low Shelf) بالهرتز. */
        const val LOW_FREQ_HZ = 250.0

        /** التردد المركزي لنطاق الوسط (Peaking Bell) بالهرتز. */
        const val MID_FREQ_HZ = 1200.0

        /** تردد قطع نطاق الحدة / التريبل (High Shelf) بالهرتز. */
        const val HIGH_FREQ_HZ = 4500.0

        /** عامل الجودة لنطاق الوسط. */
        private const val MID_Q = 1.0

        /** عتبة تخطي النطاق غير المعدَّل (0 dB). */
        private const val BYPASS_EPSILON = 0.15f

        /** بصمة eSpeak المعتدلة (بند الصوتيات): تعزيز جهير خفيف وخفض
         *  حدة خفيف — دون قَطع يُفقد النطقَ حلاوته (شكوى «صوت النطق
         *  ينخفض» كانت بقَطع −3.5dB كامل للنطاق الحاد). */
        val ESPEAK_GAINS = floatArrayOf(1.5f, 0.0f, -1.5f)

        /** بصمة افتراضية محايدة (Flat). */
        val FLAT_GAINS = floatArrayOf(0.0f, 0.0f, 0.0f)

        /**
         * يحدد كسب النطاقات الافتراضي لمحركٍ معيّن حسب بصمته المعروفة.
         */
        fun defaultGainsFor(enginePackage: String?): FloatArray {
            if (enginePackage == null) return FLAT_GAINS
            val pkg = enginePackage.lowercase()
            return when {
                pkg.contains("espeak") || pkg.contains("rhvoice") ->
                    ESPEAK_GAINS.clone()
                else -> FLAT_GAINS.clone()
            }
        }
    }

    /** معاملات مرشح ثنائي التربيع (Biquad Filter) */
    private class BiquadCoeffs(
        val b0: Double = 1.0,
        val b1: Double = 0.0,
        val b2: Double = 0.0,
        val a1: Double = 0.0,
        val a2: Double = 0.0,
        val bypassed: Boolean = true
    )

    /** حالة المرشح لكل نطاق للحفاظ على استمرارية الطور عبر الشرائح. */
    private class FilterState {
        var s1: Double = 0.0
        var s2: Double = 0.0

        fun reset() {
            s1 = 0.0
            s2 = 0.0
        }
    }

    private val stateLow = FilterState()
    private val stateMid = FilterState()
    private val stateHigh = FilterState()

    /** إعادة تصفير حالة المرشحات بين جمل النطق. */
    fun reset() {
        stateLow.reset()
        stateMid.reset()
        stateHigh.reset()
    }

    /**
     * معالجة مصفوفة PCM 16-bit (little-endian mono) في مكانها (In-Place).
     *
     * @param pcmData مصفوفة البايتات الخام للعينات 16-bit.
     * @param offset نقطة بداية العينات.
     * @param validLength طول البيانات الصالحة بالبايت.
     * @param sampleRate معدل العينات (مثلاً 22050 أو 44100).
     * @param gainsDb كسب النطاقات الثلاثة بالديسيبل [low, mid, high].
     * @return نفس المصفوفة بعد تطبيق التعديل.
     */
    fun process(
        pcmData: ByteArray,
        offset: Int,
        validLength: Int,
        sampleRate: Int,
        gainsDb: FloatArray
    ): ByteArray {
        if (validLength < 2 || sampleRate <= 0) return pcmData
        val lowGain = gainsDb.getOrElse(0) { 0f }
        val midGain = gainsDb.getOrElse(1) { 0f }
        val highGain = gainsDb.getOrElse(2) { 0f }

        val bypassLow = abs(lowGain) < BYPASS_EPSILON
        val bypassMid = abs(midGain) < BYPASS_EPSILON
        val bypassHigh = abs(highGain) < BYPASS_EPSILON

        if (bypassLow && bypassMid && bypassHigh) {
            return pcmData
        }

        val lowCoeffs = if (bypassLow) BiquadCoeffs()
            else designLowShelf(sampleRate, lowGain.toDouble())
        val midCoeffs = if (bypassMid) BiquadCoeffs()
            else designPeaking(sampleRate, midGain.toDouble())
        val highCoeffs = if (bypassHigh) BiquadCoeffs()
            else designHighShelf(sampleRate, highGain.toDouble())

        var idx = offset
        val end = offset + validLength - 1
        while (idx < end) {
            val lowByte = pcmData[idx].toInt() and 0xFF
            val highByte = pcmData[idx + 1].toInt()
            val sample = (highByte shl 8) or lowByte
            var x = sample.toDouble()

            if (!lowCoeffs.bypassed) {
                x = filterSample(x, lowCoeffs, stateLow)
            }
            if (!midCoeffs.bypassed) {
                x = filterSample(x, midCoeffs, stateMid)
            }
            if (!highCoeffs.bypassed) {
                x = filterSample(x, highCoeffs, stateHigh)
            }

            val clamped = x.toInt().coerceIn(-32768, 32767)
            pcmData[idx] = (clamped and 0xFF).toByte()
            pcmData[idx + 1] = (clamped ushr 8).toByte()
            idx += 2
        }
        return pcmData
    }

    private fun filterSample(
        x: Double,
        c: BiquadCoeffs,
        s: FilterState
    ): Double {
        val y = c.b0 * x + s.s1
        s.s1 = c.b1 * x - c.a1 * y + s.s2
        s.s2 = c.b2 * x - c.a2 * y
        return y
    }

    private fun designLowShelf(sampleRate: Int, gainDb: Double): BiquadCoeffs {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * LOW_FREQ_HZ / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val aRoot = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + aRoot)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - aRoot)
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + aRoot
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2 = (a + 1.0) + (a - 1.0) * cosW0 - aRoot

        return BiquadCoeffs(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0,
            bypassed = false
        )
    }

    private fun designPeaking(sampleRate: Int, gainDb: Double): BiquadCoeffs {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * MID_FREQ_HZ / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * MID_Q)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / a

        return BiquadCoeffs(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0,
            bypassed = false
        )
    }

    private fun designHighShelf(sampleRate: Int, gainDb: Double): BiquadCoeffs {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * HIGH_FREQ_HZ / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val aRoot = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + aRoot)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - aRoot)
        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + aRoot
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val a2 = (a + 1.0) - (a - 1.0) * cosW0 - aRoot

        return BiquadCoeffs(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0,
            bypassed = false
        )
    }
}
