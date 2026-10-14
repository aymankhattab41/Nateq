package com.aymankhattab.nateq.core.audio.accessibility

/**
 * قرار تسجيل مستمع زر إمكانية الوصول (ع٤١-ب2) — منطقٌ نقيّ قابل للاختبار.
 *
 * الشروط الثلاثة معاً:
 * 1. النظام يدعم الزر (API 26+).
 * 2. المفتاح [FeatureFlags.FEATURE_ACCESSIBILITY_BUTTON] مفعّل.
 * 3. الزر متاح فعلاً (`isAccessibilityButtonAvailable`، API 30+) — وقبل
 *    API 30 يُفترض الإتاحة لعدم توفّر الفحص.
 */
object AccessibilityButtonGate {

    private const val API_O = 26
    private const val API_R = 30

    fun shouldRegister(
        featureActive: Boolean,
        sdkInt: Int,
        buttonAvailable: Boolean
    ): Boolean {
        if (sdkInt < API_O) return false
        val available = sdkInt < API_R || buttonAvailable
        return featureActive && available
    }
}
