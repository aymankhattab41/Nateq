package com.aymankhattab.nateq.core.audio.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * معالجة PCM قبل بث المقاطع المختلطة اللغات بمعيارٍ صوتي موحّد: خفض الاستريو
 * إلى مونو ثم إعادة أخذ العينات (استيفاء خطي int16 بلا نقطة عائمة عالية
 * التكلفة على المحور الزمني) بين معدلات أخذ عينات مختلفة قد ينتجها محركان
 * مختلفان — فيُدفع كل المخرجات لـ [NateqTtsService] بمعدلٍ وقنواتٍ واحدين دون
 * تقبّط نبرة الصوت. منطق نقي قابل للاختبار مباشرة بلا أي اعتماد Android.
 */
object PcmResampler {

    /**
     * @param pcm مخزن PCM 16-bit (LE) يضمّ بيانات صالحة داخل
     *  [offset, offset+length)
     * @param offset إزاحة بدء البيانات الصالحة بالبايت
     * @param length طول البيانات الصالحة بالبايت — قد يقلّ عن طول
     *  المصفوفة إذا جاءت الشريحة من مسبحٍ مُعاد استخدامه، فلا تُعالج
     *  القمامة والبيانات المتبقية من نطق سابق (الضجيج الزاوي)
     * @param inSampleRate معدل عينات البيانات الصالحة
     * @param inChannels قنوات البيانات الصالحة
     * @param outSampleRate معدل الإخراج المطلوب
     * @return PCM 16-bit أحادي بقناة واحدة بمعدل [outSampleRate].
     *  عند `length <= 0` أو معدل/قنوات غير صالحة تُرجع مصفوفة فارغة
     *  (لا تمريرَ للمخزن كما كان — يبقى الإسكات أصحّ من بثّ قمامة).
     */
    fun convert(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int
    ): ByteArray {
        val total = convertedByteCount(
            pcm, offset, length, inSampleRate, inChannels, outSampleRate
        )
        if (total == 0) return ByteArray(0)
        val out = ByteArray(total)
        val written = convertInto(
            pcm, offset, length, inSampleRate, inChannels,
            outSampleRate, out, 0
        )
        return if (written == total) out else out.copyOf(written)
    }

    /**
     * الطول بالبايت الذي ستنتجه [convert]/[convertInto] للنافذة الصالحة نفسها
     * (فريمات مونو × 2 بايت)، قبل أي كتابة — ليحسب المتصل سعة المخزن مسبقاً
     * من مسبحه دون إهدار ولا تجاوز. يعيد 0 عند النافذة الفارغة أو المعاملات
     * غير الصالحة.
     */
    fun convertedByteCount(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int
    ): Int {
        if (length <= 0 || inSampleRate <= 0 || outSampleRate <= 0 ||
            inChannels <= 0
        ) {
            return 0
        }
        val end = (offset + length).coerceAtMost(pcm.size)
        if (offset < 0 || end <= offset) return 0
        val frames = (end - offset) / 2 / inChannels
        if (frames == 0) return 0
        return if (inSampleRate == outSampleRate) {
            frames * 2
        } else {
            (frames.toLong() * outSampleRate / inSampleRate).toInt() * 2
        }
    }

    /**
     * نسخة [convert] التي تكتب الناتج في مخزنٍ مقدَّم [out] عند [outOffset]
     * بدل إنشاء مصفوفة جديدة — يستحضر المتصل السعة عبر
     * [convertedByteCount] من مسبحه فيحوّل مسار البث كاملاً خالياً من
     * تخصيص المصفوفات (بند تسريع النطق). الكتابة **لا تتجاوز حدود [out]**
     * أبداً: إن ضاق المخزن تُكتب البيانات التي تتسع ويُعاد الطول المكتوب
     * فقط (أصغر من المطلوب) — والنقص يظهر كنهاية نطق مبتورة لا كاستثناء.
     *
     * الناتج مماثل حرفياً لناتج [convert] عند سعةٍ كافية (يعيد [required]).
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
    ): Int {
        val required = convertedByteCount(
            pcm, offset, length, inSampleRate, inChannels, outSampleRate
        )
        if (required == 0 || outOffset < 0 || outOffset >= out.size) return 0
        val end = (offset + length).coerceAtMost(pcm.size)
        // حالة مباشرة (لا إعادة عينات ولا خفض قنوات): نسخ النافذة الصالحة
        // فوراً دون أي وسيط — أرخص مسار إطلاقاً في مسار البث.
        if (inSampleRate == outSampleRate && inChannels == 1) {
            val copyLen = (end - offset).coerceAtMost(out.size - outOffset)
            System.arraycopy(pcm, offset, out, outOffset, copyLen)
            return copyLen
        }
        val mono: ByteArray = if (inChannels == 1) {
            pcm.copyOfRange(offset, end)
        } else {
            downmixToMono(pcm, offset, end - offset, inChannels)
        }
        if (inSampleRate == outSampleRate) {
            val copyLen = mono.size.coerceAtMost(out.size - outOffset)
            System.arraycopy(mono, 0, out, outOffset, copyLen)
            return copyLen
        }
        val resampled = resample(mono, inSampleRate, outSampleRate)
        val copyLen = resampled.size.coerceAtMost(out.size - outOffset)
        System.arraycopy(resampled, 0, out, outOffset, copyLen)
        return copyLen
    }

    /** خفض القنوات المتعددة إلى مونو بمتوسط العينات المتزامنة
     * (تُسقط اليُسر/اليمين بلا تتبع طوري خاص — مقبول لنطق الكلام).
     * يعالج فقط نافذة البيانات الصالحة [offset, offset+length). */
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
            // متوسّط متوازن تماماً (نصفاً بعيداً عن الصفر بإشارةٍ ثابتة)
            // بلا انحياز DC: خطأ ±0.5 متناوب صِفر-أفقي. بدل الـ (+1)/2
            // السابق الذي زاد الموجب نحو +0.5 (انحياز مسموع) وأفسد الزوج
            // السالب المطابق (-1,-1) إلى 0 (إسكات الطقطقة الخفيفة)؛
            // والإزاحة shr 1 المقتَرحة وحدها تُطبق -0.5 على القيم كليهما
            // فلا تُلغي الانحياز بل تقلبه، لذا يُعتمد نصفٌ بعيدٌ.
            val magnitude = (abs(sum) + channelCount / 2L) / channelCount
            val average = (if (sum >= 0L) magnitude else -magnitude).toInt()
            writeSample(out, frame, average)
        }
        return out
    }

    /** إعادة أخذ العينات باستيفاء خطي: إخراج [outFrames] وفق النسبة بين
     *  المعدّلين، كلياً بفاصلة ثابتة 16.16 على المحور الزمني (لا Double/Float
     *  ولا قسمة/roundToInt داخل الحلقة)، مع كبح عند حواف الإخراج. */
    fun resample(pcm: ByteArray, inRate: Int, outRate: Int): ByteArray {
        val inFrames = pcm.size / 2
        if (inFrames == 0) return pcm
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ByteArray(outFrames * 2)
        // نسبة إعادة الأخذ كاملة 16.16: الموضع كامل 16 بت علوية والكسر 16
        // سفلية. يُتراكم جمعياً (عملية Long واحدة لكل فريم) بدل مضاعفة عائمةٍ
        // لكل عينة، فيبقى المنتصف [0, 65535] جاهزاً للاستيفاء.
        val step = (inRate.toLong() shl 16) / outRate
        var position = 0L
        for (outFrame in 0 until outFrames) {
            // مكبح الحافة: بعد التراكم الكسري قد يبلغ الموضع فريم المدخل الأخير
            // أو يتجاوزه بأقل من فريمٍ — لا يُخرج موضع القراءة عن
            // حدود المصفوفة.
            val i0 = min((position ushr 16).toInt(), inFrames - 1)
            val i1 = min(i0 + 1, inFrames - 1)
            val fraction = (position and 0xFFFF).toInt()
            val s0 = sampleAt(pcm, i0)
            val s1 = sampleAt(pcm, i1)
            val delta = (s1 - s0).toLong()
            // استيفاء: s0 + Δ·frac/65536 — حسمٌ +0x8000 يُطابق roundToInt
            // (نحو +∞) لكلتا الإشارتين، فالصيغة متطابقة الحرف كما كانت.
            val interpolated =
                (s0 + ((delta * fraction + 0x8000L) shr 16)).toInt()
            writeSample(out, outFrame, interpolated)
            position += step
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, frame: Int): Int =
        sampleAt(pcm, 0, frame)

    private fun sampleAt(pcm: ByteArray, offset: Int, frame: Int): Int {
        val index = offset + frame * 2
        val raw = (pcm[index].toInt() and 0xFF) or
            (pcm[index + 1].toInt() shl 8)
        // ترميز موقّع: عينات int16 من الملف مفكوكة كقيم موقّعة (تتجه للسالب
        // فوق 0x7FFF) حتى تستقيم المتوسطات والاستيفاء مع كبح [writeSample].
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