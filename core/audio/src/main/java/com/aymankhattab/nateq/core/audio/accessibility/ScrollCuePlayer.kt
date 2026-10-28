package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.sin

/**
 * مشغّل نغمات موضع التمرير في القوائم (ع٥٦-أمر 3).
 * يولد 12 نغمة PCM برمجياً بترددات 300Hz إلى 900Hz.
 */
object ScrollCuePlayer {

    @Volatile
    var appContext: Context? = null

    const val RATE_LIMIT_MS = 150L
    private const val SAMPLE_RATE = 16000
    private const val DURATION_MS = 60
    private const val NUM_TONES = 12

    private var lastPlayTime = 0L

    // تخزين مسبق لعينات النغمات الـ 12
    private val tones: Array<ShortArray> by lazy {
        Array(NUM_TONES) { i ->
            generateToneSamples(
                freq = 300.0 + (i * (600.0 / (NUM_TONES - 1))),
                durationMs = DURATION_MS,
                sampleRate = SAMPLE_RATE
            )
        }
    }

    /**
     * حساب نسبة موضع التمرير (0.0 إلى 1.0).
     */
    fun calculateRatio(
        fromIndex: Int,
        itemCount: Int,
        scrollY: Int = 0,
        maxScrollY: Int = 0
    ): Float {
        return when {
            itemCount >= 10 -> {
                ((fromIndex + 1).toFloat() / itemCount).coerceIn(0f, 1f)
            }
            maxScrollY > 0 -> {
                (scrollY.toFloat() / maxScrollY).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    /**
     * تحويل النسبة إلى فهرس النغمة (0 إلى 11).
     */
    fun ratioToToneIndex(ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        return (clamped * (NUM_TONES - 1)).toInt().coerceIn(0, NUM_TONES - 1)
    }

    /**
     * تشغيل نغمة الموضع بحسب النسبة مع مراعاة تقييد المعدل والكلام.
     */
    fun onScroll(
        ratio: Float,
        isSpeaking: Boolean = false,
        volume: Float = 0.8f,
        now: Long = SystemClock.uptimeMillis(),
        context: Context? = null
    ): Boolean {
        val ctx = context ?: appContext
        if (ctx != null && AudioEnvironmentPolicy.shouldMuteCues(ctx)) {
            return false
        }
        if (now - lastPlayTime < RATE_LIMIT_MS) {
            return false
        }

        val toneIndex = ratioToToneIndex(ratio)
        val isEdge = toneIndex == 0 || toneIndex == NUM_TONES - 1

        // إيقاف النغمات أثناء الكلام إلا في البداية والنهاية
        if (isSpeaking && !isEdge) {
            return false
        }

        lastPlayTime = now
        playTone(toneIndex, volume, ctx)
        return true
    }

    fun playTone(
        index: Int,
        volume: Float = 0.5f,
        context: Context? = null
    ) {
        val ctx = context ?: appContext
        if (ctx != null && AudioEnvironmentPolicy.shouldMuteCues(ctx)) {
            return
        }
        val samples = tones.getOrNull(index) ?: return
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val bufferSize = samples.size * 2
            val track = AudioTrack(
                audioAttributes,
                format,
                bufferSize,
                AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.setVolume(volume.coerceIn(0f, 1f))
            track.write(samples, 0, samples.size)
            track.play()
        } catch (_: Exception) {
            // تجاهل أخطاء مسار الصوت في بيئات الاختبار
        }
    }

    private fun generateToneSamples(
        freq: Double,
        durationMs: Int,
        sampleRate: Int
    ): ShortArray {
        val numSamples = (durationMs * sampleRate) / 1000
        val buffer = ShortArray(numSamples)
        val fadeSamples = numSamples / 10

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val angle = 2.0 * Math.PI * freq * t
            var amplitude = sin(angle)

            // تدرج في البداية والنهاية لمنع طقطقة الصوت
            if (i < fadeSamples) {
                amplitude *= (i.toDouble() / fadeSamples)
            } else if (i > numSamples - fadeSamples) {
                amplitude *= ((numSamples - i).toDouble() / fadeSamples)
            }

            buffer[i] = (amplitude * Short.MAX_VALUE * 0.7).toInt().toShort()
        }
        return buffer
    }

    @androidx.annotation.VisibleForTesting
    fun resetLastPlayTimeForTesting() {
        lastPlayTime = 0L
    }
}
