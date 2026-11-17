package com.aymankhattab.nateq.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ع٤٢-2 — حارس انحدار: كان تنزيل Vocalizer يُرفض على أجهزة 32-بت
 * رغم أن مكتبة armeabi-v7a موجودة؛ الحارس يثبت أن الحالة
 * المقبولة الآن هي بالفعل المقبولة في منطق الواجهة.
 */
class VocalizerAbiEligibilityTest {

    @Test
    fun vocalizer32BitWithArmeabiV7a_isNowAccepted() {
        assertTrue(
            VocalizerAbiEligibility.isDownloadAllowed(
                listOf(VocalizerAbiEligibility.ABI_ARM32)
            )
        )
    }

    @Test
    fun vocalizer64BitDevice_stillAccepted() {
        assertTrue(
            VocalizerAbiEligibility.isDownloadAllowed(
                listOf(
                    VocalizerAbiEligibility.ABI_ARM64,
                    VocalizerAbiEligibility.ABI_ARM32
                )
            )
        )
    }

    @Test
    fun nonArmDevice_isStillRejected() {
        assertFalse(
            VocalizerAbiEligibility.isDownloadAllowed(
                listOf("x86", "x86_64")
            )
        )
    }

    @Test
    fun is32BitOnly_trueForArm32_falseForMixedAbis() {
        assertTrue(
            VocalizerAbiEligibility.is32BitOnly(
                listOf(VocalizerAbiEligibility.ABI_ARM32)
            )
        )
        assertFalse(
            VocalizerAbiEligibility.is32BitOnly(
                listOf(
                    VocalizerAbiEligibility.ABI_ARM64,
                    VocalizerAbiEligibility.ABI_ARM32
                )
            )
        )
    }
}
