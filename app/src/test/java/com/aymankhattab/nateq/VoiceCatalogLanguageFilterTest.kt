package com.aymankhattab.nateq

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات فلترة اللغات في الاكتشاف: القائمة النهائية لا تظهر أي لغة
 * تُعلن بياناتها غير مثبتة (TextToSpeech.LANG_MISSING_DATA) حتى لو
 * أعلن المحرك دعمها نظرياً. منطق نقي عبر Robolectric (لا محرك حقيقي).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCatalogLanguageFilterTest {

    private fun voice(locale: Locale): Voice =
        Voice(
            "voice-" + locale.toLanguageTag(),
            locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            false,
            emptySet()
        )

    private fun notInstalledVoice(locale: Locale): Voice =
        Voice(
            "voice-" + locale.toLanguageTag(),
            locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        )

    private fun networkVoice(locale: Locale): Voice =
        Voice(
            "voice-" + locale.toLanguageTag(),
            locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
true, // isNetworkConnectionRequired:
            // يُخلَّق عبر الشبكة لا من بيانات محلية
            emptySet()
        )

    @Test
    fun voiceDeclaredNotInstalled_removedEvenWhenAvailabilitySaysAvailable() {
        // جوجل يعلن الآسامية متاحة (LANG_AVAILABLE) لكن صوتها يحمل
        // KEY_FEATURE_NOT_INSTALLED (غير مُنزَّل محلياً) — تُحجب من القائمة
        val voices = listOf(
            voice(Locale.forLanguageTag("ar-SA")),
            voice(Locale.forLanguageTag("en-US")),
            notInstalledVoice(Locale.forLanguageTag("as-IN"))
        )
        val filtered = VoiceCatalog.filterVoicesWithInstalledData(voices) {
            if (it.language == "as") {
                TextToSpeech.LANG_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        assertEquals(
            setOf("ar", "en"),
            filtered.map { it.locale.language }.toSet()
        )
        assertFalse(filtered.any { it.locale.language == "as" })
    }

    @Test
    fun networkOnlyVoice_removedEvenWhenAvailabilitySaysAvailable() {
        // جوجل يعلن البلغارية متاحة لكن صوتها يتطلب اتصال الشبكة
        // (isNetworkConnectionRequired=true) — لا ملفات محلية مثبّتة — تُحجب
        val voices = listOf(
            voice(Locale.forLanguageTag("en-US")),
            networkVoice(Locale.forLanguageTag("bg-BG"))
        )
        val filtered = VoiceCatalog.filterVoicesWithInstalledData(voices) {
            if (it.language == "bg") {
                TextToSpeech.LANG_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        assertEquals(setOf("en"), filtered.map { it.locale.language }.toSet())
        assertFalse(filtered.any { it.locale.language == "bg" })
    }

    @Test
    fun missingDataLanguage_removedFromVoices() {
        val voices = listOf(
            voice(Locale.forLanguageTag("ar-SA")),
            voice(Locale.forLanguageTag("en-US")),
            voice(Locale.forLanguageTag("fr-FR"))
        )
        // محاكاة: الفرنسية تعلن بياناتها غير مثبتة (LANG_MISSING_DATA)
        val availability = { locale: Locale ->
            if (locale.language == "fr") {
                TextToSpeech.LANG_MISSING_DATA
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        val filtered = VoiceCatalog.filterVoicesWithInstalledData(
            voices, availability
        )
        assertEquals(
            setOf("ar", "en"),
            filtered.map { it.locale.language }.toSet()
        )
        assertFalse(filtered.any { it.locale.language == "fr" })
    }

    @Test
    fun missingDataLanguage_absentFromFinalList() {
        val voicesByEngine = mapOf(
            "com.samsung.SMT" to VoiceCatalog.filterVoicesWithInstalledData(
                listOf(
                    voice(Locale.forLanguageTag("ar-SA")),
                    voice(Locale.forLanguageTag("fr-FR"))
                )
            ) { locale ->
                if (locale.language == "fr") {
                    TextToSpeech.LANG_MISSING_DATA
                } else {
                    TextToSpeech.LANG_AVAILABLE
                }
            }
        )
        val finalList = VoiceCatalog.groupVoicesByLanguage(
            listOf("com.samsung.SMT" to "Samsung"),
            voicesByEngine
        )
        assertTrue(finalList.containsKey("ar"))
        assertFalse(finalList.containsKey("fr"))
        assertEquals(listOf("ar"), finalList.keys.sorted())
    }

    @Test
    fun languageDeclaredButNeverInVoices_isAbsent() {
        // المحرك يدّعي نظرياً دعم الألمانية، لكن لا صوت de في getVoices
        // — لا تظهر
        val voicesByEngine = mapOf(
            "com.google.android.tts" to listOf(
                voice(Locale.forLanguageTag("en-US"))
            )
        )
        val finalList = VoiceCatalog.groupVoicesByLanguage(
            listOf("com.google.android.tts" to "Google"),
            voicesByEngine
        )
        assertTrue(finalList.containsKey("en"))
        assertFalse(finalList.containsKey("de"))
        assertFalse(finalList.containsKey("fr"))
    }

    @Test
    fun screenReaderVoices_notAddedToDiscoveredLanguages() {
        // Talkman/Jieshuo (محرك eSpeak) يردّ لغاتٍ نظرية af/am عبر getVoices
        // رغم عدم تثبيت بياناتها. الاستبعاد يتم قبل discovery بفلترة حزم
        // قارئات الشاشة (نفس ما يفعله
        // VoiceCatalog.discoverAllLanguagesAcrossEngines)
        val voicesByEngine = mapOf(
            "com.nirenr.talkman" to listOf(
                voice(Locale.forLanguageTag("af-ZA")),
                voice(Locale.forLanguageTag("am-ET")),
                voice(Locale.forLanguageTag("en-US"))
            )
        )
        val realEngines = voicesByEngine.filterKeys {
            !EnginePicker.isScreenReader(it)
        }
        val discovered = VoiceCatalog.groupVoicesByLanguage(
            realEngines.map { it.key to it.key },
            realEngines
        )
        // القارئ محجوب بالكامل من الاكتشاف — لا لغاته النظرية تظهر إطلاقاً
        assertTrue(discovered.isEmpty())
        assertFalse(discovered.containsKey("af"))
        assertFalse(discovered.containsKey("am"))
        assertFalse(discovered.containsKey("en"))
    }

    @Test
    fun realEngines_keepAfrikaansOnlyWhenActuallyInstalled() {
        // محرك حقيقي (وليس قارئ شاشة) يعلن بيانات افريقية مثبتة — تُحفظ
        val voicesByEngine = mapOf(
            "com.google.android.tts" to listOf(
                voice(Locale.forLanguageTag("af-ZA"))
            )
        )
        val discovered = VoiceCatalog.groupVoicesByLanguage(
            listOf("com.google.android.tts" to "Google"),
            voicesByEngine
        )
        assertTrue(discovered.containsKey("af"))
    }
}