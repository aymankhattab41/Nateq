package com.aymankhattab.nateq.core.audio.announcement

import android.telephony.TelephonyManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ الأمر 7 — سطحُ المستقبل المُصدَّر.
 *
 * ## العيب
 *
 * كان `CallerAnnouncementReceiver` مُصدَّراً (`AndroidManifest.xml`
 * ‏178-185) ويقبل **إجراءً معرَّفاً من التطبيق**
 * (`ACTION_NOTIFICATION_CALL`) إلى جانب `PHONE_STATE`. والقيدُ في
 * المانيفست كان على **الباعث** (`android:permission=
 * READ_PHONE_STATE`) لا على الإجراء — والإجراءُ غيرُ مُعلَن في
 * `<intent-filter>` فيصل عبر `sendBroadcast` صريح. فأي تطبيقٍ يحمل
 * `READ_PHONE_STATE` (إذنٌ خطيرٌ تُمنح منهُ تطبيقاتٌ كثيرة) كان
 * يرسل extras من اختياره فيجعل الجهاز ينطق رقماً واسمًا من إنشائه.
 *
 * ## العقد
 *
 * 1. **بثُّ النظام وحده** هو ما يصل إلى المستقبل ([isPhoneStateAction]).
 * 2. **لا أثرَ للإجراء المحذوف في bytecode** — لا حقلٌ باسمه ولا حقلٌ
 *    يحمل نصَّه، فلا يعود ممّا يُستدعى حتى لو أُعيدت تسميته.
 * 3. **الاستدعاءُ بين مكوّنات التطبيق صار مباشراً** لا بثّاً: مسارُ
 *    إشعار VoIP لم يعد يشغل نافذةَ `goAsync` ولا سطحَ البثّ.
 *
 * **ولماذا حارسٌ على `isPhoneStateAction` وعلى bytecode معاً؟** لأن
 * الحارسَ الأوّل يحرس **القرار** والحارسُ الثاني يحرس **الأثرَ
 * الماديّ**: فمن يحذف البندَ الثاني ويبقي التنقيحَ في الاسم يمرّ
 * الأولُ صامتاً، ومن يحذف التنقيحَ ويترك النصَّ في حقلٍ يمرّ
 * الثاني. فلا يكفي واحدٌ منهما.
 */
class ExportedReceiverActionGuardTest {

    /**
     * **العمودُ الفقري:** ما عدا هذا الإجراء لا يبدأ جلسةً أصلاً.
     */
    @Test
    fun `the system phone state action is the only accepted one`() {
        assertTrue(
            "بثّ النظام هو المصدرُ المشروع الوحيد",
            CallerAnnouncementReceiver.isPhoneStateAction(
                TelephonyManager.ACTION_PHONE_STATE_CHANGED
            )
        )
    }

    /**
     * **الكسرُ الأول — التزوير:** الإجراءُ المحذوف بنصّه، وهو ما
     * كان مهاجمٌ يرسله. لا بدّ أن يُرفض بالمقارنة نفسها لا بغياب
     * العنصر.
     */
    @Test
    fun `the forged application action is rejected`() {
        assertFalse(
            "إجراء التطبيق المحذوف يجب ألّا يُقبل",
            CallerAnnouncementReceiver.isPhoneStateAction(FORGED_ACTION)
        )
    }

    /**
     * **ولا انفتاحٌ بالتقريب:** `null` وفارغٌ وإجراءٌ غريبٌ وإجراءُ
     * النظام بلاحقة — كلها مرفوضة. فمقارنةُ السلسلة بحرفٍ واحد
     * (`startsWith`) كانت ستنفتح على الاثنين.
     */
    @Test
    fun `null and near miss actions are rejected`() {
        assertFalse(CallerAnnouncementReceiver.isPhoneStateAction(null))
        assertFalse(CallerAnnouncementReceiver.isPhoneStateAction(""))
        assertFalse(
            CallerAnnouncementReceiver.isPhoneStateAction(
                "android.intent.action.VIEW"
            )
        )
        assertFalse(
            CallerAnnouncementReceiver.isPhoneStateAction(
                TelephonyManager.ACTION_PHONE_STATE_CHANGED + ".extra"
            )
        )
        assertFalse(
            CallerAnnouncementReceiver.isPhoneStateAction(
                FORGED_ACTION.lowercase()
            )
        )
    }

    /**
     * **أثرُ الاسم في bytecode:** `const val` في `companion object`
     * يُولِّد حقلاً ساكناً على الصنف الحاوي، فإعادةُ التسمية وحدها
     * لا بد من اختفاء الاسم — لامحيدً عنذاك التسمية.
     */
    @Test
    fun `the removed action name is gone from the bytecode`() {
        val names =
            CallerAnnouncementReceiver::class.java.declaredFields
                .map { it.name } +
                CallerAnnouncementReceiver.Companion::class.java
                    .declaredFields.map { it.name }
        assertNull(
            "للإجراء المحذوف أثرٌ في bytecode",
            names.firstOrNull { it == "ACTION_NOTIFICATION_CALL" }
        )
    }

    /**
     * **أثرُ النصّ في bytecode — الحارسُ الأشدّ:** لو أُعيد تعريفُ
     * الإجراء باسمٍ آخر لالتقط هذا الحارس. فهو يسأل عن **القيمة** لا
     * عن الاسم.
     */
    @Test
    fun `no field still carries the removed action string`() {
        val values = CallerAnnouncementReceiver::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(null) as? String
                }.getOrNull()
            }
        assertNull(
            "نصّ الإجراء المحذوف ما زال في حقلٍ ساكن",
            values.firstOrNull { it == FORGED_ACTION }
        )
    }

    private companion object {
        const val FORGED_ACTION =
            "com.aymankhattab.nateq.action.NOTIFICATION_CALL"
    }
}
