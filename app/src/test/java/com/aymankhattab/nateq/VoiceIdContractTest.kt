package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * عقد معرّفات الأصوات الموحّد: الصيغة الثابتة ar-EG / en-US / "fr" لكل
 * الأطراف (الكتالوج/المزوّد/الإعدادات). يحرس الاختبار من "انحراف" أحد
 * الجهات إلى صيغة nateq-* مرة أخرى — الخطأ الذي أسقط صوت اللغات غير
 * ar/en، ومن عودة صيغة "<lang>-local" المعطوبة على سامسونج (ليست Locale
 * صالحاً فتنهار شاشة TTS في النظام).
 */
class VoiceIdContractTest {

    /** أسماء الأصوات المعلنة في res/xml/tts_engine.xml — الإعلان الثابت
     *  للنظام قبل onGetVoices الديناميكي. يُقرأ من نظام الملفات (لا
     *  Robolectric) لأن موارد res/xml المعيارية لا تُحمَّل كأغلفة XML. */
    private fun declaredVoiceNames(): List<String> {
        val xml = File("src/main/res/xml/tts_engine.xml").readText()
        return Regex("""<voice\s+android:name="([^"]+)"\s*/>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
    }

    /** [VoiceIdContract.declaredVoiceNames] يجب أن يطابق أسماء الملف
     *  حرفاً بحرف وبنفس الترتيب — فهو المصدر المشترك الذي يلتزم به
     *  CHECK_TTS_DATA وonGetVoices معاً فلا ينحرف أحدهما عن الآخر. */
    @Test
    fun declaredVoiceNames_exactMatch_ttsEngineXml() {
        assertEquals(
            VoiceIdContract.declaredVoiceNames(),
            declaredVoiceNames()
        )
    }

    @Test
    fun ttsEngineXml_declaredNames_followContract() {
        val declared = declaredVoiceNames()
        assertTrue("الإعلان لا يحتوي صوت العربية", "ar-EG" in declared)
        assertTrue("الإعلان لا يحتوي صوت الإنجليزية", "en-US" in declared)
        // كل اسم معلن يجب أن يطابق صيغة العقد الموحّد (ar-EG/en-US/<lang>)
        for (name in declared) {
            assertEquals(
                "اسم الإعلان $name لا يطابق عقد المعرفات",
                name, VoiceIdContract.createIdForDeclared(name)
            )
        }
    }

    @Test
    fun createId_arAndEn_fixedFormats() {
        assertEquals("ar-EG", VoiceIdContract.createId(LanguageCode.AR.tag))
        assertEquals("en-US", VoiceIdContract.createId(LanguageCode.EN.tag))
        // لغات منويعة ISO-3 أيضًا تُقصى أولاً
        assertEquals("ar-EG", VoiceIdContract.createId("ara"))
        assertEquals("en-US", VoiceIdContract.createId("eng"))
    }

    @Test
    fun createId_foreignLanguages_validLocaleNames() {
        assertEquals("fr", VoiceIdContract.createId("fr"))
        assertEquals("de", VoiceIdContract.createId("de"))
        assertEquals("zh", VoiceIdContract.createId("zh"))
        assertEquals("es", VoiceIdContract.createId("es"))
        // الحروف الصغيرة دائمًا مهما كان المدخل
        assertEquals("fr", VoiceIdContract.createId("FR"))
    }

    @Test
    fun createId_neverProducesLocalSuffix() {
        // الخطأ الوظيفي الذي كسر شاشة TTS في سامسونج: "<lang>-local"
        // ليست Locale صالحاً فتنهار إعدادات النظام عند فتح اختيار المحرك.
        listOf("ar", "en", "fr", "de", "zh", "ja", "ru").forEach { lang ->
            val id = VoiceIdContract.createId(lang)
            org.junit.Assert.assertFalse(
                "لا يجب أن تظهر لاحقة -local في صيغة العقد ($id)",
                id.endsWith("-local", ignoreCase = true)
            )
            // كما يجب أن تبقى صالحة كـ Locale دائماً (قلبِ سلامة شاشة سامسونج)
            assertTrue(
                "الاسم المعلن يجب أن يُحلّ كـ Locale ($id)",
                java.util.Locale.forLanguageTag(id).language.isNotEmpty()
            )
        }
    }

    @Test
    fun createId_neverProducesNateqPrefix() {
        // الخطأ التاريخي الذي كسر تطابق اختيار اللغات غير ar/en
        listOf("ar", "en", "fr", "de", "zh", "ja", "ru").forEach { lang ->
            val id = VoiceIdContract.createId(lang)
            org.junit.Assert.assertFalse(
                "لا يجب أن تظهر بادئة nateq- في صيغة العقد ($id)",
                id.contains("nateq-", ignoreCase = true)
            )
        }
    }

    @Test
    fun normalize_legacyDriftBackToContract() {
        assertEquals("fr", VoiceIdContract.normalize("nateq-fr-local"))
        assertEquals("de", VoiceIdContract.normalize("nateq-de-local"))
        // الصيغة القديمة المعطوبة على سامسونج تُرقّى إلى صيغة Locale صالحة
        assertEquals("fr", VoiceIdContract.normalize("fr-local"))
        assertEquals("de", VoiceIdContract.normalize("de-local"))
        assertEquals("es", VoiceIdContract.normalize("es-local"))
        // القديم من نسخ ما قبل التسمية يبقى يُطبع كما كان
        assertEquals("ar-EG", VoiceIdContract.normalize("nateq-ar-1"))
        assertEquals("en-US", VoiceIdContract.normalize("nateq-en-1"))
        assertEquals("ar-EG", VoiceIdContract.normalize("ar-local"))
        assertEquals("en-US", VoiceIdContract.normalize("en-local"))
    }

    @Test
    fun normalize_currentAndNullPassThrough() {
        assertEquals("ar-EG", VoiceIdContract.normalize("ar-EG"))
        assertEquals("en-US", VoiceIdContract.normalize("en-US"))
        assertEquals("fr", VoiceIdContract.normalize("fr"))
        assertEquals("de", VoiceIdContract.normalize("de-local"))
        assertNull(VoiceIdContract.normalize(null))
    }
}