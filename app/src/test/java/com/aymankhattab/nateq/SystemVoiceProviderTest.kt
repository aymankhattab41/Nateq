package com.aymankhattab.nateq

import com.aymankhattab.nateq.providers.SystemVoiceProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات مهلة توليد TTS المتكيّفة مع طول النص — منطق نقي بلا أجهزة (JVM خالص).
 * النظام داخل [SystemVoiceProvider] (مهلة ممتدة لإكمال كتابة الملف، وقصيرة
 * للنصوص القصيرة كي لا تحتبس قراءة الشاشة ثوانٍ طوال).
 */
class SystemVoiceProviderTest {

    @Test
    fun synthesisTimeout_isShortForShortTexts() {
        // النصوص القصيرة (الأوامر والإعلانات) تتحرر فوراً: 1.5–3 ثوانٍ
        assertEquals(1500L, SystemVoiceProvider.synthesisTimeoutMs(1))
        assertEquals(1500L, SystemVoiceProvider.synthesisTimeoutMs(10))
        assertEquals(2000L, SystemVoiceProvider.synthesisTimeoutMs(80))
        assertEquals(3000L, SystemVoiceProvider.synthesisTimeoutMs(300))
    }

    @Test
    fun synthesisTimeout_wellsUpForLongTexts() {
        // النص الطويل يحصل على مهلة أوسع ليكتمل كتابة الملف كاملاً
        assertEquals(8000L, SystemVoiceProvider.synthesisTimeoutMs(301))
        assertEquals(8000L, SystemVoiceProvider.synthesisTimeoutMs(5000))
    }

    @Test
    fun synthesisTimeout_neverExceedsBound() {
        // لا تُجازز السقف في أي حجم (يشمل الحالات القصوى)
        assertEquals(8000L, SystemVoiceProvider.synthesisTimeoutMs(Int.MAX_VALUE))
    }
}