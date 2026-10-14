package com.aymankhattab.nateq.settings

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
        assertEquals(setOf("ar", "de", "en", "es", "fr", "ru", "tr"),
            codes.toSet())
    }
}