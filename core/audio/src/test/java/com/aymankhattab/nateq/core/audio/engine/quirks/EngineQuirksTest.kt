package com.aymankhattab.nateq.core.audio.engine.quirks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineQuirksTest {

    @Test
    fun vocalizerQuirks_loadedCorrectly() {
        val q = EngineQuirks.forPackage("es.codefactory.vocalizertts")
        assertEquals("es.codefactory.vocalizertts", q.packageName)
        assertTrue("Vocalizer يحتاج warmup polling", q.needsWarmupPolling)
        assertTrue("Vocalizer يتطلب إقليماً", q.requiresLocaleWithRegion)
        assertTrue("Vocalizer يرفض الصوت بصمت أحياناً", q.rejectsVoiceSilently)
        assertTrue(
            "Vocalizer يحتاج setLanguage قبل setVoice",
            q.needsSetLanguageBeforeSetVoice
        )
        assertEquals(2.5f, q.maxSafeSpeechRate, 0.001f)
        assertEquals(TashkeelPolicy.UNKNOWN, q.tashkeelPolicy)
        assertTrue(
            "العربية في اللغات المشكلة",
            q.problematicLanguages.contains("ar")
        )
    }

    @Test
    fun unknownEngine_returnsDefaults() {
        val q = EngineQuirks.forPackage("com.unknown.engine")
        assertEquals("com.unknown.engine", q.packageName)
        assertFalse(q.needsWarmupPolling)
        assertFalse(q.requiresLocaleWithRegion)
        assertFalse(q.rejectsVoiceSilently)
        assertFalse(q.needsSetLanguageBeforeSetVoice)
        assertEquals(2.5f, q.maxSafeSpeechRate, 0.001f)
        assertEquals(TashkeelPolicy.UNKNOWN, q.tashkeelPolicy)
        assertTrue(q.problematicLanguages.isEmpty())
    }

    @Test
    fun nullPackage_returnsEmptyDefaults() {
        val q = EngineQuirks.forPackage(null)
        assertEquals("", q.packageName)
        assertFalse(q.needsWarmupPolling)
    }

    @Test
    fun tashkeelPolicyEnum_hasAllValues() {
        val values = TashkeelPolicy.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(TashkeelPolicy.UNKNOWN))
        assertTrue(values.contains(TashkeelPolicy.KEEP_TASHKEEL))
        assertTrue(values.contains(TashkeelPolicy.STRIP_TASHKEEL))
        assertTrue(values.contains(TashkeelPolicy.PARTIAL_TASHKEEL))
    }
}