package com.aymankhattab.nateq.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات قرارات وحدات التحكم (Controllers A): دعم subtitle في بلاطة
 *  الإعلانات (API 29+) وبوّابة تفعيل نطق المتصل (حالة الهاتف + مصدر اسم،
 *  مع إلزام سجل المكالمات على أندرويد 12+). */
class ControllersLogicTest {

    @Test
    fun subtitleSupport_requiresQ() {
        assertFalse(AnnouncementTileService.subtitleSupported(28))
        assertTrue(AnnouncementTileService.subtitleSupported(29))
        assertTrue(AnnouncementTileService.subtitleSupported(37))
    }

    @Test
    fun canEnable_trueWhenPhoneAndAnyNameSource() {
        assertTrue(
            enable(false, phone = true, callLog = true, contacts = true)
        )
        assertTrue(
            enable(false, phone = true, callLog = true, contacts = false)
        )
        assertTrue(
            enable(false, phone = true, callLog = false, contacts = true)
        )
    }

    @Test
    fun canEnable_falseWithoutPhonePermission() {
        assertFalse(
            enable(false, phone = false, callLog = true, contacts = true)
        )
        assertFalse(
            enable(false, phone = false, callLog = true, contacts = false)
        )
        assertFalse(
            enable(false, phone = false, callLog = false, contacts = true)
        )
        assertFalse(
            enable(false, phone = false, callLog = false, contacts = false)
        )
    }

    @Test
    fun canEnable_falseWithoutAnyNameSource() {
        assertFalse(
            enable(false, phone = true, callLog = false, contacts = false)
        )
    }

    @Test
    fun canEnable_requiresCallLogOnAndroid12Plus() {
        // على أندرويد 12+ رقم المتصل لا يُسلَّم دون READ_CALL_LOG —
        // حتى مع READ_CONTACTS — فيُشرَط أساسياً.
        assertTrue(
            enable(true, phone = true, callLog = true, contacts = true)
        )
        assertTrue(
            enable(true, phone = true, callLog = true, contacts = false)
        )
        assertFalse(
            enable(true, phone = true, callLog = false, contacts = true)
        )
        assertFalse(
            enable(true, phone = true, callLog = false, contacts = false)
        )
        assertFalse(
            enable(true, phone = false, callLog = true, contacts = true)
        )
    }

    private fun enable(
        requiresCallLog: Boolean,
        phone: Boolean,
        callLog: Boolean,
        contacts: Boolean
    ): Boolean = CallerAnnouncementController.canEnable(
        phoneGranted = phone,
        callLogGranted = callLog,
        contactsGranted = contacts,
        requiresCallLog = requiresCallLog
    )
}