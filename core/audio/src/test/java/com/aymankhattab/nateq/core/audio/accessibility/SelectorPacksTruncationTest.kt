package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٢٩-12: كشف النص المقتطع (ينتهي بـ «قراءة المزيد» أو أنماط
 * اقتطاع أخرى) قبل استدعاء AccessibilityBridge.expandReadMore.
 */
class SelectorPacksTruncationTest {

    @Test
    fun looksTruncated_detectsArabicPatterns() {
        assertTrue(SelectorPacks.looksTruncated("رسالة طويلة…"))
        assertTrue(SelectorPacks.looksTruncated("رسالة طويلة..."))
        assertTrue(SelectorPacks.looksTruncated("رسالة طويلة… المزيد"))
        assertTrue(SelectorPacks.looksTruncated("رسالة طويلة... المزيد"))
        assertTrue(SelectorPacks.looksTruncated("رسالة طويلة قراءة المزيد"))
    }

    @Test
    fun looksTruncated_detectsEnglishPatterns() {
        assertTrue(SelectorPacks.looksTruncated("Long message…"))
        assertTrue(SelectorPacks.looksTruncated("Long message..."))
        assertTrue(SelectorPacks.looksTruncated("Long message See more"))
        assertTrue(SelectorPacks.looksTruncated("Long message Show more"))
        assertTrue(SelectorPacks.looksTruncated("Long message Read more"))
    }

    @Test
    fun looksTruncated_detectsOtherLanguages() {
        assertTrue(SelectorPacks.looksTruncated(
            "Message longue Lire la suite"
        ))
        assertTrue(SelectorPacks.looksTruncated(
            "Lange Nachricht Mehr anzeigen"
        ))
    }

    @Test
    fun looksTruncated_rejectsNormalText() {
        assertFalse(SelectorPacks.looksTruncated(
            "نص عادي كامل"
        ))
        assertFalse(SelectorPacks.looksTruncated(
            "Complete text without truncation"
        ))
        assertFalse(SelectorPacks.looksTruncated(""))
        assertFalse(SelectorPacks.looksTruncated(null))
        assertFalse(SelectorPacks.looksTruncated("   "))
    }

    @Test
    fun looksTruncated_rejectsMidTextKeywords() {
        // الكلمة في المنتصف لا تعني اقتطاعاً
        assertFalse(SelectorPacks.looksTruncated("See more details here"))
        assertFalse(SelectorPacks.looksTruncated("قراءة المزيد متاحة"))
    }

    @Test
    fun isReadMoreTrigger_stillWorks() {
        assertTrue(SelectorPacks.isReadMoreTrigger("قراءة المزيد"))
        assertTrue(SelectorPacks.isReadMoreTrigger("Read more"))
        assertTrue(SelectorPacks.isReadMoreTrigger("المزيد"))
        assertFalse(SelectorPacks.isReadMoreTrigger("نص عادي"))
    }
}