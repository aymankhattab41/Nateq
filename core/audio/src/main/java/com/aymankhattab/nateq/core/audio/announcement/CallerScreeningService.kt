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

        // ===== بند 3: اتجاهُ المكالمة =====
        //
        // **ثوابتُ `Call.Details` مستنسخةٌ هنا لا مُحيلةً إليها**، لأنها
        // `int` فيُضمَّن مقدارُها وقتَ التصريف فلا نحتاج أن نقرأ حقلَ
        // `getCallDirection()` أصلاً على الأجهزة القديمة. والقيمُ
        // مُثبَتةٌ بـ`javap` على `android.jar` (الإصدار 37) وأمنعُ
        // انحرافَها بحارس `platform call direction constants are
        // mirrored`.
        internal const val DIRECTION_UNKNOWN = -1
        internal const val DIRECTION_INCOMING = 0
        internal const val DIRECTION_OUTGOING = 1

        /**
         * **هل تُنشر هويةُ المتصل من خدمة الفرز؟**
         *
         * **جذرُ البند:** `onScreenCall` تُستدعى **لكلِّ مكالمةٍ تجري**
         * قبل الرنّة، بما فيها الصادرةُ — فكان الرقمُ يُنشر
         * بلا تمييزٍ فيصير المتصلُ نفسُه (الذي بدأ المكالمة) هويةَ
         * «متصلٍ وارد» فيُعلَن صوتاً. والخدمةُ أسبقُ المساراتِ زمنياً
         * فهي **الفائزُ الأول** بالهوية، فخطؤها يلغي ما بعده ولا يُصحَّح.
         *
         * **والإثباتُ بالإيجاب لا بغياب الدليل:** يُشترط
         * `DIRECTION_INCOMING` صراحةً، فـ`DIRECTION_UNKNOWN` (حالةٌ
         * حقيقيةٌ في المنصّة) ترفض ولا تُنشر.
         *
         * **وحارسُ `sdkInt` ليس زينة:** حقلُ الاتجاه `getCallDirection()`
         * وُجد في Q (29) بينما `CallScreeningService` موجودةٌ من 24،
         * فمن لم يحرس لـ`sdkInt` انهار بـ`NoSuchMethodError` على جهازٍ
         * قديم. ودورُ الفرز نفسه من 29 فلا تُستدعى الخدمةُ أصلاً على ما
         * دونه — فالمرورُ بلا تمييزٍ هناك مسارٌ غيرُ قابل للتنفيذ.
         *
         * **و[readDirection] تُؤخَّذ دالّةً لا قيمةً — وهذا هو حارسُ
         * الانهيار لا زينةٌ him:** تمريرُ `callDetails.callDirection`
         * قيمةً يجعل Kotlin يقيّمُها **قبل** الدخول إلى الدالة، فلا
         * يحرسها `sdkInt` بل يقع الانهيارُ قبل أن تُفتح. فبالدالّة
         * يكون قَصْرُ الدائرة في `||` باطنها يحفظُها، والعقدُ قابلٌ
         * للاختبار، والكسرُ محفورٌ في اسم اختبار.
         *
         * خالصةٌ بلا `Context` فتبقى قابلةً للاختبار بكلِّ حدودها.
         */
        internal inline fun shouldPublishIdentity(
            sdkInt: Int,
            readDirection: () -> Int
        ): Boolean =
            sdkInt < Build.VERSION_CODES.Q ||
                readDirection() == DIRECTION_INCOMING

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
            // **بند 3:** الرقمُ يُنشر للواردةِ فقط. الخدمةُ تُستدعى
            // لكلِّ مكالمةٍ تجري — الصادرةَ قبل الرنّة كغيرها — فبلا
            // هذا الحارس صار المتصلُ نفسُه هويةَ «متصلٍ وارد».
            //
            // **والقراءةُ داخلَ دالّةٍ لا خارجه:** فمضى
            // `callDetails.callDirection` معاملاً يقيَّم **قبل**
            // الحارس، فينهار `getCallDirection()` على جهازٍ دون 29
            // بـ`NoSuchMethodError` قبل أن يفتح الحارسُ أصلاً — أي أن
            // التعليقَ القديم كان يصفُ حارساً لم يكن موجوداً.
            if (!shouldPublishIdentity(Build.VERSION.SDK_INT) {
                    callDetails.callDirection
                }
            ) {
                Log.w(
                    TAG,
                    "CALL-SCREEN skipped direction=" +
                        "${callDetails.callDirection}"
                )
                return
            }
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