package com.aymankhattab.nateq.core.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * ضاغط ومحدد ديناميكي لمنع تشويش وتجاوز الصوت الرقمي (ع٢٧-53، ع٢٧-54).
 */
class DynamicCompressor {

    private var threshold = 0.85
    private var attackCoeff = 0.0
    private var releaseCoeff = 0.0
    private var envelope = 0.0

    fun configure(
        sampleRate: Int,
        thresholdLinear: Double = 0.85,
        attackMs: Double = 2.0,
        releaseMs: Double = 50.0
    ) {
        this.threshold = thresholdLinear.coerceIn(0.1, 1.0)
        this.attackCoeff = exp(-1.0 / (sampleRate * (attackMs / 1000.0)))
        this.releaseCoeff = exp(-1.0 / (sampleRate * (releaseMs / 1000.0)))
        this.envelope = 0.0
    }

    /**
     * معالجة مصفوفة عينات PCM
     */
    fun process(samples: ShortArray, length: Int = samples.size) {
        for (i in 0 until length) {
            val input = samples[i] / 32768.0
            val inputAbs = abs(input)

            envelope = if (inputAbs > envelope) {
                attackCoeff * envelope + (1.0 - attackCoeff) * inputAbs
            } else {
                releaseCoeff * envelope + (1.0 - releaseCoeff) * inputAbs
            }

            val gain = if (envelope > threshold) {
                threshold / max(envelope, 0.0001)
            } else {
                1.0
            }

            val output = (input * gain).coerceIn(-1.0, 1.0)
            samples[i] = (output * 32767.0).toInt().toShort()
        }
    }

    fun reset() {
        envelope = 0.0
    }
}
