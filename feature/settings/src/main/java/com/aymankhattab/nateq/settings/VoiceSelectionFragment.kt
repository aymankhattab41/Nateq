package com.aymankhattab.nateq.settings

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.data.UpdateChecker
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NetworkMetering
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation

/** تُسجّل حواراً من أي ضابط في سجل الفصيل ليُغلق عند تدمير العرض
 *  (لا تسريب مراجع الواجهة) — تُستخدم من الضابطات التي تملك `fragment`. */
internal fun Fragment.trackDialog(dialog: Dialog) {
    (this as? VoiceSelectionFragment)?.trackDialog(dialog)
}

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
        // رابط تواصل المطوّر الرسمي — يُفتح خارجياً في المتصفح فلا يحتاج
        // أي إذن.
        // المعرّف الرسمي لبوت الدعم: @LordTTSBot (أنشئ عبر @BotFather).
        const val DEVELOPER_SUPPORT_URL = "https://t.me/LordTTSBot"
    }

    // طبقة الحالة المحقونة عبر Hilt (تحوي مصدرَي الإعدادات والقاموس).
    private val vm: SettingsViewModel by viewModels()

    private val settings: SettingsRepository
        get() = vm.settings

    private val pronunciationDict: PronunciationDictionary
        get() = vm.pronunciationDict

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvPronunciationDict: RecyclerView

    /** محركات TTS المثبتة (اكتشاف فقط بلا اختيار عام — لا محرك افتراضي). */
    private val engines = mutableListOf<EngineInfo>()
    private lateinit var engineSection: EngineSectionController

    // قسم المحرك والتحويل التلقائي (الاكتشاف/setupAutoConvertUI/
    // قائمة اللغات المكتشفة لكل المحركات...) انتقل بالكامل إلى
    // EngineSectionController.

    // ضابطات أقسام الشاشة (نقل منطق الإعدادات إليها — المرحلة ج من التفكيك)
    private lateinit var timeSection: TimeAnnouncementController
    private lateinit var batterySection: BatteryAnnouncementController
    private lateinit var notificationSection: NotificationReadingController
    private lateinit var callerSection: CallerAnnouncementController
    private lateinit var smsSection: SmsReadingController
    private lateinit var generalSection: GeneralSettingsController
    private lateinit var numberSection: NumberReadingController
    private lateinit var deviceHealthSection: DeviceHealthController
    private lateinit var textReadingSection: TextReadingController
    private lateinit var instantSilenceSection: InstantSilenceController

    // مفتاح تبديل لغة التطبيق (أسفل الشاشة)
    private lateinit var btnToggleLanguage:
        com.google.android.material.button.MaterialButton

    // زر التواصل مع المطوّر (يُفتح خارجياً بلا أذونات)
    private lateinit var btnContactDeveloper:
        com.google.android.material.button.MaterialButton

    // زر الإبلاغ عن خطأ (يجمع الأخطاء من سجل التطبيق ويشاركها)
    private lateinit var btnReportError:
        com.google.android.material.button.MaterialButton

    // زر آخر التحديثات (يعرض ملخص الإصدار الحالي وتغييراته في حوار)
    private lateinit var btnChangelog:
        com.google.android.material.button.MaterialButton

    // المفتاح الرئيسي لكل الإعلانات
    private lateinit var switchAllAnnouncements: SwitchMaterial

    private lateinit var switchLockScreenPrivacy: SwitchMaterial
    private lateinit var btnSetDefaultEngine:
        com.google.android.material.button.MaterialButton

    // معاينة نطق رقم
    private lateinit var etNumberPreview: TextView
    private lateinit var btnPreviewNumber:
        com.google.android.material.button.MaterialButton
    private lateinit var btnStopNumberPreview:
        com.google.android.material.button.MaterialButton

    // أسماء المتصلين المخصصة
    private lateinit var btnCallerNames:
        com.google.android.material.button.MaterialButton

    // استيراد/تصدير القاموس عبر شاشة الوثائق (SAF)
    private lateinit var btnImportDict:
        com.google.android.material.button.MaterialButton
    private lateinit var btnExportDict:
        com.google.android.material.button.MaterialButton
    // نص ملف القاموس المُختار من SAF لحين اختيار طريقة الاستيراد (دمج/استبدال)
    private var pendingImportJson: String? = null

    // نوافذ حوارية مفتوحة: تُغلق كلها عند تدمير العرض حتى لا يتسرب
    // مرجع الواجهة (WindowLeaked) عند تدوير الشاشة.
    private val activeDialogs =
        java.util.Collections.synchronizedSet(mutableSetOf<Dialog>())

    /** يسجّل حواراً مفتوحاً ليُغلق تلقائياً مع تدمير عرض الشاشة، ويزيله
     *  من السجل عند إغلاقه يدوياً. */
    internal fun trackDialog(dialog: Dialog) {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
    }

    private val openDictLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // نقرأ الملف على خيط IO ثم نعرض حوار طريقة الاستيراد (دمج/استبدال)
            lifecycleScope.launch(AppDispatchers.io) {
                val text = runCatching {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                withContext(AppDispatchers.main) {
                    if (isAdded && text != null) {
                        pendingImportJson = text
                        showImportModeDialog()
                    }
                }
            }
        }
    }

    private val createDictLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            // التصدير كاملاً على خيط IO عبر الفي إم (لا تجميد في Main)
            vm.exportDict(uri, requireContext().contentResolver)
        }
    }

    /** حوار طريقة الاستيراد: دمج مع الإدخالات الحالية أو استبدال كامل، ثم
     *  تطبيق ملف القاموس المُختار من SAF بالطريقة المختارة (يُحفظ نصه في
     *  [pendingImportJson] كي لا يقفز حوار الطريقة خارج سياق النتيجة). */
    private fun showImportModeDialog() {
        val json = pendingImportJson ?: return
        val options = arrayOf(
            getString(R.string.dict_import_merge),
            getString(R.string.dict_import_replace)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dict_import_mode_title)
            .setItems(options) { _, which ->
                pendingImportJson = null
                // الاستيراد كاملاً على خيط IO عبر الفي إم
                vm.importDict(json, merge = which == 0)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                pendingImportJson = null
            }
            .setOnCancelListener { pendingImportJson = null }
            .create().also(::trackDialog).show()
    }

    // ===== النسخ الاحتياطي / الاستعادة الكاملان
    // (إعدادات + قاموس + أسماء متصلين) =====
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            // التصدير كاملاً على خيط IO عبر الفي إم
            vm.exportBackup(uri, requireContext().contentResolver)
        }
    }

    private val openRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // القراءة والتفكيك والاستعادة كلها على خيط IO عبر الفي إم
            vm.restoreBackup(uri, requireContext().contentResolver)
        }
    }

    private var announcementSpeaker: AnnouncementSpeaker? = null

    // كتالوج صريح للأصوات المدعومة في ناطق (يتطابق مع onGetVoices)
    // مبسّطة إلى لغتين فقط: "العربية" و"الإنجليزية"
    private lateinit var nateqVoices: List<NateqVoice>

    // طلب إذنَي القراءة عند تفعيل إعلان المتصل (READ_PHONE_STATE لاستقبال
    // بث PHONE_STATE المحمي، وREAD_CALL_LOG للوصول إلى الرقم على أندرويد 12+
    // والاسم من سجل المكالمات، وREAD_CONTACTS للبحث عن الاسم في دفتر
    // الاتصالات).
    // يبقى المُطلِق في الفصيل (يحتاج registerForActivityResult) ونتيجتُه
    // تُحمَّل إلى ضابط قسم المتصل الذي يملك المفتاح والحارس.
    internal val callerPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (::callerSection.isInitialized) {
            callerSection.onPermissionsResult(granted)
        }
    }

    // طلب إذن قراءة الرسائل الواردة لحظة تفعيل قراءة الرسائل فقط (لا عند
    // أول تشغيل). الوضع المختار (full/source) لا يُحفظ إلا بعد المنح الفعلي —
    // مسؤولية الضابط (بند [9]).
    internal val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::smsSection.isInitialized) {
            smsSection.onSmsPermissionResult(granted)
        }
    }

    // ===== ضابط الأكورديون والتنقّل: بطاقات الأقسام وبناء أسطر الحالة =====
    private lateinit var accordion: SettingsAccordionController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // لا إنشاء مباشر للإعدادات/القاموس: كلاهما محقون عبر
        // SettingsViewModel.

        // edge-to-edge (إلزامي من targetSdk 35+): نطبّق الوسائد يدوياً عبر
        // ViewCompat.setOnApplyWindowInsetsListener
        // بدل android:fitsSystemWindows
        // (الحل المهمل) حتى لا تُغطى أي عناصر تفاعلية تحت شريط الحالة/التنقل.
        // تُعاد الوسائد عند كل تغيير (إظهار/إخفاء الأشرطة) فتنزلق العبارة
        // العليا والملاحة السفلية تحت النظام تلقائياً.
        ViewCompat.setOnApplyWindowInsetsListener(
            view.findViewById(R.id.sv_settings_scroll)
        ) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // تهيئة الأصوات هنا بعد الانضمام للسياق (لا يجوز في مُنشئ/خاصية
        // تستدعي getString())
        nateqVoices = listOf(
            NateqVoice(
                "ar-EG",
                LanguageCode.AR.tag,
                getString(R.string.voice_name_arabic),
                Locale.forLanguageTag(LanguageCode.AR.tag)
            ),
            NateqVoice(
                "en-US",
                LanguageCode.EN.tag,
                getString(R.string.voice_name_english),
                Locale.forLanguageTag(LanguageCode.EN.tag)
            )
        )

        // قسم المحركات: اكتشاف المحركات المثبتة فقط (لا صندوق اختيار عام —
        // لا محرك افتراضي؛ محرك كل لغة/فئة يُحسم وقت النطق)
        accordion = SettingsAccordionController(
            this,
            settings,
            nateqVoices
        ).apply {
            setup(view, viewLifecycleOwner)
        }
        engineSection = EngineSectionController(this, settings, engines)
            .apply {
                onStatusChanged = { accordion.updateSectionStatuses() }
            }
        engineSection.setupEngineDiscovery()

        // Categories RecyclerView
        rvCategories = view.findViewById(R.id.rv_categories)
        rvCategories.layoutManager = LinearLayoutManager(requireContext())
        rvCategories.adapter = CategoryVoiceAdapter(
            requireContext(),
            settings,
            nateqVoices,
            ::speakWithVoice
        )
        rvCategories.isNestedScrollingEnabled = false

        // Pronunciation dictionary RecyclerView
        rvPronunciationDict = view.findViewById(R.id.rv_pronunciation_dict)
        rvPronunciationDict.layoutManager = LinearLayoutManager(
            requireContext()
        )
        rvPronunciationDict.adapter = null
        // التمرير الداخلي مفعّل ليتدحرج القاموس المحدود الارتفاع داخل الصفحة
        rvPronunciationDict.isNestedScrollingEnabled = true
        refreshDictAdapter()

        // Add dictionary entry button
        val btnAddDictEntry = view.findViewById(
            R.id.btn_add_dict_entry
        ) as com.google.android.material.button.MaterialButton
        btnAddDictEntry.setOnClickListener { showDictEditDialog() }

        btnImportDict = view.findViewById(R.id.btn_import_dict)
        btnImportDict.setOnClickListener {
            runCatching {
                openDictLauncher.launch(arrayOf("application/json"))
            }
        }
        btnExportDict = view.findViewById(R.id.btn_export_dict)
        btnExportDict.setOnClickListener {
            runCatching { createDictLauncher.launch("nateq_dictionary.json") }
        }

        // المفتاح الرئيسي لكل الإعلانات
        switchAllAnnouncements =
            view.findViewById(R.id.switch_all_announcements)
        switchAllAnnouncements.isChecked =
            runCatching { settings.isAllAnnouncementsEnabled() }
                .getOrDefault(true)
        switchAllAnnouncements.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setAllAnnouncementsEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(requireContext())
                accordion.warnIfNotificationsHidden()
            } else {
                runCatching {
                    requireContext().stopService(
                        Intent(
                            requireContext(),
                            AnnouncementSchedulerService::class.java
                        )
                    )
                }
            }
            accordion.updateSectionStatuses()
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // حماية خصوصية قفل الشاشة: حجب تفاصيل الرسائل/الإشعارات/المتصل
        // عند القفل
        switchLockScreenPrivacy =
            view.findViewById(R.id.switch_lock_screen_privacy)
        switchLockScreenPrivacy.isChecked =
            runCatching { settings.isLockScreenPrivacyEnabled() }
                .getOrDefault(true)
        switchLockScreenPrivacy.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setLockScreenPrivacyEnabled(checked) }
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // نطق الإيموجي ورموز المشاعر: تُنطق الأسماء بدل حذف الرموز من النطق
        val switchEmojiReading: SwitchMaterial =
            view.findViewById(R.id.switch_emoji_reading)
        switchEmojiReading.isChecked =
            runCatching { settings.isEmojiPronunciationEnabled() }
                .getOrDefault(true)
        switchEmojiReading.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setEmojiPronunciationEnabled(checked) }
            accordion.updateSectionStatuses()
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // زر جعل Lord المحرك الافتراضي (يفتح شاشة TTS النظامية لاختياره
        // يدوياً)
        btnSetDefaultEngine = view.findViewById(R.id.btn_set_default_engine)
        btnSetDefaultEngine.setOnClickListener {
            runCatching {
                startActivity(
                    // الثابت الرسمي لنافذة إعدادات TTS غير متاح في كل مستويات
                    // SDK، لذا نستخدم الإجراء النصي الثابت نفسه.
                    Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Toast.makeText(
                requireContext(),
                R.string.set_default_engine_hint,
                Toast.LENGTH_LONG
            ).show()
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

        // مفتاح تبديل لغة التطبيق (أسفل الشاشة)
        btnToggleLanguage = view.findViewById(R.id.btn_toggle_language)

        // زر التواصل مع المطوّر: يفتح رابط الدعم خارجياً دون كشف وسيلة التواصل
        btnContactDeveloper = view.findViewById(R.id.btn_contact_developer)
        btnContactDeveloper.setOnClickListener { openDeveloperSupport() }

        // زر الإبلاغ عن خطأ: يجمع سطور الأخطاء من السجل ويشاركها عبر
        // وسائل المشاركة
        btnReportError = view.findViewById(R.id.btn_report_error)
        btnReportError.setOnClickListener { onReportErrorClicked() }

        // زر آخر التحديثات: يعرض ملخص أحدث إصدار وتغييراته في حوار
        btnChangelog = view.findViewById(R.id.btn_changelog)
        btnChangelog.setOnClickListener { showChangelogDialog() }

        // إنشاء ضابطات الأقسام وربطها (المرحلة ج): كل ضابط يسحب عناصره
        // ويبني مستمعيه عند setup()، وonStatusChanged تُحدّث أسطر حالة
        // الأكورديون.
        timeSection = TimeAnnouncementController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        batterySection = BatteryAnnouncementController(
            this, settings, nateqVoices, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        notificationSection = NotificationReadingController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        callerSection = CallerAnnouncementController(
            this, settings, nateqVoices, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        smsSection = SmsReadingController(
            this, settings, nateqVoices, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        generalSection = GeneralSettingsController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        numberSection = NumberReadingController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        deviceHealthSection = DeviceHealthController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        textReadingSection = TextReadingController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        instantSilenceSection = InstantSilenceController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        OemGuidanceController(this).apply { setup(view) }
        setupLanguageToggle()
        setupCheckUpdates()
        engineSection.setupAutoConvertUI(view)
        setupSaveAndResetButtons()
        setupBackupRestoreButtons()
        accordion.updateSectionStatuses()

        // التحديث التفاعلي: المراجعة الابتدائية (0) لا تُحدّث شيئاً،
        // وأي مراجعة لاحقة (استعادة/إعادة ضبط) تُعيد بناء كل أقسام
        // الواجهة تلقائياً.
        viewLifecycleOwner.lifecycleScope.launch {
            vm.settingsRevision.collect { revision ->
                if (revision > 0) refreshAllSettingsUi()
            }
        }

        // نتائج عمليات الفي إم غير المتزامنة (تصدير/استيراد/نسخ احتياطي)
        // — تُعرض Toast/إعلان مسموع وتُعاد رسكلة القاموس عند الحاجة.
        viewLifecycleOwner.lifecycleScope.launch {
            vm.operationEvents.collect(::handleOperationEvent)
        }

        // فحص تلقائي عند فتح التطبيق: يُنبه بوجود تحديث (صامت إن لم يوجد)
        checkForUpdatesOnStart()
    }

    override fun onDestroyView() {
        // إغلاق المتحدث المستقل الخاص بالمعاينة (إن أُنشئ) حتى لا يبقى محرك
        // TTS مفتوحاً بعد مغادرة الشاشة. المثيل هنا خاص بالشاشة وليس المشترك
        // (getInstance) الذي تُدار حياته في مستقبلات الإعلانات التلقائية.
        announcementSpeaker?.stop()
        announcementSpeaker = null
        // إغلاق كل النوافذ المفتوحة حتى لا تتسرب مراجع الواجهة (WindowLeaked)
        // عند تدوير الشاشة أو مغادرتها.
        val open = activeDialogs.toList()
        activeDialogs.clear()
        open.forEach { runCatching { it.dismiss() } }
        super.onDestroyView()
    }

    /** يعالج أحداث العمليات غير المتزامنة القادمة من الفي إم (تجري كلها على
     *  خيط IO في الفي إم): يعرض النتيجة ويعيد رسكلة القاموس عند الحاجة. */
    private fun handleOperationEvent(event: SettingsOperation) {
        val stats = when (event) {
            is SettingsOperation.DictImported -> {
                if (event.ok) refreshDictAdapter()
                if (event.ok) R.string.dict_imported_ok
                else R.string.dict_import_failed
            }
            is SettingsOperation.DictExported -> if (event.ok)
                R.string.dict_exported_ok else R.string.dict_export_failed
            is SettingsOperation.BackedUp -> if (event.ok)
                R.string.backup_saved else R.string.backup_failed
            is SettingsOperation.Restored -> {
                if (event.ok) {
                    // إعادة عرض الأقسام تتم تلقائياً
                    // عبر مراجعة settingsRevision
                    AnnouncementSchedulerService.requestStart(requireContext())
                    if (event.callersOnly) R.string.restore_done_callers_only
                    else R.string.restore_done
                } else {
                    R.string.restore_failed
                }
            }
        }
        val duration = if (event is SettingsOperation.Restored)
            Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(requireContext(), stats, duration).show()
        view?.announceCompat(getString(stats))
    }

    // ===== اختيار المحرك وحوار التحويل التلقائي: انتقلا إلى
    // EngineSectionController =====
    /** نسخة حصرية للفصيل من محلّل الـ Spinner الأساسي (المنفَّذ في
     *  EngineSectionController). */
    internal fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        simpleAdapter(requireContext(), items)

    // ===== قاموس النطق =====
    private fun showDictEditDialog(existing: Pair<String, String>? = null) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_dict_entry, null)
        val etWord = dialogView.findViewById<EditText>(R.id.et_dict_word)
        val etPhonetic =
            dialogView.findViewById<EditText>(R.id.et_dict_phonetic)
        if (existing != null) {
            etWord.setText(existing.first)
            etPhonetic.setText(existing.second)
        }
        builder.setView(dialogView)
            .setTitle(
                if (existing == null) getString(R.string.dict_add_title)
                else getString(R.string.dict_edit_entry)
            )
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val word = etWord.text.toString().trim()
                val phonetic = etPhonetic.text.toString().trim()
                if (word.isNotEmpty() && phonetic.isNotEmpty()) {
                    if (existing != null && existing.first != word) {
                        runCatching {
                            pronunciationDict.removeEntry(existing.first)
                        }
                    }
                    runCatching { pronunciationDict.addEntry(word, phonetic) }
                    refreshDictAdapter()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.enter_word_and_pronunciation),
                        Toast.LENGTH_SHORT
                    ).show()
                    view?.announceCompat(
                        getString(R.string.enter_word_and_pronunciation)
                    )
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create().also(::trackDialog).show()
    }

    /** المثيل الوحيد لمسند القاموس — يُعاد استخدامه عبر submitList بدل
     *  بناء مسند جديد وإعادة تسنيده بالكامل مع كل تغيير. */
    private var pronunciationDictAdapter: PronunciationDictAdapter? = null

    /** إعادة رسم قائمة إدخالات القاموس بعد أي تغيير
     *  (إضافة/تعديل/حذف/استيراد) — يحسب المسند الفرق فيحدّث الصفوف المتغيّرة
     *  فقط ويحافظ على موضع التمرير. */
    private fun refreshDictAdapter() {
        val entries = runCatching { pronunciationDict.getAllEntries() }
            .getOrDefault(emptyMap())
            .toList()
        val adapter = pronunciationDictAdapter
            ?: PronunciationDictAdapter(::showDictRowOptions).also {
                pronunciationDictAdapter = it
                rvPronunciationDict.adapter = it
            }
        adapter.submitList(entries)
    }

    /** نطق الرقم المدخل في حقل المعاينة بنفس منطق النطق الفعلي للإعلانات */
    private fun previewNumber() {
        // نقبل الأرقام الهندية/الشرقية في الحقل (٠١٢٣...) مع الغربية
        val raw = com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(
            etNumberPreview.text.toString().trim()
        )
        val number = raw.toIntOrNull()
        if (number == null) {
            Toast.makeText(
                requireContext(),
                R.string.number_preview_invalid,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(R.string.number_preview_invalid))
            return
        }
        val forced =
            runCatching { settings.getAnnouncementSpeechLanguage() }
                .getOrNull()
        val appLang = runCatching { settings.getAppLanguage() }.getOrNull()
            ?: Locale.getDefault().language
        val isEnglish = if (forced != null) LanguageCode.isEnglish(forced)
            else LanguageCode.isEnglish(appLang)
        val mode = runCatching { settings.getNumberReadingMode() }
            .getOrDefault(1).coerceIn(1, 8)
        val text = NumberSpeech.formatByMode(mode, number, isEnglish)
        val langTag =
            if (isEnglish) LanguageCode.EN.tag else LanguageCode.AR.tag
        speakWithVoice(langTag, text)
    }

    /** حوار إدارة أسماء المتصلين المخصصة (رقم → اسم يُنطق به) */
    private fun showCallerNamesDialog() {
        val names = runCatching { settings.getCustomCallerNames() }
            .getOrDefault(emptyMap()).toMutableMap()
        val rows = names.toList().toMutableList()
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // هوامش بمعامل الكثافة حتى لا تتضخم على الشاشات عالية الدقة
            val density = resources.displayMetrics.density
            setPadding(
                (48 * density).toInt(),
                (24 * density).toInt(),
                (48 * density).toInt(),
                0
            )
        }
        val listContainer = android.widget.LinearLayout(
            requireContext()
        ).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        fun addRow(number: String = "", name: String = "") {
            val numberPicker =
                com.google.android.material.textfield.TextInputEditText(
                    requireContext()
                ).apply {
                hint = getString(R.string.caller_names_number_hint)
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setText(number)
            }
            val namePicker =
                com.google.android.material.textfield.TextInputEditText(
                    requireContext()
                ).apply {
                hint = getString(R.string.caller_names_name_hint)
                setText(name)
            }
            val removeBtn = com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.style
                    .Widget_MaterialComponents_Button_TextButton
            ).apply {
                text = getString(R.string.caller_names_delete)
                textSize = 13f
                // أهداف لمس لا تقل عن 48dp لقارئ الشاشة
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            val rowLayout =
                com.google.android.material.textfield.TextInputLayout(
                    requireContext()
                ).apply {
                    setPadding(0, 0, 0, 0)
                }
            // بسيط: LinearLayout أفقي بعمودين نصيين وزر حذف
            val fields = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(
                    numberPicker,
                    android.widget.LinearLayout.LayoutParams(0, -2, 2f)
                )
                addView(
                    namePicker,
                    android.widget.LinearLayout.LayoutParams(0, -2, 2f)
                )
                addView(
                    removeBtn,
                    android.widget.LinearLayout.LayoutParams(-2, -2)
                )
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
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
            }
            listContainer.addView(emptyHint)
        }
        val addBtn = com.google.android.material.button.MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.style
                    .Widget_MaterialComponents_Button_TextButton
        ).apply {
            text = getString(R.string.caller_names_add)
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        addBtn.setOnClickListener { addRow() }

        val scroll = android.widget.ScrollView(requireContext()).apply {
            addView(listContainer)
        }
        root.addView(
            scroll,
            android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(addBtn)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.caller_names_title))
            .setView(root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newNames = mutableMapOf<String, String>()
                var duplicate = false
                for (i in 0 until listContainer.childCount) {
                    val rowLayout = listContainer.getChildAt(i) as?
                    com.google.android.material.textfield
                        .TextInputLayout ?: continue
                    val fields = rowLayout.getChildAt(0)
                        as? android.widget.LinearLayout ?: continue
                    if (fields.childCount < 2) continue
                    val num = (fields.getChildAt(0)
                        as? android.widget.EditText)?.text?.toString()
                        ?.trim().orEmpty()
                    val nm = (fields.getChildAt(1)
                        as? android.widget.EditText)?.text?.toString()
                        ?.trim().orEmpty()
                    if (num.isEmpty() || nm.isEmpty()) continue
                    if (newNames.containsKey(num)) {
                        duplicate = true
                        continue
                    }
                    newNames[num] = nm
                }
                if (duplicate) {
                    Toast.makeText(
                        requireContext(),
                        R.string.caller_names_duplicate,
                        Toast.LENGTH_LONG
                    ).show()
                    view?.announceCompat(
                        getString(R.string.caller_names_duplicate)
                    )
                }
                runCatching { settings.setCustomCallerNames(newNames) }
                if (!duplicate) {
                    Toast.makeText(
                        requireContext(),
                        R.string.caller_names_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                    view?.announceCompat(
                        getString(R.string.caller_names_saved)
                    )
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create().also(::trackDialog).show()
    }

    /** توسيع/طي قسم قابل للطي، ويُحدّث السهم (▼/▲) ووصف الأب بحالة الطي.
 *  internal لأن ضابط البطارية يستخدمه للعنوان القابل للطي. */
    internal fun toggleCollapsible(
        content: View,
        arrow: TextView,
        header: View
    ) {
        val collapsed =
            content.visibility == View.GONE ||
                content.visibility == View.INVISIBLE
        content.visibility = if (collapsed) View.VISIBLE else View.GONE
        // سهم متجهي يلون بلون النص (مطوّي → للأسفل، موسّع → للأعلى)
        arrow.text = ""
        val res = if (collapsed) R.drawable.ic_expand_more
        else R.drawable.ic_expand_less
        val icon = androidx.core.content.ContextCompat.getDrawable(
            requireContext(), res
        )?.apply { setTint(arrow.currentTextColor) }
        arrow.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, icon, null
        )
        // يقرأ قارئ الشاشة نصاً واحداً: العنوان الأساسي + حالة (موسّع/مطوي)
        val stateLabel =
            if (collapsed) getString(R.string.expand)
            else getString(R.string.collapse)
        val base = header.getTag() as? String
        if (base != null) {
            header.contentDescription = base + " — " + stateLabel
        } else {
            header.contentDescription = stateLabel
        }
    }

    private fun showDictRowOptions(entry: Pair<String, String>) {
        val options = arrayOf(
            getString(R.string.dict_edit_entry),
            getString(R.string.dict_delete_entry)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDictEditDialog(entry)
                    1 -> {
                        runCatching {
                            pronunciationDict.removeEntry(entry.first)
                        }
                        // إعلان مسموع بنتيجة الحذف لتوفير تغذية راجعة لقارئ
                        // الشاشة
                        announcementSpeaker?.speak(
                            getString(R.string.dict_removed),
                            if (com.aymankhattab.nateq.util.LocaleUtils
                                    .containsArabic(entry.second)
                            ) {
                                Locale.forLanguageTag(LanguageCode.AR.tag)
                            } else {
                                Locale.forLanguageTag(LanguageCode.EN.tag)
                            },
                            1.0f, 1.0f, 1.0f
                        )
                        refreshDictAdapter()
                    }
                }
            }
            .create().also(::trackDialog).show()
    }

    /** إيقاف النطق الجاري للمتحدث الخاص بمعاينات الأقسام (يُستخدمه ضابط
     *  صحة الجهاز وزر إيقاف معاينة الأرقام). */
    internal fun stopPreviewSpeech() {
        announcementSpeaker?.stop()
    }

    internal fun speakWithVoice(languageTag: String, text: String) {
        // نستخدم نفس مسار الإعلانات الصوتية التلقائية (AnnouncementSpeaker)
        // الذي يربط المحرك المختار فعليا عبر setEngineByPackageName —
        // لا نعتمد على المحرك الافتراضي للنظام حتى لا يكون "نحن" أنفسنا.
        val speaker = announcementSpeaker
            ?: AnnouncementSpeaker(requireContext())
                .also { announcementSpeaker = it }
        speaker.speak(
            text,
            Locale.forLanguageTag(languageTag),
            runCatching { settings.getSpeechRate(languageTag) }
                .getOrDefault(1.0f),
            1.0f,
            1.0f,
        )
    }

    // ===== تبديل لغة التطبيق (أسفل الشاشة) =====
    private fun setupLanguageToggle() {
        val current = runCatching { settings.getAppLanguage() }
            .getOrNull()
            ?: Locale.getDefault().language

        val isArabic = LanguageCode.isArabic(current)
        // يعرض اللغة الحالية ثم الإجراء نحو اللغة الأخرى
        btnToggleLanguage.text = if (isArabic) {
            getString(R.string.language_current_label) + " " +
                getString(R.string.language_arabic) + " — " +
                getString(R.string.language_change_to_en)
        } else {
            getString(R.string.language_current_label) + " " +
                getString(R.string.language_english) + " — " +
                getString(R.string.language_change_to_ar)
        }

        btnToggleLanguage.setOnClickListener {
            val newLang =
            if (isArabic) LanguageCode.EN.tag else LanguageCode.AR.tag
            runCatching { settings.setAppLanguage(newLang) }
            // تطبيق اللغة على مستوى التطبيق (AppCompatDelegate) قبل إعادة
            // إنشاء النشاط حتى تُنشأ موارد النشاط الجديد باللغة الجديدة
            // فعلياً.
            runCatching {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(newLang)
                )
            }
            // إعادة إنشاء النشاط لتطبيق اللغة فورياً (UI + افتراضيات)
            requireActivity().recreate()
        }
    }

    // ===== زر التواصل مع المطوّر =====
    private fun openDeveloperSupport() {
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(DEVELOPER_SUPPORT_URL)
                )
            )
        }.onFailure {
            Toast.makeText(
                requireContext(),
                R.string.contact_developer_no_handler,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(
                getString(R.string.contact_developer_no_handler)
            )
        }
    }

    // ===== الإبلاغ عن خطأ: جمع الأخطاء من السجل ومشاركتها =====
    private fun onReportErrorClicked() {
        val context = requireContext()
        Toast.makeText(
            context,
            R.string.report_error_collecting,
            Toast.LENGTH_SHORT
        ).show()
        lifecycleScope.launch {
            val report = runCatching { buildErrorReport(context) }.getOrNull()
            if (report == null || report.second.isEmpty()) {
                Toast.makeText(
                    context,
                    R.string.report_error_empty,
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(getString(R.string.report_error_empty))
                return@launch
            }
            shareErrorReport(
                context,
                report.second.joinToString("\n"),
                report.first
            )
        }
    }

    /**
     * يجمع سطور الأخطاء من سجل التقنية عبر logcat (عملية التطبيق الحالية فقط)،
     * ويُبقي مستوى الخطورة E/F ضمن وسومنا فقط — أي لا يُخرج السجل كله.
     * يعيد نصاً مسبوقاً بترويسة تعريف (جهاز/إصدار/وقت) إن وُجدت أسطر،
     * وإلا نصاً فارغاً.
     */
    private suspend fun buildErrorReport(context: android.content.Context) =
        kotlinx.coroutines.withContext(AppDispatchers.io) {
            // نجمع سطور الأخطاء/الاستثناءات للتطبيق نفسه فقط — لا السجل كله:
            // نقرأ آخر 1500 سطر لعملية التطبيق الحالية (تحديداً بالـ PID)،
            // ونُبقي ما يحمل مستوى ERROR (E) أو Fatal (F) ضمن وسوم ناتك.
            val filtered = mutableListOf<String>()
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d",
                        "-t", "1500",
                        "--pid", android.os.Process.myPid().toString()
                    )
                )
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val lower = line.lowercase()
                        // تنسيق logcat الافتراضي (brief): الحرف الأول هو مستوى
                        // الخطورة
                        // (V/D/I/W/E/F). نبقي E (Error) وF (Fatal) —
                        // أي لا نُخرج كل السجل.
                        val isErrorLevel = line.isNotEmpty() &&
                            (line[0] == 'E' || line[0] == 'F')
                        // نقيّد المصدر بحزمتنا/وسومها دون سجل النظام الآخر
                        val isOurTag =
                            lower.contains("nateq") || lower.contains("lordt")
                        if (isErrorLevel && isOurTag) filtered.add(line)
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                android.util.Log.e("NATEQ_APP", "logcat collect failed", e)
            }
            if (filtered.isEmpty()) {
                return@withContext "" to emptyList<String>()
            }
            val info = context.packageManager.getPackageInfo(
                context.packageName, 0
            )
            val versionName = info.versionName ?: "?"
            val versionCode =
                if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.P
                ) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
            val header = buildString {
                appendLine(
                    context.getString(R.string.error_report_header_title)
                )
                appendLine(
                    context.getString(
                        R.string.error_report_version,
                        versionName, versionCode
                    )
                )
                appendLine(
                    context.getString(
                        R.string.error_report_device,
                        android.os.Build.MANUFACTURER,
                        android.os.Build.MODEL
                    )
                )
                appendLine(
                    context.getString(
                        R.string.error_report_android_os,
                        android.os.Build.VERSION.RELEASE,
                        android.os.Build.VERSION.SDK_INT
                    )
                )
                appendLine(
                    context.getString(
                        R.string.error_report_time,
                        java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                        ).format(java.util.Date())
                    )
                )
                appendLine("-----")
            }
            header to filtered
        }

    /** يفتح وسائل المشاركة (اقتراح نصوص، بريد…) بالمحتوى المجمّع. */
    private fun shareErrorReport(
        context: android.content.Context,
        body: String,
        header: String
    ) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT, getString(R.string.report_error_subject)
            )
            putExtra(Intent.EXTRA_TEXT, header + body)
        }
        val chooser = Intent.createChooser(
            send, getString(R.string.report_error)
        )
        runCatching {
            startActivity(chooser)
        }.onFailure {
            Toast.makeText(
                context, R.string.report_error_no_handler, Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(R.string.report_error_no_handler))
        }
    }

    // ===== آخر التحديثات =====
    private fun showChangelogDialog() {
        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            ).versionName
        }.getOrNull() ?: "?"
        val body = getString(R.string.changelog_text, versionName)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        trackDialog(dialog)
        view?.announceCompat(getString(R.string.changelog_title))
    }

    // ===== البحث عن تحديثات =====
    private fun setupCheckUpdates() {
        val btn = view?.findViewById<View>(R.id.btn_check_updates) ?: return
        btn.setOnClickListener { onCheckUpdatesClicked() }
    }

    /** فحص تلقائي عند بدء الشاشة (صامت عند عدم وجود تحديث). */
    internal fun checkForUpdatesOnStart() {
        performUpdateCheck(showFeedback = false)
    }

    /** فحص عند ضغط زر «البحث عن تحديثات» (مع رسائل واضحة). */
    private fun onCheckUpdatesClicked() {
        performUpdateCheck(showFeedback = true)
    }

    /** منطق الفحص المشترك: إن وُجد تحديث يعرض حوار «نعم/لا» قبل التنزيل. */
    private fun performUpdateCheck(showFeedback: Boolean) {
        val context = requireContext()
        val currentName = runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName, 0
            )
            info.versionName
        }.getOrNull() ?: ""

        if (showFeedback) {
            Toast.makeText(
                context, getString(R.string.check_updates), Toast.LENGTH_SHORT
            ).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // الفحص التلقائي يفضّل الكاش (لا يضغط GitHub عند كل فتح شاشة)؛
            // الفحص اليدوي يلتفّ عليه دائماً ليُجيب فوراً عن «هل من جديد؟».
            when (
                val res = UpdateChecker.check(
                    currentName, preferCache = !showFeedback
                )
            ) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    promptDownloadUpdate(
                        context, res.apkUrl, res.expectedSha256Hex
                    )
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    if (showFeedback) {
                        Toast.makeText(
                            context,
                            getString(R.string.check_updates_up_to_date),
                            Toast.LENGTH_SHORT
                        ).show()
                        view?.announceCompat(
                            getString(R.string.check_updates_up_to_date)
                        )
                    }
                }
                is UpdateChecker.CheckResult.NetworkError -> {
                    if (showFeedback) {
                        Toast.makeText(
                            context,
                            getString(R.string.check_updates_network_error),
                            Toast.LENGTH_SHORT
                        ).show()
                        view?.announceCompat(
                            getString(R.string.check_updates_network_error)
                        )
                    }
                }
            }
        }
    }

    /** حوار تأكيد قبل التنزيل: يسأل المستخدم إن كان يريد تنزيل التحديث
     *  (نعم/لا). على الشبكات المدفوعة يُحذَّر أن التنزيل سيستهلك بياناته،
     *  وموافقته عليه صراحةً تسمح بالتنزيل فوقها. */
    private fun promptDownloadUpdate(
        context: android.content.Context,
        apkUrl: String,
        expectedSha256Hex: String?
    ) {
        val base = getString(R.string.check_updates_confirm_message)
        // إن لم يوفّر الناشر بصمة SHA-256 (غياب digest) يُحذَّر المستخدم
        // بصراحة قبل التثبيت بلا تحقق (القرار يبقى له).
        val warnings = buildList {
            if (expectedSha256Hex == null) {
                add(getString(R.string.check_updates_no_digest_warning))
            }
            if (NetworkMetering.isMetered(requireContext())) {
                add(getString(R.string.check_updates_metered_warning))
            }
        }
        val message = if (warnings.isEmpty()) base
        else base + "\n" + warnings.joinToString("\n")
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.check_updates_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.check_updates_confirm_yes) { _, _ ->
                startApkDownload(
                    context,
                    apkUrl,
                    expectedSha256Hex,
                    allowMetered = NetworkMetering.isMetered(context)
                )
            }
            .setNegativeButton(R.string.check_updates_confirm_no, null)
            .create().also(::trackDialog).show()
    }

    /** ينزّل الـ APK ويعرض إشعاراً بأن التنزيل بدأ — يُستدعى بعد موافقة
     *  المستخدم. [expectedSha256Hex] (إن وُجدت) تُفحص قبل فتح شاشة
     *  التثبيت: بصمة متوقعة محلّياً تساوي بصمة التنزيل = نثبّت؛ غير ذلك
     *  يُحذف الملف ويُبلغ المستخدم. */
    private fun startApkDownload(
        context: android.content.Context,
        apkUrl: String,
        expectedSha256Hex: String?,
        allowMetered: Boolean
    ) {
        Toast.makeText(
            context,
            getString(R.string.check_updates_downloading_title),
            Toast.LENGTH_SHORT
        ).show()
        val downloadId = UpdateChecker.enqueueDownload(
            context, apkUrl, allowMetered
        )

        // مستمع مؤقت مشترك يفتح شاشة التثبيت عند اكتمال تنزيل الـ APK.
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(
                ctx: android.content.Context,
                intent: Intent
            ) {
                val id = intent.getLongExtra(
                    android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L
                )
                if (id != downloadId) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                    /* سبق تسجيله أو فُكّ */
                }
                val apk = UpdateChecker.downloadedApk(ctx)
                if (expectedSha256Hex != null &&
                    !UpdateChecker.verifyApkSha256(apk, expectedSha256Hex)
                ) {
                    // بصمة الـ APK المُنزَّل لا تطابق ما نشره GitHub —
                    // ملف تالف/مبتور أو عبث: لا تثبيت، نحذف ونُبلغ المستخدم.
                    apk.delete()
                    Toast.makeText(
                        ctx,
                        getString(R.string.check_updates_checksum_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    view?.announceCompat(
                        getString(
                            R.string.check_updates_checksum_failed
                        )
                    )
                    return
                }
                UpdateChecker.promptInstall(ctx, apk)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(
                android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ===== زر الحفظ + زر استعادة الافتراضيات =====
    private fun setupSaveAndResetButtons() {
        val btnSave = view?.findViewById<View>(R.id.btn_save_settings)
        val btnReset = view?.findViewById<View>(R.id.btn_reset_settings)

        // الحفظ: الإعدادات تُخزَّن فورياً عند كل تغيير عبر setters، لكن نقدم
        // للمستخدم تأكيداً واضحاً بأن إعداداته مأخوذة في مكانها.
        btnSave?.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.saved_successfully),
                Toast.LENGTH_SHORT
            ).show()
            view?.findViewById<View>(R.id.btn_save_settings)
                ?.announceCompat(getString(R.string.saved_successfully))
        }

        // استعادة الافتراضيات: مسح كل الإعدادات ثم إعادة بناء الواجهة لتحميل
        // القيم الافتراضية (بدون إعادة إنشاء الـ Activity).
        btnReset?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setPositiveButton(R.string.reset_done) { _, _ ->
                    runCatching { settings.resetAllToDefault() }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.reset_done),
                        Toast.LENGTH_SHORT
                    ).show()
                    view?.findViewById<View>(R.id.btn_reset_settings)
                        ?.announceCompat(getString(R.string.reset_done))
                    // التحديث الكلي للواجهة يتم تلقائياً عبر StateFlow
                    // المراجعة (notifySettingsChanged) — يستمع له الفصيل
                    // فيعيد بناء كل الأقسام من القيم الافتراضية دون تكرار
                    // كتلة setup() يدوية.
                    vm.notifySettingsChanged()
                }
                .setNegativeButton(R.string.reset_cancel, null)
                .create().also(::trackDialog).show()
        }
    }

    // ===== النسخ الاحتياطي / الاستعادة =====
    private fun setupBackupRestoreButtons() {
        view?.findViewById<View>(R.id.btn_backup_settings)
            ?.setOnClickListener {
            // تحذير صريح قبل التصدير: الملف نص صريح قد يحوي بيانات شخصية
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_warning_title)
                .setMessage(R.string.backup_export_warning_message)
                .setPositiveButton(R.string.backup_settings) { _, _ ->
                    runCatching {
                        createBackupLauncher.launch("lord_tts_backup.json")
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }
        view?.findViewById<View>(R.id.btn_restore_settings)
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

    /** إعادة تحميل كل قيم الواجهة بعد الاستعادة (دون إعادة إنشاء النشاط). */
    private fun refreshAllSettingsUi() {
        val v = requireView()
        timeSection.setup(v)
        batterySection.setup(v)
        notificationSection.setup(v)
        callerSection.setup(v)
        smsSection.setup(v)
        generalSection.setup(v)
        numberSection.setup(v)
        deviceHealthSection.setup(v)
        textReadingSection.setup(v)
        instantSilenceSection.setup(v)
        rvCategories.adapter?.notifyDataSetChanged()
        refreshDictAdapter()
        accordion.updateSectionStatuses()
    }

}
