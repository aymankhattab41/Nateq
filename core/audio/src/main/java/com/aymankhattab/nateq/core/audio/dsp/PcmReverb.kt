package com.aymankhattab.nateq.core.audio.dsp

/**
 * صدى برمجي خوارزمي (Algorithmic Reverb) يعمل مباشرة على عينات PCM (ع٢٧-57).
 */
class PcmReverb {

    private class CombFilter(size: Int) {
        val buffer = DoubleArray(size)
        var bufferIndex = 0
        var filterStore = 0.0

        fun process(input: Double, feedback: Double, damp: Double): Double {
            val output = buffer[bufferIndex]
            filterStore = (output * (1.0 - damp)) + (filterStore * damp)
            buffer[bufferIndex] = input + (filterStore * feedback)
            if (++bufferIndex >= buffer.size) bufferIndex = 0
            return output
        }

        fun mute() {
            buffer.fill(0.0)
            filterStore = 0.0
        }
    }

    private class AllPassFilter(size: Int) {
        val buffer = DoubleArray(size)
        var bufferIndex = 0

        fun process(input: Double): Double {
            val bufOut = buffer[bufferIndex]
            val output = -input + bufOut
            buffer[bufferIndex] = input + (bufOut * 0.5)
            if (++bufferIndex >= buffer.size) bufferIndex = 0
            return output
        }

        fun mute() {
            buffer.fill(0.0)
        }
    }

    private val combFilters = mutableListOf<CombFilter>()
    private val allPassFilters = mutableListOf<AllPassFilter>()

    private var roomSize = 0.5
    private var damping = 0.2
    private var wet = 0.3
    private var dry = 0.7

    init {
        // أحجام التأخير الكلاسيكية لـ Freeverb بمعدل عينات 22050
        val combTunings = intArrayOf(556, 594, 670, 715, 782, 831, 908, 970)
        val allPassTunings = intArrayOf(112, 170, 220, 278)

        combTunings.forEach { combFilters.add(CombFilter(it)) }
        allPassTunings.forEach { allPassFilters.add(AllPassFilter(it)) }
    }

    fun configure(
        roomSizeVal: Double = 0.5,
        dampingVal: Double = 0.2,
        wetVal: Double = 0.25,
        dryVal: Double = 0.75
    ) {
        this.roomSize = roomSizeVal.coerceIn(0.0, 0.98)
        this.damping = dampingVal.coerceIn(0.0, 1.0)
        this.wet = wetVal.coerceIn(0.0, 1.0)
        this.dry = dryVal.coerceIn(0.0, 1.0)
    }

    fun process(samples: ShortArray, length: Int = samples.size) {
        for (i in 0 until length) {
            val input = samples[i] / 32768.0
            var out = 0.0

            for (comb in combFilters) {
                out += comb.process(input, roomSize, damping)
            }

            for (allpass in allPassFilters) {
                out = allpass.process(out)
            }

            val mixed = (input * dry) + (out * wet * 0.2)
            val clamped = mixed.coerceIn(-1.0, 1.0)
            samples[i] = (clamped * 32767.0).toInt().toShort()
        }
    }

    fun reset() {
        combFilters.forEach { it.mute() }
        allPassFilters.forEach { it.mute() }
    }
}
