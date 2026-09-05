package com.aymankhattab.nateq.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.speech.tts.Voice
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
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.providers.EnginePicker
import com.aymankhattab.nateq.util.AnnouncementSpeaker
import java.util.Calendar
import java.util.Locale

/**
 * شاشة الإعدادات الرئيسية - تتضمن:
 * 1. اختيار المحرك ثم اللغة ثم الصوت (هرمية)
 * 2. إعدادات الفئات (صوت، سرعة، نبرة، مستوى صوت)
 * 3. إعدادات إعلان الوقت
 * 4. قاموس النطق الشخصي
 */
class VoiceSelectionFragment : Fragment(R.layout.fragment_voice_selection) {

    private lateinit var settings: SettingsRepository
    private lateinit var pronunciationDict: PronunciationDictionary

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvPronunciationDict: RecyclerView

    private lateinit var spinnerEngine: Spinner
    private data class EngineInfo(val packageName: String, val label: String)
    private val engines = mutableListOf<EngineInfo>()


    // languageTags اللغات المعروضة حالياً في حوار التحويل (حسب ترتيب صفوف اللغة)،
    // تُملأ في (showConvertDialog) ليُستخرج languageTag الصحيح عند الحفظ.
    private var convertDialogLangTags: List<String> = emptyList()

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
        }
    }

    // ===== النسخ الاحتياطي / الاستعادة الكاملان (إعدادات + قاموس + أسماء متصلين) =====
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val json = buildBackupJson()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            Toast.makeText(
                requireContext(),
                if (ok) R.string.backup_saved else R.string.backup_failed,
                Toast.LENGTH_SHORT
            ).show()
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
                if (applyBackupJson(text)) {
                    refreshAllSettingsUi()
                    AnnouncementSchedulerService.requestStart(requireContext())
                    Toast.makeText(requireContext(), R.string.restore_done, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var announcementSpeaker: AnnouncementSpeaker? = null

    // كتالوج صريح للأصوات المدعومة في ناطق (يتطابق مع onGetVoices)
    // مبسّطة إلى لغتين فقط: "العربية" و"الإنجليزية"
    private data class NateqVoice(
        val name: String,
        val languageTag: String,
        val displayName: String,
        val locale: Locale
    )

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
            updateSectionStatuses()
            val msg = when {
                callLogGranted -> R.string.caller_permission_granted_both
                phoneGranted -> R.string.caller_permission_granted_phone_only
                else -> R.string.caller_permission_granted_contacts_only
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        } else {
            switchCallerAnnouncement.isChecked = false
            runCatching { settings.setCallerAnnouncementEnabled(false) }
            updateSectionStatuses()
            Toast.makeText(requireContext(), R.string.caller_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    /** يمنع مناداة المستمع من رد الطلب (تفادي إعادة طلب الأذونات دورياً) */
    private var callerSwitchGuard = false

    // ===== الأكورديون: أقسام قابلة للطي بعنوان حالة (يُفتح قسم واحد فقط) =====
    private data class AccordionEntry(
        val header: View,
        val arrow: TextView,
        val status: TextView?,
        val content: View
    )

    private val accordionEntries = mutableListOf<AccordionEntry>()

    // ===== التنقّل بين المستويين: القائمة الرئيسية وشاشة القسم =====
    private var llDetailBack: android.widget.LinearLayout? = null
    private var llMasterSwitch: android.widget.LinearLayout? = null
    private var svSettingsScroll: androidx.core.widget.NestedScrollView? = null
    private var tvSectionTitle: TextView? = null
    private var tvBackToList: com.google.android.material.button.MaterialButton? = null
    private var detailOpen = false
    private var dictContentPriorVisibility = View.GONE

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (detailOpen) showHome()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        pronunciationDict = PronunciationDictionary(requireContext())

        // تهيئة الأصوات هنا بعد الانضمام للسياق (لا يجوز في مُنشئ/خاصية تستدعي getString())
        nateqVoices = listOf(
            NateqVoice("nateq-ar-local", "ar", getString(R.string.voice_name_arabic), Locale("ar")),
            NateqVoice("nateq-en-local", "en", getString(R.string.voice_name_english), Locale("en"))
        )

        // صندوق المحركات داخل قسم اللغة الأولى/الثانية (اختيار محرك TTS للنطق)
        spinnerEngine = view.findViewById(R.id.spinner_engine)
        setupEngineSpinner()

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
            } else {
                runCatching {
                    requireContext().stopService(
                        Intent(requireContext(), AnnouncementSchedulerService::class.java)
                    )
                }
            }
            updateSectionStatuses()
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
        setupAutoConvertUI()
        setupSaveAndResetButtons()
        setupBackupRestoreButtons()
        setupAccordionSections()
        updateSectionStatuses()
        llDetailBack = view.findViewById(R.id.ll_detail_back)
        llMasterSwitch = view.findViewById(R.id.ll_master_switch)
        svSettingsScroll = view.findViewById(R.id.sv_settings_scroll)
        tvSectionTitle = view.findViewById(R.id.tv_detail_section_title)
        tvBackToList = view.findViewById(R.id.btn_back_to_list)
        view.findViewById<View>(R.id.btn_back_to_list).setOnClickListener { showHome() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        showHome()
    }

    // ===== صندوق المحركات (اختيار محرك TTS ضمن قسم اللغة الأولى/الثانية) =====
    private fun setupEngineSpinner() {
        engines.clear()

        // محركات النظام المتاحة عبر TTS_SERVICE intent.
        // نستخدم MATCH_ALL ليظهر القارئان/المحركات غير-المُصدَّرة (مثل eSpeak
        // داخل Jieshuo/TalkMan) التي لا تُستعلم على أندرويد 7+ دونها.
        engines.addAll(discoverEngines())

        if (engines.isEmpty()) {
            spinnerEngine.adapter = simpleAdapter(listOf(getString(R.string.no_voices_available)))
        } else {
            spinnerEngine.adapter = simpleAdapter(engines.map { it.label })
            spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onEngineSelected(engines[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // نستعيد المحرك المحفوظ، أو نفضّل MultiTTS إن لم يكن محفوظاً.
            val saved = settings.getSelectedEnginePackage()
            val target = engineIndexOf(saved)
                ?: if (saved == null) engineIndexOf("org.nobody.multitts") else null
            if (target != null && target < engines.size) {
                spinnerEngine.setSelection(target)
            }
        }
    }

    /** فهرس المحرك بمطابقة الحزمة في قائمة المحركات */
    private fun engineIndexOf(pkg: String?): Int? {
        if (pkg == null) return null
        val idx = engines.indexOfFirst { it.packageName == pkg }
        return if (idx >= 0) idx else null
    }

    private fun onEngineSelected(engine: EngineInfo) {
        // عند اختيار محرك نُخزّنه ليستخدمه مزوّد الصوت عند النطق.
        settings.setSelectedEnginePackage(engine.packageName)
    }

    /**
     * يحمّل كل اللغات التي يوفّرها محرك نظامي معيّن، ويُفشل بأمان إن تعذّر.
     * يستخدم نفس نهج (queryIntentServices) الذي كشف "smart voice" تلقائياً:
     * المحرك يُتعامل معه كصندوق معتم، فاللغات والأصوات تُستعرض ديناميكياً.
     */
    @Suppress("DEPRECATION")
    private fun loadEngineCatalog(
        enginePackage: String,
        onResult: (voices: List<Voice>) -> Unit
    ) {
        lateinit var eng: TextToSpeech
        eng = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = runCatching { eng.setEngineByPackageName(enginePackage) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (result == TextToSpeech.SUCCESS) {
                    val avail = runCatching { eng.getVoices() }.getOrDefault(emptySet())
                    onResult(avail.toList())
                } else {
                    onResult(emptyList())
                }
                eng.shutdown()
            } else {
                Log.w("LordTTS", "تعذّر إنشاء TextToSpeech لمحرك: $enginePackage (status=$status)")
                onResult(emptyList())
            }
        }
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            items
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    // ===== التحويل التلقائي بين اللغتين =====
    private fun setupAutoConvertUI() {
        val chk = view?.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_auto_convert)
        val btn1 = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang1)
        val btn2 = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang2)

        chk?.isChecked = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        chk?.setOnClickListener { v ->
            val enabled = (v as com.google.android.material.checkbox.MaterialCheckBox).isChecked
            runCatching { settings.setAutoConvertEnabled(enabled) }
            refreshConvertButtonsEnabled(enabled)
            updateSectionStatuses()
        }

        btn1?.setOnClickListener { showConvertDialog(1) }
        btn2?.setOnClickListener { showConvertDialog(2) }

        refreshConvertButtonsEnabled(runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false))
    }

    /** يُفعّل/يعطّل زري اللغتين تبعاً لمربع التفعيل */
    private fun refreshConvertButtonsEnabled(enabled: Boolean) {
        val btn1 = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang1)
        val btn2 = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang2)
        btn1?.isEnabled = enabled
        btn2?.isEnabled = enabled
    }

    /** قائمة كل المحركات المتاحة عبر INTENT_ACTION_TTS_SERVICE (MATCH_ALL) */
    private fun discoverEngines(): List<EngineInfo> {
        return EnginePicker.installedEngines(requireContext())
            .map { EngineInfo(it.packageName, it.label) }
    }

    /**
     * يفتح حوار إعداد إحدى اللغتين (1 أو 2) فيقوم الكفيف بتحديد:
     * المحرك ← اللغة ← الصوت، ثم مستوى الصوت والنبرة والسرعة، وحفظ.
     */
    private fun showConvertDialog(lang: Int) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_auto_convert, null)
        val spEngine = dialogView.findViewById<Spinner>(R.id.spinner_convert_engine)
        val spLang = dialogView.findViewById<Spinner>(R.id.spinner_convert_language)
        val spVoice = dialogView.findViewById<Spinner>(R.id.spinner_convert_voice)
        val seekVol = dialogView.findViewById<SeekBar>(R.id.seek_convert_volume)
        val tvVol = dialogView.findViewById<TextView>(R.id.tv_convert_volume_value)
        val seekPitch = dialogView.findViewById<SeekBar>(R.id.seek_convert_pitch)
        val tvPitch = dialogView.findViewById<TextView>(R.id.tv_convert_pitch_value)
        val seekRate = dialogView.findViewById<SeekBar>(R.id.seek_convert_rate)
        val tvRate = dialogView.findViewById<TextView>(R.id.tv_convert_rate_value)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_save)
        val btnPlay = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_play)
        val tvSaved = dialogView.findViewById<TextView>(R.id.tv_convert_dialog_saved)

        // استرجاع القيم المحفوظة (أو الافتراضية)
        val savedEngine = if (lang == 1) settings.getConvertEngine1() else settings.getConvertEngine2()
        val savedLang = if (lang == 1) settings.getConvertLanguageTag1() else settings.getConvertLanguageTag2()
        val savedVoice = if (lang == 1) settings.getConvertVoice1() else settings.getConvertVoice2()
        val savedVol = if (lang == 1) settings.getConvertVolume1() else settings.getConvertVolume2()
        val savedPitch = if (lang == 1) settings.getConvertPitch1() else settings.getConvertPitch2()
        val savedRate = if (lang == 1) settings.getConvertRate1() else settings.getConvertRate2()

        // ===== المحركات =====
        val engines = discoverEngines()
        if (engines.isEmpty()) {
            spEngine.adapter = simpleAdapter(listOf(getString(R.string.no_voices_available)))
        } else {
            spEngine.adapter = simpleAdapter(engines.map { it.label })
            spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val engPkg = engines[position].packageName
                    spLang.adapter = simpleAdapter(listOf(getString(R.string.auto_convert_loading)))
                    spVoice.adapter = simpleAdapter(emptyList())
                    loadEngineCatalog(engPkg) { voices ->
                        // جميع اللغات التي يوفرها هذا المحرك (تلقائياً مهما كان عددها)،
                        // مجمعةً بحسب اللسان دون البلد حتى يظهر لكل لغة خيارٌ واحد
                        // وتُعرَض كل أصواتها.
                        val langs = voices.distinctBy { it.locale.language }
                        // نحفظ الـ languageTag لكل صف لغة (بالترتيب) ليُستخرج موثوقاً عند الحفظ.
                        convertDialogLangTags = langs.map { it.locale.language }
                        spLang.adapter = simpleAdapter(
                            if (langs.isEmpty()) listOf(getString(R.string.auto_convert_none))
                            else langs.map { displayLang(it.locale) }
                        )
                        // اختيار اللغة المحفوظة إن وجدت (باللسان، لأننا نخزن كود اللغة فقط)
                        val savedTagIndex = if (savedLang != null)
                            langs.indexOfFirst { it.locale.language == savedLang } else -1

                        spLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                if (position < 0 || position >= langs.size) {
                                    spVoice.adapter = simpleAdapter(emptyList())
                                    return
                                }
                                val langCode = langs[position].locale.language
                                // كل الأصوات التي تلائم هذا اللسان (مهما كان البلد) تُعرض كلها،
                                // حتى لا يظهر صوتٌ واحد فقط لأن البلد قسّمها.
                                val matching = voices.filter {
                                    it.locale.language == langCode
                                }
                                spVoice.adapter = simpleAdapter(
                                    if (matching.isEmpty()) listOf(getString(R.string.auto_convert_none))
                                    else matching.map { it.name }
                                )
                                if (savedVoice != null) {
                                    val vi = matching.indexOfFirst { it.name == savedVoice }
                                    if (vi >= 0) spVoice.setSelection(vi)
                                }
                            }
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                        }
                        if (savedTagIndex >= 0) spLang.setSelection(savedTagIndex)
                        else spLang.setSelection(0)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            // اختيار المحرك المحفوظ
            if (savedEngine != null) {
                val ei = engines.indexOfFirst { it.packageName == savedEngine }
                if (ei >= 0) spEngine.setSelection(ei)
            }
        }

        // ===== مستوى الصوت / النبرة / السرعة =====
        seekVol.progress = (savedVol * 100).toInt().coerceIn(0, 100)
        tvVol.text = "${(savedVol * 100).toInt()}%"
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvVol.text = "$progress%"
                if (fromUser) seekBar.announceForAccessibility("$progress%")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekPitch.progress = (savedPitch * 100).toInt().coerceIn(0, 200)
        tvPitch.text = String.format(java.util.Locale.US, "%.1fx", savedPitch)
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                tvPitch.text = String.format(java.util.Locale.US, "%.1fx", v)
                if (fromUser) seekBar.announceForAccessibility(String.format(java.util.Locale.US, "%.1fx", v))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekRate.progress = (savedRate * 100).toInt().coerceIn(0, 200)
        tvRate.text = String.format(java.util.Locale.US, "%.1fx", savedRate)
        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                tvRate.text = String.format(java.util.Locale.US, "%.1fx", v)
                if (fromUser) seekBar.announceForAccessibility(String.format(java.util.Locale.US, "%.1fx", v))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // ===== زر حفظ: يخزّن كل قيم هذه اللغة =====
        btnSave.setOnClickListener {
            val engIdx = spEngine.selectedItemPosition
            val langIdx = spLang.selectedItemPosition

            // الأشرطة (سرعة/نبرة/صوت) تُحفظ دائماً مهما كانت حالة المحرك/اللغة.
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f

            // المحرك/اللغة/الصوت تُحفظ فقط عند اختيار صالح.
            val canSaveEngine = engIdx >= 0 && engines.isNotEmpty() && langIdx >= 0
            val engPkg = if (canSaveEngine) engines[engIdx].packageName else null
            val langTag = if (canSaveEngine) spLangAdapterTag(spLang) else null
            val voiceName = if (canSaveEngine) spVoice.selectedItem?.toString() else null

            if (lang == 1) {
                if (canSaveEngine) {
                    settings.setConvertEngine1(engPkg)
                    settings.setConvertLanguageTag1(langTag)
                    settings.setConvertVoice1(voiceName)
                }
                settings.setConvertVolume1(volume)
                settings.setConvertPitch1(pitch)
                settings.setConvertRate1(rate)
            } else {
                if (canSaveEngine) {
                    settings.setConvertEngine2(engPkg)
                    settings.setConvertLanguageTag2(langTag)
                    settings.setConvertVoice2(voiceName)
                }
                settings.setConvertVolume2(volume)
                settings.setConvertPitch2(pitch)
                settings.setConvertRate2(rate)
            }

            tvSaved.text = getString(R.string.auto_convert_saved)
            tvSaved.announceForAccessibility(getString(R.string.auto_convert_saved))
        }

        // ===== زر استماع (تجربة) =====
        btnPlay.setOnClickListener {
            val engIdx = spEngine.selectedItemPosition
            val voiceName = spVoice.selectedItem?.toString() ?: return@setOnClickListener
            if (engIdx < 0 || engines.isEmpty() || voiceName.isBlank()) return@setOnClickListener
            val engPkg = engines[engIdx].packageName
            val rate = seekRate.progress / 100f
            val pitch = seekPitch.progress / 100f
            val volume = seekVol.progress / 100f
            playbackPreview(engPkg, voiceName, volume, pitch, rate)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.auto_convert_dialog_title) + " — " +
                getString(if (lang == 1) R.string.auto_convert_lang1_button else R.string.auto_convert_lang2_button))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.reset_cancel), null)
            .show()
    }

    /** إرجاع languageTag للصف المحدد في spinner لغة الحوار (بشكل موثوق) */
    private fun spLangAdapterTag(spLang: Spinner): String? {
        val idx = spLang.selectedItemPosition
        // نستعيد الـ languageTag الحقيقي من القائمة المحفوظة عند بناء الحوار؛
        // وليس من الاسم المعروض لأنه لا يحمل الكود بدقة.
        return if (idx in convertDialogLangTags.indices) convertDialogLangTags[idx]
            else runCatching { spLang.selectedItem?.toString() }.getOrNull()
    }

    /** صيغة عرضٍ للغة: اسم وعرض البلاد/اللغة معاً */
    private fun displayLang(locale: java.util.Locale): String {
        val l = locale
        return "${l.displayName} (${l.country} ${l.language})".trim()
    }

    /** يُشغّل تكليفاً تجريبياً عبر محرك مؤقت بأية القيم المختارة دون حفظ */
    @Suppress("DEPRECATION")
    private fun playbackPreview(enginePkg: String, voiceName: String, volume: Float, pitch: Float, rate: Float) {
        lateinit var previewTts: TextToSpeech
        previewTts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { previewTts.setEngineByPackageName(enginePkg) }
                try {
                    val avail = runCatching { previewTts.getVoices() }.getOrDefault(emptySet())
                    val voice = avail.firstOrNull { it.name == voiceName }
                    if (voice != null) {
                        // نضبط المحرك على لسان الصوت المختار حتى لا يقرأ النص
                        // بلغة المحرك الافتراضية (مثلاً الإنجليزية رغم اختيار العربي).
                        runCatching { previewTts.setVoice(voice) }
                        runCatching { previewTts.language = voice.locale }
                    }
                } catch (_: Exception) {}
                previewTts.setSpeechRate(rate)
                runCatching { previewTts.setPitch(pitch) }
                // نمرر مستوى الصوت للمحرك عبر المعاملات (كان بلا مستوى صوت إطلاقاً)
                // ليقترب ناتج المعاينة من النطق الفعلي الذي يطبق نفس القيم.
                val params = android.os.Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                }
                // نعرض عينة بنفس لغة الصوت: عربي إن كان الصوت عربياً وإلا إنجليزي.
                // (سبق: كان النص تجريبياً إنجليزياً دائماً فبدا للمستخدم أن الصوت إنجليزي.)
                val sampleText = if (voiceName.lowercase().contains("ar") || voiceName.lowercase().contains("arab"))
                    getString(R.string.sample_text_preview_ar)
                else
                    getString(R.string.sample_text_default_en)
                previewTts.speak(
                    sampleText,
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    "preview"
                )
                previewTts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    @Deprecated("Java Deprecated")
                    override fun onDone(utteranceId: String?) { previewTts.shutdown() }

                    @Deprecated("Java Deprecated")
                    override fun onError(utteranceId: String?) { previewTts.shutdown() }
                })
            }
        }
    }

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
                }
                runCatching { settings.setCustomCallerNames(newNames) }
                if (!duplicate) {
                    Toast.makeText(requireContext(), R.string.caller_names_saved, Toast.LENGTH_SHORT).show()
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
            updateSectionStatuses()
        }
        switchTime24h.isChecked =
            runCatching { settings.isTime24Hour() }.getOrDefault(false)
        switchTime24h.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTime24Hour(checked) }
        }
        switchHijriDate.isChecked =
            runCatching { settings.isHijriDateEnabled() }.getOrDefault(false)
        switchHijriDate.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setHijriDateEnabled(checked) }
        }
        switchClockWidget.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setClockWidgetEnabled(checked) }
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
                updateSectionStatuses()
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

    // ===== الأكورديون: بطاقات الأقسام والتنقّل لشاشة القسم =====
    private fun accordionEntry(
        headerId: Int,
        arrowId: Int,
        statusId: Int,
        contentId: Int,
        base: String
    ) {
        val header = view?.findViewById<View>(headerId) ?: return
        val arrow = view?.findViewById<TextView>(arrowId) ?: return
        val status = view?.findViewById<TextView>(statusId)
        val content = view?.findViewById<View>(contentId) ?: return
        header.tag = base
        accordionEntries.add(AccordionEntry(header, arrow, status, content))
        // في القائمة الرئيسية: فتح شاشة القسم عند الضغط على البطاقة
        header.setOnClickListener { openSection(content) }
        content.visibility = View.GONE
        arrow.text = sectionArrowGlyph()
        refreshCardDesc(content)
    }

    /** سهم بطاقة القسم: يشير لليسار في RTL (اتجاه التقدّم) ولليمين في LTR */
    private fun sectionArrowGlyph(): String {
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) "‹" else "›"
    }

    /** وصف وصول موحّد لبطاقة القسم: الأساس + الحالة */
    private fun refreshCardDesc(content: View) {
        val e = accordionEntries.firstOrNull { it.content === content } ?: return
        val base = e.header.tag as? String ?: ""
        val statusText = e.status?.text?.toString()?.trim().orEmpty()
        e.header.contentDescription = if (statusText.isNotEmpty()) {
            base + "، " + statusText
        } else {
            base
        }
    }

    private fun setSectionStatus(contentId: Int, text: String) {
        val e = accordionEntries.firstOrNull { it.content.id == contentId } ?: return
        e.status?.text = text
        refreshCardDesc(e.content)
    }

    /** فتح شاشة قسم فرعي: إخفاء كل شيء عدا القسم المطلوب + شريط العودة */
    private fun openSection(content: View) {
        detailOpen = true
        backCallback.isEnabled = true
        llDetailBack?.visibility = View.VISIBLE
        llMasterSwitch?.visibility = View.GONE
        dictContentPriorVisibility = llDictContent.visibility
        llDictHeader.visibility = View.GONE
        llDictContent.visibility = View.GONE
        setHomeActionsVisible(false)
        view?.findViewById<View>(R.id.btn_toggle_language)?.visibility = View.GONE
        setSectionDividersVisible(false)
        svSettingsScroll?.scrollTo(0, 0)
        var sectionName = ""
        for (e in accordionEntries) {
            val target = e.content === content
            if (target) sectionName = e.header.tag as? String ?: ""
            e.header.visibility = if (target) View.VISIBLE else View.GONE
            e.status?.visibility = if (target) View.VISIBLE else View.GONE
            e.content.visibility = if (target) View.VISIBLE else View.GONE
        }
        tvSectionTitle?.text = sectionName
        // إعلان مسموع لفتح القسم + نقل تركيز الوصول إلى زر العودة
        tvBackToList?.let { bt ->
            bt.announceForAccessibility(getString(R.string.section_opened, sectionName))
            focusForAccessibility(bt)
        }
    }

    /** العودة إلى القائمة الرئيسية: تُظهر كل البطاقات وتطوي المحتويات */
    private fun showHome() {
        val returning = detailOpen
        detailOpen = false
        backCallback.isEnabled = false
        llDetailBack?.visibility = View.GONE
        llMasterSwitch?.visibility = View.VISIBLE
        llDictHeader.visibility = View.VISIBLE
        llDictContent.visibility = dictContentPriorVisibility
        setHomeActionsVisible(true)
        view?.findViewById<View>(R.id.btn_toggle_language)?.visibility = View.VISIBLE
        setSectionDividersVisible(true)
        svSettingsScroll?.scrollTo(0, 0)
        for (e in accordionEntries) {
            e.header.visibility = View.VISIBLE
            e.status?.visibility = View.VISIBLE
            e.content.visibility = View.GONE
            e.arrow.visibility = View.VISIBLE
            e.arrow.text = sectionArrowGlyph()
        }
        // عند العودة من قسم فقط: نعيد التركيز للمفتاح الرئيسي مع إعلان مسموع
        if (returning) {
            switchAllAnnouncements.announceForAccessibility(getString(R.string.back_to_home))
            focusForAccessibility(switchAllAnnouncements)
        }
    }

    /** نقل تركيز الوصول إلى عرض معيّن عبر واجهات عامة */
    private fun focusForAccessibility(target: View) {
        target.post {
            target.requestFocus(View.FOCUS_FORWARD)
            target.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
        }
    }

    /** إظهار/إخفاء الفواصل بين بطاقات القائمة (تُخفى داخل شاشات الأقسام) */
    private fun setSectionDividersVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        val root = view ?: return
        forEachView(root) { if (it.tag == "section_divider") it.visibility = v }
    }

    /** تجوال الشجرة كاملة وتنفيذ إجراء على كل عرض (بديل findViewsWithTag) */
    private fun forEachView(current: View, action: (View) -> Unit) {
        action(current)
        if (current is android.view.ViewGroup) {
            for (i in 0 until current.childCount) {
                current.getChildAt(i).let { forEachView(it, action) }
            }
        }
    }

    /** إظهار/إخفاء أزرار الحفظ والاستعادة في القائمة الرئيسية */
    private fun setHomeActionsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        view?.findViewById<View>(R.id.btn_save_settings)?.visibility = v
        view?.findViewById<View>(R.id.btn_reset_settings)?.visibility = v
        view?.findViewById<View>(R.id.btn_backup_settings)?.visibility = v
        view?.findViewById<View>(R.id.btn_restore_settings)?.visibility = v
    }

    /** تسجيل الأقسام التسعة كبطاقات في القائمة الرئيسية (تُفتح كل منها شاشة فرعية) */
    private fun setupAccordionSections() {
        accordionEntries.clear()
        val engine = getString(R.string.section_voice_selection)
        val cats = getString(R.string.voice_category_default)
        val time = getString(R.string.section_time_announcement)
        val num = getString(R.string.section_number_reading)
        val battery = getString(R.string.section_battery_announcement)
        val notif = getString(R.string.section_notification_reading)
        val caller = getString(R.string.section_caller_announcement)
        val sms = getString(R.string.section_sms_reading)
        val general = getString(R.string.section_general_settings)
        accordionEntry(
            R.id.ll_engine_header, R.id.tv_engine_arrow,
            R.id.tv_engine_status, R.id.ll_engine_content,
            engine
        )
        accordionEntry(
            R.id.ll_categories_header, R.id.tv_categories_arrow,
            R.id.tv_categories_status, R.id.ll_categories_content,
            cats
        )
        accordionEntry(
            R.id.ll_time_announcement_header, R.id.tv_time_announcement_arrow,
            R.id.tv_time_announcement_status, R.id.ll_time_announcement_settings,
            time
        )
        accordionEntry(
            R.id.ll_number_reading_header, R.id.tv_number_reading_arrow,
            R.id.tv_number_reading_status, R.id.ll_numbers_content,
            num
        )
        accordionEntry(
            R.id.ll_battery_announcement_header, R.id.tv_battery_announcement_arrow,
            R.id.tv_battery_announcement_status, R.id.ll_battery_announcement_settings,
            battery
        )
        accordionEntry(
            R.id.ll_notification_reading_header, R.id.tv_notification_reading_arrow,
            R.id.tv_notification_reading_status, R.id.ll_notification_reading_settings,
            notif
        )
        accordionEntry(
            R.id.ll_caller_announcement_header, R.id.tv_caller_announcement_arrow,
            R.id.tv_caller_announcement_status, R.id.ll_caller_announcement_settings,
            caller
        )
        accordionEntry(
            R.id.ll_sms_reading_header, R.id.tv_sms_reading_arrow,
            R.id.tv_sms_reading_status, R.id.ll_sms_reading_settings,
            sms
        )
        accordionEntry(
            R.id.ll_general_settings_header, R.id.tv_general_settings_arrow,
            R.id.tv_general_settings_status, R.id.ll_general_settings_content,
            general
        )
    }

    /** تحديث أسطر الحالة لكل قسم (يُستدعى عند التهيئة وبعد كل تغيير أساسي) */
    private fun updateSectionStatuses() {
        setSectionStatus(R.id.ll_engine_content, buildEngineStatus())
        setSectionStatus(R.id.ll_categories_content, buildCategoriesStatus())
        setSectionStatus(R.id.ll_time_announcement_settings, buildTimeStatus())
        setSectionStatus(R.id.ll_numbers_content, buildNumberStatus())
        setSectionStatus(R.id.ll_battery_announcement_settings, buildBatteryStatus())
        setSectionStatus(R.id.ll_notification_reading_settings, buildNotificationStatus())
        setSectionStatus(R.id.ll_caller_announcement_settings, buildCallerStatus())
        setSectionStatus(R.id.ll_sms_reading_settings, buildSmsStatus())
        setSectionStatus(R.id.ll_general_settings_content, buildGeneralStatus())
    }

    private fun buildEngineStatus(): String {
        val auto = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        val engine = engines.getOrNull(spinnerEngine.selectedItemPosition)?.label
            ?: getString(R.string.no_voices_available)
        val autoLabel = if (auto) getString(R.string.toggle_on)
        else getString(R.string.toggle_off)
        return getString(R.string.auto_convert_enabled) + ": " +
            autoLabel + "، " + engine
    }

    private fun buildCategoriesStatus(): String {
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(SettingsRepository.VOICE_CATEGORY_DEFAULT)
        }.getOrNull()
        val name = nateqVoices.firstOrNull { it.name == saved }?.displayName
            ?: nateqVoices.firstOrNull()?.displayName
            ?: getString(R.string.no_voices_available)
        return getString(R.string.voice_category_default) + ": " + name
    }

    private fun buildTimeStatus(): String {
        val enabled = runCatching { settings.isTimeAnnouncementEnabled() }
            .getOrDefault(true)
        val interval = runCatching { settings.getTimeAnnouncementInterval() }
            .getOrDefault(30)
        val intervalLabel = getString(
            when (interval) {
                15 -> R.string.time_interval_15
                30 -> R.string.time_interval_30
                45 -> R.string.time_interval_45
                else -> R.string.time_interval_60
            }
        )
        val quietStart = runCatching { settings.getQuietStartForDay(Calendar.DAY_OF_WEEK) }
            .getOrDefault(23)
        val quietEnd = runCatching { settings.getQuietEndForDay(Calendar.DAY_OF_WEEK) }
            .getOrDefault(7)
        return buildString {
            val on = if (enabled) getString(R.string.toggle_on)
            else getString(R.string.toggle_off)
            append(on)
            append("، ").append(intervalLabel)
            append("، ").append(getString(R.string.time_quiet_schedule_title))
            append(": ").append(quietStart).append("/").append(quietEnd)
        }
    }

    private fun buildNumberStatus(): String {
        val mode = runCatching { settings.getNumberReadingMode() }
            .getOrDefault(1).coerceIn(1, 8)
        val label = getString(
            when (mode) {
                1 -> R.string.number_mode_single
                2 -> R.string.number_mode_pairs
                3 -> R.string.number_mode_triples
                4 -> R.string.number_mode_quadruples
                5 -> R.string.number_mode_quintuples
                6 -> R.string.number_mode_sextuples
                7 -> R.string.number_mode_septuples
                else -> R.string.number_mode_octuples
            }
        )
        return getString(R.string.number_reading_mode) + ": " + label
    }

    private fun buildBatteryStatus(): String {
        val enabled = runCatching { settings.isBatteryAnnouncementEnabled() }
            .getOrDefault(false)
        val levels = runCatching { settings.getBatteryAnnouncementLevels() }
            .getOrDefault(emptySet())
        val on = if (enabled) getString(R.string.toggle_on)
        else getString(R.string.toggle_off)
        val summary = levels.sortedDescending().joinToString("، ") { "$it%" }
        return buildString {
            append(on)
            if (summary.isNotEmpty()) append("، ").append(summary)
        }
    }

    private fun buildNotificationStatus(): String {
        val enabled = runCatching { settings.isNotificationReadingEnabled() }
            .getOrDefault(false)
        val sel = runCatching { settings.getNotificationAppsSelection() }
            .getOrDefault(SettingsRepository.DEFAULT_NOTIFICATION_APPS)
        val on = if (enabled) getString(R.string.toggle_on)
        else getString(R.string.toggle_off)
        val apps = if (SettingsRepository.NOTIF_READ_ALL in sel) {
            getString(R.string.notification_apps_all)
        } else {
            sel.size.toString()
        }
        return buildString {
            append(on)
            append("، ").append(getString(R.string.notification_apps_title))
            append(": ").append(apps)
        }
    }

    private fun buildCallerStatus(): String {
        val enabled = runCatching { settings.isCallerAnnouncementEnabled() }
            .getOrDefault(false)
        val repeat = runCatching { settings.getCallerAnnouncementRepeat() }
            .getOrDefault(1).coerceIn(1, 5)
        val on = if (enabled) getString(R.string.toggle_on)
        else getString(R.string.toggle_off)
        val label = getString(
            when (repeat) {
                1 -> R.string.repeat_once
                2 -> R.string.repeat_twice
                3 -> R.string.repeat_3
                4 -> R.string.repeat_4
                else -> R.string.repeat_5
            }
        )
        return on + "، " + label
    }

    private fun buildSmsStatus(): String {
        val mode = runCatching { settings.getSmsReadingMode() }.getOrDefault("off")
        val label = getString(
            when (mode) {
                "full" -> R.string.sms_mode_full
                "source" -> R.string.sms_mode_source
                else -> R.string.sms_mode_off
            }
        )
        return getString(R.string.sms_reading_mode) + ": " + label
    }

    private fun buildGeneralStatus(): String {
        val rate = runCatching { settings.getDefaultSpeechRate() }.getOrDefault(1.0f)
        val volume = runCatching { settings.getDefaultVolume() }.getOrDefault(1.0f)
        val rateText = String.format(java.util.Locale.US, "%.1fx", rate)
        val volumeText = (volume * 100).toInt().toString() + "%"
        return getString(R.string.default_speech_rate_label) + ": " +
            rateText + "، " + volumeText
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
            updateSectionStatuses()
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
                runCatching { settings.setBatteryAnnouncementRate(value) }
                if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // مستوى الصوت
        val batteryVolume = runCatching { settings.getBatteryAnnouncementVolume() }.getOrDefault(1.0f)
        tvBatteryVolumeValue.text = "${(batteryVolume * 100).toInt()}%"
        seekBatteryVolume.progress = (batteryVolume * 100).toInt().coerceIn(0, 100)
        seekBatteryVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvBatteryVolumeValue.text = "$progress%"
                runCatching { settings.setBatteryAnnouncementVolume(progress / 100f) }
                if (fromUser) seekBar.announceForAccessibility("$progress%")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // ===== قراءة الإشعارات =====
    private fun setupNotificationReadingSettings() {
        // المفتاح الرئيسي
        switchNotificationReading.isChecked = runCatching { settings.isNotificationReadingEnabled() }.getOrDefault(false)
        switchNotificationReading.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setNotificationReadingEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(requireContext())
            updateSectionStatuses()
        }

        // فتح إعدادات إذن الوصول للإشعارات من النظام
        llNotificationListenerSettings.setOnClickListener {
            val granted = runCatching {
                com.aymankhattab.nateq.receivers.NateqNotificationListener.isPermissionGranted(requireContext())
            }.getOrDefault(false)
            if (granted) {
                Toast.makeText(requireContext(), R.string.notification_reading_enabled_summary, Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), R.string.notification_permission_needed, Toast.LENGTH_LONG).show()
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
                updateSectionStatuses()
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
                updateSectionStatuses()
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
                updateSectionStatuses()
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
                runCatching { settings.setCallerAnnouncementRate(value) }
                if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // مستوى الصوت
        val callerVolume = runCatching { settings.getCallerAnnouncementVolume() }.getOrDefault(1.0f)
        tvCallerVolumeValue.text = "${(callerVolume * 100).toInt()}%"
        seekCallerVolume.progress = (callerVolume * 100).toInt().coerceIn(0, 100)
        seekCallerVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvCallerVolumeValue.text = "$progress%"
                runCatching { settings.setCallerAnnouncementVolume(progress / 100f) }
                if (fromUser) seekBar.announceForAccessibility("$progress%")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // ===== قراءة الرسائل الواردة =====
    private fun setupSmsReadingSettings() {
        // وضع القراءة: مفعل / معطل / قراءة مصدر الرسالة فقط
        val modes = listOf(
            getString(R.string.sms_mode_full),
            getString(R.string.sms_mode_off),
            getString(R.string.sms_mode_source)
        )
        spinnerSmsMode.adapter = simpleAdapter(modes)
        val savedMode = runCatching { settings.getSmsReadingMode() }.getOrDefault("off")
        val modeIndex = when (savedMode) {
            "full" -> 0
            "source" -> 2
            else -> 1 // "off"
        }
        spinnerSmsMode.setSelection(modeIndex)
        spinnerSmsMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> "full"
                    1 -> "off"
                    else -> "source"
                }
                runCatching { settings.setSmsReadingMode(mode) }
                if (mode != "off") AnnouncementSchedulerService.requestStart(requireContext())
                updateSectionStatuses()
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
                runCatching { settings.setSmsReadingRate(value) }
                if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // مستوى الصوت
        val smsVolume = runCatching { settings.getSmsReadingVolume() }.getOrDefault(1.0f)
        tvSmsVolumeValue.text = "${(smsVolume * 100).toInt()}%"
        seekSmsVolume.progress = (smsVolume * 100).toInt().coerceIn(0, 100)
        seekSmsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSmsVolumeValue.text = "$progress%"
                runCatching { settings.setSmsReadingVolume(progress / 100f) }
                if (fromUser) seekBar.announceForAccessibility("$progress%")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
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
                runCatching { settings.setDefaultSpeechRate(value) }
                if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
                updateSectionStatuses()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekDefaultPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvDefaultPitchValue.text = String.format(Locale.US, "%.1fx", value)
                runCatching { settings.setDefaultPitch(value) }
                if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
                updateSectionStatuses()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekDefaultVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvDefaultVolumeValue.text = "$progress%"
                runCatching { settings.setDefaultVolume(progress / 100f) }
                if (fromUser) seekBar.announceForAccessibility("$progress%")
                updateSectionStatuses()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
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
                    runCatching { settings.setSpeechRateForCategory(category, value) }
                    if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            holder.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress / 100f
                    holder.tvPitchValue.text = String.format(Locale.US, "%.1fx", value)
                    runCatching { settings.setPitchForCategory(category, value) }
                    if (fromUser) seekBar.announceForAccessibility(String.format(Locale.US, "%.1fx", value))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            holder.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    holder.tvVolumeValue.text = "$progress%"
                    runCatching { settings.setVolumeForCategory(category, progress / 100f) }
                    if (fromUser) seekBar.announceForAccessibility("$progress%")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
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
            getString(R.string.language_current_label) + " العربية — " + getString(R.string.language_change_to_en)
        } else {
            getString(R.string.language_current_label) + " English — " + getString(R.string.language_change_to_ar)
        }

        btnToggleLanguage.setOnClickListener {
            runCatching { settings.setAppLanguage(if (isArabic) "en" else "ar") }
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
                ?.announceForAccessibility(getString(R.string.saved_successfully))
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
                        ?.announceForAccessibility(getString(R.string.reset_done))
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
                }
                .setNegativeButton(R.string.reset_cancel, null)
                .show()
        }
    }

    // ===== النسخ الاحتياطي / الاستعادة =====
    private fun setupBackupRestoreButtons() {
        view?.findViewById<View>(R.id.btn_backup_settings)?.setOnClickListener {
            runCatching { createBackupLauncher.launch("nateq_backup.json") }
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
        updateSectionStatuses()
    }

    /** بناء ملف JSON كامل: إعدادات مصنفة الأنواع + القاموس + أسماء المتصلين. */
    private fun buildBackupJson(): String {
        return try {
            val root = org.json.JSONObject()
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())

            val settingsObj = org.json.JSONObject()
            settings.exportSettings().forEach { (key, value) ->
                val entry = org.json.JSONObject()
                when (value) {
                    is Float -> { entry.put("type", "float"); entry.put("value", value.toDouble()) }
                    is Int -> { entry.put("type", "int"); entry.put("value", value) }
                    is Long -> { entry.put("type", "int"); entry.put("value", value) }
                    is Boolean -> { entry.put("type", "bool"); entry.put("value", value) }
                    is String -> { entry.put("type", "string"); entry.put("value", value) }
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        for (item in value) arr.put(item.toString())
                        entry.put("type", "stringset"); entry.put("value", arr)
                    }
                    else -> return@forEach
                }
                settingsObj.put(key, entry)
            }
            root.put("settings", settingsObj)

            val dictArr = org.json.JSONArray()
            pronunciationDict.getAllEntries().forEach { (word, phon) ->
                dictArr.put(org.json.JSONArray().put(word).put(phon))
            }
            root.put("dictionary", dictArr)

            val callers = org.json.JSONObject()
            runCatching { settings.getCustomCallerNames() }.getOrDefault(emptyMap())
                .forEach { (num, name) -> callers.put(num, name) }
            root.put("callerNames", callers)

            root.toString()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * تطبيق نسخة احتياطية: يتحقق من البنية ثم يستعيد القاموس والأسماء ثم
     * الإعدادات (آخرها لأن استعادتها تمسح القرص) — وتُعقَّل قيم النطاقات
     * داخل SettingsRepository.importSettings.
     */
    private fun applyBackupJson(text: String): Boolean {
        return try {
            val root = org.json.JSONObject(text)
            if (root.optInt("version", 0) != 1) return false
            var applied = false

            val dictArr = root.optJSONArray("dictionary")
            if (dictArr != null) {
                val map = org.json.JSONObject()
                for (i in 0 until dictArr.length()) {
                    val pair = dictArr.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    map.put(pair.getString(0), pair.getString(1))
                }
                applied = pronunciationDict.importFromJson(map.toString()) || applied
            }

            val callers = root.optJSONObject("callerNames")
            if (callers != null) {
                val map = HashMap<String, String>()
                val names = callers.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    map[key] = callers.getString(key)
                }
                settings.setCustomCallerNames(map)
                applied = true
            }

            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null) {
                val restored = HashMap<String, Any>()
                val names = settingsObj.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    val entry = settingsObj.optJSONObject(key) ?: continue
                    when (entry.optString("type")) {
                        "int" -> restored[key] = entry.optLong("value").toInt()
                        "float" -> restored[key] = entry.optDouble("value", 0.0).toFloat()
                        "bool" -> restored[key] = entry.optBoolean("value")
                        "string" -> restored[key] = entry.optString("value")
                        "stringset" -> {
                            val arr = entry.optJSONArray("value") ?: continue
                            val set = HashSet<String>()
                            for (j in 0 until arr.length()) set.add(arr.getString(j))
                            restored[key] = set
                        }
                    }
                }
                if (restored.isNotEmpty()) {
                    applied = settings.importSettings(restored) || applied
                }
            }

            applied
        } catch (t: Throwable) {
            false
        }
    }

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
            btnSpeechLanguage.announceForAccessibility(
                getString(R.string.speech_language_switch) + " — " + btnSpeechLanguage.text
            )
            updateSectionStatuses()
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
