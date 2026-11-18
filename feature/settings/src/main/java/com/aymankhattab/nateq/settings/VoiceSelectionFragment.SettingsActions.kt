package com.aymankhattab.nateq.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.settings.phone.LordPhoneActivity
import com.aymankhattab.nateq.settings.phone.LordSpeedDialActivity
import com.aymankhattab.nateq.settings.phone.PhoneSettingsActivity
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/** تبديل لغة التطبيق (أسفل الشاشة) وإعادة إنشاء النشاط لتطبيقها. */
internal fun VoiceSelectionFragment.setupLanguageToggle() {
    val current = runCatching { settings.getAppLanguage() }
        .getOrNull()
        ?: Locale.getDefault().language

    val isArabic = LanguageCode.isArabic(current)
    // نص الزر يوضح اللغة الحالية ويقترح التبديل إلى الأخرى
    btnToggleLanguage.text = if (isArabic) {
        getString(R.string.language_current_label) + " " +
            getString(R.string.language_arabic) + " - " +
            getString(R.string.language_change_to_en)
    } else {
        getString(R.string.language_current_label) + " " +
            getString(R.string.language_english) + " - " +
            getString(R.string.language_change_to_ar)
    }

    btnToggleLanguage.setOnClickListener {
        val newLang =
            if (isArabic) LanguageCode.EN.tag else LanguageCode.AR.tag
        runCatching { settings.setAppLanguage(newLang) }
        // تطبيق اللغة عبر AppCompatDelegate لضمان اتساق الموارد فوراً
        runCatching {
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(newLang)
            )
        }
        // إعادة إنشاء النشاط لتحديث الواجهة والموارد بالكامل
        requireActivity().recreate()
    }
}

/** حفظ إعدادات الصوت/الفئة الحالية صراحةً؛ يُرجع false عند فشل الحفظ. */
internal fun VoiceSelectionFragment.performExplicitVoiceSave(): Boolean =
    runCatching { categoryVoicePanel.saveCurrent() }
        .getOrDefault(false)

/** إعداد زرّي الحفظ واستعادة الافتراضيات. */
internal fun VoiceSelectionFragment.setupSaveAndResetButtons() {
    val btnSave = view?.findViewById<android.view.View>(R.id.btn_save_settings)
    val btnReset =
        view?.findViewById<android.view.View>(
            R.id.btn_reset_settings
        )

    btnSave?.setOnClickListener {
        val saved = performExplicitVoiceSave()
        if (saved) {
            val msg = getString(R.string.saved_successfully)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            view?.findViewById<android.view.View>(R.id.btn_save_settings)
                ?.announceCompat(msg)
        } else {
            val msg = getString(R.string.settings_save_failed)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            view?.findViewById<android.view.View>(R.id.btn_save_settings)
                ?.announceCompat(msg)
        }
    }

    btnReset?.setOnClickListener {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setPositiveButton(R.string.reset_done) { _, _ ->
                runCatching { settings.resetAllToDefault() }
                // مسح قاموس النطق أيضاً عند الاستعادة الافتراضية
                runCatching { pronunciationDict.clear() }
                val msg = getString(R.string.reset_done)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                view?.findViewById<android.view.View>(R.id.btn_reset_settings)
                    ?.announceCompat(msg)
                // إشعار ViewModel بتغيّر الإعدادات لإعادة بناء الواجهة
                vm.notifySettingsChanged()
            }
            .setNegativeButton(R.string.reset_cancel, null)
            .create().also(::trackDialog).show()
    }
}

/** إعداد أزرار النسخ الاحتياطي والاستعادة (واجهة المستخدم). */
internal fun VoiceSelectionFragment.setupBackupRestoreButtons() {
    view?.findViewById<android.view.View>(R.id.btn_backup_settings)
        ?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_warning_title)
                .setMessage(R.string.backup_export_warning_message)
                .setPositiveButton(R.string.backup_settings) { _, _ ->
                    runCatching {
                        createBackupLauncher.launch("nateq_backup.json")
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }
    view?.findViewById<android.view.View>(R.id.btn_restore_settings)
        ?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_settings) { _, _ ->
                    runCatching {
                        openRestoreLauncher.launch(
                            arrayOf(
                                "application/json",
                                "application/octet-stream"
                            )
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }
}

/** اختصارات أدوات التطبيق (الهاتف، إعدادات الهاتف،
 * لوحة المفاتيح، الطلب السريع). */
internal fun VoiceSelectionFragment.setupToolsAppShortcuts() {
    view?.findViewById<android.view.View>(R.id.btn_open_phone_app)
        ?.setOnClickListener {
            startActivity(
                Intent(
                    requireContext(),
                    LordPhoneActivity::class.java
                )
            )
        }
    view?.findViewById<android.view.View>(R.id.btn_phone_settings)
        ?.setOnClickListener {
            startActivity(
                Intent(requireContext(), PhoneSettingsActivity::class.java)
            )
        }
    view?.findViewById<android.view.View>(R.id.btn_keyboard_settings)
        ?.setOnClickListener {
            startActivity(
                Intent(requireContext(), KeyboardSettingsActivity::class.java)
            )
        }
    view?.findViewById<android.view.View>(R.id.btn_open_speed_dial)
        ?.setOnClickListener {
            startActivity(
                Intent(requireContext(), LordSpeedDialActivity::class.java)
            )
        }
    val swSpeedDial = view?.findViewById<SwitchMaterial>(
        R.id.switch_tools_speed_dial_drawer
    )
    swSpeedDial?.isChecked =
        runCatching { settings.isSpeedDialLauncherIconEnabled() }
            .getOrDefault(false)
    swSpeedDial?.setOnCheckedChangeListener { _, isChecked ->
        settings.setSpeedDialLauncherIconEnabled(isChecked)
        setSpeedDialLauncherAliasEnabled(isChecked)
        Toast.makeText(
            requireContext(),
            if (isChecked) R.string.speed_dial_shortcut_created
            else R.string.speed_dial_shortcut_removed,
            Toast.LENGTH_SHORT
        ).show()
    }
}

/** تفعيل/تعطيل اختصار الطلب السريع في المشغّل عبر activity-alias. */
internal fun VoiceSelectionFragment.setSpeedDialLauncherAliasEnabled(
    enabled: Boolean
) {
    val ctx = context ?: return
    val alias = ComponentName(
        ctx,
        "com.aymankhattab.nateq.settings.phone.LordSpeedDialLauncherAlias"
    )
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    ctx.packageManager.setComponentEnabledSetting(
        alias,
        state,
        PackageManager.DONT_KILL_APP
    )
}

/** مزامنة مفتاح اختصار الطلب السريع مع الإعداد المحفوظ. */
internal fun VoiceSelectionFragment.refreshToolsShortcuts() {
    val sw = view?.findViewById<SwitchMaterial>(
        R.id.switch_tools_speed_dial_drawer
    ) ?: return
    val enabled = runCatching {
        settings.isSpeedDialLauncherIconEnabled()
    }.getOrDefault(false)
    if (sw.isChecked != enabled) {
        sw.isChecked = enabled
    }
}

/** إعادة تحميل واجهة كل الإعدادات بعد تغيير خارجي (استعادة/إعادة ضبط). */
internal fun VoiceSelectionFragment.refreshAllSettingsUi() {
    val v = requireView()
    switchAllAnnouncements.isChecked =
        runCatching { settings.isAllAnnouncementsEnabled() }
            .getOrDefault(true)
    switchLockScreenPrivacy.isChecked =
        runCatching { settings.isLockScreenPrivacyEnabled() }
            .getOrDefault(true)
    v.findViewById<SwitchMaterial>(R.id.switch_emoji_reading)?.isChecked =
        runCatching { settings.isEmojiPronunciationEnabled() }
            .getOrDefault(true)
    timeSection.setup(v)
    batterySection.setup(v)
    notificationSection.setup(v)
    callerSection.setup(v)
    smsSection.setup(v)
    generalSection.setup(v)
    numberSection.setup(v)
    deviceHealthSection.setup(v)
    textReadingSection.setup(v)
    textProcessingSection.bind(v)
    instantSilenceSection.setup(v)
    embeddedDownloaderSection.setup(v)
    categoryVoicePanel.reloadCurrent()
    refreshDictAdapter()
    engineSection.setupAutoConvertUI(v)
    accordion.updateSectionStatuses()
    refreshToolsShortcuts()
}
