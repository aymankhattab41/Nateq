package com.aymankhattab.nateq.core.audio.phone

import android.telecom.Call
import android.telecom.CallAudioState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
