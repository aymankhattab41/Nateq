package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.providers.MicrosoftCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * حارسُ عقدِ شجرة الأصوات الهرمية: يثبّت أن الأصوات تُجمَّع
 * صوتاً واحداً بتعدّد جوداته، وأن اللغات مميّزة، وأن فصل
 * المتغيّرات يعتمد على اسم الملف لا على النص المعروض.
 */
class EmbeddedVoiceTreeTest {

    private val arSa = Locale.forLanguageTag("ar-SA")

    @Test
    fun vocalizer_arabicSaudi_groupsFourVoicesTwoQualitiesEach() {
        val voices = EmbeddedVoiceTree.vocalizerVoices(arSa)
        assertEquals(4, voices.size)
        val laila = voices.first { it.key == "Laila" }
        assertEquals(2, laila.variants.size)
        assertEquals(
            setOf("Compact", "High"),
            laila.variants.map { it.quality }.toSet()
        )
        assertEquals("ليلى", laila.titleAr)
        assertEquals("Laila", laila.titleEn)
    }

    @Test
    fun vocalizerLanguages_areDistinctAndSortedByTag() {
        val langs = EmbeddedVoiceTree.vocalizerLanguages()
        assertEquals(langs.size, langs.distinct().size)
        val tags = langs.map { it.toLanguageTag() }
        assertEquals(tags.sorted(), tags)
        assertTrue(tags.any { it.startsWith("ar") })
    }

    @Test
    fun vocalizerVoices_unknownLocale_returnsEmpty() {
        assertTrue(
            EmbeddedVoiceTree.vocalizerVoices(
                Locale.forLanguageTag("xx-XX")
            ).isEmpty()
        )
    }

    @Test
    fun vocalizerVoices_english_mergesBothRegions() {
        val en = EmbeddedVoiceTree.vocalizerVoices(
            Locale.forLanguageTag("en-US")
        )
        assertEquals(6, en.size)
    }

    @Test
    fun vocalizerLanguages_hasOneEntryPerLanguage() {
        val langs = EmbeddedVoiceTree.vocalizerLanguages()
        val codes = langs.map { it.language }
        assertEquals(codes.size, codes.distinct().size)
        assertEquals(
            setOf("ar", "de", "en", "es", "fr", "ru", "tr"),
            codes.toSet()
        )
    }

    @Test
    fun microsoft_groupsVoicesAndExtractsLanguages() {
        val sample1 = MicrosoftCatalogItem(
            id = "ar-eg-salma",
            voiceName = "Salma",
            displayNameAr = "سلمى (مصر)",
            displayNameEn = "Salma (Egypt)",
            locale = Locale.forLanguageTag("ar-EG"),
            languageCategory = "ar",
            gender = "Female",
            modelName = "ar-EG-SalmaNeural",
            downloadUrl = "https://example.com/salma.zip",
            sampleUrl = null,
            sizeBytes = 25000000L,
            archiveSha256 = "abc",
            topDirName = "ar-EG-SalmaNeural",
            quality = "Neural"
        )
        val sample2 = MicrosoftCatalogItem(
            id = "en-us-jenny",
            voiceName = "Jenny",
            displayNameAr = "جيني (الولايات المتحدة)",
            displayNameEn = "Jenny (United States)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            gender = "Female",
            modelName = "en-US-JennyNeural",
            downloadUrl = "https://example.com/jenny.zip",
            sampleUrl = null,
            sizeBytes = 30000000L,
            archiveSha256 = "def",
            topDirName = "en-US-JennyNeural",
            quality = "Neural"
        )
        val items = listOf(sample1, sample2)
        val langs = EmbeddedVoiceTree.microsoftLanguages(items)
        assertEquals(2, langs.size)
        assertEquals(listOf("ar", "en"), langs.map { it.language })

        val arVoices = EmbeddedVoiceTree.microsoftVoices(
            items,
            Locale.forLanguageTag("ar-EG")
        )
        assertEquals(1, arVoices.size)
        assertEquals("Salma", arVoices[0].key)
        assertEquals("سلمى", arVoices[0].titleAr)
        assertEquals("Salma", arVoices[0].titleEn)
    }
}
