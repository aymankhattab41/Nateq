package com.aymankhattab.nateq.engine

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * معالجة PCM قبل بث المقاطع المختلطة اللغات بمعيارٍ صوتي موحّد: خفض الاستريو
 * إلى مونو ثم إعادة أخذ العينات (استيفاء خطي int16 بلا نقطة عائمة عالية
 * التكلفة على المحور الزمني) بين معدلات أخذ عينات مختلفة قد ينتجها محركان
 * مختلفان — فيُدفع كل المخرجات لـ [NateqTtsService] بمعدلٍ وقنواتٍ واحدين دون
 * تقبّط نبرة الصوت. منطق نقي قابل للاختبار مباشرة بلا أي اعتماد Android.
 */
object PcmResampler {

    /**
     * @param pcm بيانات PCM 16-bit (LE) بمعدل [inSampleRate] وقنوات [inChannels]
     * @return PCM 16-bit أحادي بقناته الواحدة، بمعدل [outSampleRate].
     *  الحالات التافهة (معدلان متساويان/مدخل أحادي/مدخل صفر) تُرجع كما هي
     *  بلا نسخٍ مكلف.
     */
    fun convert(
        pcm: ByteArray,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int
    ): ByteArray {
        if (pcm.isEmpty() || inSampleRate <= 0 || outSampleRate <= 0 || inChannels <= 0) {
            return pcm
        }
        val mono = if (inChannels == 1) pcm else downmixToMono(pcm, inChannels)
        if (inSampleRate == outSampleRate) return mono
        return resample(mono, inSampleRate, outSampleRate)
    }

    /** خفض القنوات المتعددة إلى مونو بمتوسط العينات المتزامنة (تُسقط اليُسر/اليمين
     *  بلا تتبع طوري خاص — مقبول لنطق الكلام). */
    fun downmixToMono(pcm: ByteArray, channelCount: Int): ByteArray {
        val frames = pcm.size / 2 / channelCount
        val out = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            var sum = 0L
            for (channel in 0 until channelCount) {
                sum += sampleAt(pcm, frame * channelCount + channel)
            }
            val average = ((sum + channelCount / 2L) / channelCount)
            writeSample(out, frame, average.toInt())
        }
        return out
    }

    /** إعادة أخذ العينات باستيفاء خطي: إخراج [outFrames] وفق النسبة بين
     *  المعدّلين، مع كبح عند حواف الإخراج. */
    fun resample(pcm: ByteArray, inRate: Int, outRate: Int): ByteArray {
        val inFrames = pcm.size / 2
        if (inFrames == 0) return pcm
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ByteArray(outFrames * 2)
        val step = inRate.toDouble() / outRate
        for (outFrame in 0 until outFrames) {
            val position = outFrame * step
            val i0 = position.toInt()
            val i1 = min(i0 + 1, inFrames - 1)
            val fraction = (position - i0).toFloat()
            val s0 = sampleAt(pcm, i0)
            val s1 = sampleAt(pcm, i1)
            val interpolated = (s0 + (s1 - s0) * fraction).roundToInt()
            writeSample(out, outFrame, interpolated)
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, frame: Int): Int {
        val index = frame * 2
        val raw = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
        // ترميز موقّع: عينات int16 من الملف مفكوكة كقيم موقّعة (تتجه للسالب
        // فوق 0x7FFF) حتى تستقيم المتوسطات والاستيفاء مع كبح [writeSample].
        return raw.toShort().toInt()
    }

    private fun writeSample(out: ByteArray, frame: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val index = frame * 2
        out[index] = (clamped and 0xFF).toByte()
        out[index + 1] = (clamped shr 8).toByte()
    }
}