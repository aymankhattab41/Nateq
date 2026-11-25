package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
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
 * اختبارات تسجيل ومعالجة مستقبل البلوتوث (ع٤١-ب9):
 * التحقق من استخدام RECEIVER_EXPORTED على API 33+، ومن اشتراط
 * إذن BLUETOOTH_CONNECT على API 31+، وإلغاء التسجيل الآمن.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothReceiverRegistrationTest {

    @Test
    @Config(sdk = [37])
    fun onApi33Plus_withPermission_registersWithExportedFlag() {
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()
        val shadow = shadowOf(service)
        shadow.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        service.registerBluetoothReceiver()
        assertTrue(service.isBluetoothReceiverRegistered())
        assertEquals(
            Context.RECEIVER_EXPORTED,
            service.lastBluetoothReceiverFlags
        )

        service.unregisterBluetoothReceiver()
        assertFalse(service.isBluetoothReceiverRegistered())
        assertEquals(-1, service.lastBluetoothReceiverFlags)
    }

    @Test
    @Config(sdk = [37])
    fun onApi31Plus_withoutPermission_doesNotRegister() {
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()
        val shadow = shadowOf(service)
        shadow.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        service.registerBluetoothReceiver()
        assertFalse(service.isBluetoothReceiverRegistered())
        assertEquals(-1, service.lastBluetoothReceiverFlags)
    }

    @Test
    @Config(sdk = [30])
    fun onPreApi31_registersWithoutRequiringBluetoothConnectPermission() {
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()

        service.registerBluetoothReceiver()
        assertTrue(service.isBluetoothReceiverRegistered())
        assertEquals(0, service.lastBluetoothReceiverFlags)

        service.unregisterBluetoothReceiver()
        assertFalse(service.isBluetoothReceiverRegistered())
        assertEquals(-1, service.lastBluetoothReceiverFlags)
    }

    @Test
    @Config(sdk = [37])
    fun handleBluetoothEvent_processesAclConnectAndDisconnect() {
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()

        val connectIntent = Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
        service.handleBluetoothEvent(connectIntent)

        val disconnectIntent = Intent(
            BluetoothDevice.ACTION_ACL_DISCONNECTED
        )
        service.handleBluetoothEvent(disconnectIntent)

        // null intent or unknown action does not crash
        service.handleBluetoothEvent(null)
        service.handleBluetoothEvent(Intent("UNKNOWN_ACTION"))
    }
}
