package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبار منطق السرعة الجديد (نموذج الضرب) في [NateqTtsService]:
 * سرعةُ القارئ النسبةُ المئوية (100 = طبيعي) تتحول إلى معامل
 * ([readerRateFromPercent]) ثم تُضرب في معامل LORD
 * ([computeFinalSpeechRate]) بقصٍّ على نطاقٍ آمن.
 *
 * الحالات المطلوبة:
 *  - readerRate=150 مع nateqMultiplier=1.0 ⇒ 1.5f (وليس 150f)
 *  - readerRate=100 مع تفضيل ناطق 1.5 ⇒ 1.5f
 *  - readerRate=200 مع تفضيل 1.5 ⇒ 3.0f
 *
 * الاختبار نقيٌّ JVM بلا Robolectric — لا أندرويد ولا محاكاة (كتوأم
 * NumberSpeechTest).
 */
class SpeechRateMathTest {

    @Test
    fun percentFromReader_isDividedByHundred() {
        assertEquals("150% = 1.5f", 1.5f, readerRateFromPercent(150f))
        assertEquals("100% = 1.0f", 1.0f, readerRateFromPercent(100f))
        assertEquals("50% = 0.5f", 0.5f, readerRateFromPercent(50f))
    }

    @Test
    fun percent_oddValues_fallBackToNormal() {
        assertEquals("0 يعتبر طبيعياً", 1.0f, readerRateFromPercent(0f))
        assertEquals("سالب يعتبر طبيعياً", 1.0f, readerRateFromPercent(-50f))
    }

    @Test
    fun readerRate150_timesOnePointZero_equalsOnePointFive() {
        // الحالة الحرجة: كان التمرير الحرفي 150 إلى setSpeechRate يُنطق
        // أسرع بمئة وخمسين ضعفاً بدل 1.5.
        val readerRate = readerRateFromPercent(150f)
        val finalRate = computeFinalSpeechRate(readerRate, 1.0f)
        assertEquals("1.5f لا 150f", 1.5f, finalRate)
    }

    @Test
    fun readerRate100_timesOnePointFive_equalsOnePointFive() {
        // قارئ طبيعي (100) مع تفضيل LORD 1.5 ⇒ النطق 1.5×.
        val readerRate = readerRateFromPercent(100f)
        val finalRate = computeFinalSpeechRate(readerRate, 1.5f)
        assertEquals(1.5f, finalRate)
    }

    @Test
    fun readerRate200_timesOnePointFive_equalsThree() {
        // قارئ سريع (200 = 2.0) مضروباً في تفضيل 1.5 ⇒ 3.0×.
        val readerRate = readerRateFromPercent(200f)
        val finalRate = computeFinalSpeechRate(readerRate, 1.5f)
        assertEquals(3.0f, finalRate)
    }

    @Test
    fun finalRate_isClampedToSafeRange() {
        // قصّ على النطاق الآمن لا يُفلت سرعات مهولة للمحرك.
        assertEquals(6.0f, computeFinalSpeechRate(2.0f, 5.0f))
        assertEquals(0.1f, computeFinalSpeechRate(0.01f, 1.0f))
        val inRange = computeFinalSpeechRate(1.5f, 1.5f)
        assertTrue("في نطاق آمن", inRange in 0.1f..6.0f)
    }

    @Test
    fun boost_multipliesFinalRate_whenEnabled() {
        // مضاعف السرعة العام (2.0) فوق معامل LORD 1.5 وقارئ طبيعي ⇒ 3.0×.
        val rate = computeFinalSpeechRate(
            readerRateFromPercent(100f), 1.5f, boost = 2.0f
        )
        assertEquals(3.0f, rate)
    }

    @Test
    fun boost_defaultOnePointZero_changesNothing() {
        // عند التعطيل boost = 1.0 فلا يتغير الناتج عن الدالة القديمة.
        val boosted = computeFinalSpeechRate(1.2f, 1.5f, boost = 1.0f)
        val plain = computeFinalSpeechRate(1.2f, 1.5f)
        assertEquals(plain, boosted)
    }

    @Test
    fun boost_isClampedToSafeRange() {
        // مضاعف جامح تُقصّ نتيجته على السقف الآمن 6.0 (المحرك لا يطبّقه).
        assertEquals(
            6.0f,
            computeFinalSpeechRate(2.0f, 2.0f, boost = 5.0f)
        )
        // boost=1.0 مع قارئ بطيء جداً يبقى داخل الحد الأدنى الآمن.
        assertEquals(
            0.1f,
            computeFinalSpeechRate(0.01f, 1.0f, boost = 1.0f)
        )
    }

    @Test
    fun volumeBoost_multipliesFinalVolume_whenEnabled() {
        // مضاعف الصوت (1.5) فوق مستوى أساسي 0.8 ⇒ 1.2 يُقصّ على الكامل.
        assertEquals(1.0f, computeFinalVolume(0.8f, boost = 1.5f))
        // 0.4 مبدئياً × 2.0 ⇒ 0.8 (بداخل، غير مقصوص).
        assertEquals(0.8f, computeFinalVolume(0.4f, boost = 2.0f))
    }

    @Test
    fun volumeBoost_defaultOnePointZero_changesNothing() {
        val boosted = computeFinalVolume(0.9f, boost = 1.0f)
        val plain = computeFinalVolume(0.9f)
        assertEquals(plain, boosted)
        assertEquals(0.9f, boosted)
    }

    @Test
    fun volumeBoost_neverRisesAboveFullOrBelowSilence() {
        // القصّ على النطاق الكامل (0..1): لا تشويه بالكسب >1 ولا سقوط تحت
        // الصمت.
        assertEquals(1.0f, computeFinalVolume(1.0f, boost = 2.5f))
        assertEquals(0f, computeFinalVolume(-0.5f, boost = 1.0f))
        val inRange = computeFinalVolume(0.2f, boost = 4.0f)
        assertTrue("داخل النطاق الكامل", inRange in 0f..1f)
    }
}