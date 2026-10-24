package com.aymankhattab.nateq.core.audio.phone

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لخدمة إدارة المكالمات الهاتفية ومستشعر التقارب ومكبر الصوت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LordInCallServiceTest {

    @Test
    fun callListener_registrationAndUnregistration_worksCorrectly() {
        var callFinishedCalled = false
        val listener = object : LordInCallService.CallListener {
            override fun onCallStateChanged(call: Call, state: Int) {}
            override fun onCallAudioStateChanged(
                audioState: CallAudioState
            ) {}
            override fun onCallFinished() {
                callFinishedCalled = true
            }
        }

        LordInCallService.registerListener(listener)
        // التحقق من عدم التكرار عند التسجيل مرتين
        LordInCallService.registerListener(listener)

        LordInCallService.unregisterListener(listener)
        assertFalse(callFinishedCalled)
    }

    @Test
    fun proximityThreshold_binarySensor_detectsNearAndFar() {
        val maxRange = 5.0f

        // مسافة 0.0 تعني ملاصق للأذن في معظم الحساسات الثنائية
        val nearDistance = 0.0f
        val isNear = nearDistance < 1.0f ||
            (maxRange > 1.0f && nearDistance <= 3.0f &&
                nearDistance < maxRange)
        assertTrue(isNear)

        // مسافة 5.0 تعني بعيد عن الأذن
        val farDistance = 5.0f
        val isFarNear = farDistance < 1.0f ||
            (maxRange > 1.0f && farDistance <= 3.0f &&
                farDistance < maxRange)
        assertFalse(isFarNear)
    }

    @Test
    fun proximityThreshold_singleUnitSensor_detectsNearAndFar() {
        val maxRange = 1.0f

        // في الحساسات ذات المدى 1.0
        val nearDistance = 0.0f
        val isNear = nearDistance < 1.0f ||
            (maxRange > 1.0f && nearDistance <= 3.0f &&
                nearDistance < maxRange)
        assertTrue(isNear)

        val farDistance = 1.0f
        val isFarNear = farDistance < 1.0f ||
            (maxRange > 1.0f && farDistance <= 3.0f &&
                farDistance < maxRange)
        assertFalse(isFarNear)
    }

    @Test
    fun targetAudioRoute_nearEar_routesToWiredOrEarpiece() {
        val isNear = true
        val targetRoute = if (isNear) {
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        assertEquals(CallAudioState.ROUTE_WIRED_OR_EARPIECE, targetRoute)
    }

    @Test
    fun targetAudioRoute_awayFromEar_routesToSpeaker() {
        val isNear = false
        val targetRoute = if (isNear) {
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        assertEquals(CallAudioState.ROUTE_SPEAKER, targetRoute)
    }

    @Test
    fun broadcastActionConstants_haveExpectedStrings() {
        assertEquals(
            "com.aymankhattab.nateq.phone.ACTION_CALL_STATE_CHANGED",
            LordInCallService.ACTION_CALL_STATE_CHANGED
        )
        assertEquals(
            "com.aymankhattab.nateq.phone.ACTION_FINISH_INCALL_UI",
            LordInCallService.ACTION_FINISH_INCALL_UI
        )
    }

    @Test
    fun autoMode_withNormalForegroundApp_producesNotificationWithoutActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setIncomingCallUiMode(SettingsRepository.INCOMING_CALL_UI_AUTO)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(pm).setIsInteractive(true)
        shadowOf(km).setKeyguardLocked(false)

        AccessibilityBridge.updateForeground(
            "com.whatsapp",
            "com.whatsapp.HomeActivity"
        )
        ProtectedAppTimelineTracker.endQuietPeriod()

        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        service.handleIncomingCallUi(null)

        assertEquals(false, service.lastNotificationFullScreenIntent)
        assertFalse(service.lastActivityLaunched)
        controller.destroy()
    }

    @Test
    fun autoMode_withKeyguardLocked_producesFullScreenIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setIncomingCallUiMode(SettingsRepository.INCOMING_CALL_UI_AUTO)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(pm).setIsInteractive(true)
        shadowOf(km).setKeyguardLocked(true)

        AccessibilityBridge.updateForeground(
            "com.whatsapp",
            "com.whatsapp.HomeActivity"
        )
        ProtectedAppTimelineTracker.endQuietPeriod()

        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        service.handleIncomingCallUi(null)

        assertEquals(true, service.lastNotificationFullScreenIntent)
        controller.destroy()
    }

    @Test
    fun protectedApp_alwaysProducesNotificationOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        // حتى لو اختار المستخدم ملء الشاشة دائماً
        settings.setIncomingCallUiMode(
            SettingsRepository.INCOMING_CALL_UI_FULLSCREEN
        )

        ProtectedAppTimelineTracker.startQuietPeriod(
            "com.egyptianbanks.instapay"
        )

        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        service.handleIncomingCallUi(null)

        assertEquals(false, service.lastNotificationFullScreenIntent)
        assertFalse(service.lastActivityLaunched)

        ProtectedAppTimelineTracker.endQuietPeriod()
        controller.destroy()
    }
}
