package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * عقد معرّفات الأصوات الموحّد: الصيغة الثابتة ar-EG / en-US / "<lang>-local"
 * لكل الأطراف (الكتالوج/المزوّد/الإعدادات). يحرس الاختبار من "انحراف" أحد
 * الجهات إلى صيغة nateq-* مرة أخرى — الخطأ الذي أسقط صوت اللغات غير ar/en.
 */
class VoiceIdContractTest {

    @Test
    fun createId_arAndEn_fixedFormats() {
        assertEquals("ar-EG", VoiceIdContract.createId(LanguageCode.AR.tag))
        assertEquals("en-US", VoiceIdContract.createId(LanguageCode.EN.tag))
        // لغات منويعة ISO-3 أيضًا تُقصى أولاً
        assertEquals("ar-EG", VoiceIdContract.createId("ara"))
        assertEquals("en-US", VoiceIdContract.createId("eng"))
    }

    @Test
    fun createId_foreignLanguages_localSuffix() {
        assertEquals("fr-local", VoiceIdContract.createId("fr"))
        assertEquals("de-local", VoiceIdContract.createId("de"))
        assertEquals("zh-local", VoiceIdContract.createId("zh"))
        assertEquals("es-local", VoiceIdContract.createId("es"))
        // الحروف الصغيرة دائمًا مهما كان المدخل
        assertEquals("fr-local", VoiceIdContract.createId("FR"))
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
        assertEquals("fr-local", VoiceIdContract.normalize("nateq-fr-local"))
        assertEquals("de-local", VoiceIdContract.normalize("nateq-de-local"))
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
        assertEquals("fr-local", VoiceIdContract.normalize("fr-local"))
        assertNull(VoiceIdContract.normalize(null))
    }
}