package com.aymankhattab.nateq.core.audio.engine

/**
 * معالج تدفق الصوت وخافض النقر (declicker / crossfader) لبث المقاطع
 * الصوتية في [NateqTtsService].
 *
 * ## فصل حدود النقل عن حدود المقاطع:
 * - دفعات النقل (transport chunks) ضمن المقطع الواحد متصلة زمنياً؛
 *   تمرر كاملة بنسبة 1:1 مع الحفاظ التام على العينات والزمن (بلا نقص
 *   ولا اقتطاع ولا تداخل مصطنع يضيع الصوت).
 * - عند حدود المقاطع الحقيقية (أو تبديل الصوت/المحرك)، يطبق انسياب
 *   ميكرو لتخفيف قفزة السعة ومنع النقر (click) دون حذف عينات أو تقصير الزمن.
 * - لا يطبق overlap-add إلا عند طلب تداخل صريح ومحدد عبر [allowOverlapAdd].
 */
internal class ChunkCrossfader(
    private val frames: Int,
    private val allowOverlapAdd: Boolean = false
) {

    /** ذيل الشريحة السابقة المحجوز لنمط overlap-add الصريح. */
    private val pending = IntArray(frames)

    /** عدد عينات الذيل المحجوز في نمط overlap-add. */
    private var pendingLen = 0

    /** مؤشر وجود حد بين مقطعين صوتيين يستوجب تنعيم الانتقال. */
    private var hasBoundary = false

    /** عينة نهاية المقطع السابق للبدء منها في التنعيم. */
    private var boundarySample = 0

    /** آخر عينة تم بثها من التدفق الحالي. */
    private var lastEmittedSample = 0

    /** عدد العينات المتبقية لتنعيم الانتقال عند الحد. */
    private var declickRemaining = 0

    /** إجمالي نافذة التنعيم المعتمدة للحد الحالي. */
    private var declickTotal = 0

    /**
     * يعالج دفعة نقل صوتية [pcm] حتى [written] بايت (16-بت مونو).
     * في النمط الافتراضي يحافظ على كامل العينات والزمن وينعم الحد
     * إن وُجد انتقال صوتي سابق.
     */
    fun process(pcm: ByteArray, written: Int): Int {
        if (written < 2) return 0
        return if (allowOverlapAdd) {
            processOverlapAdd(pcm, written)
        } else {
            processPreserving(pcm, written)
        }
    }

    /**
     * مسار الحفاظ التام على العينات والزمن لتدفق النقل المتصل.
     */
    private fun processPreserving(pcm: ByteArray, written: Int): Int {
        val samples = written / 2
        if (hasBoundary && declickRemaining > 0 && samples > 0) {
            val toProcess = minOf(samples, declickRemaining)
            for (i in 0 until toProcess) {
                val oldVal = boundarySample.toDouble()
                val newVal = readSample(pcm, i).toDouble()
                val progress = declickTotal - declickRemaining + i
                val t = (progress + 1.0) / (declickTotal + 1.0)
                writeSample(pcm, i, oldVal * (1.0 - t) + newVal * t)
            }
            declickRemaining -= toProcess
            if (declickRemaining <= 0) {
                hasBoundary = false
            }
        }
        if (samples > 0) {
            lastEmittedSample = readSample(pcm, samples - 1)
        }
        return samples * 2
    }

    /**
     * مسار overlap-add الصريح عند طلبه فقط.
     */
    private fun processOverlapAdd(pcm: ByteArray, written: Int): Int {
        val samples = written / 2
        val overlap = minOf(pendingLen, samples)
        if (overlap > 0) {
            for (i in 0 until overlap) {
                val oldSample = pending[i].toDouble()
                val newSample = readSample(pcm, i).toDouble()
                val t = (i + 1.0) / (overlap + 1)
                writeSample(pcm, i, oldSample * (1 - t) + newSample * t)
            }
        }
        val hold = minOf(frames, samples)
        if (hold > 0) {
            val start = samples - hold
            for (i in 0 until hold) {
                pending[i] = readSample(pcm, start + i)
            }
            pendingLen = hold
        }
        return (samples - hold) * 2
    }

    /** عدد عينات الذيل المحجوز المنتظر بثه. */
    val pendingFrames: Int
        get() = if (allowOverlapAdd) pendingLen else 0

    /**
     * ينسخ الذيل المحجوز إلى [dst] بدءاً من [dstOffset].
     */
    fun copyPending(dst: ByteArray, dstOffset: Int) {
        if (!allowOverlapAdd) return
        for (i in 0 until pendingLen) {
            val sample = pending[i]
            val lo = sample and 0xFF
            val hi = (sample ushr 8) and 0xFF
            dst[dstOffset + i * 2] = lo.toByte()
            dst[dstOffset + i * 2 + 1] = hi.toByte()
        }
        pendingLen = 0
    }

    /**
     * يبلغ باكتمال مقطع صوتي حقيقي للانتقال للمقطع التالي أو تبديل الصوت.
     */
    fun onSegmentComplete() {
        if (!allowOverlapAdd) {
            hasBoundary = true
            boundarySample = lastEmittedSample
            declickRemaining = frames
            declickTotal = frames
        }
    }

    /** يبلغ بتبديل الصوت/المحرك بين المقاطع. */
    fun onVoiceSwitch() {
        onSegmentComplete()
    }

    /**
     * يبلغ بتخطي مقطع متعثر: تصفير حالة الحد لمنع تسريب تشويه
     * للمقاطع اللاحقة.
     */
    fun onSegmentSkipped() {
        if (!allowOverlapAdd) {
            hasBoundary = false
            declickRemaining = 0
            boundarySample = 0
        }
    }

    /**
     * يصفر كافة الحالات والذيل المحجوز عند بداية الطلب أو الإلغاء والفشل.
     */
    fun reset() {
        pendingLen = 0
        hasBoundary = false
        boundarySample = 0
        lastEmittedSample = 0
        declickRemaining = 0
        declickTotal = 0
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = pcm[index * 2 + 1].toInt() shl 8
        return (lo or hi).toShort().toInt()
    }

    private fun writeSample(pcm: ByteArray, index: Int, value: Double) {
        val sample = value.toInt().coerceIn(-32768, 32767)
        val lo = sample and 0xFF
        val hi = (sample ushr 8) and 0xFF
        pcm[index * 2] = lo.toByte()
        pcm[index * 2 + 1] = hi.toByte()
    }
}
