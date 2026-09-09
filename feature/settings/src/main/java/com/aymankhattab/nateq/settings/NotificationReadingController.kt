package com.aymankhattab.nateq.settings

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «قراءة الإشعارات»: المفتاح + فتح إعدادات صلاحية النظام
 *  + اختيار التطبيقات. */
internal class NotificationReadingController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var switchNotificationReading: SwitchMaterial
    private lateinit var llNotificationListenerSettings: View

    fun setup(view: View) {
        switchNotificationReading =
            view.findViewById(R.id.switch_notification_reading)
        llNotificationListenerSettings =
            view.findViewById(R.id.ll_notification_listener_settings)

        // المفتاح الرئيسي
        switchNotificationReading.isChecked =
            runCatching { settings.isNotificationReadingEnabled() }
                .getOrDefault(false)
        switchNotificationReading.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setNotificationReadingEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(
                    fragment.requireContext()
                )
            } else {
                AnnouncementSchedulerService.syncIfRunning(
                    fragment.requireContext()
                )
            }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // فتح إعدادات إذن الوصول للإشعارات من النظام
        llNotificationListenerSettings.setOnClickListener {
            val granted = runCatching {
                NateqNotificationListener.isPermissionGranted(
                    fragment.requireContext()
                )
            }.getOrDefault(false)
            if (granted) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.notification_reading_enabled_summary,
                    Toast.LENGTH_SHORT
                ).show()
                fragment.view?.announceCompat(
                    fragment.getString(
                        R.string.notification_reading_enabled_summary
                    )
                )
            } else {
                try {
                    fragment.requireContext().startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                } catch (t: Throwable) {
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.notification_permission_needed,
                        Toast.LENGTH_LONG
                    ).show()
                    fragment.view?.announceCompat(
                        fragment.getString(
                            R.string.notification_permission_needed
                        )
                    )
                }
            }
        }

        // اختيار التطبيقات التي تُقرأ إشعاراتها
        view.findViewById<View>(R.id.ll_notification_apps_settings)
            ?.setOnClickListener { showNotificationAppsDialog() }
    }

    /** حوار اختيار التطبيقات التي تُقرأ إشعاراتها (بعلامة "كل التطبيقات"). */
    private fun showNotificationAppsDialog() {
        val current = runCatching { settings.getNotificationAppsSelection() }
            .getOrDefault(SettingsRepository.DEFAULT_NOTIFICATION_APPS)
        val options = listOf(
            fragment.getString(R.string.notification_apps_all) to
                SettingsRepository.NOTIF_READ_ALL,
            fragment.getString(R.string.app_whatsapp) to "com.whatsapp",
            fragment.getString(R.string.app_whatsapp_business) to
                "com.whatsapp.w4b",
            fragment.getString(R.string.app_telegram) to
                "org.telegram.messenger",
            fragment.getString(R.string.app_telegram_web) to
                "org.telegram.messenger.web",
            fragment.getString(R.string.app_messenger) to "com.facebook.orca",
            fragment.getString(R.string.app_instagram) to
                "com.instagram.android"
        )
        val checked = BooleanArray(options.size) { i ->
            val pkg = options[i].second
            if (pkg == SettingsRepository.NOTIF_READ_ALL) {
                SettingsRepository.NOTIF_READ_ALL in current
            } else {
                pkg in current
            }
        }
        val labels = options.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.notification_apps_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val selected = options.indices
                    .filter { checked[it] }
                    .map { options[it].second }
                val finalSet = when {
                    SettingsRepository.NOTIF_READ_ALL in selected ->
                        setOf(SettingsRepository.NOTIF_READ_ALL)
                    selected.isEmpty() -> current
                    else -> selected.toSet()
                }
                runCatching { settings.setNotificationAppsSelection(finalSet) }
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.notification_apps_saved,
                    Toast.LENGTH_SHORT
                ).show()
                fragment.view?.announceCompat(
                    fragment.getString(R.string.notification_apps_saved)
                )
                onStatusChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
