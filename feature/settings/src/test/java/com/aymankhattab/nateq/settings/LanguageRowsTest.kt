package com.aymankhattab.nateq.settings

import android.speech.tts.Voice
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات بناء صفوف كل اللغات المكتشفة في حوار التحويل
 *  (بند 17.2) — نقي JVM. */
class LanguageRowsTest {

    private fun enginesOf(vararg packages: String): List<EngineWithVoices> =
        packages.map { EngineWithVoices(it, "", emptyList()) }

    @Test
    fun allDiscoveredLanguages_included() {
        val discovered = mapOf(
            "fr" to enginesOf("fr.pkg"),
            "de" to enginesOf("de.pkg"),
            "ja" to enginesOf("ja.pkg"),
            "ar" to enginesOf("ar.pkg"),
            "en" to enginesOf("en.pkg")
        )
        val rows = buildAllLanguageRows(discovered)
        val tags = rows.map { it.languageTag }
        assertEquals(listOf("ar", "en", "de", "fr", "ja"), tags)
    }

    @Test
    fun arabicAndEnglish_alwaysFirst_guaranteedWhenMissing() {
        val discovered = mapOf(
            "fr" to enginesOf("fr.pkg"),
            "de" to enginesOf("de.pkg")
        )
        val rows = buildAllLanguageRows(discovered)
        val tags = rows.map { it.languageTag }
        assertEquals(listOf("ar", "en", "de", "fr"), tags)
        rows.take(2).forEach {
            assertTrue("ar/en بلا محرك لا تعني غيابهما", it.engines.isEmpty())
        }
    }

    @Test
    fun emptyDiscovery_returnsJustArabicAndEnglish() {
        val rows = buildAllLanguageRows(emptyMap())
        assertEquals(listOf("ar", "en"), rows.map { it.languageTag })
    }

    @Test
    fun enginesCarriedFromDiscovery() {
        val discovered = mapOf("fr" to enginesOf("fr.engine"))
        val frRow = buildAllLanguageRows(discovered).first {
            it.languageTag == "fr"
        }
        assertEquals("fr.engine", frRow.engines.single().enginePackage)
    }

    @Test
    fun displayNames_areNonBlank() {
        val rows = buildAllLanguageRows(mapOf("de" to enginesOf("de.pkg")))
        rows.forEach {
            assertTrue(
                "اسم عرض غير فارغ: ${it.languageTag}",
                it.displayName.isNotBlank()
            )
        }
    }

    // ── سقوط أصوات المحرك المدمج في اللغات الفارغة (شكوى «لا أصوات») ──

    @Test
    fun `guaranteed rows fill empty ar and en with lord voices`() {
        val byName = lordsVoicesByName()
        val guaranteed = buildLordsGuaranteedRows(
            "app.pkg", "Lord TTS", byName
        )
        val rows = buildAllLanguageRows(emptyMap(), guaranteed)
        assertEquals(listOf("ar", "en"), rows.map { it.languageTag })
        val arRow = rows.first { it.languageTag == "ar" }
        val enRow = rows.first { it.languageTag == "en" }
        assertEquals(1, arRow.engines.size)
        assertEquals("app.pkg", arRow.engines.single().enginePackage)
        assertEquals("Lord TTS", arRow.engines.single().engineLabel)
        assertSame(
            "صوت اللورد العربي يرصد في صف العربية (لا يُقرأ اسمُه)",
            byName.getValue("ar-EG"), arRow.engines.single().voices.single()
        )
        assertSame(
            "صوت اللورد الإنجليزي يرصد في صف الإنجليزية",
            byName.getValue("en-US"), enRow.engines.single().voices.single()
        )
    }

    @Test
    fun `guaranteed rows never override a discovered engine`() {
        val discovered = mapOf(
            "ar" to enginesOf("ar.pkg"),
            "en" to enginesOf("en.pkg")
        )
        val guaranteed = buildLordsGuaranteedRows(
            "app.pkg", "Lord TTS", lordsVoicesByName()
        )
        val rows = buildAllLanguageRows(discovered, guaranteed)
        rows.forEach { row ->
            assertEquals(
                "الاكتشاف الحقيقي يتقدَّم على السقوط",
                discovered[row.languageTag]!!.map { it.enginePackage },
                row.engines.map { it.enginePackage }
            )
        }
    }

    @Test
    fun `guaranteed rows skip language with no declared voice`() {
        val guaranteed = buildLordsGuaranteedRows(
            "app.pkg", "Lord TTS", mapOf("en-US" to voiceOf("en-US"))
        )
        assertEquals(listOf("en"), guaranteed.keys.toList())
    }

    @Test
    fun `guaranteed rows leave unrelated discovered languages untouched`() {
        val discovered = mapOf("fr" to enginesOf("fr.pkg"))
        val guaranteed = buildLordsGuaranteedRows(
            "app.pkg", "Lord TTS", lordsVoicesByName()
        )
        val frRow = buildAllLanguageRows(
            discovered, guaranteed
        ).first { it.languageTag == "fr" }
        assertEquals(1, frRow.engines.size)
        assertEquals("fr.pkg", frRow.engines.single().enginePackage)
    }

    private fun lordsVoicesByName(): Map<String, Voice> = mapOf(
        "ar-EG" to voiceOf("ar-EG"),
        "en-US" to voiceOf("en-US")
    )

    private fun voiceOf(name: String): Voice = Voice(
        name,
        Locale.forLanguageTag(name),
        Voice.QUALITY_HIGH,
        Voice.LATENCY_LOW,
        false,
        emptySet()
    )
}