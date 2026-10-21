package com.aymankhattab.nateq.settings.banking

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.aymankhattab.nateq.core.audio.accessibility.NateqAccessibilityService
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.feature.settings.R
import java.io.File

/** خدمة إمكانية وصول مُفعَّلة فعلياً على الجهاز. */
data class EnabledAccessibilityService(
    val id: String,
    val label: String,
    val packageName: String,
    val isAccessibilityTool: Boolean,
    val canRetrieveWindowContent: Boolean,
    /** هل الخدمة تابعةٌ لناطق (لا يُعامَل باستثناء — بل علامةٌ للعرض فقط). */
    val isLordTts: Boolean,
    /** هل قُرئت وسومُ الخدمة فعلاً أم تعذّر ذلك (فيرجَّح للتصنيف). */
    val flagsKnown: Boolean
)

/** حالة قدرات ناطق الثلاث المشتبه بها — تُملأ دفعةً واحدة ثم تُعرض. */
data class LordCapabilityState(
    val accessibilityServiceEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val overlayGranted: Boolean
)

/**
 * جامعُ حالة الجهاز لفحص توافق التطبيقات المصرفية.
 *
 * **كل فرعٍ مغلوفٌ بـ`runCatching`**: شاشةُ التشخيص يجب ألّا تنهار على
 * جهازٍ غريب (Samsung/Xiaomi مع تغيليات مُعدَّلة ترمي استثناءً من
 * استعلاماتٍ نظاميةٍ بريءّة) — أي استعلامٍ فاشلٍ يظهر «تعذّر» ويُكمل
 * بقية البنود، ولا يُسقط الشاشة.
 *
 * **لا يُعامَل أي حزمٍ باستثناء**: حزمُ ناطق تُعلَم كي يُميَّز في العرض
 * فقط، لا كي يُصنَّف بقاعدةٍ مختلفة.
 */
object BankingCompatProbe {

    /** مسارات su الشائعة (عرضُ تشخيصٍ — قرارُ الأمان ليس هنا). */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/local/tmp/su",
        "/data/local/su"
    )

    /** حزمة Shizuku. */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** اسم مفتاح `adb_wifi_enabled` في `Settings.Global`. */
    private const val GLOBAL_ADB_WIFI = "adb_wifi_enabled"

    /**
     * مفتاح تصحيح USB في Settings.Secure — الاسم الثابت المُعلَن
     * Settings.Secure.ADB_ENABLED مهجورٌ منذ API 34 فلا يُستخدم.
     */
    private const val ADB_USB_KEY = "adb_enabled"

    /**
     * جمعُ كل البنود: خدمة إمكانية الوصول لكل خدمةٍ مُفعَّلة (لا خدمةٌ
     * واحدة)، ثم بقية الإعدادات. تُحذَّم من الخيط غير الرئيسي.
     */
    fun probe(context: Context): List<BankingCheckItem> {
        val items = mutableListOf<BankingCheckItem>()
        items += accessibilityServiceItems(context)
        items += notificationListenerItem(context)
        items += overlayItem(context)
        items += adbItems(context)
        items += deviceAdminItem(context)
        items += dialerRoleItem(context)
        items += vpnItem(context)
        items += suItem(context)
        items += shizukuItem(context)
        items += installSourceItem(context)
        return items
    }

    /** حالة قدرات ناطق الثلاث — تُستخدم في المعالج وفي ترويسة الشاشة. */
    fun lordCapabilityState(context: Context): LordCapabilityState =
        LordCapabilityState(
            accessibilityServiceEnabled =
                isLordAccessibilityServiceEnabled(context),
            notificationListenerEnabled = runCatching {
                NateqNotificationListener.isPermissionGranted(context)
            }.getOrDefault(false),
            overlayGranted = runCatching {
                Settings.canDrawOverlays(context)
            }.getOrDefault(false)
        )

    /** هل خدمة إمكانية الوصول في ناطق مُفعَّلة فعلاً؟ */
    fun isLordAccessibilityServiceEnabled(context: Context): Boolean =
        runCatching {
            val wanted = ComponentName(
                context,
                NateqAccessibilityService::class.java
            ).flattenToString()
            enabledComponentIds(context).any {
                it.equals(wanted, ignoreCase = true)
            }
        }.getOrDefault(false)

    /** معرّفات الخدمات المُفعَّلة (`pkg/cls`) من إعدادات النظام. */
    private fun enabledComponentIds(context: Context): List<String> =
        runCatching {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@runCatching emptyList<String>()
            raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

    /** بنود خدمات إمكانية الوصول — واحدة لكل خدمةٍ مُفعَّلة. */
    private fun accessibilityServiceItems(
        context: Context
    ): List<BankingCheckItem> {
        val runtime = runtimeAccessibilityInfos(context)
        val components = runCatching {
            enabledComponentIds(context).map {
                ComponentName.unflattenFromString(it)
            }
        }.getOrDefault(emptyList())

        if (components.isEmpty()) {
            return listOf(
                BankingCheckItem(
                    id = BankingCheckIds.ACCESSIBILITY_SERVICE,
                    label = context.getString(
                        R.string.banking_item_a11y_none_label
                    ),
                    detail = context.getString(
                        R.string.banking_item_a11y_none_detail
                    ),
                    risk = BankingCompatClassifier.riskFor(
                        BankingCheckIds.ACCESSIBILITY_SERVICE
                    )
                )
            )
        }

        return components.filterNotNull().mapNotNull { component ->
            val id = component.flattenToString()
            val info = runtime[id]
            val label = runCatching {
                context.packageManager
                    .getServiceInfo(component, 0)
                    .loadLabel(context.packageManager)
                    .toString()
            }.getOrDefault(component.className)
            val isLord = component.packageName == context.packageName
            val known = info != null
            val isTool = info?.isAccessibilityTool == true
            val canRetrieve = info?.capabilities
                ?.and(AccessibilityServiceInfo
                    .CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
            val risk = if (!known) {
                // وسومٌ غير مقروءة: لا نرفع راية خطرٍ بلا دليل، ونذكرها.
                BankingRisk.LOW
            } else {
                BankingCompatClassifier
                    .accessibilityServiceRisk(isTool)
            }
            BankingCheckItem(
                id = BankingCheckIds.ACCESSIBILITY_SERVICE,
                label = context.getString(
                    R.string.banking_item_a11y_label, label
                ),
                detail = a11yDetail(context, component, known, isTool,
                    canRetrieve, isLord),
                risk = risk
            )
        }
    }

    /** تفصيل خدمة إمكانية الوصول — يُقرأ على قارئ الشاشة بالعربية. */
    private fun a11yDetail(
        context: Context,
        component: ComponentName,
        known: Boolean,
        isTool: Boolean,
        canRetrieve: Boolean,
        isLord: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts += context.getString(
            R.string.banking_item_a11y_detail_pkg, component.packageName
        )
        if (!known) {
            parts += context.getString(R.string.banking_item_a11y_flags_unknown)
            return parts.joinToString(" — ")
        }
        parts += if (isTool) {
            context.getString(R.string.banking_item_a11y_tool_yes)
        } else {
            context.getString(R.string.banking_item_a11y_tool_no)
        }
        parts += if (canRetrieve) {
            context.getString(R.string.banking_item_a11y_retrieve_yes)
        } else {
            context.getString(R.string.banking_item_a11y_retrieve_no)
        }
        if (isLord) {
            parts += context.getString(R.string.banking_item_a11y_is_lord)
        }
        return parts.joinToString(" — ")
    }

    /** خرائط `AccessibilityServiceInfo` المتاحة من مدير الإمكانية. */
    private fun runtimeAccessibilityInfos(
        context: Context
    ): Map<String, AccessibilityServiceInfo> = runCatching {
        val manager = context
            .getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager
            ?: return@runCatching emptyMap<String, AccessibilityServiceInfo>()
        val mask = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        manager.getEnabledAccessibilityServiceList(mask)
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    /** بند «الاستماع للإشعارات» — أعلى المشتبه بالإثبات المُشاهَد. */
    private fun notificationListenerItem(
        context: Context
    ): BankingCheckItem {
        val enabled = runCatching {
            NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
        return BankingCheckItem(
            id = BankingCheckIds.NOTIFICATION_LISTENER,
            label = context.getString(
                R.string.banking_item_notif_listener_label
            ),
            detail = if (enabled) {
                context.getString(
                    R.string.banking_item_notif_listener_on
                )
            } else {
                context.getString(
                    R.string.banking_item_notif_listener_off
                )
            },
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.NOTIFICATION_LISTENER
            )
        )
    }

    /** بند «العرض فوق التطبيقات». */
    private fun overlayItem(context: Context): BankingCheckItem {
        val granted = runCatching {
            Settings.canDrawOverlays(context)
        }.getOrDefault(false)
        return BankingCheckItem(
            id = BankingCheckIds.OVERLAY,
            label = context.getString(R.string.banking_item_overlay_label),
            detail = if (granted) {
                context.getString(R.string.banking_item_overlay_on)
            } else {
                context.getString(R.string.banking_item_overlay_off)
            },
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.OVERLAY
            )
        )
    }

    /**
     * بنود ADB (السلكي واللاسلكي) — مُصنَّفة LOW عمداً: رُصد أن
     * إنستاباي يعمل مع تفعيل خيارات المطوّر وتصحيح USB واللاسلكي،
     * فتصنيفها خطراً يُضلِّل المستخدم في عزل السبب.
     */
    private fun adbItems(context: Context): List<BankingCheckItem> =
        listOf(
            adbItem(
                context = context,
                id = BankingCheckIds.ADB_ENABLED,
                labelRes = R.string.banking_item_adb_usb_label,
                onRes = R.string.banking_item_adb_usb_on,
                offRes = R.string.banking_item_adb_usb_off
            ),
            adbItem(
                context = context,
                id = BankingCheckIds.ADB_WIFI,
                labelRes = R.string.banking_item_adb_wifi_label,
                onRes = R.string.banking_item_adb_wifi_on,
                offRes = R.string.banking_item_adb_wifi_off
            )
        )

    private fun adbItem(
        context: Context,
        id: String,
        labelRes: Int,
        onRes: Int,
        offRes: Int
    ): BankingCheckItem {
        val enabled = runCatching {
            val resolver = context.contentResolver
            if (id == BankingCheckIds.ADB_WIFI) {
                Settings.Global.getInt(resolver, GLOBAL_ADB_WIFI, 0) == 1
            } else {
                Settings.Secure.getString(resolver, ADB_USB_KEY) == "1"
            }
        }.getOrDefault(false)
        return BankingCheckItem(
            id = id,
            label = context.getString(labelRes),
            detail = context.getString(if (enabled) onRes else offRes),
            risk = BankingCompatClassifier.riskFor(id)
        )
    }

    /** بند مديري الجهاز. */
    private fun deviceAdminItem(context: Context): BankingCheckItem {
        val count = runCatching {
            val dpm = context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as? DevicePolicyManager
            // `getDeviceAdmins()` نظاميٌّ غير متاح للتطبيق؛ العموميُّ
            // هو `getActiveAdmins()` ويعدّ المُفعَّلين فعلاً.
            dpm?.activeAdmins?.size ?: 0
        }.getOrDefault(0)
        return BankingCheckItem(
            id = BankingCheckIds.DEVICE_ADMIN,
            label = context.getString(R.string.banking_item_admin_label),
            detail = context.getString(
                R.string.banking_item_admin_detail, count
            ),
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.DEVICE_ADMIN
            )
        )
    }

    /**
     * بند دور الهاتف الافتراضي.
     *
     * **القيد التقني**: `RoleManager.getRoleHolders` و
     * `TelephonyManager.getDefaultDialerPackage` مخفيَّتان (`@SystemApi`)
     * وغير مُتاحتين في حزمة SDK العامّة؛ فالمعرِّف العموميُّ الوحيد
     * لمستقبِل `ACTION_DIAL` هو المستقبِلُ الافتراضي فعلياً، وهو ما
     * يُجيب على السؤال نفسه.
     */
    private fun dialerRoleItem(context: Context): BankingCheckItem {
        val defaultDialer = runCatching {
            val intent = Intent(Intent.ACTION_DIAL)
            @Suppress("DEPRECATION")
            val resolved = context.packageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            resolved?.activityInfo?.packageName
        }.getOrNull()
        val anotherAppHoldsRole =
            defaultDialer != null && defaultDialer != context.packageName
        return BankingCheckItem(
            id = BankingCheckIds.DIALER_ROLE,
            label = context.getString(R.string.banking_item_dialer_label),
            detail = if (anotherAppHoldsRole) {
                context.getString(R.string.banking_item_dialer_other)
            } else {
                context.getString(R.string.banking_item_dialer_not_other)
            },
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.DIALER_ROLE
            )
        )
    }

    /** بند اتصال VPN نشط على الجهاز. */
    private fun vpnItem(context: Context): BankingCheckItem {
        val active = runCatching {
            val manager = context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager ?: return@runCatching false
            val network = manager.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(network)
                ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrDefault(false)
        return BankingCheckItem(
            id = BankingCheckIds.VPN,
            label = context.getString(R.string.banking_item_vpn_label),
            detail = if (active) {
                context.getString(R.string.banking_item_vpn_on)
            } else {
                context.getString(R.string.banking_item_vpn_off)
            },
            risk = BankingCompatClassifier.riskFor(BankingCheckIds.VPN)
        )
    }

    /** بند وجود مُنظِّم جذر (`su`) — عرضُ تشخيصٍ لا قرارُ أمان. */
    private fun suItem(context: Context): BankingCheckItem {
        val path = SU_PATHS.firstOrNull {
            runCatching { File(it).exists() }.getOrDefault(false)
        }
        val fromPath = runCatching {
            val pathEnv = System.getenv("PATH") ?: ""
            pathEnv.split(File.pathSeparator)
                .map { "$it/su" }
                .firstOrNull { File(it).exists() }
        }.getOrNull()
        return BankingCheckItem(
            id = BankingCheckIds.SU_BINARY,
            label = context.getString(R.string.banking_item_su_label),
            detail = when {
                path != null -> context.getString(
                    R.string.banking_item_su_found, path
                )
                fromPath != null -> context.getString(
                    R.string.banking_item_su_found, fromPath
                )
                else -> context.getString(R.string.banking_item_su_none)
            },
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.SU_BINARY
            )
        )
    }

    /** بند وجود حزمة Shizuku. */
    private fun shizukuItem(context: Context): BankingCheckItem {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        return BankingCheckItem(
            id = BankingCheckIds.SHIZUKU,
            label = context.getString(R.string.banking_item_shizuku_label),
            detail = if (installed) {
                context.getString(R.string.banking_item_shizuku_on)
            } else {
                context.getString(R.string.banking_item_shizuku_off)
            },
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.SHIZUKU
            )
        )
    }

    /** بند مصدر تثبيت ناطق — بمسارٍ احتياطيٍّ على API < 30. */
    private fun installSourceItem(
        context: Context
    ): BankingCheckItem {
        val source = runCatching {
            InstallSourceReader.read(
                context.packageManager,
                context.packageName
            )
        }.getOrDefault(InstallSourceReader.InstallSource(null))
        val cls = BankingCompatClassifier
            .classifyInstallSource(source.installingPackageName)
        val detailRes = when (cls) {
            InstallSourceClass.PLAY_STORE ->
                R.string.banking_item_install_play
            InstallSourceClass.SIDE_LOADED,
            InstallSourceClass.UNKNOWN ->
                R.string.banking_item_install_side
        }
        return BankingCheckItem(
            id = BankingCheckIds.INSTALL_SOURCE,
            label = context.getString(
                R.string.banking_item_install_label
            ),
            detail = context.getString(
                detailRes,
                source.installingPackageName ?: "—"
            ),
            risk = BankingCompatClassifier.riskFor(
                BankingCheckIds.INSTALL_SOURCE
            )
        )
    }
}
