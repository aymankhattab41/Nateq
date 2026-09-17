package com.aymankhattab.nateq.core.audio.engine

/**
 * إدماجٌ سلس (crossfade overlap-add) بين شريحتَين متتاليتين من بثّ مونو
 * 16-بت: عند الحَدّ تنتقل الشريحةُ الجديدة من ذيلِ سابقتها بانحدارٍ خطي
 * بدل قفزةٍ حادّة تُسمع «كلِك» (شائع عند تقطيع الـ PCM المتابع).
 *
 * ## كيف يجمع بلا تكرار؟
 * كل شريحة تحجز آخر [frames] عيّنةً منها (لا تُبثّ بعد) ثم تُدمجها
 * [process] مع رأس الشريحة التالية فيُبثّ الاندماجُ مرةً واحدة —
 * latency ≜ [frames] عيّنة فقط، وبدون إرجاعٍ مزدوج للذيل نفسه.
 * بعد انتهاء كل المقاطع/البثّ يجب بثّ الذيل المحجوز عبر [copyPending]
 * (انظر [NateqTtsService.emitCrossfadeTail]).
 *
 * كل مقطعٍ يملك كائنَه (لا اندماج بين محتوَيات مقاطع مختلفة الصوت).
 * بلا تخصيص في مسار البثّ الحار (المخزن المحجوز أقصى حجم مسبقاً).
 */
internal class ChunkCrossfader(private val frames: Int) {

    /** ذيل الشريحة السابقة المحجوز (عيّنات، قُسّم فعلياً حتى [pendingLen]). */
    private val pending = IntArray(frames)

    /** عدد عيّنات الذيل المحجوز (< [frames] فقط إن قصُرت الشريحة). */
    private var pendingLen = 0

    /** يدمج رأس [pcm] (حتى [written] بايت = عيّنات 16-بت مونو) مع الذيل
     *  المحجوز، يعدّلها في مكانها، يحجز آخر عيّناتٍ من الناتج كذيلٍ
     *  للشريحة التالية، ويرجع عدد البايتات الجاهزة للبثّ من إزاحة 0.
     */
    fun process(pcm: ByteArray, written: Int): Int {
        if (written < 2) return 0
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
        // الناتجُ الجاهز: كل الشريحة عدا الذيل المحجوز؛ رأسُها مدمجٌ
        // بسابقتها (دمجٌ ضمن النطاق المبثوث لا تكراراً له).
        return (samples - hold) * 2
    }

    /** عدد عيّنات الذيل المحجوز المنتظر بثّه بعد انتهاء المقاطع. */
    val pendingFrames: Int get() = pendingLen

    /** ينسخ الذيل المحجوز (عيّنات 16-بت) إلى [dst] بدءاً من [dstOffset]
     *  بايت للبثّ النهائي — الشريحةُ الأخيرة من البثّ تصل صفرَ قفزة. */
    fun copyPending(dst: ByteArray, dstOffset: Int) {
        for (i in 0 until pendingLen) {
            val sample = pending[i]
            val lo = sample and 0xFF
            val hi = (sample ushr 8) and 0xFF
            dst[dstOffset + i * 2] = lo.toByte()
            dst[dstOffset + i * 2 + 1] = hi.toByte()
        }
    }

    /** يصفّر الذيل — يُستدعى عند بداية نطقٍ جديد. */
    fun reset() {
        pendingLen = 0
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        return (pcm[index * 2 + 1].toInt() shl 8) or
            (pcm[index * 2].toInt() and 0xFF)
    }

    private fun writeSample(pcm: ByteArray, index: Int, value: Double) {
        val sample = value.toInt().coerceIn(-32768, 32767)
        val lo = sample and 0xFF
        val hi = (sample ushr 8) and 0xFF
        pcm[index * 2] = lo.toByte()
        pcm[index * 2 + 1] = hi.toByte()
    }
}