package com.aymankhattab.nateq.core.audio.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * مولّد النغمات الصوتية التنبيهية (Earcons) برمجياً دون ملفات خارجية (ع٢٧-59).
 */
object EarconGenerator {

    enum class CueType {
        START,
        STOP,
        PAUSE,
        RESUME,
        ERROR,
        CLICK
    }

    /**
     * توليد مصفوفة عينات PCM 16-bit لنغمة تنبيهية
     */
    fun generateTone(
        cueType: CueType,
        sampleRate: Int = 22050
    ): ShortArray {
        val durationMs = when (cueType) {
            CueType.CLICK -> 15
            CueType.START -> 60
            CueType.STOP -> 50
            CueType.PAUSE -> 80
            CueType.RESUME -> 80
            CueType.ERROR -> 120
        }

        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val pcm = ShortArray(totalSamples)

        val freqStart = when (cueType) {
            CueType.START -> 440.0
            CueType.STOP -> 880.0
            CueType.PAUSE -> 600.0
            CueType.RESUME -> 400.0
            CueType.ERROR -> 220.0
            CueType.CLICK -> 1200.0
        }

        val freqEnd = when (cueType) {
            CueType.START -> 880.0
            CueType.STOP -> 440.0
            CueType.PAUSE -> 400.0
            CueType.RESUME -> 600.0
            CueType.ERROR -> 110.0
            CueType.CLICK -> 800.0
        }

        var phase = 0.0
        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples
            val currentFreq = freqStart + (freqEnd - freqStart) * progress
            val phaseIncrement = (2.0 * PI * currentFreq) / sampleRate
            phase += phaseIncrement

            // تطبيق Envelope للتنعيم ومنع الفرقعة عند البداية والنهاية
            val envelope = when {
                progress < 0.1 -> progress / 0.1
                progress > 0.8 -> (1.0 - progress) / 0.2
                else -> 1.0
            }

            val sample = sin(phase) * envelope * 0.8
            pcm[i] = (sample * 32767.0).toInt().toShort()
        }

        return pcm
    }
}
