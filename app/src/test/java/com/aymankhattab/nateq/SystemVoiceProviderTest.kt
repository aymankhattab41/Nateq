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
        // النصوص القصيرة (الأوامر والإعلانات) تتحرر فوراً: 1.5–3 ثوانٍ
        assertEquals(1_500L, SystemVoiceProvider.synthesisTimeoutMs(1))
        assertEquals(1_500L, SystemVoiceProvider.synthesisTimeoutMs(10))
        assertEquals(2_000L, SystemVoiceProvider.synthesisTimeoutMs(80))
        assertEquals(3_000L, SystemVoiceProvider.synthesisTimeoutMs(300))
    }

    @Test
    fun synthesisTimeout_wellsUpForLongTexts() {
        // النص الطويل يحصل على مهلة واسعة: 3000 + طول*30 ms
        // 301 حرف: 3000 + 301*30 = 12030ms
        assertEquals(12_030L, SystemVoiceProvider.synthesisTimeoutMs(301))
        // 1000 حرف: 3000 + 30000 = 33000ms
        assertEquals(33_000L, SystemVoiceProvider.synthesisTimeoutMs(1_000))
        // 5000 حرف: 3000 + 150000 = 153000ms
        assertEquals(153_000L, SystemVoiceProvider.synthesisTimeoutMs(5_000))
    }

    @Test
    fun synthesisTimeout_neverExceedsFiveMinutes() {
        val cap = 5 * 60 * 1_000L
        // النص الضخم جداً يُقصّ على 5 دقائق
        assertEquals(cap, SystemVoiceProvider.synthesisTimeoutMs(Int.MAX_VALUE))
        // 10000 حرف: 3000+300000 > 300000 → مقصوص
        assertTrue(
            SystemVoiceProvider.synthesisTimeoutMs(10_000) <= cap
        )
    }
}