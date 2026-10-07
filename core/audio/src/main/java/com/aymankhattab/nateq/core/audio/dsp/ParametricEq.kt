package com.aymankhattab.nateq.core.audio.dsp

/**
 * موازن صوتي ثلاثي النطاقات (Bass, Mid, Treble) عبر مرشحات Biquad (ع٢٧-52).
 */
class ParametricEq {

    private val bassFilter = BiquadFilter()
    private val midFilter = BiquadFilter()
    private val trebleFilter = BiquadFilter()

    private var sampleRate = 22050

    fun configure(
        sampleRate: Int,
        bassGainDb: Double = 0.0,
        midGainDb: Double = 0.0,
        trebleGainDb: Double = 0.0
    ) {
        this.sampleRate = sampleRate

        // Bass: LowShelf عند 200 هرتز
        bassFilter.configure(
            type = BiquadFilter.Type.LOW_SHELF,
            sampleRate = sampleRate,
            frequency = 200.0,
            gainDb = bassGainDb.coerceIn(-12.0, 12.0)
        )

        // Mid: Peaking عند 1500 هرتز
        midFilter.configure(
            type = BiquadFilter.Type.PEAKING,
            sampleRate = sampleRate,
            frequency = 1500.0,
            gainDb = midGainDb.coerceIn(-12.0, 12.0),
            q = 1.0
        )

        // Treble: HighShelf عند 4000 هرتز
        trebleFilter.configure(
            type = BiquadFilter.Type.HIGH_SHELF,
            sampleRate = sampleRate,
            frequency = 4000.0,
            gainDb = trebleGainDb.coerceIn(-12.0, 12.0)
        )
    }

    /**
     * معالجة مصفوفة عينات PCM 16-bit
     */
    fun process(samples: ShortArray, length: Int = samples.size) {
        for (i in 0 until length) {
            val input = samples[i] / 32768.0
            val s1 = bassFilter.processSample(input)
            val s2 = midFilter.processSample(s1)
            val s3 = trebleFilter.processSample(s2)

            // قص العينة بأمان لمنع التشويش
            val clamped = s3.coerceIn(-1.0, 1.0)
            samples[i] = (clamped * 32767.0).toInt().toShort()
        }
    }

    fun reset() {
        bassFilter.reset()
        midFilter.reset()
        trebleFilter.reset()
    }
}
