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
        assertTrue(
            SelectorPacks.looksTruncated("رسالة طويلة… المزيد")
        )
        assertTrue(
            SelectorPacks.looksTruncated("رسالة طويلة... المزيد")
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "رسالة طويلة قراءة المزيد"
            )
        )
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
        assertFalse(
            SelectorPacks.looksTruncated("قراءة المزيد متاحة")
        )
    }

    @Test
    fun looksTruncated_detectsTalkBackRoleSuffixes() {
        // ع٤٣-1: نصوص TalkBack تنتهي بدور العنصر (، زر / , button / إلخ)
        assertTrue(
            SelectorPacks.looksTruncated("رسالة طويلة… زر")
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "رسالة طويلة… قراءة المزيد، زر"
            )
        )
        assertTrue(SelectorPacks.looksTruncated(
            "رسالة طويلة... Read more, button"
        ))
        assertTrue(SelectorPacks.looksTruncated(
            "رسالة طويلة... مزدوج النقر للتفعيل"
        ))
        assertTrue(SelectorPacks.looksTruncated(
            "رسالة طويلة… زر إمكانية الوصول"
        ))
    }

    @Test
    fun looksTruncated_detectsMessagingMetadataSuffixes() {
        // ع٤٣-1: نصوص واتساب وتلغرام المنتهية بالتوقيت وحالة الإرسال
        assertTrue(
            SelectorPacks.looksTruncated(
                "رسالة طويلة... " +
                    "\u200Fقراءة المزيد، ١:٤٦ ص، تم الإرسال"
            )
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "Long message... Read more, 1:46 AM, Sent"
            )
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "رسالة طويلة... " +
                    "قراءة المزيد، ١٢:٣٠ م"
            )
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "رسالة طويلة… المزيد، تم التسليم"
            )
        )
        assertFalse(
            SelectorPacks.looksTruncated(
                "نص كامل عادي، ١:٤٦ ص، تم الإرسال"
            )
        )
    }

    @Test
    fun isReadMoreTrigger_rejectsBlacklistedPatterns() {
        // ع٤٣-1: استبعاد صريح لـ More options والخيارات والقوائم
        assertFalse(SelectorPacks.isReadMoreTrigger("More options"))
        assertFalse(SelectorPacks.isReadMoreTrigger("Show more options"))
        assertFalse(
            SelectorPacks.isReadMoreTrigger("خيارات إضافية")
        )
        assertFalse(
            SelectorPacks.isReadMoreTrigger("مزيد من الخيارات")
        )
        assertFalse(SelectorPacks.isReadMoreTrigger("More settings"))
        assertFalse(SelectorPacks.isReadMoreTrigger("See more details"))
    }

    @Test
    fun isTextMatchingMessage_matchesAcrossTalkBackPrefixesAndRoles() {
        val talkbackText =
            "أحمد، 10:15: رسالة طويلة " +
                "ومهمة جداً تتناول خطة العمل… زر"
        val nodeText =
            "رسالة طويلة ومهمة جداً " +
                "تتناول خطة العمل…"
        assertTrue(
            SelectorPacks.isTextMatchingMessage(talkbackText, nodeText)
        )
        assertTrue(
            SelectorPacks.isTextMatchingMessage(nodeText, talkbackText)
        )

        // نصوص غير متطابقة
        assertFalse(
            SelectorPacks.isTextMatchingMessage(
                "رسالة أولى تتحدث عن الطقس",
                "رسالة مختلفة تماماً عن الأخبار"
            )
        )
    }

    @Test
    fun isReadMoreTrigger_stillWorks() {
        assertTrue(SelectorPacks.isReadMoreTrigger("قراءة المزيد"))
        assertTrue(SelectorPacks.isReadMoreTrigger("Read more"))
        assertTrue(SelectorPacks.isReadMoreTrigger("المزيد"))
        assertFalse(SelectorPacks.isReadMoreTrigger("نص عادي"))
    }

    @Test
    fun isReadMoreTrigger_handlesBidiMarksAndWhitespace() {
        // ع٤٣-1: واتساب يغلّف نصوص الأزرار بمحارف اتجاه مثل \u200F
        assertTrue(
            SelectorPacks.isReadMoreTrigger("\u200Fقراءة المزيد")
        )
        assertTrue(
            SelectorPacks.isReadMoreTrigger(
                "\u200Eقراءة المزيد\u200F"
            )
        )
        assertTrue(SelectorPacks.isReadMoreTrigger("\u061Cالمزيد"))
        assertTrue(SelectorPacks.isReadMoreTrigger("  \u200FRead more  "))
    }

    @Test
    fun looksTruncated_handlesBidiMarksAndTalkBackSuffixes() {
        // ع٤٣-1: محاكاة رسالة واتساب ونطق TalkBack الحقيقي
        assertTrue(
            SelectorPacks.looksTruncated(
                "الإصدار 1.6... \u200Fقراءة المزيد، زر"
            )
        )
        assertTrue(
            SelectorPacks.looksTruncated(
                "الإصدار 1.6:... \u200Fقراءة المزيد"
            )
        )
    }
}