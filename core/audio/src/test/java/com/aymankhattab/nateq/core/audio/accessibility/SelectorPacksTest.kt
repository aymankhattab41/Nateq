package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorPacksTest {

    @Test
    fun `isReadMoreTrigger detects triggers in multiple languages`() {
        assertTrue(SelectorPacks.isReadMoreTrigger("قراءة المزيد"))
        assertTrue(SelectorPacks.isReadMoreTrigger("عرض المزيد"))
        assertTrue(SelectorPacks.isReadMoreTrigger("Read more"))
        assertTrue(SelectorPacks.isReadMoreTrigger("... Read more"))
        assertTrue(SelectorPacks.isReadMoreTrigger("Show more"))

        assertFalse(SelectorPacks.isReadMoreTrigger("مرحبا كيف حالك"))
        assertFalse(SelectorPacks.isReadMoreTrigger(null))
        assertFalse(SelectorPacks.isReadMoreTrigger(""))
    }
}
