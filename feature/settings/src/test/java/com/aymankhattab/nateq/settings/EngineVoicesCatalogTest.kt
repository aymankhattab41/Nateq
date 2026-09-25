package com.aymankhattab.nateq.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات كتالوج أصوات المحركات المتدرجة (لغة ← محرك ← أصوات):
 *  ترتيب اللغات، قوائم الأصوات للمحرك/اللغة، السقوط للأصوات المنطقية،
 *  والتسميات المعروضة — منطق نقي بلا أندرويد. */
class EngineVoicesCatalogTest {

    private val acapella = EngineVoicesRow(
        enginePackage = "com.acapella",
        engineLabel = "Acapella",
        voices = listOf(
            VoiceOption("ar-EG-Wavenet-A", "الويني"),
            VoiceOption("ar-EG-Wavenet-B", "الويني B")
        )
    )

    private val google = EngineVoicesRow(
        enginePackage = "com.google.android.tts",
        engineLabel = "Google",
        voices = listOf(
            VoiceOption("ar-XA-Wavenet-C", "Wavenet C")
        )
    )

    /** صوت محركٍ مكتشفٍ فعلي (مثل Google TTS) — معرّفه لا يطابق
     *  صيغ اللورد المنطقية، ويمثل جذر «ارتداد الصوت إلى العربية». */
    private val googleEn = EngineVoicesRow(
        enginePackage = "com.google.android.tts",
        engineLabel = "Google",
        voices = listOf(
            VoiceOption("com.google.android.tts:eng-usa", "English US")
        )
    )

    private val catalog = EngineVoicesCatalog(
        initial = mapOf(
            "ar" to listOf(acapella, google),
            "en" to listOf(googleEn),
            "fr" to listOf(
                EngineVoicesRow(
                    "com.vocalizer",
                    "Vocalizer",
                    listOf(VoiceOption("fr-FR-Sylvia", "Sylvia"))
                )
            )
        ),
        arabicVoiceLabel = "العربية",
        englishVoiceLabel = "English"
    )

    @Test
    fun `languages prioritize arabic then english then rest alphabetical`() {
        val languages = catalog.languages()
        assertEquals(listOf("ar", "en", "fr"), languages)
    }

    @Test
    fun `language display name uses native label`() {
        assertEquals("العربية", catalog.languageDisplayName("ar"))
        assertEquals("English", catalog.languageDisplayName("en"))
    }

    @Test
    fun `voices are scoped to the engine package`() {
        val options = catalog.voicesFor("ar", "com.acapella")
        assertEquals(
            listOf("ar-EG-Wavenet-A", "ar-EG-Wavenet-B"),
            options.map { it.name }
        )
        assertEquals("الويني", options[0].label)
    }

    @Test
    fun `flattened listing prefixes labels with the engine name`() {
        val options = catalog.voicesFor("ar", null)
        assertEquals(
            listOf("ar-EG-Wavenet-A", "ar-EG-Wavenet-B", "ar-XA-Wavenet-C"),
            options.map { it.name }
        )
        assertEquals("Acapella: الويني", options[0].label)
        assertEquals("Google: Wavenet C", options[2].label)
    }

    @Test
    fun `unknown engine for language falls back to logical voices`() {
        val options = catalog.voicesFor("ar", "com.unknown.engine")
        assertEquals(listOf(VoiceOption("ar-EG", "العربية")), options)
    }

    @Test
    fun `fallback returns logical voices for ar and en only`() {
        assertEquals(
            listOf(VoiceOption("ar-EG", "العربية")),
            catalog.fallbackVoices("ar")
        )
        assertEquals(
            listOf(VoiceOption("en-US", "English")),
            catalog.fallbackVoices("en")
        )
        assertTrue(catalog.fallbackVoices("fr").isEmpty())
    }

    @Test
    fun `empty catalog yields empty voice list for unknown languages`() {
        val empty = EngineVoicesCatalog(
            emptyMap(), "العربية", "English"
        )
        assertTrue(empty.voicesFor("fr", null).isEmpty())
        assertEquals(
            listOf("ar", "en"), empty.languages()
        )
    }

    @Test
    fun `update replaces discovery map`() {
        val mutable = EngineVoicesCatalog(
            emptyMap(), "العربية", "English"
        )
        mutable.update(mapOf("ar" to listOf(acapella)))
        assertEquals(
            listOf("ar-EG-Wavenet-A", "ar-EG-Wavenet-B"),
            mutable.voicesFor("ar", "com.acapella").map { it.name }
        )
    }

    @Test
    fun `languageOfVoice resolves discovered voice to its language`() {
        assertEquals("ar", catalog.languageOfVoice("ar-XA-Wavenet-C"))
        assertEquals("en", catalog.languageOfVoice(
            "com.google.android.tts:eng-usa"
        ))
        assertEquals("fr", catalog.languageOfVoice("fr-FR-Sylvia"))
        assertNull(catalog.languageOfVoice("unknown-voice-id"))
        assertNull(catalog.languageOfVoice(""))
    }

    @Test
    fun `languageForSavedVoice resolves logical identifiers directly`() {
        assertEquals("ar", catalog.languageForSavedVoice("ar-EG"))
        assertEquals("en", catalog.languageForSavedVoice("en-US"))
        assertEquals("ar", catalog.languageForSavedVoice("ar-local"))
        assertEquals("en", catalog.languageForSavedVoice("en-local"))
        assertEquals("ar", catalog.languageForSavedVoice("nateq-arabic-1"))
    }

    @Test
    fun `languageForSavedVoice finds discovered engine voices`() {
        // جذر الخلل المُصلَح: صوت إنجليزي من محركٍ مكتشف كان يُستنتج
        // «عربية» فيرتد الصوت الافتراضي إلى العربية — الآن يُكشف إنجليزياً.
        assertEquals(
            "en", catalog.languageForSavedVoice(
                "com.google.android.tts:eng-usa"
            )
        )
        assertEquals("fr", catalog.languageForSavedVoice("fr-FR-Sylvia"))
        assertNull(catalog.languageForSavedVoice("com.unknown:pitch"))
    }
}