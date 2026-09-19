package com.aymankhattab.nateq.core.audio.providers

/**
 * تطبيع RMS لارتفاع الصوت الموحّد بين المحركات (بند المرحلة 7):
 * كل محرك (eSpeak/MultiTTS/جوجل...) يخرج بجهارة مختلفة جداً، فيُقاس
 * الجذر التربيعي المتوسط للعيّنات ثم يُحسب كسبٌ يجذب الجهارة إلى هدفٍ
 * ثابت [RMS_TARGET] فيتساوى صوتُ المحركات بلا تدخل يدوي، ويُضرب
 * كسبُ المستخدم [volume] فوقه.
 *
 * يُحجم الكسب ضمن [MIN_RMS_GAIN]..[MAX_RMS_GAIN] حتى لا يرفع الصمتَ
 * إلى ضجيجٍ ولا يسحق الصوتَ العالي (انفجار/اختناق أذى).
 *
 * دالة نقية (بلا Context/دولة) لسهولة الاختبار الآلي.
 *
 * @param pcmData عيّنات PCM 16-بت، ليست بالضرورة ممتلئة (مصباح مُعاد
 *        استخدامه) — لا يُقرأ إلا حتى [validLength].
 * @param volume كسب المستخدم (1.0 = محايد).
 * @return الكسبُ الكلي (تطبيع RMS × volume) المقيّد ضمن الحدود؛ إن كان
 *         الصوت شبه صامت ([SILENCE_RMS]) يُرجع [volume] وحده فلم يرفع
 *         النسخةَ الفارغة إلى همهمة.
 */
internal fun normalizedRmsGain(
    pcmData: ByteArray,
    validLength: Int,
    volume: Float
): Float {
    val scale = rmsNormalizationScale(pcmData, validLength) ?: return volume
    return scale * volume
}

/** يقيس RMS للعيّنات الصالحة (16-بت little endian) — 0.0 للقصير/الفارغ. */
internal fun measureRms(pcmData: ByteArray, validLength: Int): Double {
    if (validLength < 2) return 0.0
    var sumSquares = 0.0
    var i = 0
    while (i + 1 < validLength) {
        // عيّنة 16-بت (little endian) بلا إشارة تُرقَّع.
        val sample = (pcmData[i + 1].toInt() shl 8) or
            (pcmData[i].toInt() and 0xFF)
        sumSquares += sample.toDouble() * sample
        i += 2
    }
    return Math.sqrt(sumSquares / (validLength / 2))
}

/** مقياس التطبيع وحده (بلا كسب المستخدم) — null للصمت [SILENCE_RMS]
 *  أو القصير: تُستخدم صلاحيتُها كبوابة عيّنات المعايرة (بند د.3.3). */
internal fun rmsNormalizationScale(
    pcmData: ByteArray,
    validLength: Int
): Float? {
    val rms = measureRms(pcmData, validLength)
    if (rms <= SILENCE_RMS) return null
    return (RMS_TARGET / rms).coerceIn(
        MIN_RMS_GAIN.toDouble(), MAX_RMS_GAIN.toDouble()
    ).toFloat()
}

/** مستوى RMS الهدف (نسبةً من السعة الكلية 32767) — نحو -16 dBFS. */
internal const val RMS_TARGET = 0.2 * 32767

/** أدنى RMS لا نعتبر ما تحته صوتاً (≈ -50 dBFS): لا كسب للصمت. */
internal const val SILENCE_RMS = 100.0

/** أدنى/أقصى كسب تطبيع — يمنع انفجار الصمت أو سحق الصوت العالي. */
internal const val MIN_RMS_GAIN = 0.5f

internal const val MAX_RMS_GAIN = 4.0f