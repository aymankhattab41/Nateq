package com.aymankhattab.nateq

import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مهلة توليد TTS المتكيّفة مع طول النص — منطق نقي بلا
 * أجهزة (JVM خالص).
 * النظام داخل [SystemVoiceProvider] (مهلة ممتدة لإكمال كتابة الملف، وقصيرة
 * للنصوص القصيرة كي لا تحتبس قراءة الشاشة ثوانٍ طوال).
 */
class SystemVoiceProviderTest {

    @Test
    fun synthesisTimeout_isShortForShortTexts() {
        // النصوص القصيرة (الأوامر والإعلانات) تتحرر فوراً: 1.5–8 ثوانٍ
        assertEquals(1_500L, SystemVoiceProvider.synthesisTimeoutMs(1))
        assertEquals(1_500L, SystemVoiceProvider.synthesisTimeoutMs(10))
        assertEquals(3_000L, SystemVoiceProvider.synthesisTimeoutMs(80))
        assertEquals(8_000L, SystemVoiceProvider.synthesisTimeoutMs(300))
    }

    @Test
    fun synthesisTimeout_wellsUpForLongTexts() {
        // النص الطويل يحصل على مهلة واسعة: 8000 + (طول-300)*30 ms
        // 301 حرف: 8000 + 30 = 8030ms
        assertEquals(8_030L, SystemVoiceProvider.synthesisTimeoutMs(301))
        // 1000 حرف: 8000 + 700*30 = 29000ms
        assertEquals(29_000L, SystemVoiceProvider.synthesisTimeoutMs(1_000))
        // 5000 حرف: 8000 + 4700*30 = 149000ms
        assertEquals(149_000L, SystemVoiceProvider.synthesisTimeoutMs(5_000))
    }

    @Test
    fun synthesisTimeout_neverExceedsFiveMinutes() {
        val cap = 5 * 60 * 1_000L
        // النص الضخم جداً يُقصّ على 5 دقائق
        assertEquals(cap, SystemVoiceProvider.synthesisTimeoutMs(Int.MAX_VALUE))
        // 10000 حرف: 8000+9700*30 > 300000 → مقصوص
        assertTrue(
            SystemVoiceProvider.synthesisTimeoutMs(10_000) <= cap
        )
    }
}