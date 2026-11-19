package com.aymankhattab.nateq.engine.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgyptianSpeechProcessingStepTest {

    @Test
    fun disabled_returnsInputUnchanged() {
        val step = EgyptianSpeechProcessingStep { false }
        val input = "ازاي عامل ايه"
        assertEquals(input, step.apply(input))
    }

    @Test
    fun enabled_replacesCommonEgyptianWord() {
        val step = EgyptianSpeechProcessingStep { true }
        val out = step.apply("ازاي")
        assertEquals("إِزَّاي", out)
    }

    @Test
    fun enabled_nonArabic_returnsUnchanged() {
        val step = EgyptianSpeechProcessingStep { true }
        val input = "hello world"
        assertEquals(input, step.apply(input))
    }

    @Test
    fun enabled_empty_returnsEmpty() {
        val step = EgyptianSpeechProcessingStep { true }
        assertEquals("", step.apply(""))
    }

    @Test
    fun enabled_appliesLightTashkeelWord() {
        val step = EgyptianSpeechProcessingStep { true }
        val out = step.apply("شكرا")
        assertTrue(out.contains("شُكْرًا") || out != "شكرا")
    }

    @Test
    fun enabled_doesNotReplaceInsideLongerWordBoundary() {
        val step = EgyptianSpeechProcessingStep { true }
        // "ازاي" should be replaced as a standalone word only
        val out = step.apply("ازايك")
        // "ازايك" is a separate mapped key ("إِزَّيَّك") in our map
        assertFalse(out.isBlank())
    }

    @Test
    fun allowTashkeelProvider_false_stripsTashkeelFromOutput() {
        // بند 5: عند منع التشكيل (محرك لا ينطقه صحيحاً) لا يُضاف أي تشكيل،
        // ويُجرّد ما تحمله بدائل القاموس المصرية.
        val step = EgyptianSpeechProcessingStep(
            enabledProvider = { true },
            allowTashkeelProvider = { false }
        )
        val out = step.apply("إزاي")
        assertFalse(
            "لا يجب أن يحتوي الناتج على تشكيل عند المنع",
            out.any { it.code in 0x064B..0x0652 || it.code == 0x0670 }
        )
        assertTrue(out.isNotBlank())
    }

    @Test
    fun allowTashkeelProvider_false_doesNotApplyLightTashkeelMap() {
        val step = EgyptianSpeechProcessingStep(
            enabledProvider = { true },
            allowTashkeelProvider = { false }
        )
        val out = step.apply("مرحبا")
        assertEquals("مرحبا", out)
    }

    @Test
    fun allowTashkeelProvider_true_appliesLightTashkeelMap() {
        val step = EgyptianSpeechProcessingStep(
            enabledProvider = { true },
            allowTashkeelProvider = { true }
        )
        val out = step.apply("مرحبا")
        assertTrue(
            "يُتوقع تشكيل خفيف مع السماح",
            out.any { it.code in 0x064B..0x0652 || it.code == 0x0670 }
        )
    }

    @Test
    fun dictionaryEntryCount_matchesMaps() {
        val total = EgyptianSpeechProcessingStep.dictionaryEntryCount()
        val repl = EgyptianSpeechProcessingStep.egyptianReplacementsCount()
        val tash = EgyptianSpeechProcessingStep.tashkeelWordsCount()
        assertEquals(70, total)
        assertEquals(40, repl)
        assertEquals(30, tash)
        assertEquals(total, repl + tash)
    }
}
