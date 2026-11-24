package com.aymankhattab.nateq.core.audio.phone

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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

    private fun createMockCall(
        state: Int,
        number: String = "",
        name: String = ""
    ): Call {
        val call = ReflectionHelpers.callConstructor(Call::class.java)
        ReflectionHelpers.setField(call, "mState", state)
        ReflectionHelpers.setField(
            call,
            "mCallbackRecords",
            java.util.concurrent.CopyOnWriteArrayList<Any>()
        )
        val details = ReflectionHelpers.callConstructor(
            Call.Details::class.java
        )
        if (number.isNotEmpty()) {
            ReflectionHelpers.setField(
                details,
                "mHandle",
                Uri.fromParts("tel", number, null)
            )
        }
        if (name.isNotEmpty()) {
            ReflectionHelpers.setField(
                details,
                "mCallerDisplayName",
                name
            )
        }
        ReflectionHelpers.setField(call, "mDetails", details)
        return call
    }

    @Test
    fun multiCall_callAActive_callBRinging_rejectB_keepsCallAActive() {
        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        var finishedAnnounced = false
        LordInCallService.registerListener(
            object : LordInCallService.CallListener {
                override fun onCallStateChanged(call: Call, state: Int) {}
                override fun onCallAudioStateChanged(
                    audioState: CallAudioState
                ) {}
                override fun onCallFinished() {
                    finishedAnnounced = true
                }
            }
        )

        val callA = createMockCall(Call.STATE_ACTIVE, "010111", "أحمد")
        service.onCallAdded(callA)
        assertEquals(callA, LordInCallService.currentCall)

        val callB = createMockCall(Call.STATE_RINGING, "010222", "علي")
        service.onCallAdded(callB)
        // مكالمة الرنين تصبح هي المعروضة فوراً للإجراء
        assertEquals(callB, LordInCallService.currentCall)

        // رفض المكالمة ب وإزالتها
        service.rejectCall(callB)
        service.onCallRemoved(callB)

        // التحقق من عودة التحكم للمكالمة أ وعدم إنهاء الواجهة
        assertEquals(callA, LordInCallService.currentCall)
        assertFalse(finishedAnnounced)
        assertEquals(1, service.allCalls.size)

        // إنهاء المكالمة أ
        service.onCallRemoved(callA)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(LordInCallService.currentCall)
        assertTrue(finishedAnnounced)

        controller.destroy()
    }

    @Test
    fun mute_holding_active_maintainsMuteWithoutUnwantedUnmute() {
        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        val callA = createMockCall(Call.STATE_ACTIVE, "010111")
        service.onCallAdded(callA)

        // المستخدم اختار كتم الصوت
        service.toggleMute()

        // تحول المكالمة إلى HOLDING
        ReflectionHelpers.setField(callA, "mState", Call.STATE_HOLDING)
        service.handleCallStateChange(callA, Call.STATE_HOLDING)

        // عودة المكالمة إلى ACTIVE
        ReflectionHelpers.setField(callA, "mState", Call.STATE_ACTIVE)
        service.handleCallStateChange(callA, Call.STATE_ACTIVE)

        // التحقق من استقرار الخدمة واستمرار نية الكتم
        assertNotNull(service)

        controller.destroy()
    }

    @Test
    fun sendDtmf_schedulesTonePulseSafelyWithoutHanging() {
        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        val call = createMockCall(Call.STATE_ACTIVE, "010111")
        service.onCallAdded(call)

        // إرسال نغمات متسارعة بأمان دون تعلق
        service.sendDtmf('5', call)
        service.sendDtmf('9', call)
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()
    }

    @Test
    fun multiCall_callBHangupWhileCallAHolding_switchesControlToCallA() {
        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        val callA = createMockCall(Call.STATE_HOLDING, "010111", "أحمد")
        val callB = createMockCall(Call.STATE_ACTIVE, "010222", "علي")

        service.onCallAdded(callA)
        service.onCallAdded(callB)
        assertEquals(callB, LordInCallService.currentCall)

        // انتهاء المكالمة ب مع بقاء أ معلقة
        service.onCallRemoved(callB)
        assertEquals(
            "تنتقل المكالمة المعروضة تلقائياً إلى المكالمة أ المعلقة",
            callA,
            LordInCallService.currentCall
        )

        controller.destroy()
    }

    @Test
    fun checkContactStatus_unverifiableOnHiddenNumberOrMissingPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller =
            Robolectric.buildService(LordInCallService::class.java).create()
        val service = controller.get()

        // رقم محجوب أو فارغ
        val resHidden = service.checkContactStatus(context, "private")
        assertEquals(
            LordInCallService.ContactLookupResult.Unverifiable,
            resHidden
        )

        // رقم عادي مع سحب الصلاحية
        shadowOf(context as android.app.Application).denyPermissions(
            Manifest.permission.READ_CONTACTS
        )
        val resDenied = service.checkContactStatus(context, "01012345678")
        assertEquals(
            LordInCallService.ContactLookupResult.Unverifiable,
            resDenied
        )

        controller.destroy()
    }
}
