package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.media.AudioManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة شاملة للأتمتة الخفية لتبديل محرك النظام (الجولة 48).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TtsSettingsAutomationRound48Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testNormalizeForMatch_comprehensive() {
        // حركات وتطويل
        assertEquals("نص", TtsSettingsAutomation.normalizeForMatch("نَصّـ"))
        // توحيد الألف
        assertEquals(
            "احمد",
            TtsSettingsAutomation.normalizeForMatch("أحمد")
        )
        assertEquals(
            "اسلام",
            TtsSettingsAutomation.normalizeForMatch("إسلام")
        )
        assertEquals(
            "ايه",
            TtsSettingsAutomation.normalizeForMatch("آية")
        )
        // توحيد التاء المربوطة
        assertEquals(
            "شاشه",
            TtsSettingsAutomation.normalizeForMatch("شاشة")
        )
        // توحيد الياء والألف المقصورة
        assertEquals(
            "علي",
            TtsSettingsAutomation.normalizeForMatch("على")
        )
        // أحرف لاتينية صغيرة
        assertEquals(
            "google tts",
            TtsSettingsAutomation.normalizeForMatch("Google TTS")
        )
        // توحيد الشرطات
        assertEquals(
            "text-to-speech",
            TtsSettingsAutomation.normalizeForMatch("text–to—speech")
        )
        // توحيد المسافات
        assertEquals(
            "محرك البحث",
            TtsSettingsAutomation.normalizeForMatch("  محرك   البحث  ")
        )
    }

    @Test
    fun testShouldPostponeSwitch_inCallMode() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_CALL

        assertTrue(TtsSettingsAutomation.shouldPostponeSwitch(context))

        am.mode = AudioManager.MODE_NORMAL
        assertFalse(TtsSettingsAutomation.shouldPostponeSwitch(context))
    }

    @Test
    fun testPendingTargetSwitchExecution() = runBlocking {
        TtsSettingsAutomation.pendingTargetPackage.set("com.google.android.tts")
        assertEquals(
            "com.google.android.tts",
            TtsSettingsAutomation.pendingTargetPackage.get()
        )
    }

    @Test
    fun testFindNodeByCandidates_exactMatchPriority() {
        val root = AccessibilityNodeInfo.obtain()
        root.text = "Google Text-to-speech Engine"

        val candidates = listOf("Google TTS", "Google Text-to-speech Engine")
        val found = TtsSettingsAutomation.findNodeByCandidates(root, candidates)
        assertNotNull(found)
        assertEquals(root, found)

        // استبعاد أنماط الترس والإعدادات
        val gearNode = AccessibilityNodeInfo.obtain()
        gearNode.text = "إعدادات Google TTS"
        val foundGear = TtsSettingsAutomation.findNodeByCandidates(
            gearNode,
            candidates,
            excludePatterns = listOf("إعدادات", "settings", "gear")
        )
        assertNull(foundGear)
    }
}
