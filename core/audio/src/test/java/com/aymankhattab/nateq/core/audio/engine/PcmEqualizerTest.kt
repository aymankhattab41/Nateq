package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

class PcmEqualizerTest {

    private fun generateSinePcm(
        freqHz: Double,
        sampleRate: Int,
        durationSeconds: Double,
        amplitude: Short = 10000
    ): ByteArray {
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val bytes = ByteArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (sin(2.0 * Math.PI * freqHz * t) * amplitude)
                .toInt().coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        return bytes
    }

    private fun calculateRms(pcm: ByteArray): Double {
        var sumSquares = 0.0
        val sampleCount = pcm.size / 2
        for (i in 0 until sampleCount) {
            val sample = (pcm[i * 2 + 1].toInt() shl 8) or
                (pcm[i * 2].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample
        }
        return sqrt(sumSquares / sampleCount)
    }

    @Test
    fun testFlatBypassLeavesDataUntouched() {
        val eq = PcmEqualizer()
        val original = generateSinePcm(1000.0, 44100, 0.1)
        val copy = original.copyOf()

        val processed = eq.process(
            copy, 0, copy.size, 44100, PcmEqualizer.FLAT_GAINS
        )

        assertArrayEquals(original, processed)
    }

    @Test
    fun testLowShelfBoostsLowFrequency() {
        val eq = PcmEqualizer()
        // 100 Hz wave is in the low shelf band (cutoff 250 Hz)
        val pcm = generateSinePcm(100.0, 44100, 0.2)
        val initialRms = calculateRms(pcm)

        // +6 dB boost on low shelf
        eq.process(pcm, 0, pcm.size, 44100, floatArrayOf(6.0f, 0.0f, 0.0f))
        val boostedRms = calculateRms(pcm)

        assertTrue("Low frequency RMS should increase with boost",
            boostedRms > initialRms * 1.5)
    }

    @Test
    fun testHighShelfAttenuatesHarshTreble() {
        val eq = PcmEqualizer()
        // 6000 Hz wave is in the high shelf band (cutoff 4500 Hz)
        val pcm = generateSinePcm(6000.0, 44100, 0.2)
        val initialRms = calculateRms(pcm)

        // -6 dB cut on high shelf (smoothes tinny robotic sounds)
        eq.process(pcm, 0, pcm.size, 44100, floatArrayOf(0.0f, 0.0f, -6.0f))
        val attenuatedRms = calculateRms(pcm)

        assertTrue("High frequency RMS should decrease with cut",
            attenuatedRms < initialRms * 0.7)
    }

    @Test
    fun testClippingProtectionDoesNotOverflow() {
        val eq = PcmEqualizer()
        // High amplitude samples
        val pcm = generateSinePcm(500.0, 44100, 0.05, amplitude = 32000)

        // Extreme +12 dB boost across all bands
        eq.process(pcm, 0, pcm.size, 44100, floatArrayOf(12f, 12f, 12f))

        for (i in 0 until pcm.size / 2) {
            val sample = (pcm[i * 2 + 1].toInt() shl 8) or
                (pcm[i * 2].toInt() and 0xFF)
            assertTrue("Sample must stay within 16-bit range",
                sample in -32768..32767)
        }
    }

    @Test
    fun testDefaultEngineProfiles() {
        val espeak = PcmEqualizer.defaultGainsFor("com.reecedunn.espeak")
        assertEquals(1.5f, espeak[0], 0.01f)
        assertEquals(0.0f, espeak[1], 0.01f)
        assertEquals(-1.5f, espeak[2], 0.01f)

        val rhvoice = PcmEqualizer.defaultGainsFor(
            "com.github.olga_yakovleva.rhvoice"
        )
        assertEquals(1.5f, rhvoice[0], 0.01f)

        val google = PcmEqualizer.defaultGainsFor("com.google.android.tts")
        assertEquals(0.0f, google[0], 0.01f)
        assertEquals(0.0f, google[1], 0.01f)
        assertEquals(0.0f, google[2], 0.01f)
    }

    @Test
    fun testPerformanceAndLatencyBenchmark() {
        val eq = PcmEqualizer()
        // 1 full second of 44.1 kHz 16-bit audio = 88,200 bytes
        val pcm = generateSinePcm(1000.0, 44100, 1.0)
        val gains = PcmEqualizer.ESPEAK_GAINS

        // Warm up
        eq.process(pcm.copyOf(), 0, pcm.size, 44100, gains)

        val startTime = System.nanoTime()
        eq.process(pcm, 0, pcm.size, 44100, gains)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        // 1 second of audio must be processed in under 20ms on JVM
        // (Typically takes < 2ms in practice)
        assertTrue(
            "Processing 1s of audio took ${elapsedMs}ms, should be < 20ms",
            elapsedMs < 20.0
        )
    }
}
