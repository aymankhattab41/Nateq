package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.CategoryLoudnessConfig
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.StreamMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * اختبارات سياسة جهارة الأحداث [EventLoudnessPolicy] (ع٤٢-2):
 * سقف السماعات، تجاوز وضع الصامت، مطابقة القناة للسمات، واستعادة التعليق.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EventLoudnessPolicyTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var shadowAudio: ShadowAudioManager
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager
        shadowAudio = shadowOf(audioManager)
        repo = SettingsRepository.create(context)
        repo.clearPendingBoost()
    }

    @After
    fun tearDown() {
        repo.clearPendingBoost()
        EventLoudnessPolicy.classifyFailureReason(
            Exception(),
            audioManager
        )
    }

    @Test
    fun isHeadphoneDeviceType_recognizesAllHeadphoneTypes() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ).forEach { type ->
            assertTrue(
                "نوع الجهاز $type يُعتبر سماعة أذن",
                EventLoudnessPolicy.isHeadphoneDeviceType(type)
            )
        }

        assertFalse(
            "المكبّر المدمج ليس سماعة أذن",
            EventLoudnessPolicy.isHeadphoneDeviceType(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            )
        )
    }

    @Test
    fun computeTargetVolume_noForceMax_returnsCurrent() {
        val config = CategoryLoudnessConfig(forceMax = false)
        val target = EventLoudnessPolicy.computeTargetVolume(
            current = 5,
            max = 15,
            config = config,
            isHeadsetConnected = false
        )
        assertEquals(5, target)
    }

    @Test
    fun computeTargetVolume_headsetConnected_appliesCeiling() {
        val config = CategoryLoudnessConfig(
            forceMax = true,
            headsetCapPercent = 80
        )
        // max = 15 -> 80% = 12
        val target = EventLoudnessPolicy.computeTargetVolume(
            current = 3,
            max = 15,
            config = config,
            isHeadsetConnected = true
        )
        assertEquals(12, target)

        // إذا كان المستوى الحالي أعلى من السقف أصلاً، يبقى كما هو دون تخفيض
        val targetHigher = EventLoudnessPolicy.computeTargetVolume(
            current = 14,
            max = 15,
            config = config,
            isHeadsetConnected = true
        )
        assertEquals(14, targetHigher)
    }

    @Test
    fun computeTargetVolume_noHeadset_boostsToMax() {
        val config = CategoryLoudnessConfig(forceMax = true)
        val target = EventLoudnessPolicy.computeTargetVolume(
            current = 5,
            max = 15,
            config = config,
            isHeadsetConnected = false
        )
        assertEquals(15, target)
    }

    @Test
    fun resolveTargetStreamAndAttributes_inCall_routesToCall() {
        val config = CategoryLoudnessConfig()
        val route = EventLoudnessPolicy.resolveTargetStreamAndAttributes(
            category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            config = config,
            inCall = true,
            isSilentOrVibrate = false
        )
        assertEquals(AudioManager.STREAM_VOICE_CALL, route.stream)
        assertEquals(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            route.audioAttributes.usage
        )
    }

    @Test
    fun resolveTargetStreamAndAttributes_autoSilent_bypassesWithAlarm() {
        val config = CategoryLoudnessConfig(
            streamMode = StreamMode.AUTO,
            bypassSilent = true
        )
        val route = EventLoudnessPolicy.resolveTargetStreamAndAttributes(
            category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            config = config,
            inCall = false,
            isSilentOrVibrate = true
        )
        assertEquals(
            "تجاوز الصامت في الوضع التلقائي يوجّه إلى تيار الإنذار",
            AudioManager.STREAM_ALARM,
            route.stream
        )
        assertEquals(
            AudioAttributes.USAGE_ALARM,
            route.audioAttributes.usage
        )
    }

    @Test
    fun resolveTargetStreamAndAttributes_autoNormalCaller_usesNotification() {
        val config = CategoryLoudnessConfig(
            streamMode = StreamMode.AUTO,
            bypassSilent = true
        )
        val route = EventLoudnessPolicy.resolveTargetStreamAndAttributes(
            category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            config = config,
            inCall = false,
            isSilentOrVibrate = false
        )
        assertEquals(AudioManager.STREAM_NOTIFICATION, route.stream)
        assertEquals(
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            route.audioAttributes.usage
        )
    }

    @Test
    fun resolveTargetStreamAndAttributes_autoNormalNonCaller_usesMedia() {
        val config = CategoryLoudnessConfig(
            streamMode = StreamMode.AUTO,
            bypassSilent = true
        )
        val route = EventLoudnessPolicy.resolveTargetStreamAndAttributes(
            category = SettingsRepository.VOICE_CATEGORY_TIME,
            config = config,
            inCall = false,
            isSilentOrVibrate = false
        )
        assertEquals(AudioManager.STREAM_MUSIC, route.stream)
        assertEquals(
            AudioAttributes.USAGE_MEDIA,
            route.audioAttributes.usage
        )
    }

    @Test
    fun resolveTargetStreamAndAttributes_explicitStreamModes_match() {
        val modes = listOf(
            StreamMode.ACCESSIBILITY to AudioAttributes
                .USAGE_ASSISTANCE_ACCESSIBILITY,
            StreamMode.ALARM to AudioAttributes.USAGE_ALARM,
            StreamMode.NOTIFICATION to AudioAttributes.USAGE_NOTIFICATION_EVENT,
            StreamMode.MEDIA to AudioAttributes.USAGE_MEDIA
        )
        modes.forEach { (mode, expectedUsage) ->
            val config = CategoryLoudnessConfig(streamMode = mode)
            val route = EventLoudnessPolicy.resolveTargetStreamAndAttributes(
                category = "any",
                config = config,
                inCall = false,
                isSilentOrVibrate = false
            )
            assertEquals(expectedUsage, route.audioAttributes.usage)
        }
    }

    @Test
    fun classifyFailureReason_handlesSecurityAndRingerRestrictions() {
        val sec = SecurityException("DND restricted")
        val reasonSec = EventLoudnessPolicy.classifyFailureReason(
            sec,
            audioManager
        )
        assertEquals(VolumeFailureReason.SECURITY_DND, reasonSec)

        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val reasonSilent = EventLoudnessPolicy.classifyFailureReason(
            RuntimeException("Failed"),
            audioManager
        )
        assertEquals(
            VolumeFailureReason.RINGER_MODE_RESTRICTION,
            reasonSilent
        )

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val reasonNormal = EventLoudnessPolicy.classifyFailureReason(
            RuntimeException("Unknown"),
            audioManager
        )
        assertEquals(VolumeFailureReason.UNKNOWN, reasonNormal)
    }

    @Test
    fun restorePendingBoostIfAny_restoresAndClears() {
        val stream = AudioManager.STREAM_MUSIC
        audioManager.setStreamVolume(stream, 10, 0)

        repo.savePendingBoost(stream = stream, originalVolume = 4)
        assertEquals(4, repo.getPendingBoostVolume())

        val restored = EventLoudnessPolicy.restorePendingBoostIfAny(
            audioManager,
            repo
        )
        assertTrue(restored)
        assertEquals(4, audioManager.getStreamVolume(stream))
        assertEquals(-1, repo.getPendingBoostVolume())
    }
}
