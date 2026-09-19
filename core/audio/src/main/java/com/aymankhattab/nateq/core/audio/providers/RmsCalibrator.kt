package com.aymankhattab.nateq.core.audio.providers

/**
 * مجمّع معايرة RMS لمحركٍ واحد (بند الأوامر د.3.3): التطبيعُ اللحظي لكل
 * شريحةٍ على حدة يُضخّم الصوتَ (gain pumping) بين شرائح النطق الواحد —
 * فيُجمَع متوسّطُ مقاييس الشرائح الصالحة حتى [RMS_CALIBRATION_SAMPLES]
 * ثم يُجمَّد ويُحفَظ لكل محرك، فتبدأ نطقاتُه اللاحقة بكسبٍ مستقرٍّ فوراً.
 *
 * فئة نقية (بلا Context/قرص) — الثبات عبر [VoicePrefsProvider] في المزوّد.
 * آمنة خيطياً ([Synchronized]) لجلسات التخليق المتوازية على نفس المحرك.
 */
internal class RmsCalibrator(
    private val maxSamples: Int = RMS_CALIBRATION_SAMPLES
) {
    private var sum = 0.0
    private var count = 0

    /** يغذّي مقياسَ شريحةٍ صالحة؛ يُجمَّد المتوسّط عند اكتمال العيّنات. */
    @Synchronized
    fun observe(scale: Float): Float? {
        if (!scale.isFinite() || scale <= 0f) return current()
        if (count >= maxSamples) return current()
        sum += scale
        count++
        return current()
    }

    /** المتوسّط الحالي — null قبل أول عيّنةٍ صالحة. */
    @Synchronized
    fun current(): Float? =
        if (count == 0) null else (sum / count).toFloat()

    /** هل اكتملت العيّنات وتجمّدت المعايرة؟ */
    @Synchronized
    fun isSettled(): Boolean = count >= maxSamples

    /** بذرٌ بقيمةٍ محفوظة: تُحتسَب مكتملةً فوراً فلا تُعاد معايرتُها. */
    @Synchronized
    fun seed(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        sum = value.toDouble() * maxSamples
        count = maxSamples
    }

    /** تصفيرٌ لإعادة المعايرة (مع مسح المفتاح المحفوظ في المزوّد). */
    @Synchronized
    fun reset() {
        sum = 0.0
        count = 0
    }
}

/** عدد شرائح المعايرة قبل تجميد كسب المحرك — وسطٌ كافٍ بلا إبطاءٍ ملحوظ. */
internal const val RMS_CALIBRATION_SAMPLES = 5
