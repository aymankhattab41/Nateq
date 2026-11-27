package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog.Companion.BlacklistReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * اختبارات تمييز الفشل والبطء وحفظ الأصوات السابقة
 * وتهدئة الاكتشاف وإلغائه (المجموعة 15: بنود 30 و31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VoiceCatalogDiscoveryLatencyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VoiceCatalog.clearBlacklistForTesting(context)
        VoiceCatalog.clearEngineVoicesCache()
        VoiceCatalog.resetDiscoveryCancellation()
        VoiceCatalog.simulateProbeTimeoutForTesting("com.test.slow", false)
        VoiceCatalog.simulateDefinitiveFailureForTesting("com.test.fail", false)
    }

    @After
    fun tearDown() {
        VoiceCatalog.clearBlacklistForTesting(context)
        VoiceCatalog.clearEngineVoicesCache()
        VoiceCatalog.resetDiscoveryCancellation()
        VoiceCatalog.simulateProbeTimeoutForTesting("com.test.slow", false)
        VoiceCatalog.simulateDefinitiveFailureForTesting("com.test.fail", false)
    }

    @Test
    fun probeEngineVoices_definitiveFailure_blacklistsFor24Hours() {
        val failPkg = "com.test.fail"
        VoiceCatalog.simulateDefinitiveFailureForTesting(failPkg, true)

        runBlocking {
            val voices = VoiceCatalog.getVoicesForEngine(context, failPkg)
            assertTrue(voices.isEmpty())
        }

        assertTrue(VoiceCatalog.isBlacklisted(failPkg, context))
        val entry = VoiceCatalog.getBlacklistEntryForTesting(failPkg)
        assertNotNull(entry)
        assertEquals(
            BlacklistReason.DEFINITIVE_ERROR,
            entry?.reason
        )
    }

    @Test
    fun probeEngineVoices_slowTimeout_blacklistsShortTtlNot24Hours() {
        val slowPkg = "com.test.slow"
        VoiceCatalog.simulateProbeTimeoutForTesting(slowPkg, true)

        runBlocking {
            val voices = VoiceCatalog.getVoicesForEngine(context, slowPkg)
            assertTrue(voices.isEmpty())
        }

        assertTrue(VoiceCatalog.isBlacklisted(slowPkg, context))
        val entry = VoiceCatalog.getBlacklistEntryForTesting(slowPkg)
        assertNotNull(entry)
        assertEquals(
            BlacklistReason.SLOW_TIMEOUT,
            entry?.reason
        )
    }

    @Test
    fun probeEngineVoices_slowEngine_returnsLastKnownCachedVoices() {
        val slowPkg = "com.test.slow"
        val knownVoice = Voice(
            "ar-voice-1",
            Locale.forLanguageTag("ar"),
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
        // حفظ الأصوات السابقة في الكاش والقرص
        VoiceCatalog.cacheEngineVoices(slowPkg, listOf(knownVoice), context)

        // محاكاة بطء المحرك
        VoiceCatalog.simulateProbeTimeoutForTesting(slowPkg, true)

        val retrieved = VoiceCatalog.getLastKnownEngineVoices(context, slowPkg)
        assertNotNull(retrieved)
        assertEquals(1, retrieved?.size)
        assertEquals("ar-voice-1", retrieved?.first()?.name)

        // تفريغ ذاكرة الرام للتأكد من القراءة من القرص
        VoiceCatalog.clearEngineVoicesCache()
        val fromDisk = VoiceCatalog.getLastKnownEngineVoices(context, slowPkg)
        assertNotNull(fromDisk)
        assertEquals(1, fromDisk?.size)
        assertEquals("ar-voice-1", fromDisk?.first()?.name)
    }

    @Test
    fun nateqTtsService_refreshDiscovery_throttlesRepeatedTriggers() {
        val controller = Robolectric.buildService(
            NateqTtsService::class.java
        ).create()
        val service = controller.get()

        // إلغاء أي مهام أولية وتصفير الحالة
        service.getDiscoveryJobForTesting()?.cancel()
        service.setDiscoveryJobForTesting(null)
        service.invalidateCatalogDiscoveryForTesting()
        service.setLastDiscoveryTriggerAttemptMsForTesting(0L)

        val t0 = 1_000_000L
        assertTrue(service.shouldTriggerDiscovery(t0))

        // محاكاة الإطلاق الأول
        service.setLastDiscoveryTriggerAttemptMsForTesting(t0)

        // استدعاء متكرر بعد 10 ثوانٍ (أقل من مهلة 60 ثانية): محجوب بالتهدئة
        val t1 = t0 + 10_000L
        assertFalse(service.shouldTriggerDiscovery(t1))

        // استدعاء بعد 59 ثانية: لا زال محجوباً
        val t2 = t0 + 59_000L
        assertFalse(service.shouldTriggerDiscovery(t2))

        // استدعاء بعد 60 ثانية كاملة: مسموح به
        val t3 = t0 + 60_000L
        assertTrue(service.shouldTriggerDiscovery(t3))
    }

    @Test
    fun speechArrival_cancelsActiveDiscovery() {
        VoiceCatalog.resetDiscoveryCancellation()
        assertFalse(VoiceCatalog.isDiscoveryCancelled())

        VoiceCatalog.cancelActiveDiscovery()
        assertTrue(VoiceCatalog.isDiscoveryCancelled())

        VoiceCatalog.resetDiscoveryCancellation()
        assertFalse(VoiceCatalog.isDiscoveryCancelled())
    }
}
