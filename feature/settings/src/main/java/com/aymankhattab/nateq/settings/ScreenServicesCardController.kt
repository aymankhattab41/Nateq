package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.aymankhattab.nateq.core.audio.accessibility.A11yPauseScheduler
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.AppPolicy
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar

/**
 * ضابط بطاقة خدمات الشاشة وإمكانية الوصول (ع٢٧-13، ع٢٧-15، ع٢٩-14).
 * يدير:
 * - فحص تفعيل الخدمة في النظام وتوجيه المستخدم لشاشة التهيئة.
 * - **عند عدم التفعيل: زر واحد «فعّل خدمة إمكانية الوصول»**
 *   يفتح AccessibilityOnboardingActivity
 * - **أندرويد 13+: كشف «الإعدادات المقيّدة» وإظهار خطوات التفعيل**
 * - حوار الإيقاف المؤقت لمدد محددة (15 دقيقة، ساعة، منتصف الليل، يدوي).
 * - حوار قائمة التطبيقات المسموحة (AppPolicy).
 * - مفاتيح جميع ميزات قراءة الشاشة والمحادثات.
 */
class ScreenServicesCardController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit = {}
) {

    private val stateStore = A11yStateStore.getInstance(context)

    private var tvStatus: TextView? = null
    private var tvDescription: TextView? = null
    private var btnSetup: MaterialButton? = null
    private var btnPause: MaterialButton? = null
    private var btnAllowedApps: MaterialButton? = null
    private var switchesContainer: View? = null

    fun isServiceEnabledInSystem(): Boolean {
        return AccessibilityUtils.isNateqAccessibilityServiceEnabled(
            context
        )
    }

    fun isServicePaused(): Boolean {
        return stateStore?.isPaused() ?: settings.isA11yPaused()
    }

    fun getServiceStatusText(): String {
        return when {
            !isServiceEnabledInSystem() -> {
                context.getString(R.string.a11y_status_disabled)
            }
            isServicePaused() -> {
                context.getString(R.string.a11y_status_paused)
            }
            else -> {
                context.getString(R.string.a11y_status_active)
            }
        }
    }

    fun setup(root: View) {
        tvStatus = root.findViewById(R.id.tv_a11y_services_status)
        tvDescription =
            root.findViewById<TextView>(R.id.tv_a11y_services_description)
        btnSetup =
            root.findViewWithTag<MaterialButton>("btn_a11y_setup_service")
        btnPause =
            root.findViewWithTag<MaterialButton>("btn_a11y_pause_duration")
        btnAllowedApps =
            root.findViewWithTag<MaterialButton>("btn_a11y_allowed_apps")
        switchesContainer =
            root.findViewWithTag<View>("ll_a11y_switches_container")

        // ربط المستمعين مرة واحدة؛ الإظهار/الإخفاء يُعاد تطبيقه في
        // applyState() دون إعادة استدعاء setup.
        btnPause?.setOnClickListener {
            showPauseDurationDialog { updateUiState() }
        }
        btnAllowedApps?.setOnClickListener {
            showAppPolicyDialog { updateUiState() }
        }

        bindSwitch(
            root,
            "switch_a11y_read_more",
            FeatureFlags.FEATURE_READ_MORE_EXPAND
        )
        bindSwitch(
            root,
            "switch_a11y_chat_live",
            FeatureFlags.FEATURE_CHAT_LIVE_READING
        )
        bindSwitch(
            root,
            "switch_a11y_chat_summary",
            FeatureFlags.FEATURE_CHAT_SUMMARY
        )
        bindSwitch(
            root,
            "switch_a11y_typing_echo",
            FeatureFlags.FEATURE_TYPING_ECHO
        )
        bindSwitch(
            root,
            "switch_a11y_otp_reading",
            FeatureFlags.FEATURE_OTP_READING
        )
        bindSwitch(
            root,
            "switch_a11y_url_shortening",
            FeatureFlags.FEATURE_URL_SHORTENING
        )
        bindSwitch(
            root,
            "switch_a11y_popup",
            FeatureFlags.FEATURE_POPUP_ANNOUNCEMENT
        )
        bindSwitch(
            root,
            "switch_a11y_progress",
            FeatureFlags.FEATURE_PROGRESS_MONITORING
        )
        bindSwitch(
            root,
            "switch_a11y_bluetooth",
            FeatureFlags.FEATURE_BLUETOOTH_ANNOUNCEMENT
        )

        val switchCall = root.findViewWithTag<SwitchMaterial>(
            "switch_a11y_smart_pause_call"
        )
        switchCall?.isChecked = settings.isA11ySmartPauseCallEnabled()
        switchCall?.setOnCheckedChangeListener { _, checked ->
            settings.setA11ySmartPauseCallEnabled(checked)
            onStatusChanged()
        }

        val switchGame = root.findViewWithTag<SwitchMaterial>(
            "switch_a11y_smart_pause_game"
        )
        switchGame?.isChecked = settings.isA11ySmartPauseGameEnabled()
        switchGame?.setOnCheckedChangeListener { _, checked ->
            settings.setA11ySmartPauseGameEnabled(checked)
            onStatusChanged()
        }

        updateUiState()
    }

    /**
     * أندرويد 13+ (API 33): تحقق إن كانت الخدمة مقيّدة (Restricted Settings).
     * إذا كانت مقيّدة، لا يمكن تفعيلها مباشرة من إعدادات إمكانية الوصول
     * بل يجب أن يضغط المستخدم «السماح بالإعدادات المقيّدة» في معلومات التطبيق.
     */
    private fun isRestrictedByAndroid13(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // على أندرويد 13+، التحقق الحقيقي يتم عبر محاولة فتح إعدادات
        // إمكانية الوصول
        // إن لم تظهر الخدمة قابلة للتفعيل، نعتبرها مقيّدة.
        return true // افتراضياً على Tiramisu+ نعرض تحذير الإعدادات المقيّدة
    }

    /**
     * فتح إعدادات التطبيق للسماح بالإعدادات المقيّدة (أندرويد 13+).
     */
    private fun openRestrictedSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .apply {
                data = android.net.Uri.fromParts(
                    "package", context.packageName, null
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private fun bindSwitch(root: View, tag: String, feature: String) {
        val sw = root.findViewWithTag<SwitchMaterial>(tag) ?: return
        sw.isChecked = settings.isAccessibilityFeatureEnabled(feature)
        sw.setOnCheckedChangeListener { _, checked ->
            settings.setAccessibilityFeatureEnabled(feature, checked)
            onStatusChanged()
        }
    }

    fun updateUiState() {
        tvStatus?.text = getServiceStatusText()
        applyState()
    }

    /**
     * يطبّق منطق الإظهار/الإخفاء وزر التفعيل حسب حالة الخدمة.
     * منفصل عن [setup] عمداً: كان updateUiState() ينادي setup() فينادي
     * setup() بدوره updateUiState() — تكرار لا نهائي يُسقط الشاشة
     * بـ StackOverflowError.
     */
    private fun applyState() {
        val enabled = isServiceEnabledInSystem()
        if (!enabled) {
            // حالة غير مفعّلة: أظهر زر الإعداد، أخفِ باقي العناصر
            btnSetup?.visibility = View.VISIBLE
            if (isRestrictedByAndroid13()) {
                tvDescription?.text =
                    context.getString(R.string.a11y_restricted_settings_desc)
                btnSetup?.text =
                    context.getString(R.string.a11y_open_restricted_settings)
                btnSetup?.setOnClickListener { openRestrictedSettings() }
            } else {
                tvDescription?.text =
                    context.getString(R.string.a11y_onboarding_explanation)
                btnSetup?.text =
                    context.getString(R.string.a11y_open_onboarding)
                btnSetup?.setOnClickListener { openOnboarding() }
            }
            btnPause?.visibility = View.GONE
            btnAllowedApps?.visibility = View.GONE
            switchesContainer?.visibility = View.GONE
        } else {
            // حالة مفعّلة: أظهر جميع التحكمات
            btnSetup?.visibility = View.GONE
            btnPause?.visibility = View.VISIBLE
            btnAllowedApps?.visibility = View.VISIBLE
            switchesContainer?.visibility = View.VISIBLE
        }
    }

    fun openOnboarding() {
        val intent = Intent(
            context,
            AccessibilityOnboardingActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * عرض حوار اختيار مدة الإيقاف المؤقت (ع٢٧-15).
     */
    fun showPauseDurationDialog(onSelected: () -> Unit = {}) {
        val options = arrayOf(
            context.getString(R.string.a11y_pause_15m),
            context.getString(R.string.a11y_pause_1h),
            context.getString(R.string.a11y_pause_3h),
            context.getString(R.string.a11y_pause_midnight),
            context.getString(R.string.a11y_pause_manual)
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_pause_duration_title)
            .setItems(options) { _, which ->
                val now = System.currentTimeMillis()
                val (paused, untilEpoch) = when (which) {
                    0 -> true to (now + 15 * 60 * 1000L)
                    1 -> true to (now + 60 * 60 * 1000L)
                    2 -> true to (now + 3 * 60 * 60 * 1000L)
                    3 -> {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                        }
                        true to cal.timeInMillis
                    }
                    4 -> true to 0L // يدوي
                    else -> false to 0L
                }

                stateStore?.setPaused(paused, untilEpoch, 1)
                if (paused && untilEpoch > now) {
                    A11yPauseScheduler.scheduleResumeAlarm(
                        context, untilEpoch
                    )
                } else if (!paused) {
                    A11yPauseScheduler.cancelResumeAlarm(context)
                }
                updateUiState()
                onStatusChanged()
                onSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun resumeService() {
        stateStore?.setPaused(false, 0L, 1)
        A11yPauseScheduler.cancelResumeAlarm(context)
        updateUiState()
        onStatusChanged()
    }

    /**
     * عرض حوار اختيار سياسة التطبيقات المسموحة (ع٢٧-9).
     */
    fun showAppPolicyDialog(onUpdated: () -> Unit = {}) {
        val currentAllowed = (settings.getA11yAllowedPackages()
            ?: AppPolicy.DEFAULT_WHITELIST).toMutableSet()
        val defaultApps = AppPolicy.DEFAULT_WHITELIST.toList()

        val appLabels = defaultApps.map { pkg ->
            when (pkg) {
                "com.whatsapp" -> context.getString(R.string.app_whatsapp)
                "com.whatsapp.w4b" ->
                    context.getString(R.string.app_whatsapp_business)
                "org.telegram.messenger" ->
                    context.getString(R.string.app_telegram)
                else -> pkg
            }
        }.toTypedArray()

        val checkedItems = defaultApps.map { pkg ->
            currentAllowed.contains(pkg)
        }.toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.a11y_tile_label))
            .setMultiChoiceItems(
                appLabels, checkedItems
            ) { _, which, checked ->
                val pkg = defaultApps[which]
                if (checked) {
                    currentAllowed.add(pkg)
                } else {
                    currentAllowed.remove(pkg)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setA11yAllowedPackages(currentAllowed)
                onUpdated()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
