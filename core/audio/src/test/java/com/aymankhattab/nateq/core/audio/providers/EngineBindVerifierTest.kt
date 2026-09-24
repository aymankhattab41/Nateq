package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات كشف الربط الدخيل (جوجل بدل المختار) — منطق نقي بلا أجهزة. */
class EngineBindVerifierTest {

    @Test
    fun googleEngineRequested_neverTreatedAsIntruder() {
        assertTrue(EngineBindVerifier.isGoogleEngine("com.google.android.tts"))
        assertFalse(
            EngineBindVerifier.isWronglyBoundToGoogle(
                "com.google.android.tts",
                listOf("ar-x-isc#male_2-local")
            )
        )
    }

    @Test
    fun externalEngineWithGoogleVoices_isForeignBind() {
        // الشكوى المبلغ عنها: المحرك المختار خارجي لكن الجهاز أعطى أصوات
        // جوجل — يجب رفض المثيل وعدم إخراج صوت محركٍ دخيل.
        assertTrue(
            EngineBindVerifier.isWronglyBoundToGoogle(
                "org.reeceduncan.speak",
                listOf("ar-x-isc#male_2-local", "en-us-x-sfg")
            )
        )
    }

    @Test
    fun externalEngineWithItsOwnVoices_isAccepted() {
        assertFalse(
            EngineBindVerifier.isWronglyBoundToGoogle(
                "org.reeceduncan.speak",
                listOf("en-us", "en-gb", "ar")
            )
        )
    }

    @Test
    fun emptyOrAmbiguousVoices_areAccepted() {
        // لا دليل على ربط دخيل — لا نرفض بلا دليل (يجنّب قطع النطق لئلا
        // تُخلّ محركات تعلن أصواتها متأخراً).
        assertFalse(
            EngineBindVerifier.isWronglyBoundToGoogle(
                "org.reeceduncan.speak", emptyList()
            )
        )
    }

    @Test
    fun firstRebindAllowed_thenGivesUp() {
        assertTrue(EngineBindVerifier.shouldRetryRebind(0))
        assertFalse(EngineBindVerifier.shouldRetryRebind(1))
        assertFalse(EngineBindVerifier.shouldRetryRebind(2))
    }
}