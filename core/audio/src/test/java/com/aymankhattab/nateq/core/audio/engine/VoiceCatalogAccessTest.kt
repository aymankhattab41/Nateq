package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبارات الوصول لاكتشاف «محركات كل لغة» (خريطة الاكتشاف). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
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

    // ============ بند 8.2: اللغة المضمونة تُبنى بالبلد ============

    /** اللغتان المضمونتان دائماً تُعلَنان بصمتين حقيقيين بالبلد (ar-EG/
     *  en-US) لا بصمتين بلا بلد — فيُبلّغ onIsLanguageAvailable عودةً
     *  LANG_COUNTRY_AVAILABLE حقيقيّةً فتَلتقطُ محركاتُ سامسونج وغيرها
     *  اللغةَ بالبلدِ بدل الرفضِ الجزئي. */
    @Test
    fun supportedLocales_guaranteedLanguages_carryACountry() {
        val catalog = catalogWithDiscovery()
        val locales = catalog.supportedLocales()
        val ar = locales.first { it.language == "ar" }
        val en = locales.first { it.language == "en" }
        assertEquals("EG", ar.country)
        assertEquals("US", en.country)
    }

    /** لا نفقدُ أيَّ لغةٍ مكتشفةٍ ديناميكياً عند إضافة البلدِ للغتينِ
     *  الأساسيتين. */
    @Test
    fun supportedLocales_preservesDiscoveredLanguages() {
        val catalog = catalogWithDiscovery()
        val languages = catalog.supportedLocales().map { it.language }
        assertTrue(languages.containsAll(setOf("ar", "en")))
    }

    /** أصواتُ onGetVoices بلا أي محرك مكتشف (كتالوج فارغ) تساوي أصواتَ
     *  الإعلان الثابتة في tts_engine.xml تامةً — اتساق CHECK_TTS_DATA
     *  مع onGetVoices (لا تختفي الأصوات في قوائم النظام). */
    @Test
    fun supportedVoices_emptyCatalog_coversDeclaredVoices() {
        val catalog = VoiceCatalog(emptyList())
        val names = catalog.supportedVoices().map { it.name }
        assertEquals(
            com.aymankhattab.nateq.util.VoiceIdContract
                .declaredVoiceNames()
                .toSet(),
            names.toSet()
        )
    }

    // ============ بند 6.5: إبطال الاكتشاف عند تغير الحزم ============

    /** بعد [applyDiscovery] تكون الذاكرة طازجة (لا حاجة لتحديث)؛
     *  [invalidateDiscovery] تجعلها قابلة للتحديث فوراً حتى لو مُرَّت
     *  ثانيةٌ واحدة — بدل انتظار انقضاء فترة الصلاحية. */
    @Test
    fun invalidateDiscovery_forcesRefreshImmediately() {
        val catalog = catalogWithDiscovery()
        assertFalse(
            "اكتشافٌ طازجٌ لا يحتاج تحديثاً",
            catalog.needsRefresh(LONG_TTL_MS)
        )
        catalog.invalidateDiscovery()
        assertTrue(
            "الإبطال يجعل الاكتشاف بحاجة تحديثٍ فوراً",
            catalog.needsRefresh(LONG_TTL_MS)
        )
    }

    private companion object {
        /** فترة صلاحية طويلة تُبقي الاختبار بعيداً عن كرونومتر النظام. */
        const val LONG_TTL_MS = 60 * 60 * 1000L
    }
}