package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.role.RoleManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.aymankhattab.nateq.core.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * خدمة فرز المكالمات — المصدر **الأسبق** لهوية المتصل: النظام يناديها
 * قبل أن تُشغَّل رنّة الهاتف، فتكون النافذة الزمنية أوسع من إشعار
 * الهاتف ومن بث `PHONE_STATE`.
 *
 * **الشرط:** أن يمنح المستخدم التطبيق دور **فرز المكالمات**
 * (`CALL_SCREENING`) من إعدادات النظام. بدون الدور لا يستدعيها النظام
 * إطلاقاً — فالتسجيل في الـ Manifest وحده لا يكفي، وهو **دور** لا
 * إذن فلا يُطلب في وقت التشغيل.
 *
 * **وظيفتها هنا محدودة ومقصودة:** نشر الهوية في [RingCallerIdentity]
 * فتنطقها حلقةُ [CallerAnnouncementReceiver] وحدها. لا نُخفي ولا
 * نحجب ولا نغيّر مسار المكالمة: التطبيق ليس تطبيق اتصال، والتلاعب
 * بالرنّة أو تحويلها قرار يخصّ تطبيق الهاتف وحده.
 *
 * **بلا استجابة مقصودة:** لا نمرّر `respondToCall` إطلاقاً — وهي الدالة
 * التي يُلغي النظامُ بها الرنّة. بتركها بلا استجابة يبقى الرنُّ
 * طبيعياً مطابقاً لتطبيق الهاتف تماماً، فلا نحجب ولا نحرم المستخدم من
 * أي مكالمة.
 *
 * كما أن `respondToCall` نهائية (`final`) في المنصّة فلا تُعاد
 * كتابتها أصلاً — فسلوكُ «السماح بالمكالمة» هو السلوكُ الطبيعي بلا أي
 * كودٍ منّا، وهو ما نريده بالضبط.
 */
@AndroidEntryPoint
class CallerScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "NATEQ_SCREEN"

        /**
         * هل يحمل التطبيق دور فرز المكالمات؟ يُقرأ من النظام مباشرةً
         * بدل تخزين نسخة — فالدور قد يُمنح أو يُسحب في أي وقت، والنسخة
         * المخزّنة تكذب عن الواقع.
         */
        @JvmStatic
        fun hasScreeningRole(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return runCatching {
                val manager = context.getSystemService(RoleManager::class.java)
                manager != null &&
                    manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                    manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            }.getOrDefault(false)
        }

        /**
         * طلب دور فرز المكالمات من النظام.
         *
         * ملاحظة مهمة: `RoleManager.createRequestRoleIntent` يرفض طلب
         * دورٍ لا يجتاز التطبيق شروطه، ومن شروطه أن يكون التطبيق
         * **معالج المكالمات النشطة** (Call handling) — وهو دور لا يطالِبه
         * تطبيقٌ كهذا دون أن يكون تطبيق الهاتف الفعلي. لذلك قد يفشل
         * الطلب، وحينها يبقى **إشعار الهاتف** هو المسار الفعلي ولا
         * يُسقط الإشعار بأي حال.
         */
        @JvmStatic
        fun requestScreeningRole(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return runCatching {
                val manager = context.getSystemService(RoleManager::class.java)
                val available = manager?.isRoleAvailable(
                    RoleManager.ROLE_CALL_SCREENING
                ) ?: false
                if (!available) return false
                context.startActivity(
                    manager.createRequestRoleIntent(
                        RoleManager.ROLE_CALL_SCREENING
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
        }
    }

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            if (!settingsRepository.isCallerAnnouncementEnabled()) return
            // استدعاءُ هذه الدالة مزامِنٌ على خيط الربط، ومنعُه يوقف
            // المهلة ويوقف الرنّة — فلا يُلمس القرص ولا الشبكة هنا.
            val number = callDetails.handle?.schemeSpecificPart
                ?.takeIf { it.isNotBlank() }
            if (number != null) {
                RingCallerIdentity.publish(number, null)
            }
            Log.w(TAG, "CALL-SCREEN number=${number ?: "?"}")
        } catch (t: Throwable) {
            Log.e(TAG, "onScreenCall failed", t)
        }
    }
}