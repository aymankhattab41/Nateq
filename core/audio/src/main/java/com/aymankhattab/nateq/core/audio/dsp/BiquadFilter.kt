package com.aymankhattab.nateq.core.audio.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * مرشح Biquad ثنائي الترتيب لمعالجة الإشارات الصوتية (ع٢٧-51).
 * يدعم المرشحات الشائعة: LowShelf, HighShelf, Peaking (Mid), LowPass, HighPass.
 */
class BiquadFilter {

    enum class Type {
        LOW_SHELF,
        HIGH_SHELF,
        PEAKING,
        LOW_PASS,
        HIGH_PASS
    }

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /**
     * ضبط معاملات المرشح
     * @param type نوع المرشح
     * @param sampleRate معدل العينات بالهرتز (مثال: 22050، 44100)
     * @param frequency التردد المستهدف بالهرتز
     * @param gainDb الكسب بالديسيبل (-12 إلى +12)
     * @param q عامل الجودة Q (افتراضي 0.707)
     */
    fun configure(
        type: Type,
        sampleRate: Int,
        frequency: Double,
        gainDb: Double = 0.0,
        q: Double = 0.707
    ) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * frequency / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        var a0 = 1.0

        when (type) {
            Type.LOW_SHELF -> {
                val sqrtA = sqrt(a)
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha
            }
            Type.HIGH_SHELF -> {
                val sqrtA = sqrt(a)
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha
            }
            Type.PEAKING -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
            Type.LOW_PASS -> {
                b0 = (1.0 - cosW0) / 2.0
                b1 = 1.0 - cosW0
                b2 = (1.0 - cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            Type.HIGH_PASS -> {
                b0 = (1.0 + cosW0) / 2.0
                b1 = -(1.0 + cosW0)
                b2 = (1.0 + cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
        }

        // تطبيع المعاملات على a0
        this.b0 = b0 / a0
        this.b1 = b1 / a0
        this.b2 = b2 / a0
        this.a1 = a1 / a0
        this.a2 = a2 / a0
    }

    /**
     * معالجة عينة صوتية واحدة
     */
    fun processSample(input: Double): Double {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}
