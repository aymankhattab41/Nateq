package com.aymankhattab.nateq.core.audio.announcement

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.abs

/**
 * توليد موجات PCM للمؤثرات الصوتية (Audio Cues) برمجياً بدون ملفات أصول.
 *
 * الموجات محددة تماماً (deterministic) فيمكن اختبارها بالطول والصمت.
 * كل موجة mono 16-bit @ 44.1 kHz مع غلالة هجوم (attack envelope)
 * لمنع صوت النقر عند الحدود.
 */
internal object CueSynth {

    const val SAMPLE_RATE = 44100

    fun synthesize(cue: AudioCue): ShortArray {
        val floats = when (cue.type) {
            CueType.TIME_HOURLY -> hourlyChime(cue.soundName)
            CueType.BATTERY_CHARGING -> batteryCharging()
            CueType.BATTERY_DISCONNECTED -> batteryDisconnected()
            CueType.BATTERY_FULL -> batteryFull()
            CueType.BATTERY_LOW -> batteryLow()
        }
        return floatsToShort(floats)
    }

    fun durationMs(cue: AudioCue): Int = when (cue.type) {
        CueType.TIME_HOURLY -> when (cue.soundName) {
            "digital_chime" -> 650
            "soft_ding" -> 950
            else -> 1250
        }
        CueType.BATTERY_CHARGING -> 500
        CueType.BATTERY_DISCONNECTED -> 550
        CueType.BATTERY_FULL -> 650
        CueType.BATTERY_LOW -> 620
    }

    private fun hourlyChime(soundName: String?): FloatArray =
        when (soundName) {
            "digital_chime" -> digitalChime()
            "soft_ding" -> softDing()
            else -> classicBell()
        }

    private fun classicBell(): FloatArray {
        val f0 = 523.25
        val dur = 1.25
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val freqs = doubleArrayOf(f0, f0 * 2.76, f0 * 4.07, f0 * 7.02)
        val gains = doubleArrayOf(1.0, 0.55, 0.35, 0.18)
        val tau = 0.55
        val peak = 0.7
        val atk = 0.005
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = (t / atk).coerceAtMost(1.0)
            var s = 0.0
            for (j in freqs.indices) {
                s += gains[j] * sin(2.0 * PI * freqs[j] * t)
            }
            floats[i] = (s * env * exp(-t / tau) * peak).toFloat()
        }
        return floats
    }

    private fun digitalChime(): FloatArray {
        val n = (0.65 * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val seg1 = (0.18 * SAMPLE_RATE).toInt()
        val gap = (0.08 * SAMPLE_RATE).toInt()
        val tau = 0.3
        val peak = 0.65
        val atk = 0.005

        for (i in 0 until seg1.coerceAtMost(n)) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = (t / atk).coerceAtMost(1.0)
            floats[i] = (sin(2.0 * PI * 880.0 * t) *
                exp(-t / tau) * env * peak).toFloat()
        }
        for (i in seg1 until (seg1 + gap).coerceAtMost(n)) {
            floats[i] = 0f
        }
        val start2 = seg1 + gap
        for (i in start2 until (start2 + seg1).coerceAtMost(n)) {
            val t = (i - start2).toDouble() / SAMPLE_RATE
            val env = (t / atk).coerceAtMost(1.0)
            floats[i] = (sin(2.0 * PI * 1174.66 * t) *
                exp(-t / tau) * env * peak).toFloat()
        }
        return floats
    }

    private fun softDing(): FloatArray {
        val dur = 0.95
        val f0 = 1046.5
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val tau = 0.6
        val peak = 0.55
        val atk = 0.003
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = (t / atk).coerceAtMost(1.0)
            floats[i] = (sin(2.0 * PI * f0 * t) *
                exp(-t / tau) * env * peak).toFloat()
        }
        return floats
    }

    private fun batteryCharging(): FloatArray {
        val dur = 0.5
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val fStart = 440.0
        val fEnd = 880.0
        val riseDur = 0.4
        val peak = 0.68
        val atk = 0.01
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = if (t <= riseDur) {
                (t / atk).coerceAtMost(1.0) *
                    exp(-(t - riseDur) / 0.08).coerceAtMost(1.0)
            } else {
                0.0
            }
            val frac = (t / riseDur).coerceIn(0.0, 1.0)
            val freq = fStart + (fEnd - fStart) * frac
            floats[i] = (sin(2.0 * PI * freq * t) * env * peak).toFloat()
        }
        return floats
    }

    private fun batteryDisconnected(): FloatArray {
        val dur = 0.55
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val fStart = 880.0
        val fEnd = 330.0
        val fallDur = 0.45
        val peak = 0.68
        val atk = 0.01
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = if (t <= fallDur) {
                (t / atk).coerceAtMost(1.0) *
                    exp(-(t - fallDur) / 0.1).coerceAtMost(1.0)
            } else {
                0.0
            }
            val frac = (t / fallDur).coerceIn(0.0, 1.0)
            val freq = fStart + (fEnd - fStart) * frac
            floats[i] = (sin(2.0 * PI * freq * t) * env * peak).toFloat()
        }
        return floats
    }

    private fun batteryFull(): FloatArray {
        val dur = 0.65
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val notes = doubleArrayOf(523.25, 659.25, 783.99)
        val noteDur = 0.15
        val peak = 0.62
        val atk = 0.005
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val idx = ((t / noteDur).toInt()).coerceIn(0, notes.size - 1)
            val tNote = t - idx * noteDur
            val env = (tNote / atk).coerceAtMost(1.0) *
                exp(-tNote / 0.08).coerceAtMost(1.0)
            floats[i] = (sin(2.0 * PI * notes[idx] * t) *
                env * peak).toFloat()
        }
        return floats
    }

    private fun batteryLow(): FloatArray {
        val dur = 0.62
        val n = (dur * SAMPLE_RATE).toInt()
        val floats = FloatArray(n)
        val noteDur = 0.22
        val gapDur = 0.10
        val notes = doubleArrayOf(392.0, 330.0)
        val peak = 0.6
        val atk = 0.005
        val note2Start = noteDur + gapDur
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val s = when {
                t < noteDur -> {
                    val env = (t / atk).coerceAtMost(1.0) *
                        exp(-(t - noteDur) / 0.06).coerceAtMost(1.0)
                    sin(2.0 * PI * notes[0] * t) * env
                }
                t < note2Start -> 0.0
                else -> {
                    val tN = t - note2Start
                    val env = (tN / atk).coerceAtMost(1.0) *
                        exp(-tN / 0.08).coerceAtMost(1.0)
                    sin(2.0 * PI * notes[1] * t) * env
                }
            }
            floats[i] = (s * peak).toFloat()
        }
        return floats
    }

    private fun floatsToShort(src: FloatArray): ShortArray {
        val maxAbs = src.maxOfOrNull { abs(it) }?.coerceAtLeast(0.001f) ?: 1f
        val scale = 0.7f / maxAbs
        return ShortArray(src.size) { i ->
            (src[i] * scale * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}
