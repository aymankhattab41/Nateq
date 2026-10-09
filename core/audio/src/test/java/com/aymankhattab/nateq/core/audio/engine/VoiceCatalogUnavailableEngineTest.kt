package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات المحركات غير المتاحة واحتوائها في نتائج الاكتشاف (ع٣١-5 + ع٣١-7).
 */
class VoiceCatalogUnavailableEngineTest {

    @Test
    fun discoveryResult_implementsMap_andPreservesUnavailableEngines() {
        val engVoices = listOf(
            EngineWithVoices("com.engine.ok", "OK Engine", emptyList())
        )
        val langMap = mapOf("ar" to engVoices)
        val unavail = listOf(
            UnavailableEngine(
                enginePackage = "com.engine.failing",
                engineLabel = "Failing Engine",
                code = "NATIVE_INIT_FAILED",
                message = "JNI error"
            )
        )

        val result = DiscoveryResult(
            languages = langMap,
            unavailableEngines = unavail
        )

        // التحقق من سلوك الخريطة (Map delegation)
        assertEquals(1, result.size)
        assertTrue(result.containsKey("ar"))
        assertFalse(result.containsKey("en"))
        assertEquals(engVoices, result["ar"])

        // التحقق من قائمة المحركات غير المتاحة
        assertEquals(1, result.unavailableEngines.size)
        val firstUnavail = result.unavailableEngines[0]
        assertEquals("com.engine.failing", firstUnavail.enginePackage)
        assertEquals("Failing Engine", firstUnavail.engineLabel)
        assertEquals("NATIVE_INIT_FAILED", firstUnavail.code)
        assertEquals("JNI error", firstUnavail.message)
    }

    @Test
    fun unavailableEngine_holdsReasonAndCodeSafely() {
        val unavailNulls = UnavailableEngine(
            enginePackage = "pkg",
            engineLabel = "label"
        )
        assertEquals("pkg", unavailNulls.enginePackage)
        assertEquals("label", unavailNulls.engineLabel)
        org.junit.Assert.assertNull(unavailNulls.code)
        org.junit.Assert.assertNull(unavailNulls.message)
    }
}
