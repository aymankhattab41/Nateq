package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.ConvertPreferencesCodec
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات ترميز وتطبيع تفضيلات التحويل لكل لغة — منطق نقي بلا أجهزة
 * (JVM خالص، بنمط EnginePickerTest).
 */
class ConvertPreferencesCodecTest {

    // ---- تطبيع علامة اللغة (languageTag → كود ISO-2) ----

    @Test
    fun normalizeStripsCountryAndVariant() {
        assertEquals("ar", ConvertPreferencesCodec.normalizeLanguageTag("ar-EG"))
        assertEquals("en", ConvertPreferencesCodec.normalizeLanguageTag("en-US"))
        assertEquals("fr", ConvertPreferencesCodec.normalizeLanguageTag("fr-FR"))
        assertEquals("en", ConvertPreferencesCodec.normalizeLanguageTag("en_US"))
        assertEquals("zh", ConvertPreferencesCodec.normalizeLanguageTag("zh-Hans-CN"))
    }

    @Test
    fun normalizeMapsIso3ToIso2() {
        assertEquals("ar", ConvertPreferencesCodec.normalizeLanguageTag("ara"))
        assertEquals("en", ConvertPreferencesCodec.normalizeLanguageTag("eng"))
        assertEquals("de", ConvertPreferencesCodec.normalizeLanguageTag("deu"))
        assertEquals("es", ConvertPreferencesCodec.normalizeLanguageTag("spa"))
        assertEquals("fr", ConvertPreferencesCodec.normalizeLanguageTag("fra"))
    }

    @Test
    fun normalizeLowercasesAndToleratesWhitespace() {
        assertEquals("ar", ConvertPreferencesCodec.normalizeLanguageTag("AR"))
        assertEquals("en", ConvertPreferencesCodec.normalizeLanguageTag("ENG-US"))
        assertEquals("fr", ConvertPreferencesCodec.normalizeLanguageTag("  FR-fr  "))
    }

    @Test
    fun normalizeUnknownOrBlankFallsBackToUnd() {
        assertEquals("und", ConvertPreferencesCodec.normalizeLanguageTag(""))
        assertEquals("und", ConvertPreferencesCodec.normalizeLanguageTag("   "))
        assertEquals("und", ConvertPreferencesCodec.normalizeLanguageTag("-EG"))
    }

    // ---- بناء مدخل الحفظ ----

    @Test
    fun entryForSaveCoercesRangesAndBlanks() {
        val e = ConvertPreferencesCodec.entryForSave("", "voice", 5.0f, -3.0f, 9.0f)
        assertNull(e.engine)
        assertEquals("voice", e.voiceName)
        assertEquals(2.0f, e.rate, 0.001f)
        assertEquals(0.0f, e.pitch, 0.001f)
        assertEquals(1.0f, e.volume, 0.001f)
    }

    @Test
    fun hasAdjustmentDetectsRealConfig() {
        assertFalse(LanguageSpeechPrefs().hasAdjustment)
        assertTrue(LanguageSpeechPrefs(engine = "com.google.android.tts").hasAdjustment)
        assertTrue(LanguageSpeechPrefs(voiceName = "v").hasAdjustment)
        assertTrue(LanguageSpeechPrefs(rate = 1.25f).hasAdjustment)
        assertTrue(LanguageSpeechPrefs(pitch = 0.9f).hasAdjustment)
        assertTrue(LanguageSpeechPrefs(volume = 0.7f).hasAdjustment)
    }

    // ---- JSON: الجزء والتسلسل ----

    @Test
    fun fromJsonParsesValidMap() {
        val json = """{"ar":{"engine":"com.google.android.tts","voiceName":"ar-EG","rate":0.8,"pitch":1.0,"volume":0.9}}"""
        val map = ConvertPreferencesCodec.fromJson(json)
        assertEquals(1, map.size)
        val ar = map["ar"]
        assertTrue(ar != null)
        assertEquals("com.google.android.tts", ar!!.engine)
        assertEquals("ar-EG", ar.voiceName)
        assertEquals(0.8f, ar.rate, 0.001f)
        assertEquals(1.0f, ar.pitch, 0.001f)
        assertEquals(0.9f, ar.volume, 0.001f)
    }

    @Test
    fun fromJsonToleratesMissingFieldsWithDefaults() {
        val json = """{"en":{"engine":"com.svox.pico"}}"""
        val map = ConvertPreferencesCodec.fromJson(json)
        assertEquals(1, map.size)
        assertEquals("com.svox.pico", map["en"]!!.engine)
        assertEquals(1.0f, map["en"]!!.rate, 0.001f)
        assertEquals(1.0f, map["en"]!!.pitch, 0.001f)
        assertEquals(1.0f, map["en"]!!.volume, 0.001f)
    }

    @Test
    fun fromJsonCoercesOutOfRangeValues() {
        val json = """{"fr":{"rate":5.0,"volume":3.0,"pitch":-1.0}}"""
        val fr = ConvertPreferencesCodec.fromJson(json)["fr"]!!
        assertEquals(2.0f, fr.rate, 0.001f)
        assertEquals(1.0f, fr.volume, 0.001f)
        assertEquals(0.0f, fr.pitch, 0.001f)
    }

    @Test
    fun fromJsonRejectsMalformedAndBlank() {
        assertEquals(emptyMap<String, LanguageSpeechPrefs>(), ConvertPreferencesCodec.fromJson(null))
        assertEquals(emptyMap<String, LanguageSpeechPrefs>(), ConvertPreferencesCodec.fromJson(""))
        assertEquals(emptyMap<String, LanguageSpeechPrefs>(), ConvertPreferencesCodec.fromJson("   "))
        assertEquals(emptyMap<String, LanguageSpeechPrefs>(), ConvertPreferencesCodec.fromJson("{not json"))
        assertEquals(emptyMap<String, LanguageSpeechPrefs>(), ConvertPreferencesCodec.fromJson("[]"))
    }

    @Test
    fun toJsonRoundTrips() {
        val original = mapOf(
            "ar" to LanguageSpeechPrefs("com.google.android.tts", "ar-EG", 0.75f, 1.1f, 0.9f),
            "de" to LanguageSpeechPrefs("org.nobody.multitts", "de-female", 1.0f, 1.0f, 0.5f)
        )
        val restored = ConvertPreferencesCodec.fromJson(ConvertPreferencesCodec.toJson(original))
        assertEquals(original, restored)
    }

    // ---- الترحيل من السلوتات القديمة ----

    @Test
    fun mergeLegacySlotMigratesUnderDefaultKey_WhenLanguageMissing() {
        val target = mutableMapOf<String, LanguageSpeechPrefs>()
        ConvertPreferencesCodec.mergeLegacySlot(
            target, null,
            ConvertPreferencesCodec.entryForSave(null, "ar-EG", 0.8f, 1.0f, 1.0f),
            "ar"
        )
        ConvertPreferencesCodec.mergeLegacySlot(
            target, null,
            ConvertPreferencesCodec.entryForSave("com.svox.pico", null, 1.0f, 1.0f, 0.6f),
            "en"
        )
        assertEquals(setOf("ar", "en"), target.keys)
        assertEquals("ar-EG", target["ar"]!!.voiceName)
        assertEquals("com.svox.pico", target["en"]!!.engine)
    }

    @Test
    fun mergeLegacySlotNormalizesLanguageKey() {
        val target = mutableMapOf<String, LanguageSpeechPrefs>()
        ConvertPreferencesCodec.mergeLegacySlot(
            target, "ENG",
            ConvertPreferencesCodec.entryForSave("com.google.android.tts", null, 1.0f, 1.0f, 1.0f),
            "en"
        )
        // "ENG" يُوحَّد إلى "en" فيكتب في مفتاح en نفسه وليس eng
        assertEquals(setOf("en"), target.keys)
        assertEquals("com.google.android.tts", target["en"]!!.engine)
    }

    @Test
    fun mergeLegacySlotSkipsDefaultEntry() {
        val target = mutableMapOf<String, LanguageSpeechPrefs>()
        ConvertPreferencesCodec.mergeLegacySlot(
            target, "ar",
            ConvertPreferencesCodec.entryForSave(null, null, 0.8f, 0.6f, 1.0f), "ar"
        )
        ConvertPreferencesCodec.mergeLegacySlot(
            target, "fr",
            ConvertPreferencesCodec.entryForSave(null, null, 1.0f, 1.0f, 1.0f), "fr"
        )
        assertEquals(setOf("ar"), target.keys)
    }

    @Test
    fun mergeLegacySlotLastWriteWins() {
        val target = mutableMapOf(
            "ar" to LanguageSpeechPrefs("com.google.android.tts", "ar-EG", 1.0f, 1.0f, 1.0f)
        )
        ConvertPreferencesCodec.mergeLegacySlot(
            target, "ar-EG",
            ConvertPreferencesCodec.entryForSave("com.svox.pico", "male", 1.2f, 1.0f, 0.8f),
            "ar"
        )
        assertEquals("com.svox.pico", target["ar"]!!.engine)
        assertEquals(1.2f, target["ar"]!!.rate, 0.001f)
    }
}