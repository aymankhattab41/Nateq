package com.aymankhattab.nateq.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ContactLookupNormalizerTest {

    @Test
    fun normalizeName_stripsTashkeelAndNormalizes() {
        assertEquals("محمد", ContactLookupNormalizer.normalizeName("مُحَمَّدٌ"))
        assertEquals("احمد", ContactLookupNormalizer.normalizeName("أحمد"))
        assertEquals(
            "ابراهيم", ContactLookupNormalizer.normalizeName("إبراهيم")
        )
        assertEquals("فاطمه", ContactLookupNormalizer.normalizeName("فاطمة"))
        assertEquals("مني", ContactLookupNormalizer.normalizeName("منى"))
    }

    @Test
    fun normalizeName_stripsTitles() {
        assertEquals("احمد", ContactLookupNormalizer.normalizeName("د. أحمد"))
        assertEquals("عمر", ContactLookupNormalizer.normalizeName("م. عمر"))
        assertEquals(
            "خالد", ContactLookupNormalizer.normalizeName("الشيخ خالد")
        )
        assertEquals("john", ContactLookupNormalizer.normalizeName("Dr. John"))
    }

    @Test
    fun firstName_extractsFirstTokenOnly() {
        assertEquals("احمد", ContactLookupNormalizer.firstName("د. أحمد علي"))
        assertEquals("ساره", ContactLookupNormalizer.firstName("سارة محمد"))
        assertEquals("john", ContactLookupNormalizer.firstName("Dr. John Doe"))
    }

    @Test
    fun phoneLookupForms_generatesLocalAndInternationalForms() {
        val saForms = ContactLookupNormalizer.phoneLookupForms(
            "+966501234567", "SA"
        )
        assertTrue(saForms.contains("+966501234567"))
        assertTrue(saForms.contains("0501234567"))
        assertTrue(saForms.contains("501234567"))

        val egForms = ContactLookupNormalizer.phoneLookupForms(
            "+201012345678", "EG"
        )
        assertTrue(egForms.contains("+201012345678"))
        assertTrue(egForms.contains("01012345678"))
    }
}
