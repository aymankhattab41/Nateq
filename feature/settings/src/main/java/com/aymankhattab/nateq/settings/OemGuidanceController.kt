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
 */
internal class OemGuidanceController(
    private val fragment: VoiceSelectionFragment
) {

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
internal enum class OemVendor {
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