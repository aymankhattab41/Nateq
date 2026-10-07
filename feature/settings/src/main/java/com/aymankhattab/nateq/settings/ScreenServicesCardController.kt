package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.accessibility.A11yPauseScheduler
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.AppPolicy
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.audio.accessibility.NateqAccessibilityService
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/**
 * ضابط بطاقة خدمات الشاشة وإمكانية الوصول (ع٢٧-13 و ع٢٧-15).
 * يدير:
 * - فحص تفعيل الخدمة في النظام وتوجيه المستخدم لشاشة التهيئة.
 * - حوار الإيقاف المؤقت لمدد محددة (15 دقيقة، ساعة، منتصف الليل، يدوي).
 * - حوار قائمة التطبيقات المسموحة (AppPolicy).
 */
class ScreenServicesCardController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit = {}
) {

    private val stateStore = A11yStateStore.getInstance(context)

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
                    A11yPauseScheduler.scheduleResumeAlarm(context, untilEpoch)
                } else if (!paused) {
                    A11yPauseScheduler.cancelResumeAlarm(context)
                }
                onStatusChanged()
                onSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun resumeService() {
        stateStore?.setPaused(false, 0L, 1)
        A11yPauseScheduler.cancelResumeAlarm(context)
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
            .setMultiChoiceItems(appLabels, checkedItems) { _, which, checked ->
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
