package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * كشف مشهد الصوت من AudioManager (Robolectric على أحدث بيئة مستهدفة).
 * حالة الوضع/الرنين تُضبط عبر واجهة AudioManager العامة (محمية في
 * الـ Shadow)، ونشاط الموسيقى عبر ShadowAudioManager.setIsMusicActive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeAnnouncementGateSceneTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun detectAudioScene_normalScene_allFlagsFalse() {
        val audio = audioManager()
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(audio).setIsMusicActive(false)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL

        val scene = detectAudioScene(context)
        assertEquals(false, scene.inCall)
        assertEquals(false, scene.mediaActive)
        assertEquals(false, scene.silent)
    }

    @Test
    fun detectAudioScene_inCallMode_inCallTrue() {
        audioManager().mode = AudioManager.MODE_IN_CALL
        assertEquals(true, detectAudioScene(context).inCall)
    }

    @Test
    fun detectAudioScene_communicationMode_treatedAsCall() {
        audioManager().mode = AudioManager.MODE_IN_COMMUNICATION
        assertEquals(true, detectAudioScene(context).inCall)
    }

    @Test
    fun detectAudioScene_musicActive_mediaActiveTrue() {
        shadowOf(audioManager()).setIsMusicActive(true)
        assertEquals(true, detectAudioScene(context).mediaActive)
    }

    @Test
    fun detectAudioScene_silentAndVibrate_flagged() {
        val audio = audioManager()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(true, detectAudioScene(context).silent)

        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(true, detectAudioScene(context).silent)

        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertEquals(false, detectAudioScene(context).silent)
    }
}