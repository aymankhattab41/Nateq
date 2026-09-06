package com.aymankhattab.nateq.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.util.AnnouncementSpeaker
import com.aymankhattab.nateq.util.announceCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import java.util.Locale

/**
 * شاشة الإعدادات الرئيسية - تتضمن:
 * 1. اختيار المحرك ثم اللغة ثم الصوت (هرمية)
 * 2. إعدادات الفئات (صوت، سرعة، نبرة، مستوى صوت)
 * 3. إعدادات إعلان الوقت
 * 4. قاموس النطق الشخصي
 */
@AndroidEntryPoint
class VoiceSelectionFragment : Fragment(R.layout.fragment_voice_selection) {

    companion object {
        // (حدود النسخ الاحتياطي انتقلت إلى SettingsViewModel.MAX_BACKUP_* — لم يعد
        //  الفصيل مسؤولاً عن المنطق بل عن تشغيله فقط في مواضع SAF).
    }

    // طبقة الحالة المحقونة عبر Hilt (تحوي مصدرَي الإعدادات والقاموس).
    private val vm: SettingsViewModel by viewModels()

    private val settings: SettingsRepository
        get() = vm.settings

    private val pronunciationDict: PronunciationDictionary
        get() = vm.pronunciationDict

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvPronunciationDict: RecyclerView

    private lateinit var spinnerEngine: Spinner
    private val engines = mutableListOf<EngineInfo>()
    private lateinit var engineSection: EngineSectionController

    // أعضاء المحرك وحوار التحويل (setupEngineSpinner/loadEngineCatalog/
    // setupAutoConvertUI/showConvertDialog...) انتقلت إلى EngineSectionController.

    // Time announcement settings
    private lateinit var switchTimeAnnouncement: SwitchMaterial
    private lateinit var spinnerTimeInterval: Spinner
    private lateinit var llQuietSchedule: android.widget.LinearLayout
    private lateinit var spinnerTimeFormat: Spinner
    private lateinit var switchTime24h: SwitchMaterial
    private lateinit var switchHijriDate: SwitchMaterial
    private lateinit var switchClockWidget: SwitchMaterial

    // General settings
    private lateinit var seekDefaultSpeechRate: SeekBar
    private lateinit var tvDefaultSpeechRateValue: TextView
    private lateinit var seekDefaultPitch: SeekBar
    private lateinit var tvDefaultPitchValue: TextView
    private lateinit var seekDefaultVolume: SeekBar
    private lateinit var tvDefaultVolumeValue: TextView

    // Battery announcement settings
    private lateinit var switchBatteryAnnouncement: SwitchMaterial
    private lateinit var llBatteryLevelsHeader: android.widget.LinearLayout
    private lateinit var tvBatteryLevelsArrow: TextView
    private lateinit var llBatteryLevels: android.widget.LinearLayout
    private lateinit var spinnerBatteryVoice: Spinner
    private lateinit var seekBatteryRate: SeekBar
    private lateinit var tvBatteryRateValue: TextView
    private lateinit var seekBatteryVolume: SeekBar
    private lateinit var tvBatteryVolumeValue: TextView
    private lateinit var switchChargingComplete: SwitchMaterial
    private lateinit var switchChargingDisconnect: SwitchMaterial
    private lateinit var switchPowerSaver: SwitchMaterial
    private lateinit var llPowerSaverThreshold: android.widget.LinearLayout
    private lateinit var tvPowerSaverThresholdValue: TextView
    private lateinit var seekPowerSaverThreshold: SeekBar

    // Notification reading settings
    private lateinit var switchNotificationReading: SwitchMaterial
    private lateinit var llNotificationListenerSettings: android.widget.LinearLayout

    // Caller announcement settings
    private lateinit var switchCallerAnnouncement: SwitchMaterial
    private lateinit var spinnerCallerRepeat: Spinner
    private lateinit var seekCallerRate: SeekBar
    private lateinit var tvCallerRateValue: TextView
    private lateinit var seekCallerVolume: SeekBar
    private lateinit var tvCallerVolumeValue: TextView

    // SMS reading settings
    private lateinit var spinnerSmsMode: Spinner
    private lateinit var spinnerSmsVoice: Spinner
    private lateinit var seekSmsRate: SeekBar
    private lateinit var tvSmsRateValue: TextView
    private lateinit var seekSmsVolume: SeekBar
    private lateinit var tvSmsVolumeValue: TextView
    private lateinit var etCallerTemplate: com.google.android.material.textfield.TextInputEditText
    private lateinit var etSmsTemplate: com.google.android.material.textfield.TextInputEditText
    private lateinit var spinnerCallerVoiceAr: Spinner
    private lateinit var spinnerCallerVoiceEn: Spinner

    // Language switcher
    private lateinit var btnToggleLanguage: com.google.android.material.button.MaterialButton

    // Number reading
    private lateinit var spinnerNumberReadingMode: Spinner
    private lateinit var btnSpeechLanguage: com.google.android.material.button.MaterialButton

    // Dictionary
    private lateinit var llDictHeader: android.widget.LinearLayout
    private lateinit var tvDictArrow: TextView
    private lateinit var llDictContent: android.widget.LinearLayout

    // المفتاح الرئيسي لكل الإعلانات
    private lateinit var switchAllAnnouncements: SwitchMaterial

    private lateinit var switchLockScreenPrivacy: SwitchMaterial
    private lateinit var btnSetDefaultEngine: com.google.android.material.button.MaterialButton

    // معاينة نطق رقم
    private lateinit var etNumberPreview: TextView
    private lateinit var btnPreviewNumber: com.google.android.material.button.MaterialButton
    private lateinit var btnStopNumberPreview: com.google.android.material.button.MaterialButton

    // أسماء المتصلين المخصصة
    private lateinit var btnCallerNames: com.google.android.material.button.MaterialButton

    // استيراد/تصدير القاموس عبر شاشة الوثائق (SAF)
    private lateinit var btnImportDict: com.google.android.material.button.MaterialButton
    private lateinit var btnExportDict: com.google.android.material.button.MaterialButton

    private val openDictLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                text != null && pronunciationDict.importFromJson(text)
            }.getOrDefault(false)
            Toast.makeText(
                requireContext(),
                if (ok) R.string.dict_imported_ok else R.string.dict_import_failed,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(if (ok) R.string.dict_imported_ok else R.string.dict_import_failed))
            if (ok) refreshDictAdapter()
        }
    }

    private val createDictLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val json = pronunciationDict.exportToJson()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            Toast.makeText(
                requireContext(),
                if (ok) R.string.dict_exported_ok else R.string.dict_export_failed,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(if (ok) R.string.dict_exported_ok else R.string.dict_export_failed))
        }
    }

    // ===== النسخ الاحتياطي / الاستعادة الكاملان (إعدادات + قاموس + أسماء متصلين) =====
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val json = vm.buildBackupJson()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            Toast.makeText(
                requireContext(),
                if (ok) R.string.backup_saved else R.string.backup_failed,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(if (ok) R.string.backup_saved else R.string.backup_failed))
        }
    }

    private val openRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            if (text != null) {
                if (vm.applyBackupJson(text)) {
                    refreshAllSettingsUi()
                    AnnouncementSchedulerService.requestStart(requireContext())
                    Toast.makeText(requireContext(), R.string.restore_done, Toast.LENGTH_LONG).show()
                    view?.announceCompat(getString(R.string.restore_done))
                } else {
                    Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_LONG).show()
                    view?.announceCompat(getString(R.string.restore_failed))
                }
            }
        }
    }

    private var announcementSpeaker: AnnouncementSpeaker? = null

    // كتالوج صريح للأصوات المدعومة في ناطق (يتطابق مع onGetVoices)
    // مبسّطة إلى لغتين فقط: "العربية" و"الإنجليزية"
    private lateinit var nateqVoices: List<NateqVoice>

    // طلب إذنَي القراءة عند تفعيل إعلان المتصل (READ_PHONE_STATE لاستقبال
    // بث PHONE_STATE المحمي، وREAD_CALL_LOG للوصول إلى الرقم على أندرويد 12+
    // والاسم من سجل المكالمات، وREAD_CONTACTS للبحث عن الاسم في دفتر الاتصالات).
    private val callerPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val phoneGranted = granted[Manifest.permission.READ_PHONE_STATE] == true
        val callLogGranted = granted[Manifest.permission.READ_CALL_LOG] == true
        val contactsGranted = granted[Manifest.permission.READ_CONTACTS] == true
        if (phoneGranted || callLogGranted || contactsGranted) {
            runCatching { settings.setCallerAnnouncementEnabled(true) }
            // حارس يمنع المستمع من إعادة طلب الأذونات عند تعيين قيمة المفتاح هنا
            callerSwitchGuard = true
            switchCallerAnnouncement.isChecked = true
            callerSwitchGuard = false
            AnnouncementSchedulerService.requestStart(requireContext())
            accordion.updateSectionStatuses()
            val msg = when {
                callLogGranted -> R.string.caller_permission_granted_both
                phoneGranted -> R.string.caller_permission_granted_phone_only
                else -> R.string.caller_permission_granted_contacts_only
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            view?.announceCompat(getString(msg))
        } else {
            switchCallerAnnouncement.isChecked = false
            runCatching { settings.setCallerAnnouncementEnabled(false) }
            accordion.updateSectionStatuses()
            Toast.makeText(requireContext(), R.string.caller_permission_needed, Toast.LENGTH_LONG).show()
            view?.announceCompat(getString(R.string.caller_permission_needed))
        }
    }

    /** يمنع مناداة المستمع من رد الطلب (تفادي إعادة طلب الأذونات دورياً) */
    private var callerSwitchGuard = false

    // طلب إذن قراءة الرسائل الواردة لحظة تفعيل قراءة الرسائل فقط (لا عند
    // أول تشغيل). الوضع المختار (full/source) لا يُحفظ إلا بعد المنح الفعلي
    // حتى لا يبقى مفعّلاً زوراً عند رفض المستخدم الإذن (بند [9]).
    private var pendingSmsMode: String? = null

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingSmsMode
        pendingSmsMode = null
        if (granted) {
            // مُنح الإذن: الآن فقط نُثبّت الوضع المعلّق المطلوب (full/source).
            if (pending != null) {
                runCatching { settings.setSmsReadingMode(pending) }
            }
            view?.announceCompat(getString(R.string.permission_sms_granted))
            AnnouncementSchedulerService.requestStart(requireContext())
        } else {
            // رُفض: نعيد المفتاح إلى "off" (لم يكن قد حُفظ) ونعلن السبب.
            if (pending != null) {
                runCatching { settings.setSmsReadingMode("off") }
                if (::spinnerSmsMode.isInitialized) spinnerSmsMode.setSelection(2)
            }
            view?.announceCompat(getString(R.string.sms_permission_needed))
        }
    }

    // ===== ضابط الأكورديون والتنقّل: بطاقات الأقسام وبناء أسطر الحالة =====
    private lateinit var accordion: SettingsAccordionController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // لا إنشاء مباشر للإعدادات/القاموس: كلاهما محقون عبر SettingsViewModel.

        // تهيئة الأصوات هنا بعد الانضمام للسياق (لا يجوز في مُنشئ/خاصية تستدعي getString())
        nateqVoices = listOf(
            NateqVoice("ar-EG", "ar", getString(R.string.voice_name_arabic), Locale.forLanguageTag("ar")),
            NateqVoice("en-US", "en", getString(R.string.voice_name_english), Locale.forLanguageTag("en"))
        )

        // صندوق المحركات داخل قسم اللغة الأولى/الثانية (اختيار محرك TTS للنطق)
        spinnerEngine = view.findViewById(R.id.spinner_engine)
        accordion = SettingsAccordionController(
            this,
            settings,
            nateqVoices,
            engines,
            spinnerEngine
        ).apply {
            setup(view, viewLifecycleOwner)
        }
        engineSection = EngineSectionController(this, settings, engines).apply {
            onStatusChanged = { accordion.updateSectionStatuses() }
        }
        engineSection.setupEngineSpinner(spinnerEngine)

        // Categories RecyclerView
        rvCategories = view.findViewById(R.id.rv_categories)
        rvCategories.layoutManager = LinearLayoutManager(requireContext())
        rvCategories.adapter = CategoryVoiceAdapter()
        rvCategories.isNestedScrollingEnabled = false

        // Pronunciation dictionary RecyclerView
        rvPronunciationDict = view.findViewById(R.id.rv_pronunciation_dict)
        rvPronunciationDict.layoutManager = LinearLayoutManager(requireContext())
        rvPronunciationDict.adapter = PronunciationDictAdapter()
        // التمرير الداخلي مفعّل ليتدحرج القاموس المحدود الارتفاع داخل الصفحة
        rvPronunciationDict.isNestedScrollingEnabled = true

        // Add dictionary entry button
        val btnAddDictEntry = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_dict_entry)
        btnAddDictEntry.setOnClickListener { showDictEditDialog() }

        btnImportDict = view.findViewById(R.id.btn_import_dict)
        btnImportDict.setOnClickListener {
            runCatching { openDictLauncher.launch(arrayOf("application/json")) }
        }
        btnExportDict = view.findViewById(R.id.btn_export_dict)
        btnExportDict.setOnClickListener {
            runCatching { createDictLauncher.launch("nateq_dictionary.json") }
        }

        // المفتاح الرئيسي لكل الإعلانات
        switchAllAnnouncements = view.findViewById(R.id.switch_all_announcements)
        switchAllAnnouncements.isChecked =
            runCatching { settings.isAllAnnouncementsEnabled() }.getOrDefault(true)
        switchAllAnnouncements.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setAllAnnouncementsEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(requireContext())
                accordion.warnIfNotificationsHidden()
            } else {
                runCatching {
                    requireContext().stopService(
                        Intent(requireContext(), AnnouncementSchedulerService::class.java)
                    )
                }
            }
            accordion.updateSectionStatuses()
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // حماية خصوصية قفل الشاشة: حجب تفاصيل الرسائل/الإشعارات/المتصل عند القفل
        switchLockScreenPrivacy = view.findViewById(R.id.switch_lock_screen_privacy)
        switchLockScreenPrivacy.isChecked =
            runCatching { settings.isLockScreenPrivacyEnabled() }.getOrDefault(true)
        switchLockScreenPrivacy.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setLockScreenPrivacyEnabled(checked) }
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // زر جعل Lord المحرك الافتراضي (يفتح شاشة TTS النظامية لاختياره يدوياً)
        btnSetDefaultEngine = view.findViewById(R.id.btn_set_default_engine)
        btnSetDefaultEngine.setOnClickListener {
            runCatching {
                startActivity(
                    // الثابت الرسمي لنافذة إعدادات TTS غير متاح في كل مستويات SDK،
                    // لذا نستخدم الإجراء النصي الثابت نفسه.
                    Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Toast.makeText(requireContext(), R.string.set_default_engine_hint, Toast.LENGTH_LONG).show()
            view?.announceCompat(getString(R.string.set_default_engine_hint))
        }

        // معاينة نطق رقم
        etNumberPreview = view.findViewById(R.id.et_number_preview)
        btnPreviewNumber = view.findViewById(R.id.btn_preview_number)
        btnStopNumberPreview = view.findViewById(R.id.btn_stop_number_preview)
        btnPreviewNumber.setOnClickListener { previewNumber() }
        btnStopNumberPreview.setOnClickListener {
            announcementSpeaker?.stop()
        }

        // أسماء المتصلين المخصصة
        btnCallerNames = view.findViewById(R.id.btn_caller_names)
        btnCallerNames.setOnClickListener { showCallerNamesDialog() }

        // Dictionary collapsible header
        llDictHeader = view.findViewById(R.id.ll_dict_header)
        tvDictArrow = view.findViewById(R.id.tv_dict_arrow)
        llDictContent = view.findViewById(R.id.ll_dict_content)
        llDictHeader.tag = getString(R.string.section_pronunciation_dict)
        llDictHeader.setOnClickListener { toggleCollapsible(llDictContent, tvDictArrow, llDictHeader) }

        // Time announcement settings
        switchTimeAnnouncement = view.findViewById(R.id.switch_time_announcement)
        spinnerTimeInterval = view.findViewById(R.id.spinner_time_interval)
        llQuietSchedule = view.findViewById(R.id.ll_quiet_schedule)
        spinnerTimeFormat = view.findViewById(R.id.spinner_time_format)
        switchTime24h = view.findViewById(R.id.switch_time_display_24h)
        switchHijriDate = view.findViewById(R.id.switch_hijri_date)
        switchClockWidget = view.findViewById(R.id.switch_clock_widget)

        // Battery announcement settings
        switchBatteryAnnouncement = view.findViewById(R.id.switch_battery_announcement)
        llBatteryLevelsHeader = view.findViewById(R.id.ll_battery_levels_header)
        tvBatteryLevelsArrow = view.findViewById(R.id.tv_battery_levels_arrow)
        llBatteryLevels = view.findViewById(R.id.ll_battery_levels)
        spinnerBatteryVoice = view.findViewById(R.id.spinner_battery_voice)
        seekBatteryRate = view.findViewById(R.id.seek_battery_rate)
        tvBatteryRateValue = view.findViewById(R.id.tv_battery_rate_value)
        seekBatteryVolume = view.findViewById(R.id.seek_battery_volume)
        tvBatteryVolumeValue = view.findViewById(R.id.tv_battery_volume_value)
        switchChargingComplete = view.findViewById(R.id.switch_charging_complete_announcement)
        switchChargingDisconnect = view.findViewById(R.id.switch_charging_disconnect_announcement)
        switchPowerSaver = view.findViewById(R.id.switch_power_saver_mode)
        llPowerSaverThreshold = view.findViewById(R.id.ll_power_saver_threshold)
        tvPowerSaverThresholdValue = view.findViewById(R.id.tv_power_saver_threshold_value)
        seekPowerSaverThreshold = view.findViewById(R.id.seek_power_saver_threshold)

        // Notification reading settings
        switchNotificationReading = view.findViewById(R.id.switch_notification_reading)
        llNotificationListenerSettings = view.findViewById(R.id.ll_notification_listener_settings)

        // Caller announcement settings
        switchCallerAnnouncement = view.findViewById(R.id.switch_caller_announcement)
        spinnerCallerRepeat = view.findViewById(R.id.spinner_caller_repeat)
        seekCallerRate = view.findViewById(R.id.seek_caller_rate)
        tvCallerRateValue = view.findViewById(R.id.tv_caller_rate_value)
        seekCallerVolume = view.findViewById(R.id.seek_caller_volume)
        tvCallerVolumeValue = view.findViewById(R.id.tv_caller_volume_value)

        // SMS reading settings
        spinnerSmsMode = view.findViewById(R.id.spinner_sms_reading_mode)
        spinnerSmsVoice = view.findViewById(R.id.spinner_sms_reading_voice)
        seekSmsRate = view.findViewById(R.id.seek_sms_reading_rate)
        tvSmsRateValue = view.findViewById(R.id.tv_sms_reading_rate_value)
        seekSmsVolume = view.findViewById(R.id.seek_sms_reading_volume)
        etCallerTemplate = view.findViewById(R.id.et_caller_template)
        etSmsTemplate = view.findViewById(R.id.et_sms_template)
        spinnerCallerVoiceAr = view.findViewById(R.id.spinner_caller_voice_ar)
        spinnerCallerVoiceEn = view.findViewById(R.id.spinner_caller_voice_en)
        tvSmsVolumeValue = view.findViewById(R.id.tv_sms_reading_volume_value)

        btnToggleLanguage = view.findViewById(R.id.btn_toggle_language)
        spinnerNumberReadingMode = view.findViewById(R.id.spinner_number_reading_mode)
        btnSpeechLanguage = view.findViewById(R.id.btn_speech_language)

        // General settings
        seekDefaultSpeechRate = view.findViewById(R.id.seek_default_speech_rate)
        tvDefaultSpeechRateValue = view.findViewById(R.id.tv_default_speech_rate_value)
        seekDefaultPitch = view.findViewById(R.id.seek_default_pitch)
        tvDefaultPitchValue = view.findViewById(R.id.tv_default_pitch_value)
        seekDefaultVolume = view.findViewById(R.id.seek_default_volume)
        tvDefaultVolumeValue = view.findViewById(R.id.tv_default_volume_value)

        setupTimeAnnouncementSettings()
        setupBatteryAnnouncementSettings()
        setupNotificationReadingSettings()
        setupCallerAnnouncementSettings()
        setupSmsReadingSettings()
        setupGeneralSettings()
        setupLanguageToggle()
        setupNumberReadingSettings()
        engineSection.setupAutoConvertUI(view)
        setupSaveAndResetButtons()
        setupBackupRestoreButtons()
        accordion.updateSectionStatuses()
    }

    override fun onDestroyView() {
        // إغلاق المتحدث المستقل الخاص بالمعاينة (إن أُنشئ) حتى لا يبقى محرك
        // TTS مفتوحاً بعد مغادرة الشاشة. المثيل هنا خاص بالشاشة وليس المشترك
        // (getInstance) الذي تُدار حياته في مستقبلات الإعلانات التلقائية.
        announcementSpeaker?.stop()
        announcementSpeaker = null
        super.onDestroyView()
    }

    // ===== اختيار المحرك وحوار التحويل التلقائي: انتقلا إلى EngineSectionController =====
    /** نسخة حصرية للفصيل من محلّل الـ Spinner الأساسي (المنفَّذ في EngineSectionController). */
    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        simpleAdapter(requireContext(), items)

    // ===== قاموس النطق =====
    private fun showDictEditDialog(existing: Pair<String, String>? = null) {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_dict_entry, null)
        val etWord = dialogView.findViewById<EditText>(R.id.et_dict_word)
        val etPhonetic = dialogView.findViewById<EditText>(R.id.et_dict_phonetic)
        if (existing != null) {
            etWord.setText(existing.first)
            etPhonetic.setText(existing.second)
        }
        builder.setView(dialogView)
            .setTitle(if (existing == null) getString(R.string.dict_add_title) else getString(R.string.dict_edit_entry))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val word = etWord.text.toString().trim()
                val phonetic = etPhonetic.text.toString().trim()
                if (word.isNotEmpty() && phonetic.isNotEmpty()) {
                    if (existing != null && existing.first != word) {
                        runCatching { pronunciationDict.removeEntry(existing.first) }
                    }
                    runCatching { pronunciationDict.addEntry(word, phonetic) }
                    refreshDictAdapter()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.enter_word_and_pronunciation), Toast.LENGTH_SHORT).show()
                    view?.announceCompat(getString(R.string.enter_word_and_pronunciation))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** إعادة رسم قائمة إدخالات القاموس بعد أي تغيير (إضافة/تعديل/حذف/استيراد) */
    private fun refreshDictAdapter() {
        rvPronunciationDict.adapter = PronunciationDictAdapter()
    }

    /** نطق الرقم المدخل في حقل المعاينة بنفس منطق النطق الفعلي للإعلانات */
    private fun previewNumber() {
        // نقبل الأرقام الهندية/الشرقية في الحقل (٠١٢٣...) مع الغربية
        val raw = com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(
            etNumberPreview.text.toString().trim()
        )
        val number = raw.toIntOrNull()
        if (number == null) {
            Toast.makeText(requireContext(), R.string.number_preview_invalid, Toast.LENGTH_SHORT).show()
            view?.announceCompat(getString(R.string.number_preview_invalid))
            return
        }
        val forced = runCatching { settings.getAnnouncementSpeechLanguage() }.getOrNull()
        val appLang = runCatching { settings.getAppLanguage() }.getOrNull()
            ?: Locale.getDefault().language
        val isEnglish = if (forced != null) forced.startsWith("en", ignoreCase = true)
            else appLang.startsWith("en", ignoreCase = true)
        val mode = runCatching { settings.getNumberReadingMode() }.getOrDefault(1).coerceIn(1, 8)
        val text = NumberSpeech.formatByMode(mode, number, isEnglish)
        val langTag = if (isEnglish) "en" else "ar"
        speakWithVoice(langTag, text)
    }

    /** حوار إدارة أسماء المتصلين المخصصة (رقم → اسم يُنطق به) */
    private fun showCallerNamesDialog() {
        val names = runCatching { settings.getCustomCallerNames() }.getOrDefault(emptyMap()).toMutableMap()
        val rows = names.toList().toMutableList()
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val listContainer = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        fun addRow(number: String = "", name: String = "") {
            val numberPicker = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                hint = getString(R.string.caller_names_number_hint)
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setText(number)
            }
            val namePicker = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                hint = getString(R.string.caller_names_name_hint)
                setText(name)
            }
            val removeBtn = com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.caller_names_delete)
                textSize = 13f
                // أهداف لمس لا تقل عن 48dp لقارئ الشاشة
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            val rowLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                setPadding(0, 0, 0, 0)
            }
            // بسيط: LinearLayout أفقي بعمودين نصيين وزر حذف
            val fields = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(numberPicker, android.widget.LinearLayout.LayoutParams(0, -2, 2f))
                addView(namePicker, android.widget.LinearLayout.LayoutParams(0, -2, 2f))
                addView(removeBtn, android.widget.LinearLayout.LayoutParams(-2, -2))
            }
            rowLayout.addView(fields)
            removeBtn.setOnClickListener {
                listContainer.removeView(rowLayout)
                val key = numberPicker.text.toString().trim()
                rows.removeAll { it.first == key }
            }
            listContainer.addView(rowLayout)
            rows.add(number to name)
        }

        names.forEach { (n, nm) -> addRow(n, nm) }
        if (rows.isEmpty()) {
            val emptyHint = TextView(requireContext()).apply {
                text = getString(R.string.caller_names_empty)
                textSize = 13f
                setPadding(0, 16, 0, 16)
            }
            listContainer.addView(emptyHint)
        }
        val addBtn = com.google.android.material.button.MaterialButton(requireContext(), null,
            com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.caller_names_add)
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        addBtn.setOnClickListener { addRow() }

        val scroll = android.widget.ScrollView(requireContext()).apply {
            addView(listContainer)
        }
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(addBtn)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.caller_names_title))
            .setView(root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newNames = mutableMapOf<String, String>()
                var duplicate = false
                for (i in 0 until listContainer.childCount) {
                    val rowLayout = listContainer.getChildAt(i) as? com.google.android.material.textfield.TextInputLayout ?: continue
                    val fields = rowLayout.getChildAt(0) as? android.widget.LinearLayout ?: continue
                    if (fields.childCount < 2) continue
                    val num = (fields.getChildAt(0) as? android.widget.EditText)?.text?.toString()?.trim().orEmpty()
                    val nm = (fields.getChildAt(1) as? android.widget.EditText)?.text?.toString()?.trim().orEmpty()
                    if (num.isEmpty() || nm.isEmpty()) continue
                    if (newNames.containsKey(num)) { duplicate = true; continue }
                    newNames[num] = nm
                }
                if (duplicate) {
                    Toast.makeText(requireContext(), R.string.caller_names_duplicate, Toast.LENGTH_LONG).show()
                    view?.announceCompat(getString(R.string.caller_names_duplicate))
                }
                runCatching { settings.setCustomCallerNames(newNames) }
                if (!duplicate) {
                    Toast.makeText(requireContext(), R.string.caller_names_saved, Toast.LENGTH_SHORT).show()
                    view?.announceCompat(getString(R.string.caller_names_saved))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ===== إعلان الوقت =====
    private fun setupTimeAnnouncementSettings() {
        val intervals = listOf(
            getString(R.string.time_interval_15),
            getString(R.string.time_interval_30),
            getString(R.string.time_interval_45),
            getString(R.string.time_interval_60)
        )
        spinnerTimeInterval.adapter = simpleAdapter(intervals)

        val formats = listOf(getString(R.string.time_format_natural), getString(R.string.time_format_digital))
        spinnerTimeFormat.adapter = simpleAdapter(formats)

        val intervalPref = runCatching { settings.getTimeAnnouncementInterval() }.getOrDefault(30)
        spinnerTimeInterval.setSelection(intervalIndex(intervalPref))
        val formatPref = runCatching { settings.getTimeAnnouncementFormat() }.getOrDefault("arabic_natural")
        spinnerTimeFormat.setSelection(if (formatPref == "digital") 1 else 0)
        switchTimeAnnouncement.isChecked =
            runCatching { settings.isTimeAnnouncementEnabled() }.getOrDefault(true)
        setupQuietScheduleRows()

        switchClockWidget.isChecked =
            runCatching { settings.isClockWidgetEnabled() }.getOrDefault(false)

        switchTimeAnnouncement.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeAnnouncementEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(requireContext())
            accordion.updateSectionStatuses()
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }
        switchTime24h.isChecked =
            runCatching { settings.isTime24Hour() }.getOrDefault(false)
        switchTime24h.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTime24Hour(checked) }
            view?.announceCompat(getString(if (checked) R.string.toggle_on else R.string.toggle_off))
        }
        switchHijriDate.isChecked =
            runCatching { settings.isHijriDateEnabled() }.getOrDefault(false)
        switchHijriDate.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setHijriDateEnabled(checked) }
            view?.announceCompat(getString(if (checked) R.string.toggle_on else R.string.toggle_off))
        }
        switchClockWidget.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setClockWidgetEnabled(checked) }
            view?.announceCompat(getString(if (checked) R.string.toggle_on else R.string.toggle_off))
        }
        spinnerTimeInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = when (position) {
                    1 -> 30
                    2 -> 45
                    3 -> 60
                    else -> 15
                }
                runCatching { settings.setTimeAnnouncementInterval(value) }
                accordion.updateSectionStatuses()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun intervalIndex(interval: Int): Int = when (interval) {
        30 -> 1
        45 -> 2
        60 -> 3
        else -> 0
    }

    /**
     * يبني صفوف ساعات الهدوء السبعة (يوم → بداية/نهاية) بحقول رقمية 0..23
     * تُحفظ فور التعديل لكل يوم على حدة (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت).
     */
    private fun setupQuietScheduleRows() {
        llQuietSchedule.removeAllViews()
        val days = listOf(
            R.string.day_sunday to Calendar.SUNDAY,
            R.string.day_monday to Calendar.MONDAY,
            R.string.day_tuesday to Calendar.TUESDAY,
            R.string.day_wednesday to Calendar.WEDNESDAY,
            R.string.day_thursday to Calendar.THURSDAY,
            R.string.day_friday to Calendar.FRIDAY,
            R.string.day_saturday to Calendar.SATURDAY
        )
        val density = resources.displayMetrics.density

        for ((labelRes, day) in days) {
            val dayName = getString(labelRes)
            val start = runCatching { settings.getQuietStartForDay(day) }.getOrDefault(23)
            val end = runCatching { settings.getQuietEndForDay(day) }.getOrDefault(7)

            val startField = android.widget.EditText(requireContext()).apply {
                // اليومية في الـ hint تمنح قارئ الشاشة سياق اليوم لكل حقل
                hint = dayName + "، " + getString(R.string.time_quiet_start_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(start.toString().padStart(2, '0'))
                maxLines = 1
                minHeight = (48 * density).toInt()
                addTextChangedListener(quietWatcher {
                    runCatching { settings.setQuietStartForDay(day, it) }
                })
            }
            val endField = android.widget.EditText(requireContext()).apply {
                hint = dayName + "، " + getString(R.string.time_quiet_end_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(end.toString().padStart(2, '0'))
                maxLines = 1
                minHeight = (48 * density).toInt()
                addTextChangedListener(quietWatcher {
                    runCatching { settings.setQuietEndForDay(day, it) }
                })
            }

            val label = TextView(requireContext()).apply {
                text = getString(labelRes)
                textSize = 16f
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
                addView(
                    label,
                    android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(startField, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = (8 * density).toInt()
                    marginStart = (8 * density).toInt()
                })
                addView(endField, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            }
            llQuietSchedule.addView(row)
        }
    }

    /** توسيع/طي قسم قابل للطي، ويُحدّث السهم (▼/▲) ووصف الأب بحالة الطي */
    private fun toggleCollapsible(content: View, arrow: TextView, header: View) {
        val collapsed = content.visibility == View.GONE || content.visibility == View.INVISIBLE
        content.visibility = if (collapsed) View.VISIBLE else View.GONE
        arrow.text = if (collapsed) "▲" else "▼"
        // يقرأ قارئ الشاشة نصاً واحداً: العنوان الأساسي + حالة (موسّع/مطوي)
        val stateLabel = if (collapsed) getString(R.string.expand) else getString(R.string.collapse)
        val base = header.getTag() as? String
        if (base != null) {
            header.contentDescription = base + " — " + stateLabel
        } else {
            header.contentDescription = stateLabel
        }
    }

    // ===== إعلان مستوى البطارية =====
    private fun setupBatteryAnnouncementSettings() {
        // المفتاح الرئيسي
        switchBatteryAnnouncement.isChecked = runCatching { settings.isBatteryAnnouncementEnabled() }.getOrDefault(false)
        switchBatteryAnnouncement.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setBatteryAnnouncementEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(requireContext())
            accordion.updateSectionStatuses()
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // عنوان المستويات قابل للتوسيع/الطي
        llBatteryLevelsHeader.tag = getString(R.string.battery_announcement_levels_summary)
        llBatteryLevelsHeader.setOnClickListener { toggleCollapsible(llBatteryLevels, tvBatteryLevelsArrow, llBatteryLevelsHeader) }

        // مستويات البطارية: مسقط لكل مستوى (5%، 10%، ... 100%)
        val enabledLevels = runCatching { settings.getBatteryAnnouncementLevels() }.getOrDefault(setOf("20", "15"))
        llBatteryLevels.removeAllViews()
        val allLevels = (1..20).map { it * 5 } // 5, 10, ... 100
        for (level in allLevels) {
            val levelStr = level.toString()
            val cb = android.widget.CheckBox(requireContext()).apply {
                text = "$level%"
                isChecked = levelStr in enabledLevels
                textSize = 16f
                setPadding(0, 4, 0, 4)
                // هدف لمس لا يقل عن 48dp
                minHeight = (48 * resources.displayMetrics.density).toInt()
                accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.contentDescription = getString(R.string.battery_level_contentdesc, level)
                    }
                }
                setOnCheckedChangeListener { _, isChecked ->
                    val current = settings.getBatteryAnnouncementLevels().toMutableSet()
                    if (isChecked) current.add(levelStr) else current.remove(levelStr)
                    settings.setBatteryAnnouncementLevels(current)
                }
            }
            llBatteryLevels.addView(cb)
        }

        // صوت نطق البطارية
        spinnerBatteryVoice.adapter = simpleAdapter(nateqVoices.map { it.displayName })
        val savedBatteryVoice = runCatching { settings.getBatteryAnnouncementVoiceId() }.getOrNull()
        if (savedBatteryVoice != null) {
            val idx = nateqVoices.indexOfFirst { it.name == savedBatteryVoice }
            if (idx >= 0) spinnerBatteryVoice.setSelection(idx)
        }
        spinnerBatteryVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setBatteryAnnouncementVoiceId(nateqVoices[position].name) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val batteryRate = runCatching { settings.getBatteryAnnouncementRate() }.getOrDefault(1.0f)
        tvBatteryRateValue.text = String.format(Locale.US, "%.1fx", batteryRate)
        seekBatteryRate.progress = (batteryRate * 100).toInt().coerceIn(0, 200)
        seekBatteryRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvBatteryRateValue.text = String.format(Locale.US, "%.1fx", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setBatteryAnnouncementRate(seekBar.progress / 100f) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
            }
        })

        // مستوى الصوت
        val batteryVolume = runCatching { settings.getBatteryAnnouncementVolume() }.getOrDefault(1.0f)
        tvBatteryVolumeValue.text = "${(batteryVolume * 100).toInt()}%"
        seekBatteryVolume.progress = (batteryVolume * 100).toInt().coerceIn(0, 100)
        seekBatteryVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvBatteryVolumeValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setBatteryAnnouncementVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // إعلان اكتمال الشحن (100%)
        switchChargingComplete.isChecked =
            runCatching { settings.isChargingCompleteAnnouncementEnabled() }.getOrDefault(true)
        switchChargingComplete.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setChargingCompleteAnnouncementEnabled(checked) }
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // إعلان فصل الشاحن
        switchChargingDisconnect.isChecked =
            runCatching { settings.isChargingDisconnectAnnouncementEnabled() }.getOrDefault(true)
        switchChargingDisconnect.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setChargingDisconnectAnnouncementEnabled(checked) }
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // وضع توفير الطاقة + عتبته
        switchPowerSaver.isChecked =
            runCatching { settings.isPowerSaverModeEnabled() }.getOrDefault(false)
        val powerThreshold =
            runCatching { settings.getPowerSaverBatteryThreshold() }.getOrDefault(20)
        tvPowerSaverThresholdValue.text = "$powerThreshold%"
        seekPowerSaverThreshold.progress = powerThreshold.coerceIn(0, 100)
        llPowerSaverThreshold.visibility = if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        seekPowerSaverThreshold.visibility = if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        switchPowerSaver.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setPowerSaverModeEnabled(checked) }
            llPowerSaverThreshold.visibility = if (checked) View.VISIBLE else View.GONE
            seekPowerSaverThreshold.visibility = if (checked) View.VISIBLE else View.GONE
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }
        seekPowerSaverThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvPowerSaverThresholdValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setPowerSaverBatteryThreshold(seekBar.progress) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })
    }

    // ===== قراءة الإشعارات =====
    private fun setupNotificationReadingSettings() {
        // المفتاح الرئيسي
        switchNotificationReading.isChecked = runCatching { settings.isNotificationReadingEnabled() }.getOrDefault(false)
        switchNotificationReading.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setNotificationReadingEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(requireContext())
            accordion.updateSectionStatuses()
            view?.announceCompat(getString(if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off))
        }

        // فتح إعدادات إذن الوصول للإشعارات من النظام
        llNotificationListenerSettings.setOnClickListener {
            val granted = runCatching {
                com.aymankhattab.nateq.receivers.NateqNotificationListener.isPermissionGranted(requireContext())
            }.getOrDefault(false)
            if (granted) {
                Toast.makeText(requireContext(), R.string.notification_reading_enabled_summary, Toast.LENGTH_SHORT).show()
                view?.announceCompat(getString(R.string.notification_reading_enabled_summary))
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), R.string.notification_permission_needed, Toast.LENGTH_LONG).show()
                    view?.announceCompat(getString(R.string.notification_permission_needed))
                }
            }
        }

        // اختيار التطبيقات التي تُقرأ إشعاراتها
        view?.findViewById<View>(R.id.ll_notification_apps_settings)
            ?.setOnClickListener { showNotificationAppsDialog() }
    }

    /** حوار اختيار التطبيقات التي تُقرأ إشعاراتها (بعلامة "كل التطبيقات"). */
    private fun showNotificationAppsDialog() {
        val current = runCatching { settings.getNotificationAppsSelection() }
            .getOrDefault(SettingsRepository.DEFAULT_NOTIFICATION_APPS)
        val options = listOf(
            getString(R.string.notification_apps_all) to SettingsRepository.NOTIF_READ_ALL,
            getString(R.string.app_whatsapp) to "com.whatsapp",
            getString(R.string.app_whatsapp_business) to "com.whatsapp.w4b",
            getString(R.string.app_telegram) to "org.telegram.messenger",
            getString(R.string.app_telegram_web) to "org.telegram.messenger.web",
            getString(R.string.app_messenger) to "com.facebook.orca",
            getString(R.string.app_instagram) to "com.instagram.android"
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
        AlertDialog.Builder(requireContext())
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
                    requireContext(),
                    R.string.notification_apps_saved,
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(getString(R.string.notification_apps_saved))
                accordion.updateSectionStatuses()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== إعلان اسم المتصل =====
    private fun setupCallerAnnouncementSettings() {
        // المفتاح الرئيسي: عند التفعيل نطلب الأذونات أولاً (لا نفعّل إلا بمنحها)
        switchCallerAnnouncement.isChecked = runCatching { settings.isCallerAnnouncementEnabled() }.getOrDefault(false)
        switchCallerAnnouncement.setOnCheckedChangeListener { _, checked ->
            if (callerSwitchGuard) return@setOnCheckedChangeListener
            if (checked) {
                val needed = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG
                )
                val hasContacts = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasContacts) needed.add(Manifest.permission.READ_CONTACTS)
                callerPermLauncher.launch(needed.toTypedArray())
            } else {
                runCatching { settings.setCallerAnnouncementEnabled(false) }
                // لا نوقف الخدمة؛ إن لم يبقَ أي إعلان مفعّل تتوقف هي نفسها.
                accordion.updateSectionStatuses()
                view?.announceCompat(getString(R.string.announcement_turned_off))
            }
        }

        // عدد مرات التكرار
        val repeats = listOf(
            getString(R.string.repeat_once),
            getString(R.string.repeat_twice),
            getString(R.string.repeat_3),
            getString(R.string.repeat_4),
            getString(R.string.repeat_5)
        )
        spinnerCallerRepeat.adapter = simpleAdapter(repeats)
        val savedRepeat = runCatching { settings.getCallerAnnouncementRepeat() }.getOrDefault(1)
        spinnerCallerRepeat.setSelection((savedRepeat - 1).coerceIn(0, repeats.size - 1))
        spinnerCallerRepeat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementRepeat(position + 1) }
                accordion.updateSectionStatuses()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val callerRate = runCatching { settings.getCallerAnnouncementRate() }.getOrDefault(1.0f)
        tvCallerRateValue.text = String.format(Locale.US, "%.1fx", callerRate)
        seekCallerRate.progress = (callerRate * 100).toInt().coerceIn(0, 200)
        seekCallerRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvCallerRateValue.text = String.format(Locale.US, "%.1fx", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setCallerAnnouncementRate(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
            }
        })

        // مستوى الصوت
        val callerVolume = runCatching { settings.getCallerAnnouncementVolume() }.getOrDefault(1.0f)
        tvCallerVolumeValue.text = "${(callerVolume * 100).toInt()}%"
        seekCallerVolume.progress = (callerVolume * 100).toInt().coerceIn(0, 100)
        seekCallerVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvCallerVolumeValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setCallerAnnouncementVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // قالب إعلان المتصل: {name} لاسم المتصل
        etCallerTemplate.setText(runCatching { settings.getCallerAnnouncementTemplate() }.getOrNull())
        etCallerTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    settings.setCallerAnnouncementTemplate(s?.toString()?.trim()?.takeIf { it.isNotBlank() })
                }
            }
        })

        // صوت نطق الأسماء العربية في إعلان المتصل
        spinnerCallerVoiceAr.adapter = simpleAdapter(nateqVoices.map { it.displayName })
        val savedCallerVoiceAr = runCatching { settings.getCallerAnnouncementArabicVoiceId() }.getOrNull()
        if (savedCallerVoiceAr != null) {
            val idx = nateqVoices.indexOfFirst { it.name == savedCallerVoiceAr }
            if (idx >= 0) spinnerCallerVoiceAr.setSelection(idx)
        }
        spinnerCallerVoiceAr.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementArabicVoiceId(nateqVoices[position].name) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // صوت نطق الأسماء الإنجليزية في إعلان المتصل
        spinnerCallerVoiceEn.adapter = simpleAdapter(nateqVoices.map { it.displayName })
        val savedCallerVoiceEn = runCatching { settings.getCallerAnnouncementEnglishVoiceId() }.getOrNull()
        if (savedCallerVoiceEn != null) {
            val idx = nateqVoices.indexOfFirst { it.name == savedCallerVoiceEn }
            if (idx >= 0) spinnerCallerVoiceEn.setSelection(idx)
        }
        spinnerCallerVoiceEn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementEnglishVoiceId(nateqVoices[position].name) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== قراءة الرسائل الواردة =====
    private fun setupSmsReadingSettings() {
        // وضع القراءة: مفعل / قراءة مصدر الرسالة فقط / معطل
        val modes = listOf(
            getString(R.string.sms_mode_full),
            getString(R.string.sms_mode_source),
            getString(R.string.sms_mode_off)
        )
        spinnerSmsMode.adapter = simpleAdapter(modes)
        val savedMode = runCatching { settings.getSmsReadingMode() }.getOrDefault("off")
        val modeIndex = when (savedMode) {
            "full" -> 0
            "source" -> 1
            else -> 2 // "off"
        }
        spinnerSmsMode.setSelection(modeIndex)
        spinnerSmsMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> "full"
                    1 -> "source"
                    else -> "off"
                }
                if (mode == "off") {
                    // "off": يُحفظ فوراً (لا يتطلب إذناً) ويرفع أي وضع معلّق.
                    pendingSmsMode = null
                    runCatching { settings.setSmsReadingMode("off") }
                    accordion.updateSectionStatuses()
                    return
                }
                // وضع غير "off" (full/source): لا يُحفظ حتى منح الإذن.
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        android.Manifest.permission.RECEIVE_SMS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    pendingSmsMode = mode
                    runCatching {
                        smsPermLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                    }
                } else {
                    // الإذن ممنوح من قبل: نحفظ مباشرة.
                    pendingSmsMode = null
                    runCatching { settings.setSmsReadingMode(mode) }
                    AnnouncementSchedulerService.requestStart(requireContext())
                }
                // تنبيه سياسة أندرويد 17: رسائل OTP تُحجب 3 ساعات أولى بعد التفعيل.
                view?.announceCompat(getString(R.string.sms_otp_block_hint))
                accordion.updateSectionStatuses()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // اختيار الصوت (العربية / الإنجليزية)
        spinnerSmsVoice.adapter = simpleAdapter(nateqVoices.map { it.displayName })
        val savedSmsVoice = runCatching { settings.getSmsReadingVoiceId() }.getOrNull()
        if (savedSmsVoice != null) {
            val idx = nateqVoices.indexOfFirst { it.name == savedSmsVoice }
            if (idx >= 0) spinnerSmsVoice.setSelection(idx)
        }
        spinnerSmsVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setSmsReadingVoiceId(nateqVoices[position].name) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val smsRate = runCatching { settings.getSmsReadingRate() }.getOrDefault(1.0f)
        tvSmsRateValue.text = String.format(Locale.US, "%.1fx", smsRate)
        seekSmsRate.progress = (smsRate * 100).toInt().coerceIn(0, 200)
        seekSmsRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvSmsRateValue.text = String.format(Locale.US, "%.1fx", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setSmsReadingRate(seekBar.progress / 100f) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
            }
        })

        // مستوى الصوت
        val smsVolume = runCatching { settings.getSmsReadingVolume() }.getOrDefault(1.0f)
        tvSmsVolumeValue.text = "${(smsVolume * 100).toInt()}%"
        seekSmsVolume.progress = (smsVolume * 100).toInt().coerceIn(0, 100)
        seekSmsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSmsVolumeValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setSmsReadingVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // قالب قراءة الرسائل: {name} للمرسل و{message} للرسالة
        etSmsTemplate.setText(runCatching { settings.getSmsAnnouncementTemplate() }.getOrNull())
        etSmsTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    settings.setSmsAnnouncementTemplate(s?.toString()?.trim()?.takeIf { it.isNotBlank() })
                }
            }
        })
    }

    // ===== الإعدادات العامة =====
    private fun setupGeneralSettings() {
        val rate = runCatching { settings.getDefaultSpeechRate() }.getOrDefault(1.0f)
        val pitch = runCatching { settings.getDefaultPitch() }.getOrDefault(1.0f)
        val volume = runCatching { settings.getDefaultVolume() }.getOrDefault(1.0f)

        tvDefaultSpeechRateValue.text = String.format(Locale.US, "%.1fx", rate)
        seekDefaultSpeechRate.progress = (rate * 100).toInt().coerceIn(0, 200)
        tvDefaultPitchValue.text = String.format(Locale.US, "%.1fx", pitch)
        seekDefaultPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)
        tvDefaultVolumeValue.text = "${(volume * 100).toInt()}%"
        seekDefaultVolume.progress = (volume * 100).toInt().coerceIn(0, 100)

        seekDefaultSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvDefaultSpeechRateValue.text = String.format(Locale.US, "%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultSpeechRate(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
                accordion.updateSectionStatuses()
            }
        })

        seekDefaultPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvDefaultPitchValue.text = String.format(Locale.US, "%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultPitch(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
                accordion.updateSectionStatuses()
            }
        })

        seekDefaultVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvDefaultVolumeValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultVolume(value) }
                seekBar.announceCompat("${seekBar.progress}%")
                accordion.updateSectionStatuses()
            }
        })
    }

    // ===== Adapters =====
    private inner class CategoryVoiceAdapter : RecyclerView.Adapter<CategoryVoiceAdapter.CatVH>() {

        private val categoryList = listOf(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            SettingsRepository.VOICE_CATEGORY_TIME,
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )

        inner class CatVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvCategory: TextView = itemView.findViewById(R.id.tv_category_name)
            val tvCategoryDescription: TextView = itemView.findViewById(R.id.tv_category_description)
            val spinnerVoice: Spinner = itemView.findViewById(R.id.spinner_category_voice)
            val seekRate: SeekBar = itemView.findViewById(R.id.seek_category_speech_rate)
            val tvRateValue: TextView = itemView.findViewById(R.id.tv_category_speech_rate_value)
            val seekPitch: SeekBar = itemView.findViewById(R.id.seek_category_pitch)
            val tvPitchValue: TextView = itemView.findViewById(R.id.tv_category_pitch_value)
            val seekVolume: SeekBar = itemView.findViewById(R.id.seek_category_volume)
            val tvVolumeValue: TextView = itemView.findViewById(R.id.tv_category_volume_value)
            val btnTest: View = itemView.findViewById(R.id.btn_test_category_voice)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_voice, parent, false)
            return CatVH(view)
        }

        override fun onBindViewHolder(holder: CatVH, position: Int) {
            val category = categoryList[position]
            val catLabel = when (category) {
                SettingsRepository.VOICE_CATEGORY_TIME -> getString(R.string.voice_category_time)
                SettingsRepository.VOICE_CATEGORY_NUMBERS -> getString(R.string.voice_category_numbers)
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS -> getString(R.string.voice_category_notifications)
                else -> getString(R.string.voice_category_default)
            }
            holder.tvCategory.text = catLabel
            holder.tvCategoryDescription.text = when (category) {
                SettingsRepository.VOICE_CATEGORY_TIME -> getString(R.string.voice_category_time_summary)
                SettingsRepository.VOICE_CATEGORY_NUMBERS -> getString(R.string.voice_category_numbers_summary)
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS -> getString(R.string.voice_category_notifications_summary)
                else -> getString(R.string.voice_category_default_summary)
            }

            // جميع أصوات ناطق (العربية والإنجليزية) في قائمة كل فئة
            holder.spinnerVoice.adapter = simpleAdapter(nateqVoices.map { it.displayName })

            val saved = runCatching { settings.getPreferredVoiceIdForCategory(category) }.getOrNull()
            if (saved != null) {
                val idx = nateqVoices.indexOfFirst { it.name == saved }
                if (idx >= 0) holder.spinnerVoice.setSelection(idx)
            }

            holder.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    runCatching { settings.setPreferredVoiceIdForCategory(category, nateqVoices[pos].name) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val rate = runCatching { settings.getSpeechRateForCategory(category) }.getOrDefault(1.0f)
            holder.tvRateValue.text = String.format(Locale.US, "%.1fx", rate)
            holder.seekRate.progress = (rate * 100).toInt().coerceIn(0, 200)

            val pitch = runCatching { settings.getPitchForCategory(category) }.getOrDefault(1.0f)
            holder.tvPitchValue.text = String.format(Locale.US, "%.1fx", pitch)
            holder.seekPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)

            val volume = runCatching { settings.getVolumeForCategory(category) }.getOrDefault(1.0f)
            holder.tvVolumeValue.text = "${(volume * 100).toInt()}%"
            holder.seekVolume.progress = (volume * 100).toInt().coerceIn(0, 100)

            holder.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress / 100f
                    holder.tvRateValue.text = String.format(Locale.US, "%.1fx", value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    runCatching { settings.setSpeechRateForCategory(category, seekBar.progress / 100f) }
                    holder.seekRate.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
                }
            })

            holder.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress / 100f
                    holder.tvPitchValue.text = String.format(Locale.US, "%.1fx", value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    runCatching { settings.setPitchForCategory(category, seekBar.progress / 100f) }
                    holder.seekPitch.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
                }
            })

            holder.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    holder.tvVolumeValue.text = "$progress%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    runCatching { settings.setVolumeForCategory(category, seekBar.progress / 100f) }
                    holder.seekVolume.announceCompat("${seekBar.progress}%")
                }
            })

            holder.btnTest.setOnClickListener {
                val voice = nateqVoices[holder.spinnerVoice.selectedItemPosition]
                val isArabic = !voice.languageTag.startsWith("en", ignoreCase = true)
                val text = when {
                    !isArabic && category == SettingsRepository.VOICE_CATEGORY_TIME ->
                        getString(R.string.sample_text_time_en)
                    !isArabic && category == SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                        getString(R.string.sample_text_numbers_en)
                    !isArabic && category == SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                        getString(R.string.sample_text_notifications_en)
                    !isArabic -> getString(R.string.sample_text_default_en)
                    category == SettingsRepository.VOICE_CATEGORY_TIME ->
                        getString(R.string.sample_text_time_ar)
                    category == SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                        getString(R.string.sample_text_numbers_ar)
                    category == SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                        getString(R.string.sample_text_notifications_ar)
                    else -> getString(R.string.sample_text_default_ar)
                }
                speakWithVoice(voice.languageTag, text)
            }
        }

        override fun getItemCount(): Int = categoryList.size
    }

    private inner class PronunciationDictAdapter :
        RecyclerView.Adapter<PronunciationDictAdapter.DictVH>() {

        private val entries: MutableList<Pair<String, String>> = mutableListOf()

        init {
            entries.addAll(
                runCatching { pronunciationDict.getAllEntries() }.getOrDefault(emptyMap()).toList()
            )
        }

        inner class DictVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvWord: TextView = itemView.findViewById(R.id.tv_dict_word)
            val tvPhonetic: TextView = itemView.findViewById(R.id.tv_dict_phonetic)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DictVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dict_entry, parent, false)
            return DictVH(view)
        }

        override fun onBindViewHolder(holder: DictVH, position: Int) {
            val entry = entries[position]
            holder.tvWord.text = entry.first
            holder.tvPhonetic.text = entry.second
            // وصف مدمج لعقدة الصف الواحدة (الأطفال معطَّلون في XML)
            holder.itemView.contentDescription = "${entry.first}. ${entry.second}"
            // النقر (نقرتان من TalkBack) أو الضغطة المطولة: تعديل/حذف الإدخال —
            // النقر الجهازي مكافئ لقائمة الأدوات فلا يضيع الإجراء على مستخدمي القارئ.
            holder.itemView.setOnClickListener { showDictRowOptions(entry) }
            holder.itemView.setOnLongClickListener {
                showDictRowOptions(entry)
                true
            }
        }

        override fun getItemCount(): Int = entries.size
    }

    private fun showDictRowOptions(entry: Pair<String, String>) {
        val options = arrayOf(
            getString(R.string.dict_edit_entry),
            getString(R.string.dict_delete_entry)
        )
        AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDictEditDialog(entry)
1 -> {
                            runCatching { pronunciationDict.removeEntry(entry.first) }
                            // announcement بنتيجة الحذف لتوفر تغذية rückfeed لقارئ الشاشة
                            announcementSpeaker?.speak(
                                getString(R.string.dict_removed),
                                if (com.aymankhattab.nateq.util.LocaleUtils.containsArabic(entry.second)) Locale.forLanguageTag("ar")
                                    else Locale.forLanguageTag("en"),
                                1.0f, 1.0f, 1.0f
                            )
                            refreshDictAdapter()
                        }
                }
            }
            .show()
    }

    private fun speakWithVoice(languageTag: String, text: String) {
        // نستخدم نفس مسار الإعلانات الصوتية التلقائية (AnnouncementSpeaker)
        // الذي يربط المحرك المختار فعليا عبر setEngineByPackageName —
        // لا نعتمد على المحرك الافتراضي للنظام حتى لا يكون "نحن" أنفسنا.
        val speaker = announcementSpeaker
            ?: AnnouncementSpeaker(requireContext()).also { announcementSpeaker = it }
        speaker.speak(
            text,
            Locale.forLanguageTag(languageTag),
            runCatching { settings.getSpeechRate(languageTag) }.getOrDefault(1.0f),
            1.0f,
            1.0f,
        )
    }

    // ===== تبديل لغة التطبيق (أسفل الشاشة) =====
    private fun setupLanguageToggle() {
        val current = runCatching { settings.getAppLanguage() }
            .getOrNull()
            ?: Locale.getDefault().language

        val isArabic = current.startsWith("ar", ignoreCase = true)
        // يعرض اللغة الحالية ثم الإجراء نحو اللغة الأخرى
        btnToggleLanguage.text = if (isArabic) {
            getString(R.string.language_current_label) + " " + getString(R.string.language_arabic) + " — " + getString(R.string.language_change_to_en)
        } else {
            getString(R.string.language_current_label) + " " + getString(R.string.language_english) + " — " + getString(R.string.language_change_to_ar)
        }

        btnToggleLanguage.setOnClickListener {
            val newLang = if (isArabic) "en" else "ar"
            runCatching { settings.setAppLanguage(newLang) }
            // تطبيق اللغة على مستوى التطبيق (AppCompatDelegate) قبل إعادة إنشاء
            // النشاط حتى تُنشأ موارد النشاط الجديد باللغة الجديدة فعلياً.
            runCatching {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(newLang)
                )
            }
            // إعادة إنشاء النشاط لتطبيق اللغة فورياً (UI + افتراضيات)
            requireActivity().recreate()
        }
    }

    // ===== زر الحفظ + زر استعادة الافتراضيات =====
    private fun setupSaveAndResetButtons() {
        val btnSave = view?.findViewById<View>(R.id.btn_save_settings)
        val btnReset = view?.findViewById<View>(R.id.btn_reset_settings)

        // الحفظ: الإعدادات تُخزَّن فورياً عند كل تغيير عبر setters، لكن نقدم
        // للمستخدم تأكيداً واضحاً بأن إعداداته مأخوذة في مكانها.
        btnSave?.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.saved_successfully), Toast.LENGTH_SHORT).show()
            view?.findViewById<View>(R.id.btn_save_settings)
                ?.announceCompat(getString(R.string.saved_successfully))
        }

        // استعادة الافتراضيات: مسح كل الإعدادات ثم إعادة بناء الواجهة لتحميل
        // القيم الافتراضية (بدون إعادة إنشاء الـ Activity).
        btnReset?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setPositiveButton(R.string.reset_done) { _, _ ->
                    runCatching { settings.resetAllToDefault() }
                    Toast.makeText(requireContext(), getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
                    view?.findViewById<View>(R.id.btn_reset_settings)
                        ?.announceCompat(getString(R.string.reset_done))
                    // إعادة تحميل كل القيم الإفتراضية في الواجهة الحالية:
                    // نعيد استدعاء محضِّرات الإعدادات المباشرة (Skip المحرك/الملفات).
                    setupTimeAnnouncementSettings()
                    setupBatteryAnnouncementSettings()
                    setupNotificationReadingSettings()
                    setupCallerAnnouncementSettings()
                    setupSmsReadingSettings()
                    setupGeneralSettings()
                    setupNumberReadingSettings()
                    rvCategories.adapter?.notifyDataSetChanged()
                    // تحديث نصوص حالة الأقسام بعد إعادة التحميل حتى تعكس القيم
                    // الافتراضية فوراً (كانت تبقى على القيم القديمة المحذوفة).
                    accordion.updateSectionStatuses()
                }
                .setNegativeButton(R.string.reset_cancel, null)
                .show()
        }
    }

    // ===== النسخ الاحتياطي / الاستعادة =====
    private fun setupBackupRestoreButtons() {
        view?.findViewById<View>(R.id.btn_backup_settings)?.setOnClickListener {
            // تحذير صريح قبل التصدير: الملف نص صريح قد يحوي بيانات شخصية
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.backup_export_warning_title)
                .setMessage(R.string.backup_export_warning_message)
                .setPositiveButton(R.string.backup_settings) { _, _ ->
                    runCatching { createBackupLauncher.launch("lord_tts_backup.json") }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        view?.findViewById<View>(R.id.btn_restore_settings)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
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
                .show()
        }
    }

    /** إعادة تحميل كل قيم الواجهة بعد الاستعادة (دون إعادة إنشاء النشاط). */
    private fun refreshAllSettingsUi() {
        setupTimeAnnouncementSettings()
        setupBatteryAnnouncementSettings()
        setupNotificationReadingSettings()
        setupCallerAnnouncementSettings()
        setupSmsReadingSettings()
        setupGeneralSettings()
        setupNumberReadingSettings()
        rvCategories.adapter?.notifyDataSetChanged()
        refreshDictAdapter()
        accordion.updateSectionStatuses()
    }

    // (منطق النسخ الاحتياطي/الاستعادة — buildBackupJson/applyBackupJson بحدودهما —
    //  انتقل إلى SettingsViewModel، والفصيل يعرض النتيجة في مواضع SAF فقط.)

    // ===== إعدادات نطق الأرقام + مفتاح لغة النطق =====
    private fun setupNumberReadingSettings() {
        // خيارات طريقة نطق الأرقام (1..8) ثنائية اللغة
        val modeLabels = arrayOf(
            getString(R.string.number_mode_single),
            getString(R.string.number_mode_pairs),
            getString(R.string.number_mode_triples),
            getString(R.string.number_mode_quadruples),
            getString(R.string.number_mode_quintuples),
            getString(R.string.number_mode_sextuples),
            getString(R.string.number_mode_septuples),
            getString(R.string.number_mode_octuples)
        )
        val savedMode = runCatching { settings.getNumberReadingMode() }.getOrDefault(1).coerceIn(1, 8)
        spinnerNumberReadingMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            modeLabels
        )
        spinnerNumberReadingMode.setSelection(savedMode - 1)
        spinnerNumberReadingMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setNumberReadingMode(position + 1) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // مفتاح لغة نطق الإعلانات (EN/AR) — يعرض الإجراء نحو اللغة المعاكسة للحالية
        val current = runCatching { settings.getAnnouncementSpeechLanguage() }.getOrNull()
        val isArabic = if (current == null) {
            Locale.getDefault().language.startsWith("ar", ignoreCase = true)
        } else {
            current.startsWith("ar", ignoreCase = true)
        }
        btnSpeechLanguage.text = if (isArabic) {
            getString(R.string.speech_language_to_en)
        } else {
            getString(R.string.speech_language_to_ar)
        }
        btnSpeechLanguage.setOnClickListener {
            // يُقرأ الوضع الحالي في كل ضغطة (لا قيمة مأسورة) ثم يُقلب نحو المعاكس
            val lang = runCatching { settings.getAnnouncementSpeechLanguage() }.getOrNull()
            val isArabicNow = if (lang == null) {
                Locale.getDefault().language.startsWith("ar", ignoreCase = true)
            } else {
                lang.startsWith("ar", ignoreCase = true)
            }
            val next = if (isArabicNow) "en" else "ar"
            runCatching { settings.setAnnouncementSpeechLanguage(next) }
            // النص يعرض الإجراء نحو المعاكس للحالة الجديدة
            btnSpeechLanguage.text = if (next == "ar") {
                getString(R.string.speech_language_to_en)
            } else {
                getString(R.string.speech_language_to_ar)
            }
            // إعلان مسموع للبدّل حتى يعرف المستمع أن اللغة تبدّلت (TalkBack/قراءة الشاشة)
            btnSpeechLanguage.announceCompat(
                getString(R.string.speech_language_switch) + " — " + btnSpeechLanguage.text
            )
            accordion.updateSectionStatuses()
        }
    }

    private fun quietWatcher(save: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val hour = s?.toString()?.trim()?.toIntOrNull()
            if (hour != null && hour in 0..23) save(hour)
        }
    }
}
