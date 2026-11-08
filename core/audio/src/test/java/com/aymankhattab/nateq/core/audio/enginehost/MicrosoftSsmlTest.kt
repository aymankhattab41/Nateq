package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس عقد باني SSML لمايكروسوفت: السرعة الافتراضية بلا `prosody`،
 * والسرعة المعدّلة تُصاغ نسبةً مئوية بإشارة صريحة، مع تهريب XML كامل.
 *
 * الكسر الذي يحرسه: كان معدّل النطق يُهمَل كلياً فينطق المحرك دائماً
 * بالسرعة الافتراضية مهما ضبط المستخدم.
 */
class MicrosoftSsmlTest {

    @Test
    fun defaultRate_hasNoProsodyElement() {
        val ssml = MicrosoftSsml.build(
            text = "مرحباً",
            modelName = "ar-EG-ShakirNeural",
            langTag = "ar-EG",
            rate = 1.0f
        )
        assertFalse(
            "1.0 تعني الافتراضي فلا وسَم سرعة",
            ssml.contains("<prosody")
        )
        assertTrue(ssml.contains("<voice name=\"ar-EG-ShakirNeural\">"))
        assertTrue(ssml.contains("xml:lang=\"ar-EG\""))
        assertTrue(ssml.endsWith("مرحباً</voice></speak>"))
    }

    @Test
    fun fasterRate_isWrittenAsPositivePercentage() {
        val ssml = MicrosoftSsml.build(
            text = "نصّ",
            modelName = "voice",
            langTag = "ar-EG",
            rate = 1.2f
        )
        // النسبة الموجبة تحتاج "+" صريحاً في SSML.
        assertTrue(ssml.contains("<prosody rate=\"+20%\">نصّ</prosody>"))
    }

    @Test
    fun slowerRate_isWrittenAsNegativePercentage() {
        val ssml = MicrosoftSsml.build(
            text = "نصّ",
            modelName = "voice",
            langTag = "ar-EG",
            rate = 0.8f
        )
        assertTrue(ssml.contains("<prosody rate=\"-20%\">نصّ</prosody>"))
    }

    @Test
    fun rateNearOne_isTreatedAsDefault() {
        val ssml = MicrosoftSsml.build(
            text = "نصّ",
            modelName = "voice",
            langTag = "ar-EG",
            rate = 1.004f
        )
        assertFalse(
            "الفرق العائم الضئيل لا يستحق وسَم سرعة",
            ssml.contains("<prosody")
        )
    }

    @Test
    fun textAndAttributes_areXmlEscaped() {
        val ssml = MicrosoftSsml.build(
            text = "a & b < c > d \"e\" 'f'",
            modelName = "v&1",
            langTag = "ar-EG",
            rate = 1.5f
        )
        assertTrue(
            ssml.contains(
                "a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;"
            )
        )
        assertTrue(
            "اسم الصوت يُهرَّب أيضاً",
            ssml.contains("name=\"v&amp;1\"")
        )
        assertEquals(
            "<speak version=\"1.0\" " +
                "xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
                "xml:lang=\"ar-EG\">" +
                "<voice name=\"voice\">" +
                "<prosody rate=\"+50%\">نصّ</prosody>" +
                "</voice></speak>",
            MicrosoftSsml.build(
                text = "نصّ",
                modelName = "voice",
                langTag = "ar-EG",
                rate = 1.5f
            )
        )
    }

    @Test
    fun halfRate_isMinusFiftyPercent() {
        val ssml = MicrosoftSsml.build(
            text = "x",
            modelName = "v",
            langTag = "ar",
            rate = 0.5f
        )
        assertTrue(ssml.contains("rate=\"-50%\""))
    }
}
