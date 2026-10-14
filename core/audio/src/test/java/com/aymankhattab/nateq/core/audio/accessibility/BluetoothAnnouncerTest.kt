package com.aymankhattab.nateq.core.audio.accessibility

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ع٤١-ب9: اختبارات [BluetoothAnnouncer.buildText] و[AudioRouteLabel].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BluetoothAnnouncerTest {

    // ─── BluetoothAnnouncer.buildText ───────────────────────────────

    @Test
    fun connectedWithName_returnsCorrectText() {
        assertEquals(
            "تم اتصال سماعة سوني",
            BluetoothAnnouncer.buildText("سماعة سوني", isConnected = true)
        )
    }

    @Test
    fun disconnectedWithName_returnsCorrectText() {
        assertEquals(
            "تم فصل سماعة سوني",
            BluetoothAnnouncer.buildText("سماعة سوني", isConnected = false)
        )
    }

    @Test
    fun nullName_usesDefaultFallback() {
        assertEquals(
            "تم اتصال جهاز بلوتوث",
            BluetoothAnnouncer.buildText(null, isConnected = true)
        )
    }

    @Test
    fun blankName_usesDefaultFallback() {
        assertEquals(
            "تم فصل جهاز بلوتوث",
            BluetoothAnnouncer.buildText("   ", isConnected = false)
        )
    }

    @Test
    fun emptyName_usesDefaultFallback() {
        assertEquals(
            "تم اتصال جهاز بلوتوث",
            BluetoothAnnouncer.buildText("", isConnected = true)
        )
    }

    @Test
    fun nameWithLeadingTrailingSpaces_isTrimmed() {
        assertEquals(
            "تم اتصال AirPods",
            BluetoothAnnouncer.buildText("  AirPods  ", isConnected = true)
        )
    }

    // ─── AudioRouteLabel ─────────────────────────────────────────────

    @Test
    fun builtinSpeaker_hasFriendlyName() {
        assertEquals("مكبّر الصوت", AudioRouteLabel.nameOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ))
    }

    @Test
    fun wiredHeadset_hasFriendlyName() {
        assertEquals("سماعة سلكية", AudioRouteLabel.nameOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        ))
    }

    @Test
    fun wiredHeadphones_hasFriendlyName() {
        assertEquals("سماعة رأس سلكية", AudioRouteLabel.nameOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        ))
    }

    @Test
    fun usbHeadset_hasFriendlyName() {
        assertEquals("سماعة USB", AudioRouteLabel.nameOf(
            AudioDeviceInfo.TYPE_USB_HEADSET
        ))
    }

    @Test
    fun hearingAid_hasFriendlyName() {
        assertEquals("معين سمعي", AudioRouteLabel.nameOf(
            AudioDeviceInfo.TYPE_HEARING_AID
        ))
    }

    @Test
    fun unknownType_returnsNull() {
        // TYPE_BLUETOOTH_A2DP مُستبعَد عمداً (تأتي إعلاناته من مستقبل ACL)
        assertNull(AudioRouteLabel.nameOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }

    @Test
    fun builtinSpeaker_removalNotAnnounced() {
        // فصل المكبّر الداخلي = تراجع افتراضي، لا يستحق إعلاناً
        assertFalse(AudioRouteLabel.shouldAnnounceRemoval(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ))
    }

    @Test
    fun wiredHeadset_removalIsAnnounced() {
        assertTrue(AudioRouteLabel.shouldAnnounceRemoval(
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        ))
    }

    @Test
    fun unknownType_removalNotAnnounced() {
        assertFalse(AudioRouteLabel.shouldAnnounceRemoval(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        ))
    }
}
