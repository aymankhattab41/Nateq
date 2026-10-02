package com.aymankhattab.nateq.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.announcement.CallerScreeningService
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * ضابط قسم «إرشادات توافق الجهاز» (المحور الثالث): يكتشف الشركة المصنّعة
 * ([Build.MANUFACTURER]) ويقدّم إرشاداً خاصاً بها عن ضبط البطارية والتشغيل
 * التلقائي، مع زرين يفتحان شاشات النظام المناسبة — استثناء تحسين البطارية،
 * وإدارة التشغيل التلقائي الخاصة بالمصنّع (وإلا صفحة معلومات التطبيق).
 *
 * الإعلانات الصوتية للخلفية تُقتل على أجهزة Xiaomi/Huawei/Oppo/Vivo عند عدم
 * السماح للتطبيق بالتشغيل في الخلفية — لهذا يحتاج المستخدم هذا الدليل
 * بخطوة واحدة داخل التطبيق بدل البحث عن الشاشات يدوياً.
 *
 * وفيه **مسارُ الهوية الفورية لاسم المتصل** (نُقل من قسم «إعلان المتصل»):
 * كلاهما منحٌ يدويٌّ من إعدادات النظام لا من التطبيق، وكلاهما يختلف بين
 * المصنّعات (فدورُ فرز المكالمات غيرُ موجودٍ أصلاً على بعض الأجهزة)، فمكانُه
 * هنا مع أزرار التوافق لا في ضوابط النطق.
 */
internal class OemGuidanceController(
    private val fragment: VoiceSelectionFragment
) {

    private var btnInstantIdentity: View? = null
    private var tvInstantIdentityStatus: TextView? = null

    fun setup(view: View) {
        val vendor = OemVendor.detect(Build.MANUFACTURER)
        val tvVendor = view.findViewById<TextView>(R.id.tv_oem_guidance_vendor)
        val tvText = view.findViewById<TextView>(R.id.tv_oem_guidance_text)
        val btnBattery: MaterialButton =
            view.findViewById(R.id.btn_open_battery_settings)
        val btnAutostart: MaterialButton =
            view.findViewById(R.id.btn_open_autostart_settings)

        val manufacturer = Build.MANUFACTURER
            ?.takeIf { it.isNotBlank() }
            ?: fragment.getString(R.string.oem_vendor_unknown)
        tvVendor.text = fragment.getString(
            R.string.oem_device_label, manufacturer
        )
        tvText.text = fragment.getString(
            when (vendor) {
                OemVendor.XIAOMI -> R.string.oem_guidance_xiaomi
                OemVendor.HUAWEI -> R.string.oem_guidance_huawei
                OemVendor.OPPO -> R.string.oem_guidance_oppo
                OemVendor.VIVO -> R.string.oem_guidance_vivo
                OemVendor.SAMSUNG -> R.string.oem_guidance_samsung
                OemVendor.GENERIC -> R.string.oem_guidance_generic
            }
        )
        btnBattery.setOnClickListener {
            openBatterySettings(fragment.requireContext())
        }
        btnAutostart.setOnClickListener {
            openAutostartSettings(fragment.requireContext(), vendor)
        }

        btnInstantIdentity = view.findViewById(R.id.btn_caller_instant_identity)
        tvInstantIdentityStatus =
            view.findViewById(R.id.tv_caller_instant_identity_status)
        setupInstantIdentityRow()
    }

    /**
     * صفُّ الهوية الفورية: مساران يقرّبان نطقَ اسم المتصل من لحظة الرنّة،
     * وكلاهما **منحٌ يدويّ من إعدادات النظام** (لا إذن وقت التشغيل):
     *
     *  - **إشعار الهاتف** — يمنحه النظام فيُفتح إشعارُ المكالمة ويصلنا
     *    الاسمُ مُحلّى وقت الرنّة (وهو ما تعتمد TalkBack وGoogle Phone).
     *  - **دور فرز المكالمات** — يستدعينا النظام قبل الرنّة.
     *
     * أيّهما تُتاح كان كافياً؛ فالنصُّ يوضّح ذلك بأن المسارين اختياريّان
     * وButtonُه واحدٌ يفتح ما ينقص. **بلاهما** يبقى التطبيق يعمل، لكن
     * الرقمَ قد يتأخّر ~٦ ثوانٍ كما رُصد على أجهزةٍ حقيقية، فلا يُنطق
     * إلا نغمةُ التنبيه بلا اسم.
     */
    private fun setupInstantIdentityRow() {
        btnInstantIdentity?.setOnClickListener {
            val ctx = fragment.requireContext()
            val notifGranted = runCatching {
                NateqNotificationListener.isPermissionGranted(ctx)
            }.getOrDefault(false)
            val roleHeld = runCatching {
                CallerScreeningService.hasScreeningRole(ctx)
            }.getOrDefault(false)
            when {
                !notifGranted -> openNotificationListenerSettings(ctx)
                !roleHeld -> requestScreeningRoleOrExplain(ctx)
                else -> Toast.makeText(
                    ctx, R.string.caller_instant_identity_granted,
                    Toast.LENGTH_SHORT
                ).show()
            }
            // العودة من إعدادات النظام تعيد الرسم، لكن التحديث الفوري
            // يجعل الحالة صحيحة لو عاد المستخدم بلا تغيير.
            refreshInstantIdentityStatus()
        }
        refreshInstantIdentityStatus()
    }

    /** حالة المسارين تُعرض صراحةً — المستخدم يحتاج أن يعرف أيّهما فعّال
     *  وإلا ظنّ أنّ التطبيق لا ينطق الاسم. يُنادى أيضاً في [onResume] الفَصل
     *  لأنّ الحالة تتغيّر بتغيّر إعدادات النظام لا بتغيّر بيانات التطبيق. */
    fun refreshInstantIdentityStatus() {
        val ctx = fragment.context ?: return
        val notifGranted = runCatching {
            NateqNotificationListener.isPermissionGranted(ctx)
        }.getOrDefault(false)
        val roleHeld = runCatching {
            CallerScreeningService.hasScreeningRole(ctx)
        }.getOrDefault(false)
        val resId = when {
            notifGranted && roleHeld ->
                R.string.caller_instant_identity_status_both
            notifGranted ->
                R.string.caller_instant_identity_status_notif
            roleHeld ->
                R.string.caller_instant_identity_status_role
            else ->
                R.string.caller_instant_identity_status_none
        }
        tvInstantIdentityStatus?.text = ctx.getString(resId)
    }

    /** يُفرّغ المراجع عند تدمير الواجهة حتى لا يحتجز الـFragment آباءً. */
    fun release() {
        btnInstantIdentity = null
        tvInstantIdentityStatus = null
    }

    private fun openNotificationListenerSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(
                context, R.string.notification_permission_needed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * طلبُ دور فرز المكالمات. قد يرفض النظامُ الطلب (شرطُه أن يكون
     * التطبيقُ معالجَ المكالمات النشطة)، وعندها نفتح إعدادات الدور
     * مباشرةً ليختاره المستخدم بنفسه بدل رسالةِ فشلٍ جافة.
     */
    private fun requestScreeningRoleOrExplain(context: Context) {
        val launched = runCatching {
            CallerScreeningService.requestScreeningRole(context)
        }.getOrDefault(false)
        if (!launched) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(
                    context, R.string.caller_screening_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** يفتح نافذة منح استثناء تحسين البطارية لهذا التطبيق مباشرةً
     *  (إن توفرت)، وإلا شاشة قائمة التطبيقات المستثناة. */
    private fun openBatterySettings(context: Context) {
        val pkg = context.packageName
        val opened = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$pkg")
                )
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                )
            }
        }
    }

    /** يفتح شاشة إدارة التشغيل التلقائي الخاصة بالمصنّع؛ عند التعذر
     *  (مصنّع غير معروف أو تحرّكت الحزم) يهبط على صفحة معلومات التطبيق. */
    private fun openAutostartSettings(context: Context, vendor: OemVendor) {
        for (intent in autostartIntents(context, vendor)) {
            val opened = runCatching {
                context.startActivity(intent)
            }.isSuccess
            if (opened) return
        }
        val openedDetails = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }.isSuccess
        if (!openedDetails) {
            Toast.makeText(
                context,
                R.string.oem_screen_open_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** محاولات فتح إدارة التشغيل التلقائي حسب المصنّع (الأولوية للمكوّن
     *  المباشر ثم أي إجراء بديل)، فتنتهي أخيراً بصفحة معلومات التطبيق. */
    private fun autostartIntents(
        context: Context,
        vendor: OemVendor
    ): List<Intent> {
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return when (vendor) {
            OemVendor.XIAOMI -> listOf(
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart" +
                            ".AutoStartManagementActivity"
                    )
                },
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart" +
                            ".AutoStartManagementActivity"
                    )
                )
            )
            OemVendor.HUAWEI -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr" +
                            ".ui.StartupNormalAppListActivity"
                    )
                ),
                Intent("huawei.intent.action.STARTUP_MANAGEMENT").apply {
                    setPackage("com.huawei.systemmanager")
                }
            )
            OemVendor.OPPO -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup" +
                            ".StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup" +
                            ".StartupAppListActivity"
                    )
                )
            )
            OemVendor.VIVO -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity" +
                            ".BgStartUpManagerActivity"
                    )
                )
            )
            OemVendor.SAMSUNG, OemVendor.GENERIC -> listOf(appDetails)
        }
    }
}

/** مصنّعو الأجهزة المعروفون بتشديدهم على قتل تطبيقات الخلفية، وهم من يحتاج
 *  أبناءهم الإرشادات والأزرار الخاصة. غيرهم مُصنّف [OemVendor.GENERIC] تظهر
 *  له الإرشادات العامة وزرا البطارية/صفحة التطبيق. */
enum class OemVendor {
    GENERIC, XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG;

    companion object {
        fun detect(manufacturer: String?): OemVendor {
            val key = manufacturer
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            return when {
                // Xiaomi / Redmi / POCO / Black Shark
                key.contains("xiaomi") || key.contains("redmi") ||
                    key.contains("poco") || key.contains("blackshark") ->
                    XIAOMI
                // Huawei / Honor
                key.contains("huawei") || key.contains("honor") ->
                    HUAWEI
                // Oppo / OnePlus / realme
                key.contains("oppo") || key.contains("oneplus") ||
                    key.contains("realme") ->
                    OPPO
                key.contains("vivo") -> VIVO
                key.contains("samsung") || key.contains("sec") ->
                    SAMSUNG
                else -> GENERIC
            }
        }
    }
}