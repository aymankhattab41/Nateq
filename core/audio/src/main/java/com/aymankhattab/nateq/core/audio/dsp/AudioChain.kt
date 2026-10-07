package com.aymankhattab.nateq.core.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * سلسلة المعالجة الصوتية الرقمية المجمّعة (ع٢٧-50).
 * تمرر عينات PCM 16-bit عبر الموازن والضاغط والصدى بتسلسل سلس وخالٍ من التسريب.
 */
class AudioChain {

    val equalizer = ParametricEq()
    val compressor = DynamicCompressor()
    val reverb = PcmReverb()

    var isEqEnabled: Boolean = false
    var isCompressorEnabled: Boolean = true
    var isReverbEnabled: Boolean = false

    private var sampleRate: Int = 22050

    fun configure(
        sampleRate: Int,
        eqEnabled: Boolean = false,
        compressorEnabled: Boolean = true,
        reverbEnabled: Boolean = false
    ) {
        this.sampleRate = sampleRate
        this.isEqEnabled = eqEnabled
        this.isCompressorEnabled = compressorEnabled
        this.isReverbEnabled = reverbEnabled

        equalizer.configure(sampleRate)
        compressor.configure(sampleRate)
        reverb.configure()
    }

    /**
     * معالجة مصفوفة عينات PCM 16-bit (ShortArray)
     */
    fun process(samples: ShortArray, length: Int = samples.size) {
        if (isEqEnabled) {
            equalizer.process(samples, length)
        }
        if (isReverbEnabled) {
            reverb.process(samples, length)
        }
        if (isCompressorEnabled) {
            compressor.process(samples, length)
        }
    }

    /**
     * معالجة مصفوفة بايتات PCM 16-bit بتعديلها في مكانها
     */
    fun processBytes(
        pcmBytes: ByteArray,
        offset: Int = 0,
        length: Int = pcmBytes.size
    ) {
        val shortCount = length / 2
        val shorts = ShortArray(shortCount)
        val buffer = ByteBuffer.wrap(pcmBytes, offset, length)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.asShortBuffer().get(shorts)
        process(shorts, shortCount)

        buffer.position(offset)
        buffer.asShortBuffer().put(shorts)
    }

    fun reset() {
        equalizer.reset()
        compressor.reset()
        reverb.reset()
    }
}
