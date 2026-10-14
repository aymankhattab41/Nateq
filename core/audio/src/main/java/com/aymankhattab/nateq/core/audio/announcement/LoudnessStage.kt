package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * مرحلة كسب PCM ومحدّد ناعم (Soft Limiter) لمسار الصوت المدمج (ع٤٢-3).
 *
 * ترفع جهارة أحداث النظام (المتصل، الساعة، البطارية، SMS، الإشعارات)
 * بكسب قابل للضبط حتى +6dB، مع محدد ناعم غير خطي يمنع التشوه (clipping)
 * فوق 0dBFS، بينما لا يُطبّق مطلقاً على قراءة قارئ الشاشة العادية.
 */
object LoudnessStage {

    /** أقصى كسب مسموح به بوحدة الديسيبل (+6dB). */
    const val MAX_GAIN_DB = 6.0f

    /** الكسب الافتراضي لأحداث النظام (+6dB). */
    const val DEFAULT_GAIN_DB = 6.0f

    /** عتبة بداية عمل المحدّد الناعم (0.8 = -1.94 dBFS). */
    const val LIMITER_THRESHOLD = 0.8f

    /** هامش الرأس المتبقي للضغط التدريجي حتى 1.0 (1.0 - 0.8 = 0.2). */
    const val LIMITER_HEADROOM = 1.0f - LIMITER_THRESHOLD

    /**
     * هل تُعدّ الفئة فئة حدث تخضع لزيادة الجهارة؟
     * قارئ الشاشة العادي (TalkBack) ونصوص القراءة العامة تمرّ بـ null أو فارغة،
     * فتُستثنى تماماً ولا يُطبّق عليها أي كسب أو تحديد.
     */
    fun isEventCategory(category: String?): Boolean =
        AnnouncementSpeaker.isEventCategory(category)

    /**
     * يحسب عامل التضخيم الخطي من قيمة الديسيبل:
     * linear = 10^(gainDb / 20)
     */
    fun dbToLinear(gainDb: Float): Float {
        if (gainDb <= 0f) return 1.0f
        val clampedDb = gainDb.coerceIn(0f, MAX_GAIN_DB)
        return 10.0.pow(clampedDb.toDouble() / 20.0).toFloat()
    }

    /**
     * معالجة عينة صوتية مفردة (Short 16-bit):
     * 1. عند كسب 0dB (أو أقل): تعيد العينة كما هي تماماً بلا أي تعديل.
     * 2. عند كسب موجب: تطبق الكسب الخطي ثم المحدّد الناعم إن تجاوزت العتبة.
     * 3. النتيجة مضمونة ألا تتجاوز 0dBFS (ضمن مدى Short الصالح) وبلا تشويه حاد.
     */
    fun processSample(sample: Short, gainDb: Float): Short {
        if (gainDb <= 0f) return sample

        val gainLinear = dbToLinear(gainDb)
        val normalized = sample.toFloat() / 32768.0f
        val amplified = normalized * gainLinear
        val absVal = abs(amplified)

        val limited = if (absVal <= LIMITER_THRESHOLD) {
            amplified
        } else {
            val delta = absVal - LIMITER_THRESHOLD
            val compressed = LIMITER_THRESHOLD +
                LIMITER_HEADROOM * tanh(delta / LIMITER_HEADROOM)
            if (amplified < 0f) -compressed else compressed
        }

        val outInt = (limited * 32767.0f).roundToInt()
        return outInt.coerceIn(
            Short.MIN_VALUE.toInt(),
            Short.MAX_VALUE.toInt()
        ).toShort()
    }

    /**
     * معالجة مصفوفة عينات ShortArray (مفيدة للاختبارات ومسارات الصوت النقية).
     */
    fun processSamples(samples: ShortArray, gainDb: Float): ShortArray {
        if (gainDb <= 0f) return samples.clone()
        val result = ShortArray(samples.size)
        for (i in samples.indices) {
            result[i] = processSample(samples[i], gainDb)
        }
        return result
    }

    /**
     * معالجة مخزن بايتات PCM 16-bit Little-Endian في موضعه (In-Place)
     * لتوفير الذاكرة والسرعة الفائقة في مسار البث.
     */
    fun processPcm16(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        gainDb: Float
    ) {
        if (gainDb <= 0f) return
        val end = offset + (length and 1.inv())
        var i = offset
        while (i < end) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val processed = processSample(sample, gainDb)
            buffer[i] = (processed.toInt() and 0xFF).toByte()
            buffer[i + 1] = ((processed.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
