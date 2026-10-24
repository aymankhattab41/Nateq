package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.BatteryDiagnosticsTracker
import com.aymankhattab.nateq.core.audio.accessibility.VoiceResolutionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات مزود تشخيص :tts الصادق (الجولة 53 - الأمر 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TtsDiagnosticsProviderTest {

    private lateinit var context: Context
    private lateinit var provider: TtsDiagnosticsProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = TtsDiagnosticsProvider()
        provider.attachInfo(context, null)
        VoiceResolutionTracker.clear()
    }

    @Test
    fun testSnapshot_returnsRealValues() {
        AccessibilityBridge.updateForeground(
            "com.example.app",
            "com.example.app.MainActivity"
        )
        A11yStateStore.getInstance(context)?.setConnected(true)
        BatteryDiagnosticsTracker.recordUsageStatsPoll()

        VoiceResolutionTracker.record(
            packageName = "com.example.app",
            senderName = "sender",
            winningLayer = "app",
            engine = "com.google.android.tts",
            voiceId = "Tarik",
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            source = "test",
            reason = "test reason"
        )

        val bundle = provider.call(
            TtsDiagnosticsProvider.METHOD_SNAPSHOT,
            null,
            null
        )

        assertNotNull("يجب أن يعيد Bundle غير فارغ", bundle)
        assertEquals(
            "com.example.app",
            bundle?.getString(TtsDiagnosticsProvider.KEY_FOREGROUND_PKG)
        )
        assertTrue(
            bundle?.getBoolean(
                TtsDiagnosticsProvider.KEY_A11Y_CONNECTED
            ) == true
        )
        val resolutions = bundle?.getStringArrayList(
            TtsDiagnosticsProvider.KEY_VOICE_RESOLUTIONS
        )
        assertNotNull(resolutions)
        assertTrue(resolutions?.isNotEmpty() == true)
        assertTrue(
            (bundle?.getInt(
                TtsDiagnosticsProvider.KEY_USAGE_STATS_POLLS
            ) ?: 0) >= 1
        )
    }

    @Test
    fun testUnknownMethod_returnsNull() {
        val bundle = provider.call("unknown", null, null)
        org.junit.Assert.assertNull(bundle)
    }
}
