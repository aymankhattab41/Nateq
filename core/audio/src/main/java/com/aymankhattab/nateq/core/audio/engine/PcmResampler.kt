package com.aymankhattab.nateq.core.audio.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * معالجة PCM قبل بث المقاطع الصوتية بمعيار موحد: خفض الاستريو إلى مونو
 * أو الحفاظ على القنوات، وإعادة أخذ العينات (استيفاء خطي بفاصلة ثابتة 16.16)
 * بين معدلات أخذ العينات المختلفة.
 *
 * ## سلامة التدفق عبر الدفعات الصغيرة:
 * - يفصل تقدير السعة عن قرار الاستهلاك؛ لا تسقط دفعات صغيرة بسبب تقريب الحجم.
 * - يحتفظ بحالة الطور وعينات الحدود عبر الدفعات لمنع التقطيع والتقبط.
 * - يمنع إخراج أي بايت فردي (المخرجات دائماً أطر PCM كاملة متوافقة).
 */
object PcmResampler {

    /**
     * يحول نافذة البيانات الصالحة [offset, offset+length) إلى مصفوفة جديدة.
     */
    fun convert(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        outChannels: Int = 1
    ): ByteArray {
        val total = convertedByteCount(
            pcm, offset, length, inSampleRate, inChannels,
            outSampleRate, outChannels
        )
        if (total == 0) return ByteArray(0)
        val out = ByteArray(total)
        val phase = LongArray(4)
        val written = convertInto(
            pcm, offset, length, inSampleRate, inChannels,
            outSampleRate, out, 0, phase, outChannels
        )
        return if (written == total) out else out.copyOf(written)
    }

    /**
     * يقدر السعة المطلوبة بالبايت التي قد تنتجها [convert]/[convertInto].
     * الحساب المطابق لصيغة الأطر الناتجة بالقسمة الصحيحة.
     */
    fun convertedByteCount(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        outChannels: Int = 1
    ): Int {
        if (length <= 0 || inSampleRate <= 0 || outSampleRate <= 0 ||
            inChannels <= 0 || outChannels <= 0
        ) {
            return 0
        }
        val end = (offset + length).coerceAtMost(pcm.size)
        if (offset < 0 || end <= offset) return 0
        val inFrameSize = 2 * inChannels
        val frames = (end - offset) / inFrameSize
        if (frames == 0) return 0
        val outFrameSize = 2 * outChannels
        return if (inSampleRate == outSampleRate) {
            frames * outFrameSize
        } else {
            (frames.toLong() * outSampleRate / inSampleRate).toInt() *
                outFrameSize
        }
    }

    /**
     * تقدير السعة الآمنة مع ضمان حجم فريم واحد على الأقل للدفعات الصغيرة
     * حتى لا تخصص مصفوفة صفرية للدفعات ذات الحجم < 1 إطار ناتج.
     */
    fun streamingCapacity(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        outChannels: Int = 1
    ): Int {
        val base = convertedByteCount(
            pcm, offset, length, inSampleRate, inChannels,
            outSampleRate, outChannels
        )
        val outFrameSize = 2 * outChannels
        return if (base == 0 && length >= 2 * inChannels) {
            outFrameSize
        } else {
            base
        }
    }

    /**
     * نسخة بدون مخزن طور مخصص: تستخدم طوراً طازجاً للدورة الواحدة.
     */
    fun convertInto(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        out: ByteArray,
        outOffset: Int
    ): Int = convertInto(
        pcm, offset, length, inSampleRate, inChannels,
        outSampleRate, out, outOffset, LongArray(4), 1
    )

    /**
     * يحول البيانات ويكتبها مباشرة في مخزن [out] عند [outOffset].
     * مفصول عن قرار الاستهلاك: لا يلغي الاستهلاك إذا كان التقدير الخارجي 0
     * طالما وُجدت أطر مدخلة ومساحة في مخزن الإخراج.
     */
    fun convertInto(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        out: ByteArray,
        outOffset: Int,
        phase: LongArray,
        outChannels: Int = 1
    ): Int {
        if (length <= 0 || inSampleRate <= 0 || outSampleRate <= 0 ||
            inChannels <= 0 || outChannels <= 0
        ) {
            return 0
        }
        val end = (offset + length).coerceAtMost(pcm.size)
        if (offset < 0 || end <= offset) return 0
        val inFrameSize = 2 * inChannels
        val inFrames = (end - offset) / inFrameSize
        if (inFrames <= 0) return 0
        val outFrameSize = 2 * outChannels
        val avail = out.size - outOffset
        if (outOffset < 0 || avail < outFrameSize) return 0

        // تطابق المعدل والقنوات (مونو إلى مونو)
        if (inSampleRate == outSampleRate && inChannels == 1 &&
            outChannels == 1
        ) {
            val copyFrames = min(inFrames, avail / 2)
            val copyLen = copyFrames * 2
            System.arraycopy(pcm, offset, out, outOffset, copyLen)
            phase[1] += copyFrames.toLong()
            if (phase.size >= 4 && copyFrames > 0) {
                phase[2] = 1L
                phase[3] = sampleAt(pcm, offset, copyFrames - 1).toLong()
            }
            return copyLen
        }

        // تطابق المعدل مع خفض قنوات
        if (inSampleRate == outSampleRate && inChannels > 1 &&
            outChannels == 1
        ) {
            val written = downmixInto(
                pcm, offset, inFrames * inFrameSize, inChannels,
                out, outOffset
            )
            phase[1] += inFrames.toLong()
            return written
        }

        // إعادة أخذ العينات
        val useDirectWindow = inChannels == 1
        val mono: ByteArray = if (useDirectWindow) {
            pcm
        } else {
            downmixToMono(pcm, offset, inFrames * inFrameSize, inChannels)
        }
        val monoOffset = if (useDirectWindow) offset else 0
        val monoLength = if (useDirectWindow) {
            inFrames * inFrameSize
        } else {
            mono.size
        }

        return resampleInto(
            mono, monoOffset, monoLength, inSampleRate, outSampleRate,
            out, outOffset, phase, outChannels
        )
    }

    /**
     * يفرغ أي أطر متبقية ويصفر حالة عينة الحد بعد اكتمال التدفق.
     */
    fun flush(
        outSampleRate: Int,
        out: ByteArray,
        outOffset: Int,
        phase: LongArray,
        outChannels: Int = 1
    ): Int {
        if (phase.size >= 4) {
            phase[2] = 0L
            phase[3] = 0L
        }
        return 0
    }

    /**
     * خفض القنوات إلى مونو بمتوسط متوازن تماماً بلا انحياز DC.
     */
    fun downmixToMono(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        channelCount: Int
    ): ByteArray {
        val frames = length / 2 / channelCount
        val out = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            var sum = 0L
            for (channel in 0 until channelCount) {
                sum += sampleAt(pcm, offset, frame * channelCount + channel)
            }
            val magnitude = (abs(sum) + channelCount / 2L) / channelCount
            val average = (if (sum >= 0L) magnitude else -magnitude).toInt()
            writeSample(out, frame, average)
        }
        return out
    }

    private fun downmixInto(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        channelCount: Int,
        out: ByteArray,
        outOffset: Int
    ): Int {
        val frames = length / 2 / channelCount
        if (frames == 0 || outOffset >= out.size) return 0
        val firstFrame = outOffset / 2
        val writable = min(frames, (out.size - outOffset) / 2)
        for (frame in 0 until writable) {
            var sum = 0L
            for (channel in 0 until channelCount) {
                sum += sampleAt(pcm, offset, frame * channelCount + channel)
            }
            val magnitude = (abs(sum) + channelCount / 2L) / channelCount
            val average = (if (sum >= 0L) magnitude else -magnitude).toInt()
            writeSample(out, firstFrame + frame, average)
        }
        return writable * 2
    }

    fun resample(pcm: ByteArray, inRate: Int, outRate: Int): ByteArray {
        val inFrames = pcm.size / 2
        if (inFrames == 0) return pcm
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ByteArray(outFrames * 2)
        val step = (inRate.toLong() shl 16) / outRate
        var position = 0L
        for (outFrame in 0 until outFrames) {
            val i0 = min((position ushr 16).toInt(), inFrames - 1)
            val i1 = min(i0 + 1, inFrames - 1)
            val fraction = (position and 0xFFFF).toInt()
            val s0 = sampleAt(pcm, i0)
            val s1 = sampleAt(pcm, i1)
            val delta = (s1 - s0).toLong()
            val interpolated =
                (s0 + ((delta * fraction + 0x8000L) shr 16)).toInt()
            writeSample(out, outFrame, interpolated)
            position += step
        }
        return out
    }

    private fun resampleInto(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inRate: Int,
        outRate: Int,
        out: ByteArray,
        outOffset: Int,
        phase: LongArray,
        outChannels: Int = 1
    ): Int {
        val inFrames = length / 2
        if (inFrames == 0 || outOffset >= out.size) {
            phase[1] += inFrames.toLong()
            return 0
        }
        val outFrameSize = 2 * outChannels
        val capacity = (out.size - outOffset) / outFrameSize
        if (capacity == 0) {
            phase[1] += inFrames.toLong()
            return 0
        }
        val step = (inRate.toLong() shl 16) / outRate
        if (step <= 0L) return 0
        val windowStart = phase[1] shl 16
        val windowEnd = (phase[1] + inFrames) shl 16
        var position = phase[0]
        var written = 0
        val firstFrame = outOffset / outFrameSize

        while (written < capacity && position < windowEnd) {
            val localPos = (position - windowStart).coerceAtLeast(0)
            val i0 = min((localPos ushr 16).toInt(), inFrames - 1)
            val i1 = min(i0 + 1, inFrames - 1)
            val fraction = (localPos and 0xFFFF).toInt()
            val s0 = sampleAt(pcm, offset, i0)
            val s1 = sampleAt(pcm, offset, i1)
            val delta = (s1 - s0).toLong()
            val interpolated =
                (s0 + ((delta * fraction + 0x8000L) shr 16)).toInt()

            if (outChannels == 1) {
                writeSample(out, firstFrame + written, interpolated)
            } else {
                writeSample(out, (firstFrame + written) * 2, interpolated)
                writeSample(out, (firstFrame + written) * 2 + 1, interpolated)
            }

            position += step
            written++
        }

        phase[0] = position
        phase[1] += inFrames.toLong()
        if (phase.size >= 4 && inFrames > 0) {
            phase[2] = 1L
            phase[3] = sampleAt(pcm, offset, inFrames - 1).toLong()
        }

        return written * outFrameSize
    }

    private fun sampleAt(pcm: ByteArray, frame: Int): Int =
        sampleAt(pcm, 0, frame)

    private fun sampleAt(pcm: ByteArray, offset: Int, frame: Int): Int {
        val index = offset + frame * 2
        val raw = (pcm[index].toInt() and 0xFF) or
            (pcm[index + 1].toInt() shl 8)
        return raw.toShort().toInt()
    }

    private fun writeSample(out: ByteArray, frame: Int, value: Int) {
        val clamped = value.coerceIn(
            Short.MIN_VALUE.toInt(),
            Short.MAX_VALUE.toInt()
        )
        val index = frame * 2
        out[index] = (clamped and 0xFF).toByte()
        out[index + 1] = (clamped shr 8).toByte()
    }
}
