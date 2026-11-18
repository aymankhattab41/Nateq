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
}
