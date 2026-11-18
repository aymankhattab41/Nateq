package com.aymankhattab.nateq.core.audio.providers

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** حارس «لا إخفاء لاسم صوت غير مثبت»: يُعلَن ويُعاد كما هو. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VocalizerVoiceMismatchTest {

    @Test
    fun missingInstalledVoice_isAnnouncedAndRequestIsPreserved() {
        val installed = listOf(
            InstalledVocalizerVoice("Laila", "ar"),
            InstalledVocalizerVoice("Maged", "ar")
        )
        val result = resolveInstalledVocalizerVoice(
            context = ApplicationProvider.getApplicationContext(),
            requestedName = "Ghost",
            requestedLanguage = "fr",
            installed = installed
        )
        assertFalse("يجب الإعلان عن غياب الاسم المطلوب", result.matched)
        assertEquals("يجب إعادة الاسم المطلوب كما هو", "Ghost", result.name)
    }

    @Test
    fun languageConversion_isMatchedButReportedAsConversion() {
        val result = resolveInstalledVocalizerVoice(
            context = ApplicationProvider.getApplicationContext(),
            requestedName = "ar-EG",
            requestedLanguage = "ar",
            installed = listOf(InstalledVocalizerVoice("Maged", "ar"))
        )
        assertEquals("Maged", result.name)
        assertEquals("تحويل إلى صوت مثبت للغة الطلب", result.reason)
    }
}
