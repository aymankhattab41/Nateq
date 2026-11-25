package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullUrlSpeechBypassTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        // تفعيل ميزة اختصار الروابط
        settings.setAccessibilityFeatureEnabled(
            "a11y_url_shortening",
            true
        )
    }

    @Test
    fun fullUrl_readViaFullUrlSource_preservesPathAndQuery() {
        val rawUrl = "https://example.com/account/detail?id=7"
        val message = "رابط $rawUrl"

        val prepared = SpeechEntryPoints.prepare(
            rawText = message,
            source = SpeechEntryPoints.Source.FULL_URL,
            context = context
        )

        // التحقق من بقاء المسار والاستعلام كاملاً
        assertTrue(
            "يجب بقاء المسار والاستعلام: $prepared",
            prepared.contains("/account/detail?id=7")
        )
        // التحقق من عدم تكرار كلمة "رابط رابط"
        assertFalse(
            "يجب عدم تكرار كلمة رابط: $prepared",
            prepared.contains("رابط رابط")
        )
        assertEquals(
            "رابط https://example.com/account/detail?id=7",
            prepared
        )
    }

    @Test
    fun fullUrl_readViaNormalSource_shortensWhenFeatureIsEnabled() {
        val rawUrl = "https://example.com/account/detail?id=7"
        val message = "رابط $rawUrl"

        val prepared = SpeechEntryPoints.prepare(
            rawText = message,
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )

        // في قارئ الشاشة العادي مع تفعيل الاختصار، يُختصر الرابط
        assertFalse(
            "في القارئ العادي يُختصر المسار: $prepared",
            prepared.contains("/account/detail?id=7")
        )
        assertTrue(
            "يجب أن يحوي النطاق المختصر: $prepared",
            prepared.contains("example.com")
        )
    }
}
