package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.engine.EngineWithVoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات بناء صفوف كل اللغات المكتشفة في حوار التحويل (بند 17.2) — نقي JVM. */
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
        rows.take(2).forEach { assertTrue("ar/en بلا محرك لا تعني غيابهما", it.engines.isEmpty()) }
    }

    @Test
    fun emptyDiscovery_returnsJustArabicAndEnglish() {
        val rows = buildAllLanguageRows(emptyMap())
        assertEquals(listOf("ar", "en"), rows.map { it.languageTag })
    }

    @Test
    fun enginesCarriedFromDiscovery() {
        val discovered = mapOf("fr" to enginesOf("fr.engine"))
        val frRow = buildAllLanguageRows(discovered).first { it.languageTag == "fr" }
        assertEquals("fr.engine", frRow.engines.single().enginePackage)
    }

    @Test
    fun displayNames_areNonBlank() {
        val rows = buildAllLanguageRows(mapOf("de" to enginesOf("de.pkg")))
        rows.forEach { assertTrue("اسم عرض غير فارغ: ${it.languageTag}", it.displayName.isNotBlank()) }
    }
}