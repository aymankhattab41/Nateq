package com.aymankhattab.nateq.core.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementLanguageResolver
import com.aymankhattab.nateq.core.audio.announcement.TimeAnnouncementManager
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
import com.aymankhattab.nateq.core.audio.engine.SpeechLatencyTracker
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppSuspensionStore
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.VoiceIdContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 54:
 * الأمر 1 (إنستاباي والتعطيل) والأمر 2 والأمر 4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersRound54AudioTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        settings.resetAllToDefault()
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        ProtectedAppTimelineTracker.clear()
        settings.setSuspendedProtectedApp(null)
    }

    // --- اختبارات الأمر 1: إنستاباي والتعطيل والمنبه ---

    @Test
    fun testOrder1_matchProtectedAppWithAliasesAndDiacritics() {
        val app = ProtectedApp(
            packageName = "com.emeint.android.mwallet",
            label = "إنستاباي",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        settings.setProtectedApps(listOf(app))

        // مطابقة بالاسم المشكول
        val m1 = ProtectedAppManager.matchProtectedApp(context, "إِنْسْتَابَاي")
        assertNotNull(m1)
        assertEquals("com.emeint.android.mwallet", m1?.packageName)

        // مطابقة بالاسم المجرد بدون همزة
        val m2 = ProtectedAppManager.matchProtectedApp(context, "انستاباي")
        assertNotNull(m2)
        assertEquals("com.emeint.android.mwallet", m2?.packageName)

        // مطابقة بالإنجليزية
        val m3 = ProtectedAppManager.matchProtectedApp(context, "InstaPay")
        assertNotNull(m3)
        assertEquals("com.emeint.android.mwallet", m3?.packageName)

        // مطابقة بمسافة
        val m4 = ProtectedAppManager.matchProtectedApp(context, "إنستا باي")
        assertNotNull(m4)
        assertEquals("com.emeint.android.mwallet", m4?.packageName)
    }

    @Test
    fun testOrder1_handleAppEntryDisablesAndSchedulesAlarm() {
        val app = ProtectedApp(
            packageName = "com.emeint.android.mwallet",
            label = "InstaPay",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        settings.setProtectedApps(listOf(app))

        ProtectedAppManager.readinessOverrideForTesting =
            ProtectedAppManager.AutoRestoreReadiness(
                hasWriteSecure = true,
                hasUsageStats = true,
                isBatteryOptimizationsIgnored = true,
                hasNotificationPermission = true,
                isNotificationListenerEnabled = true,
                isAutostartGuided = true
            )

        ProtectedAppManager.handleAppEntry(
            context = context,
            service = null,
            app = app,
            relaunchIfForeground = false
        )

        // تم تسجيل التعليق بنجاح
        assertTrue(ProtectedAppManager.isSuspended(context))
        assertEquals(app.packageName, settings.getSuspendedProtectedApp())
    }

    // --- اختبارات الأمر 2: تناقض المحرك والصوت وحارس التعارض ---

    @Test
    fun testOrder2_inferEngineForVoiceContract() {
        assertEquals(
            "vocalizer_embedded",
            VoiceIdContract.inferEngineForVoice("ara-SAU-Leila")
        )
        assertEquals(
            "vocalizer_embedded",
            VoiceIdContract.inferEngineForVoice("eng-USA-Karen")
        )
        assertEquals(
            "com.acapelagroup.android.tts",
            VoiceIdContract.inferEngineForVoice("ar_youssef_22k")
        )
        assertEquals(
            "com.google.android.tts",
            VoiceIdContract.inferEngineForVoice("en-us-x-sfg#female_1-local")
        )
    }

    @Test
    fun testOrder2_languageOfVoiceIdContract() {
        assertEquals("ar", VoiceIdContract.languageOfVoiceId("ara-SAU-Leila"))
        assertEquals("ar", VoiceIdContract.languageOfVoiceId("Tarik"))
        assertEquals("en", VoiceIdContract.languageOfVoiceId("eng-USA-Karen"))
        assertEquals("en", VoiceIdContract.languageOfVoiceId("en-US-SMT"))
    }

    @Test
    fun testOrder2_conflictGuardIgnoresArabicVoiceForEnglishAnnouncement() {
        val guarded =
            AnnouncementLanguageResolver.resolveVoiceWithConflictGuard(
                resolvedLanguage = "en",
                savedVoiceId = "ara-SAU-Tarik",
                languageDefaultVoiceId = "eng-USA-Karen"
            )
        // لأن Tarik عربي والإعلان إنجليزي، يجب تجاوز Tarik واستعمال Karen
        assertEquals("eng-USA-Karen", guarded)
    }

    @Test
    fun testOrder2_conflictGuardKeepsMatchingVoice() {
        val guarded =
            AnnouncementLanguageResolver.resolveVoiceWithConflictGuard(
                resolvedLanguage = "en",
                savedVoiceId = "eng-USA-Karen",
                languageDefaultVoiceId = "eng-USA-Samantha"
            )
        assertEquals("eng-USA-Karen", guarded)
    }

    @Test
    fun testOrder2_droppedSegmentTracking() {
        SpeechLatencyTracker.resetForTesting()
        SpeechLatencyTracker.recordDroppedSegment(
            segmentText = "12345",
            role = "NUMBER",
            requestedEngine = "com.acapelagroup.android.tts",
            requestedVoice = "ara-SAU-Leila",
            actualEngine = null,
            actualVoice = null,
            reason = "مزوّد غير متاح"
        )
        val report = SpeechLatencyTracker.formatSpeedReport()
        assertTrue(report.contains("أسباب الحذف الأكثر تكراراً"))
        assertTrue(report.contains("مزوّد غير متاح"))
    }

    // --- اختبارات الأمر 4: تقسيم النص ومعالجة المناطق بلغتها ---

    @Test
    fun testOrder4_speechEntryPointsSegmentsMixedText() {
        settings.setNumberReadingLanguage("en")
        val input = "الوقت 5:15 PM الآن"
        val out = SpeechEntryPoints.prepare(
            rawText = input,
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        val clean = out.replace(EngineMarkers.NUMBER_MARK_START, "")
            .replace(EngineMarkers.NUMBER_MARK_END, "")
        assertTrue(
            "Expected English time in output: $out",
            clean.contains("five fifteen PM")
        )
        assertTrue(
            "Expected Arabic words in output: $out",
            clean.contains("الوقت") && clean.contains("الآن")
        )
    }

    @Test
    fun testOrder4_timeAnnouncementFollowsAnnouncementLanguage() {
        settings.setAnnouncementSpeechLanguage("en")
        val timeManager = TimeAnnouncementManager.shared(context, settings)
        val lang = timeManager.resolveTimeSpeechLanguage()
        assertEquals("en", lang)
    }
}

