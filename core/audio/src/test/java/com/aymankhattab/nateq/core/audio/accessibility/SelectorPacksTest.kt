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

    @Test
    fun `isReadMoreActionLabel detects expand and read more action labels`() {
        assertTrue(SelectorPacks.isReadMoreActionLabel("توسيع"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("توسيع الرسالة"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("توسيع النص"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("عرض الرسالة كاملة"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("Expand"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("Expand message"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("Expand text"))
        assertTrue(SelectorPacks.isReadMoreActionLabel("قراءة المزيد"))
        assertTrue(
            SelectorPacks.isReadMoreActionLabel("قراءة المزيد من هذه الرسالة")
        )
        assertTrue(SelectorPacks.isReadMoreActionLabel("Read more of this"))

        // استبعاد الكلمات المحظورة
        assertFalse(SelectorPacks.isReadMoreActionLabel("More options"))
        assertFalse(SelectorPacks.isReadMoreActionLabel("خيارات إضافية"))
        assertFalse(SelectorPacks.isReadMoreActionLabel("Show details"))
        assertFalse(SelectorPacks.isReadMoreActionLabel("مشاركة الرسالة"))
        assertFalse(SelectorPacks.isReadMoreActionLabel(null))
        assertFalse(SelectorPacks.isReadMoreActionLabel(""))
    }
}
