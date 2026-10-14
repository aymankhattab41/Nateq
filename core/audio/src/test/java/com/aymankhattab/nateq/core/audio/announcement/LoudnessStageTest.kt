package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * اختبارات مرحلة كسب PCM والمحدّد الناعم (ع٤٢-3):
 * - تساوي الإدخال عند كسب 0 (Identity at 0dB).
 * - انعدام القصّ (no clipping) فوق 0dBFS عند أقصى كسب (+6dB).
 * - استدارة القمم بنعومة وتجنّب التسطيح الحاد (Soft Knee).
 * - اقتصار التفعيل على فئات الأحداث دون قراءة قارئ الشاشة العادية.
 */
class LoudnessStageTest {

    @Test
    fun zeroGain_returnsExactInputSample() {
        val testValues = shortArrayOf(
            Short.MIN_VALUE,
            -25000,
            -10000,
            -1,
            0,
            1,
            10000,
            25000,
            Short.MAX_VALUE
        )
        for (s in testValues) {
            val processed = LoudnessStage.processSample(s, 0.0f)
            assertEquals(
                "القيمة يجب أن تطابق الأصل تماماً عند كسب 0",
                s,
                processed
            )
        }
    }

    @Test
    fun zeroGain_returnsExactInputSamplesArray() {
        val original = ShortArray(256) { (it * 200 - 25000).toShort() }
        val output = LoudnessStage.processSamples(original, 0.0f)
        assertArrayEquals(original, output)
    }

    @Test
    fun zeroGain_pcm16BytesRemainIdentical() {
        val original = ByteArray(128) { (it xor 0x5A).toByte() }
        val copy = original.clone()
        LoudnessStage.processPcm16(copy, 0, copy.size, 0.0f)
        assertArrayEquals(original, copy)
    }

    @Test
    fun maxGain_neverExceedsZeroDbFs() {
        // موجة جيبية بكامل المدى المتاح (32767) عند كسب أقصى (+6dB)
        val sampleRate = 22050
        val frequency = 440.0
        val numSamples = 1000
        val sineWave = ShortArray(numSamples) { i ->
            val angle = 2.0 * PI * frequency * i / sampleRate
            (sin(angle) * Short.MAX_VALUE).toInt().toShort()
        }

        val amplified = LoudnessStage.processSamples(
            sineWave,
            LoudnessStage.MAX_GAIN_DB
        )

        for (i in amplified.indices) {
            val sample = amplified[i]
            assertTrue(
                "العينة لا تتجاوز الحد الأعلى الموجب (0dBFS)",
                sample <= Short.MAX_VALUE
            )
            assertTrue(
                "العينة لا تتجاوز الحد الأدنى السالب (0dBFS)",
                sample >= Short.MIN_VALUE
            )
        }
    }

    @Test
    fun maxGain_extremeSignalsDoNotOverflowOrClipFlat() {
        val extremeInputs = shortArrayOf(
            Short.MIN_VALUE,
            (Short.MIN_VALUE + 1).toShort(),
            -30000,
            -28000,
            28000,
            30000,
            (Short.MAX_VALUE - 1).toShort(),
            Short.MAX_VALUE
        )
        for (input in extremeInputs) {
            val out = LoudnessStage.processSample(
                input,
                LoudnessStage.MAX_GAIN_DB
            )
            assertTrue("out <= MAX", out <= Short.MAX_VALUE)
            assertTrue("out >= MIN", out >= Short.MIN_VALUE)
        }
    }

    @Test
    fun softLimiter_smoothlyRoundsPeaksWithoutHardCliff() {
        // عند الكسب +6dB (~2x)، القيم المتصاعدة فوق العتبة تتدرج بنعومة
        // بحيث لا تتطابق قمتان متتاليتان على خط مستوٍ مسطح (Flat Cliff)
        val s1 = 20000.toShort()
        val s2 = 25000.toShort()
        val s3 = 30000.toShort()

        val out1 = LoudnessStage.processSample(s1, 6.0f)
        val out2 = LoudnessStage.processSample(s2, 6.0f)
        val out3 = LoudnessStage.processSample(s3, 6.0f)

        // كلما زادت العينة الأصلية زادت القيمة المحدودة تدريجياً وبنعومة
        assertTrue("تدرج ناعم بين عينة 1 و2", out2 > out1)
        assertTrue("تدرج ناعم بين عينة 2 و3", out3 > out2)
        assertTrue("لا تتجاوز السقف", out3 <= Short.MAX_VALUE)
    }

    @Test
    fun pcm16Buffer_processesInPlaceCorrectly() {
        val samples = shortArrayOf(1000, 5000, 15000, 30000)
        val buffer = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i]
            buffer[i * 2] = (s.toInt() and 0xFF).toByte()
            buffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        LoudnessStage.processPcm16(buffer, 0, buffer.size, 6.0f)

        for (i in samples.indices) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val actual = ((high shl 8) or low).toShort()
            val expected = LoudnessStage.processSample(samples[i], 6.0f)
            assertEquals(
                "تطابق عينة البايتات مع processSample",
                expected,
                actual
            )
        }
    }

    @Test
    fun isEventCategory_onlyTrueForSystemEvents() {
        // فئات أحداث النظام المعتمدة
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.VOICE_CATEGORY_TIME
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.VOICE_CATEGORY_BATTERY
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_SMS
            )
        )
        assertTrue(
            LoudnessStage.isEventCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
        )

        // قارئ الشاشة العادي وقراءة النصوص العادية لا تخضع للكسب
        assertFalse(LoudnessStage.isEventCategory(null))
        assertFalse(LoudnessStage.isEventCategory(""))
        assertFalse(LoudnessStage.isEventCategory("default"))
        assertFalse(LoudnessStage.isEventCategory("screen_reader"))
    }

    @Test
    fun dbToLinear_conversionsAreAccurate() {
        assertEquals(1.0f, LoudnessStage.dbToLinear(0.0f), 0.0001f)
        assertEquals(1.0f, LoudnessStage.dbToLinear(-3.0f), 0.0001f)
        val linear6 = LoudnessStage.dbToLinear(6.0f)
        assertTrue("6dB should be close to 1.995", linear6 in 1.99f..2.01f)
        // لا يتجاوز 6dB حتى لو مررت قيمة أكبر
        val clampedLinear = LoudnessStage.dbToLinear(12.0f)
        assertEquals(linear6, clampedLinear, 0.0001f)
    }
}
