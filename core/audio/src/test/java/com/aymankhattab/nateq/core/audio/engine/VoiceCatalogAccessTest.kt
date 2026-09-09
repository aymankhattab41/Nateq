package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبارات الوصول لاكتشاف «محركات كل لغة» (خريطة الاكتشاف). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCatalogAccessTest {

    private fun catalogWithDiscovery(): VoiceCatalog {
        val catalog = VoiceCatalog(emptyList())
        catalog.applyDiscovery(
            mapOf(
                "ar" to listOf(
                    EngineWithVoices(
                        "org.nobody.multitts", "MultiTTS", emptyList()
                    )
                ),
                "en" to listOf(
                    EngineWithVoices(
                        "com.google.android.tts", "Google TTS", emptyList()
                    )
                )
            )
        )
        return catalog
    }

    @Test
    fun discoveredEnginePackagesFor_returnsEnginesOfLanguage() {
        val catalog = catalogWithDiscovery()
        assertEquals(
            listOf("org.nobody.multitts"),
            catalog.discoveredEnginePackagesFor("ar")
        )
        assertEquals(
            listOf("com.google.android.tts"),
            catalog.discoveredEnginePackagesFor("en")
        )
    }

    @Test
    fun discoveredEnginePackagesFor_normalizesLanguageCode() {
        val catalog = catalogWithDiscovery()
        assertEquals(
            listOf("org.nobody.multitts"),
            catalog.discoveredEnginePackagesFor("AR")
        )
    }

    @Test
    fun discoveredEnginePackagesFor_unknownLanguage_isNull() {
        val catalog = catalogWithDiscovery()
        assertNull(catalog.discoveredEnginePackagesFor("fr"))
    }

    @Test
    fun discoveredEnginePackagesFor_emptyDiscovery_isNull() {
        val catalog = VoiceCatalog(emptyList())
        assertNull(catalog.discoveredEnginePackagesFor("ar"))
    }
}