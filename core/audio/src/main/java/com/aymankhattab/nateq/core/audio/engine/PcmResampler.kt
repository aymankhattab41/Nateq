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
            // **بند 2.6 (محاذاة زوجية):** كان الطول المنسوخ قد يأتي فردياً
            // (نافذة بفريمٍ ناقص) فيُعطى الناتجُ عددَ بايتاتٍ فردياً يخالف
            // تقدير convertedByteCount (> اللازم) ويبعث فريماً مشوهاً يعتمده
            // المتلقي صوتاً — تُقصّ النافذة إلى فريماتٍ كاملة (زوجية) دائماً.
            val avail = min(end - offset, out.size - outOffset)
            val copyLen = avail - (avail and 1)
            System.arraycopy(pcm, offset, out, outOffset, copyLen)
            return copyLen
        }
        // معدل واحد: خفض القنوات مباشرةً في مخزن المرسل بلا مونو وسيط
        // ولا نسخة — تُطوى القنوات في موضع الإخراج ثم يُعاد الطول المكتوب.
        if (inSampleRate == outSampleRate) {
            return downmixInto(
                pcm, offset, end - offset, inChannels, out, outOffset
            )
        }
        // إعادة عينات: الإخراج يُكتب مباشرة في مخزن المرسل بلا مصفوفة
        // resampled ولا نسخة — مونو وسيط فقط عند القنوات المتعددة (الطيّ
        // متطلبٌ سابق على الاستيفاء)؛ أما المونو فنقرأ النافذة من [pcm]
        // مباشرةً بإزاحتها.
        val useDirectWindow = inChannels == 1
        val mono: ByteArray = if (useDirectWindow) {
            pcm
        } else {
            downmixToMono(pcm, offset, end - offset, inChannels)
        }
        return resampleInto(
            mono,
            if (useDirectWindow) offset else 0,
            if (useDirectWindow) end - offset else mono.size,
            inSampleRate,
            outSampleRate,
            out,
            outOffset
        )
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

    /** مثل [downmixToMono] لكنه يكتب إخراجه مباشرةً في مخزنٍ مقدَّم [out]
     *  عند [outOffset] — بلا مونو وسيط ولا نسخة ثانية (بند تسريع النطق).
     *  يكتب فريماتٍ كاملةً بلا تجاوز حدود [out] ويعيد عدد البايتات المكتوبة.
     *  عند سعةٍ كافية الناتج مطابقٌ حرفياً
     *  لخفض [downmixToMono] مع نسخٍ لاحق. */
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

    /** مثل [resample] لكنه يقرأ نافذة [offset, offset+length) ويكتب إخراجه
     *  مباشرةً في مخزنٍ مقدَّم [out] عند [outOffset] — بلا مصفوفة وسيطة
     *  resampled ولا نسخةٍ ثانية. يكتب فريماتٍ كاملةً بلا تجاوز حدود [out]
     *  ويعيد عدد البايتات المكتوبة. عند سعةٍ كافية الناتج مطابقٌ حرفياً
     *  لناتج [resample] (نفس صيغة الفاصلة 16.16 والاستيفاء فريماً ففريماً). */
    private fun resampleInto(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        inRate: Int,
        outRate: Int,
        out: ByteArray,
        outOffset: Int
    ): Int {
        val inFrames = length / 2
        if (inFrames == 0 || outOffset >= out.size) return 0
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val firstFrame = outOffset / 2
        val written = min(outFrames, (out.size - outOffset) / 2)
        if (written == 0) return 0
        val step = (inRate.toLong() shl 16) / outRate
        var position = 0L
        for (outFrame in 0 until written) {
            val i0 = min((position ushr 16).toInt(), inFrames - 1)
            val i1 = min(i0 + 1, inFrames - 1)
            val fraction = (position and 0xFFFF).toInt()
            val s0 = sampleAt(pcm, offset, i0)
            val s1 = sampleAt(pcm, offset, i1)
            val delta = (s1 - s0).toLong()
            val interpolated =
                (s0 + ((delta * fraction + 0x8000L) shr 16)).toInt()
            writeSample(out, firstFrame + outFrame, interpolated)
            position += step
        }
        return written * 2
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