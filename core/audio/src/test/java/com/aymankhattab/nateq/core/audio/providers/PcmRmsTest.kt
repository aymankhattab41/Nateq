package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبار [normalizedRmsGain] — تطبيع الجهارة الموحّد بين المحركات
 * (مرحلة 7). يثبت أن الكسبَ يجذب RMS العيّنات نحو [RMS_TARGET] مع
 * تحجيم ضمن الحدود، وصمتٌ لا يُضخَّم.
 */
class PcmRmsTest {

    private fun constantPcm(value: Short, frames: Int): ByteArray {
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            out[i * 2] = (value.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun midLevel_getsUnitGain() {
        // إشارة ثابتة عند هدف RMS تقريباً: ينبغي كسب ≈ 1 (×volume).
        // RMS لعيّنات ثابتة = قيمة العيّنة نفسها.
        val target = (0.2 * 32767).toInt()
        val pcm = constantPcm(target.toShort(), 128)
        val gain = normalizedRmsGain(pcm, 256, 1.0f)
        assertEquals(1.0f, gain, 0.05f)
    }

    @Test
    fun quietSignal_isBoostedButCapped() {
        // صمت نسبي: كسب يجذب نحو الهدف لكن لا يتجاوز السقف 4.
        val gain = normalizedRmsGain(constantPcm(2000, 128), 256, 1.0f)
        val expected = (0.2 * 32767 / 2000.0)
        assertTrue(
            "الكسب يجب أن يقرّب RMS للهدف: $gain", 2.0f < gain && gain <= 4.0f
        )
        assertEquals(expected.toFloat(), gain, 0.05f)
    }

    @Test
    fun loudSignal_isReducedButCapped() {
        // إشارة عالية: خفض نحو الهدف مع حدٍّ أدنى 0.5 فلا تُسحق.
        val gain = normalizedRmsGain(constantPcm(20000, 128), 256, 1.0f)
        assertTrue("الإشارة العالية تُخفض: $gain", gain < 1.0f)
        assertTrue("ضمن الحد الأدنى 0.5", gain >= 0.5f)
    }

    @Test
    fun volumeMultiplier_scalesOverNormalization() {
        // كسب المستخدم يضرب فوق كسب التطبيع.
        val base = normalizedRmsGain(constantPcm(5000, 128), 256, 1.0f)
        val withVol = normalizedRmsGain(constantPcm(5000, 128), 256, 0.5f)
        assertEquals(base * 0.5f, withVol, 0.01f)
    }

    @Test
    fun silence_isReturnedVerbatum() {
        // صمت كامل: لا يُضخَّم أبداً (يبقى كسب المستخدم وحده).
        val gain = normalizedRmsGain(ByteArray(256), 256, 1.0f)
        assertEquals(1.0f, gain, 0.0f)
    }

    @Test
    fun nearSilenceBelowThreshold_isNotAmplified() {
        val gain = normalizedRmsGain(constantPcm(90, 128), 256, 1.0f)
        assertEquals(1.0f, gain, 0.0f)
    }

    @Test
    fun validLengthIgnoresGarbageTail() {
        // البياناتُ الصالحة 128 عيّنة ≤ SILENCE ضمن [validLength] وحده؛
        // ما بعد validLength (ضجيج) لا يُحسب فهو ليس جزءاً من الإشارة.
        // هنا validLength يشمل صمتا فقط ́ دون القمامة المخزنية.
        val pcm = ByteArray(512)
        for (i in 256 until 512) pcm[i] = 0x7F.toByte()
        val gain = normalizedRmsGain(pcm, 256, 1.0f)
        assertEquals(1.0f, gain, 0.0f)
    }

    @Test
    fun measureRms_ofConstantSignal_equalsSampleValue() {
        assertEquals(5000.0, measureRms(constantPcm(5000, 64), 128), 1.0)
        assertEquals(0.0, measureRms(ByteArray(128), 128), 0.0)
        assertEquals(0.0, measureRms(ByteArray(128), 1), 0.0)
    }

    @Test
    fun scale_isNullForSilence_valueForSignal() {
        assertEquals(null, rmsNormalizationScale(ByteArray(256), 256))
        assertEquals(null, rmsNormalizationScale(ByteArray(256), 1))
        val scale = rmsNormalizationScale(constantPcm(2000, 128), 256)
        assertTrue("مقياس موجب حي", scale != null && scale > 1f)
        // يطابق قسمة الكسب الكلي على كسب المستخدم.
        val gain = normalizedRmsGain(constantPcm(2000, 128), 256, 0.5f)
        assertEquals(scale!! * 0.5f, gain, 0.001f)
    }
}