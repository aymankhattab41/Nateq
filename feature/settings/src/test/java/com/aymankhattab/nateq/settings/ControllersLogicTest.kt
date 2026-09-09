package com.aymankhattab.nateq.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات قرارات وحدات التحكم (Controllers A): دعم subtitle في بلاطة
 *  الإعلانات (API 29+) وبوّابة تفعيل نطق المتصل (حالة الهاتف + مصدر اسم). */
class ControllersLogicTest {

    @Test
    fun subtitleSupport_requiresQ() {
        assertFalse(AnnouncementTileService.subtitleSupported(28))
        assertTrue(AnnouncementTileService.subtitleSupported(29))
        assertTrue(AnnouncementTileService.subtitleSupported(37))
    }

    @Test
    fun canEnable_trueWhenPhoneAndAnyNameSource() {
        assertTrue(enable(phone = true, callLog = true, contacts = true))
        assertTrue(enable(phone = true, callLog = true, contacts = false))
        assertTrue(enable(phone = true, callLog = false, contacts = true))
    }

    @Test
    fun canEnable_falseWithoutPhonePermission() {
        assertFalse(enable(phone = false, callLog = true, contacts = true))
        assertFalse(enable(phone = false, callLog = true, contacts = false))
        assertFalse(enable(phone = false, callLog = false, contacts = true))
        assertFalse(enable(phone = false, callLog = false, contacts = false))
    }

    @Test
    fun canEnable_falseWithoutAnyNameSource() {
        assertFalse(enable(phone = true, callLog = false, contacts = false))
    }

    private fun enable(
        phone: Boolean,
        callLog: Boolean,
        contacts: Boolean
    ): Boolean = CallerAnnouncementController.canEnable(
        phoneGranted = phone,
        callLogGranted = callLog,
        contactsGranted = contacts
    )
}