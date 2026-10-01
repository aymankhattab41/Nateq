package com.aymankhattab.nateq.core.audio.announcement

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * حارسُ عقد «الهوية الفورية»: مستمعُ الإشعارات وخدمة الفرز يكتبان
 * الهوية، وحلقةُ النطق وحدها تقرأها وتنطق.
 */
class RingCallerIdentityTest {

    @Before
    fun setUp() = RingCallerIdentity.clear()

    @After
    fun tearDown() = RingCallerIdentity.clear()

    @Test
    fun `empty publish changes nothing`() {
        RingCallerIdentity.publish(null, null)
        assertFalse(RingCallerIdentity.hasIdentity())

        RingCallerIdentity.publish("   ", "")
        assertFalse(RingCallerIdentity.hasIdentity())
    }

    @Test
    fun `publish fills empty slots only`() {
        RingCallerIdentity.publish("0100123", null)
        RingCallerIdentity.publish("999", "أحمد")
        val (number, name) = RingCallerIdentity.snapshot()
        assertEquals("الرقم الأول لا يُداس", "0100123", number)
        assertEquals("الاسم يملأ الفراغ", "أحمد", name)
    }

    @Test
    fun `name arrives first and number does not overwrite it`() {
        RingCallerIdentity.publish(null, "سارة")
        RingCallerIdentity.publish("0100123", null)
        val (number, name) = RingCallerIdentity.snapshot()
        assertEquals("0100123", number)
        assertEquals("سارة", name)
    }

    @Test
    fun `clear resets the session`() {
        RingCallerIdentity.publish("0100123", "أحمد")
        RingCallerIdentity.clear()
        assertFalse(RingCallerIdentity.hasIdentity())
        assertNull(RingCallerIdentity.snapshot().first)
        assertNull(RingCallerIdentity.snapshot().second)
    }

    @Test
    fun `generic incoming phrase is never taken as identity`() {
        // العبارة العامة ليست هوية: لو قبلناها لنُنطق «مكالمة واردة»
        // وهو ما حذفه المستخدم صراحةً.
        assertTrue(RingCallerIdentity.isGenericCallPhrase("مكالمة واردة"))
        assertTrue(RingCallerIdentity.isGenericCallPhrase("Incoming call"))
        assertTrue(RingCallerIdentity.isGenericCallPhrase("Call"))
        assertFalse(
            RingCallerIdentity.isGenericCallPhrase("مكالمة واردة من أحمد")
        )
    }

    @Test
    fun `notification name is extracted as identity`() {
        val (number, name) = RingCallerIdentity
            .extractFromCallNotification("أحمد محمد", "مكالمة واردة", null)
        assertNull("الاسم ليس رقماً", number)
        assertEquals("أحمد محمد", name)
    }

    @Test
    fun `notification number is extracted as number`() {
        val (number, name) = RingCallerIdentity
            .extractFromCallNotification("01001234567", null, null)
        assertEquals("01001234567", number)
        assertNull(name)
    }

    @Test
    fun `generic-only notification yields no identity`() {
        // رقمٌ محجوب من الشبكة: لا اسم ولا رقم. يجب ألا يُختلق شيء.
        val (number, name) = RingCallerIdentity
            .extractFromCallNotification("مكالمة واردة", "رقم محجوب", null)
        assertNull(number)
        assertNull(name)
    }

    @Test
    fun `sub text is searched when title is generic`() {
        val (number, name) = RingCallerIdentity
            .extractFromCallNotification(
                "مكالمة واردة", null, "+20 100 123 4567"
            )
        assertEquals("+20 100 123 4567", number)
        assertNull(name)
    }

    @Test
    fun `phone number detection accepts formatting characters`() {
        assertTrue(RingCallerIdentity.looksLikePhoneNumber("01001234567"))
        assertTrue(RingCallerIdentity.looksLikePhoneNumber("+20 100 123 4567"))
        assertTrue(RingCallerIdentity.looksLikePhoneNumber("(010) 123-4567"))
        assertFalse(RingCallerIdentity.looksLikePhoneNumber("أحمد"))
        assertFalse(RingCallerIdentity.looksLikePhoneNumber("12"))
        assertFalse(RingCallerIdentity.looksLikePhoneNumber(""))
    }

    @Test
    fun `shared identity feeds the speakable check`() {
        RingCallerIdentity.publish("0100123", null)
        val (number, name) = RingCallerIdentity.snapshot()
        assertTrue(
            CallerAnnouncementReceiver.hasSpeakableIdentity(number, name)
        )
    }
}