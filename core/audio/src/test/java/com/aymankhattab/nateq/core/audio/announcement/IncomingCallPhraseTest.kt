package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الطبقةِ النصّيةِ الثانوية —
 * [RingCallerIdentity.isIncomingCallPhrase].
 *
 * **محدودتُها المحكومة:** هذه الطبقةُ تعملُ على `API < 31` **فقط**، حيث لا
 * نوعَ مكالمةَ ولا إجراءً دلاليًّا موثوق. فكلُّ خطأٍ هنا محصورٌ في
 * الأجهزة القديمة — وهذا ما يجعلها سابلةً للقبول مع كلِّ هشاشتِها.
 *
 * **والممنوعُ في قائمتها: «ringing».** وهي في قائمة الصادرة
 * ([isOutgoingCallPhrase]) لأنّ Meet يكتب «Ringing tone…» على مكالمتِه
 * **الواردة**. فلو أُدرجت هنا لانقلب الدليلان على بعضهما فيُرفضُ إعلانُ
 * Meet الوارد — وهو انحدارٌ سابقٌ حدث فعلاً في v1.6.20. فالحارسُ
 * [meet ringing tone is neither outgoing nor incoming phrase] يمنع
 * تكرارَه.
 */
class IncomingCallPhraseTest {

    // ===== يقبلُ العبارتين الصريحتين =====

    @Test
    fun `explicit incoming phrases are accepted`() {
        assertTrue(
            "عبارةٌ إنجليزيةٌ صريحة",
            RingCallerIdentity.isIncomingCallPhrase("Incoming call")
        )
        assertTrue(
            "عبارةٌ إنجليزيةٌ ناقصة",
            RingCallerIdentity.isIncomingCallPhrase("Incoming")
        )
        assertTrue(
            "عبارةٌ عربيةٌ صريحة",
            RingCallerIdentity.isIncomingCallPhrase("مكالمة واردة")
        )
        assertTrue(
            "صيغةٌ عربيةٌ بهمزةٍ مقصورة",
            RingCallerIdentity.isIncomingCallPhrase("مكالمه وارده")
        )
    }

    // ===== يرفضُ ما ليس دليلَ ورود =====

    @Test
    fun `outgoing and ongoing phrases are not incoming`() {
        // العباراتُ المقابلةُ دليلٌ على العكس، فلا يجوز أن تكون دليلَ
        // ورودٍ في الطبقةِ الثانوية أيضاً.
        assertFalse(
            "جاري الاتصال ليس وارداً",
            RingCallerIdentity.isIncomingCallPhrase("جارٍ الاتصال")
        )
        assertFalse(
            "المكالمة الجارية ليست واردة",
            RingCallerIdentity.isIncomingCallPhrase("مكالمة جارية")
        )
        assertFalse(
            "Calling ليس وارداً",
            RingCallerIdentity.isIncomingCallPhrase("calling")
        )
    }

    @Test
    fun `a contact name is not an incoming phrase`() {
        // الاسمُ المُستخرَجُ من الإشعار يقع في نفس نصوصِ الفحص، فلا بدّ
        // ألّا يُحسب دليلَ ورودٍ — وإلا صار كلُّ متصلٍ اسموه
        // «Incoming» إعلانَ ورودٍ على أيّ إشعار.
        assertFalse(
            "اسمُ متصلٍ ليس دليلَ ورود",
            RingCallerIdentity.isIncomingCallPhrase("Incoming Ahmed")
        )
        assertFalse(
            "رقمٌ ليس دليلَ ورود",
            RingCallerIdentity.isIncomingCallPhrase("0551234567")
        )
        assertFalse(
            "نصٌّ فارغٌ ليس دليلَ ورود",
            RingCallerIdentity.isIncomingCallPhrase("")
        )
    }

    // ===== حارسُ انحدار Meet (v1.6.20) =====

    @Test
    fun `meet ringing tone is neither outgoing nor incoming phrase`() {
        // Meet الوارد يكتب «Ringing tone…»: هي في قائمة الصادرة فيُرفض
        // إعلانُه... وكان هذا سببُ إسقاطِه بالكامل في v1.6.20. فنثبّت
        // أنّ هاتَي الدالةَين **لا** تطابقانه معاً، فالأمرُ يُحسم في
        // المسار بالإثباتِ الموجبِ لا بالعبارة.
        val phrase = "Ringing tone…"
        assertFalse(
            "عبارةُ Meet ليست صادرة",
            RingCallerIdentity.isOutgoingCallPhrase(phrase)
        )
        assertFalse(
            "عبارةُ Meet ليست واردة",
            RingCallerIdentity.isIncomingCallPhrase(phrase)
        )
    }

    @Test
    fun `normalisation keeps both layers disjoint`() {
        // بعد التطبيع: العربيةُ «مكالمة واردة» تبقى واردةً، و«مكالمة
        // جارية» تبقى جاريةً — فلا تلتقي الطبقتان في نصٍّ واحد.
        assertTrue(
            "واردةٌ بعد التطبيع",
            RingCallerIdentity.isIncomingCallPhrase("  مكالمةٌ واردةٌ ")
        )
        assertFalse(
            "جاريةٌ ليست واردةً بعد التطبيع",
            RingCallerIdentity.isIncomingCallPhrase("مكالمة جارية")
        )
    }
}
