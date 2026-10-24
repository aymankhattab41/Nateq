package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.settings.EngineLanguageConsistency.mismatchedLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبار ع٤٠-2: اتساق لغة/محرك/صوت نقطة الدخول (عربي/إنجليزي). */
class SettingsEngineLanguageConsistencyTest {

    /** أكواد صوت مطابقة لصاحبها بصيغة اللغة-العلم المتوقعة. */
    private val matchingEntries = listOf(
        Triple("ar", "com.google.android.tts" as String?, "ar-EG" as String?),
        Triple("en", "com.google.android.tts" as String?, "en-US" as String?)
    )

    /** عارض صوت أجنبي عن صاحب المحرك يُكتشف ويعاد بصيغة Triple كامل. */
    @Test
    fun mismatchedVoice_isReportedAsFullTriple() {
        val googleTts = "com.google.android.tts"
        val entries = listOf(
            Triple("ar", "samsung" as String?, "ara-ara-stand" as String?),
            Triple("en", googleTts as String?, "en-GB" as String?)
        )
        val reported = mismatchedLanguages(entries)
        assertEquals(1, reported.size)
        assertEquals("ar", reported[0].first)
        assertEquals("ara-ara-stand", reported[0].second)
        assertEquals("samsung", reported[0].third)
    }

    /** المطابقة الكاملة تمرّ بلا تنبيه، والفراغ والمدخل الناقص يُهمَلان. */
    @Test
    fun matchingOrIncompleteEntries_yieldNoReport() {
        val googleTts = "com.google.android.tts"
        assertTrue(mismatchedLanguages(matchingEntries).isEmpty())
        assertTrue(mismatchedLanguages(emptyList()).isEmpty())
        assertTrue(
            mismatchedLanguages(
                listOf(Triple("ar", googleTts as String?, null as String?))
            ).isEmpty()
        )
        assertTrue(
            mismatchedLanguages(
                listOf(Triple("en", null as String?, "en-US" as String?))
            ).isEmpty()
        )
    }
}
