package com.aymankhattab.nateq.settings

import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.app.DownloadManager
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.aymankhattab.nateq.core.audio.engine.VoiceReadinessCoordinator
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.UpdateChecker
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.audio.engine.DictTestProvider
import android.net.Uri
import android.widget.LinearLayout
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.DiscoveryResult
import com.aymankhattab.nateq.core.audio.engine.UnavailableEngine
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceImporter
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.VocalizerDownloader
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.engine.TtsDiagnosticsProvider
import com.aymankhattab.nateq.core.audio.engine.SpeechFallbackTracker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NetworkMetering
import com.aymankhattab.nateq.util.VoiceIdContract
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.EnginePicker.InstalledEngine
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.settings.OemVendor
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.util.ToastDebouncer
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation

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

        /**
         * سقفُ أسطر «بصمة التطبيق» في رأس التقرير — يحمي القارئ من
         تقريرٍ ضخمٍ ويكفي كلَّ ما يهمّ التشخيص.
         */
        internal const val MAX_DIAGNOSTIC_LINES = 800

        /** اسم ملف تصدير القاموس (بقرار المدير: `LOR_dictionary` لا
         *  `nateq_dictionary`) — مع امتداد JSON الذي يفرضه نوعُ
         *  `CreateDocument`، وقد يضيف النظام رقماً عند التصدير مرتين. */
        private const val DICT_EXPORT_FILE_NAME = "LOR_dictionary.json"
    }

    // طبقة الحالة المحقونة عبر Hilt (تحوي مصدرَي الإعدادات والقاموس).
    internal val vm: SettingsViewModel by viewModels()

    internal val settings: SettingsRepository
        get() = vm.settings

    internal val pronunciationDict: PronunciationDictionary
        get() = vm.pronunciationDict

    internal lateinit var categoryVoicePanel: CategoryVoicePanelController
    internal lateinit var rvPronunciationDict: RecyclerView

    /** محركات TTS المثبتة (اكتشاف فقط بلا اختيار عام — لا محرك افتراضي). */
    private val engines = mutableListOf<EngineInfo>()
    internal lateinit var engineSection: EngineSectionController

    /** معاينة النطق المشتركة لكل أقسام الشاشة (بند الأوامر 4) — تُغلق
     *  في [onDestroyView] عبر [VoicePreviewHelper.release]. */
    internal lateinit var voicePreview: VoicePreviewHelper

    /** مستقبل اكتمال تنزيل التحديث — يُحتفظ بمرجعه ليُلغى تسجيله في
     *  [onDestroyView] (بند 6.6) حتى لا يتسرب الفصيل عند تدوير الشاشة
     *  أو مغادرتها أثناء التنزيل. */
    internal var updateReceiver: android.content.BroadcastReceiver? = null

    /** حوار متابعة تنزيل التحديث النشط ومهمة الاستعلام الدوري عن تقدمه. */
    internal var updateProgressDialog: AlertDialog? = null
    internal var updatePollingJob: Job? = null
    internal var updateDialogDismissedByUser: Boolean = false

    // قسم المحرك والتحويل التلقائي (الاكتشاف/setupAutoConvertUI/
    // قائمة اللغات المكتشفة لكل المحركات...) انتقل بالكامل إلى
    // EngineSectionController.

    // ضابطات أقسام الشاشة (نقل منطق الإعدادات إليها — المرحلة ج من التفكيك)
    internal lateinit var timeSection: TimeAnnouncementController
    internal lateinit var batterySection: BatteryAnnouncementController
    internal lateinit var notificationSection: NotificationReadingController
    internal lateinit var callerSection: CallerAnnouncementController

/** إرشادات توافق الجهاز — تضمّ أيضاً مسار النطق الفوري لاسم المتصل،
     *  فيُنعش حالته في [onResume] لأنّها تتبع إعدادات النظام لا بيانات
     *  التطبيق. */
    private lateinit var oemGuidance: OemGuidanceController
    internal lateinit var smsSection: SmsReadingController
    internal lateinit var generalSection: GeneralSettingsController
    internal lateinit var numberSection: NumberReadingController
    internal lateinit var deviceHealthSection: DeviceHealthController
    internal lateinit var textReadingSection: TextReadingController
    internal lateinit var textProcessingSection: TextProcessingController
    internal lateinit var instantSilenceSection: InstantSilenceController
    private lateinit var a11yServicesSection: ScreenServicesCardController
    internal lateinit var embeddedDownloaderSection:
        EmbeddedEngineDownloaderController

    /** آخر رقم إصدار تخزين شوهدت الشاشة (ع٢٩-9) — يقارَن في
     *  [onResume] لكشف تغيّر الملفات أثناء خلفية الشاشة. */
    private var lastSeenStorageVersion: Int = -1

    // مفتاح تبديل لغة التطبيق (أسفل الشاشة)
    internal lateinit var btnToggleLanguage:
        com.google.android.material.button.MaterialButton

    // زر التواصل مع المطوّر (يُفتح خارجياً بلا أذونات)
    private lateinit var btnContactDeveloper:
        com.google.android.material.button.MaterialButton

    // زر الإبلاغ عن خطأ (يجمع الأخطاء من سجل التطبيق ويشاركها)
    internal lateinit var btnReportError:
        com.google.android.material.button.MaterialButton

    /** باب منع تكرار جمع تقرير الأخطاء (ملاحظة مراجعة السجل): ضغطتان
     *  سريعتان كانتا تشغّلان كوروتينين متوازيين يبنيان التقرير ويكتبان
     *  نفس ملف الكاش — الضغط الثاني يُرفض حتى تكتمل الرحلة الأولى. */
    internal val errorReportGate = ErrorReportGate()

    // زر آخر التحديثات (يعرض ملخص الإصدار الحالي وتغييراته في حوار)
    private lateinit var btnChangelog:
        com.google.android.material.button.MaterialButton

    // المفتاح الرئيسي لكل الإعلانات
    internal lateinit var switchAllAnnouncements: SwitchMaterial

    internal lateinit var switchLockScreenPrivacy: SwitchMaterial
    private lateinit var btnSetDefaultEngine:
        com.google.android.material.button.MaterialButton

    // معاينة نطق رقم
    internal lateinit var etNumberPreview: TextView
    private lateinit var btnPreviewNumber:
        com.google.android.material.button.MaterialButton
    private lateinit var btnStopNumberPreview:
        com.google.android.material.button.MaterialButton

    // أسماء المتصلين المخصصة
    private lateinit var btnCallerNames:
        com.google.android.material.button.MaterialButton

    // استيراد/تصدير القاموس عبر شاشة الوثائق (SAF)
    internal lateinit var btnImportDict:
        com.google.android.material.button.MaterialButton
    internal lateinit var btnExportDict:
        com.google.android.material.button.MaterialButton
    // نص ملف القاموس المُختار من SAF لحين اختيار طريقة الاستيراد (دمج/استبدال)
    internal var pendingImportJson: String? = null

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

    /**
     * استيراد القاموس: **`.json` فقط** (بقرار المدير) — لا تُعرض كل
     * الملفات ولا تُقبل إلا JSON.
     *
     * **لماذا ثلاثة أنواع MIME لا واحد؟** فلتر SAF يستثني ما لا يطابق
     * نوعه، وكثيرٌ من مزوّدي الملفات يُبلّغ عن `.json` بصفة `text/plain`
     * أو `application/octet-stream` لا `application/json` — فيختفي الملف
     * الصحيح من القائمة ولا يراه المستخدم. فالمفلتر يعرض الثلاثة، ثم
     * يُتحقَّق من الامتداد أدناه فيُرفض غيرُ `.json` صراحةً. فالنتيجة
     * المطلوبة محققة: **لا يُعرض إلا JSON ولا يُقبل إلا JSON**.
     */
    private val jsonOnlyMimeTypes = arrayOf(
        "application/json",
        "text/json",
        "text/plain"
    )


    internal val openDictLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        try {
            if (uri != null) {
                val resolver =
                    context?.contentResolver ?: return@registerForActivityResult
                // التحقق من الامتداد: يُقبل ما ينتهي بـ `.json` فقط، وما
                // لا اسمَ له (بعض المزوّدين) يُحمل على أنه JSON ويُخضعه
                // لفشل التجزئة لاحقاً — فلا يدخل إلا ما يمكن قراءته.
                val name = runCatching {
                    resolver.query(uri, null, null, null, null)?.use { cur ->
                        val idx = cur.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )
                        if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)
                        else null
                    }
                }.getOrNull()
                if (name != null &&
                    !name.endsWith(".json", ignoreCase = true)
                ) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.dict_import_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@registerForActivityResult
                }
                // نقرأ الملف على خيط IO ثم نعرض حوار طريقة الاستيراد
                lifecycleScope.launch(AppDispatchers.io) {
                    val result = runCatching {
                        readJsonWithinLimit(resolver, uri)
                    }.getOrDefault(DictReadResult.Unreadable)
                    withContext(AppDispatchers.main) {
                        if (!isAdded) return@withContext
                        when (result) {
                            is DictReadResult.Ok -> {
                                pendingImportJson = result.json
                                showImportModeDialog()
                            }
                            DictReadResult.TooLarge -> toast(
                                getString(R.string.dict_import_too_large)
                            )
                            DictReadResult.Unreadable -> toast(
                                getString(R.string.dict_import_failed)
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة فتح القاموس",
                t
            )
        }
    }

    internal fun toast(message: String) {
        ToastDebouncer.show(requireContext(), message)
    }

    /** نتيجة قراءة ملف القاموس — تُميّز سببَ الرفض ليُقال للمستخدم. */
    private sealed interface DictReadResult {
        data class Ok(val json: String) : DictReadResult
        data object TooLarge : DictReadResult
        data object Unreadable : DictReadResult
    }

    /**
     * قراءةٌ **محدودةٌ بالبايت** لا `readText()` المفتوح.
     *
     * **لماذا؟** `readText()` يقرأ الملف كلَّه ثم نُفحص الحجم بعده — فسقفُ
     * 8 ميغابايت لا يمنع شيئاً، بل يكتشف الخسارة بعد وقوعها: ملفٌ
     * بمئة ميغابايت يُحمَّل كاملاً فيُOOM التطبيق قبل أن تُقال «رُفض».
     * فنتوقّف عند تجاوز السقف أثناء القراءة، فلا يُحمَّل إلا ما يُسمح.
     */
    private fun readJsonWithinLimit(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri
    ): DictReadResult {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val max = PronunciationDictionary.MAX_IMPORT_BYTES
        val stream = resolver.openInputStream(uri)
            ?: return DictReadResult.Unreadable
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // لا نقرأ أكثر من السقف أبداً: الملفُ الزائد يُرفض
                // وهو لم يُحمَّل بعد.
                if (out.size() + read > max) return DictReadResult.TooLarge
                out.write(buffer, 0, read)
            }
        }
        return DictReadResult.Ok(String(out.toByteArray(), Charsets.UTF_8))
    }

    internal val createDictLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        try {
            if (uri != null) {
                val resolver =
                    context?.contentResolver ?: return@registerForActivityResult
                // التصدير كاملاً على خيط IO عبر الفي إم (لا تجميد في Main)
                vm.exportDict(uri, resolver)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة إنشاء ملف القاموس",
                t
            )
        }
    }

    /** حوار طريقة الاستيراد: دمج مع الإدخالات الحالية أو استبدال كامل، ثم
     *  تطبيق ملف القاموس المُختار من SAF بالطريقة المختارة (يُحفظ نصه في
     *  [pendingImportJson] كي لا يقفز حوار الطريقة خارج سياق النتيجة). */
    private fun showImportModeDialog() {
        val json = pendingImportJson ?: return
        val currentContext = context ?: return
        val options = arrayOf(
            getString(R.string.dict_import_merge),
            getString(R.string.dict_import_replace)
        )
        MaterialAlertDialogBuilder(currentContext)
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
    internal val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        try {
            if (uri != null) {
                val resolver =
                    context?.contentResolver ?: return@registerForActivityResult
                // التصدير كاملاً على خيط IO عبر الفي إم
                vm.exportBackup(uri, resolver)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة إنشاء ملف النسخ الاحتياطي",
                t
            )
        }
    }

    internal val openRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        try {
            if (uri != null) {
                val resolver =
                    context?.contentResolver ?: return@registerForActivityResult
                // القراءة والتفكيك والاستعادة كلها على خيط IO عبر الفي إم
                vm.restoreBackup(uri, resolver)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة استعادة النسخة الاحتياطية",
                t
            )
        }
    }

    internal var announcementSpeaker: AnnouncementSpeaker? = null

    // كتالوج صريح للأصوات المدعومة في ناطق (يتطابق مع onGetVoices)
    // مبسّطة إلى لغتين فقط: "العربية" و"الإنجليزية"
    private lateinit var nateqVoices: List<NateqVoice>

    // كتالوج متدرج (لغة ← محرك ← أصوات) لصفوف الفئات والرسائل
    // والمتصل والبطارية — يُملأ من اكتشاف المحركات الخلفي.
    private lateinit var engineCatalog: EngineVoicesCatalog

    // مهمة اكتشاف المحركات الجارية — إلغاؤها عند بدء جولة جديدة يمنع التسابق
    private var catalogDiscoveryJob: kotlinx.coroutines.Job? = null

    // طلب إذنَي القراءة عند تفعيل إعلان المتصل (READ_PHONE_STATE لاستقبال
    // بث PHONE_STATE المحمي، وREAD_CALL_LOG للوصول إلى الرقم على أندرويد 12+
    // والاسم من سجل المكالمات، وREAD_CONTACTS للبحث عن الاسم في دفتر
    // الاتصالات).
    // يبقى المُطلِق في الفصيل (يحتاج registerForActivityResult) ونتيجتُه
    // تُحمَّل إلى ضابط قسم المتصل الذي يملك المفتاح والحارس.
    internal val callerPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        try {
            if (granted != null && ::callerSection.isInitialized) {
                callerSection.onPermissionsResult(granted)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة أذونات المتصل",
                t
            )
        }
    }

    /** منتقي ملف نغمة الساعة المخصصة (ACTION_OPEN_DOCUMENT). */
    internal val customChimeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result != null && ::timeSection.isInitialized) {
                timeSection.onCustomChimeResult(result)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة نغمة الساعة",
                t
            )
        }
    }

    /** منتقي ملفات أصوات Acapela المضغوطة (.zip) */
    internal val openAcapelaZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        try {
            if (uri != null) {
                val ctx = context ?: return@registerForActivityResult
                val fileName = runCatching {
                    ctx.contentResolver.query(
                        uri, null, null, null, null
                    )?.use { cur ->
                        val idx = cur.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )
                        if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)
                        else null
                    }
                }.getOrNull()

                lifecycleScope.launch(AppDispatchers.main) {
                    Toast.makeText(
                        ctx,
                        R.string.acapela_import_select_file,
                        Toast.LENGTH_SHORT
                    ).show()

                    val result = withContext(AppDispatchers.io) {
                        AcapelaVoiceImporter.importFromZip(ctx, uri, fileName)
                    }

                    if (!isAdded) return@launch
                    if (result.success) {
                        val voicesNames =
                            result.importedVoices.joinToString("، ")
                        val msg = getString(
                            R.string.acapela_import_success,
                            voicesNames
                        )
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val err = result.errorMessage ?: getString(
                            R.string.acapela_import_error,
                            ""
                        )
                        Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                    }
                    refreshEmbeddedUiIfStorageChanged()
                    refreshVoiceCatalogDiscovery()
                }
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة اختيار zip لـ Acapela",
                t
            )
        }
    }

    /** منتقي مجلد أصوات Acapela عبر SAF */
    internal val openAcapelaFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        try {
            if (uri != null) {
                val ctx = context ?: return@registerForActivityResult
                lifecycleScope.launch(AppDispatchers.main) {
                    Toast.makeText(
                        ctx,
                        R.string.acapela_import_folder_select,
                        Toast.LENGTH_SHORT
                    ).show()

                    val result = withContext(AppDispatchers.io) {
                        AcapelaVoiceImporter.importFromTreeUri(ctx, uri)
                    }

                    if (!isAdded) return@launch
                    if (result.success) {
                        val voicesNames =
                            result.importedVoices.joinToString("، ")
                        val msg = getString(
                            R.string.acapela_import_success,
                            voicesNames
                        )
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val err = result.errorMessage ?: getString(
                            R.string.acapela_import_error,
                            ""
                        )
                        Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                    }
                    refreshEmbeddedUiIfStorageChanged()
                    refreshVoiceCatalogDiscovery()
                }
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة اختيار مجلد Acapela",
                t
            )
        }
    }

    // طلب إذن قراءة الرسائل الواردة لحظة تفعيل قراءة الرسائل فقط (لا عند
    // أول تشغيل). الوضع المختار (full/source) لا يُحفظ إلا بعد المنح الفعلي —
    // مسؤولية الضابط (بند [9]).
    internal val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            if (::smsSection.isInitialized) {
                smsSection.onSmsPermissionResult(granted)
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة إذن الرسائل",
                t
            )
        }
    }

    internal var pendingContactPickerCallback: ((String?, String?) -> Unit)? =
        null

    internal val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val uri = result?.data?.data
            if (result != null &&
                result.resultCode == android.app.Activity.RESULT_OK &&
                uri != null
            ) {
                val resolver = context?.contentResolver
                val cursor = runCatching {
                    resolver?.query(
                        uri,
                        arrayOf(
                            android.provider.ContactsContract.CommonDataKinds
                                .Phone.NUMBER,
                            android.provider.ContactsContract.CommonDataKinds
                                .Phone.DISPLAY_NAME
                        ),
                        null,
                        null,
                        null
                    )
                }.getOrNull()
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val numIdx = c.getColumnIndex(
                            android.provider.ContactsContract.CommonDataKinds
                                .Phone.NUMBER
                        )
                        val nameIdx = c.getColumnIndex(
                            android.provider.ContactsContract.CommonDataKinds
                                .Phone.DISPLAY_NAME
                        )
                        val num = if (numIdx >= 0) c.getString(numIdx) else null
                        val name =
                            if (nameIdx >= 0) c.getString(nameIdx) else null
                        pendingContactPickerCallback?.invoke(name, num)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(
                "VoiceSelectionFragment",
                "فشل معالجة نتيجة اختيار جهة الاتصال",
                t
            )
        } finally {
            pendingContactPickerCallback = null
        }
    }

    // ===== ضابط الأكورديون والتنقّل: بطاقات الأقسام وبناء أسطر الحالة =====
    internal lateinit var accordion: SettingsAccordionController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Trace.beginSection("VoiceSelectionFragment.onViewCreated")
        try {
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

        // كتالوج متدرج — يبدأ فارغاً فيعتمد على الأصوات الاحتياطية
        // (العربية/الإنجليزية) ثم يُملأ من الاكتشاف الخلفي.
        engineCatalog = EngineVoicesCatalog(
            emptyMap(),
            getString(R.string.voice_name_arabic),
            getString(R.string.voice_name_english)
        )

        // قسم المحركات: اكتشاف المحركات المثبتة فقط (لا صندوق اختيار عام —
        // لا محرك افتراضي؛ محرك كل لغة/فئة يُحسم وقت النطق)
        voicePreview = VoicePreviewHelper(requireContext().applicationContext)
        accordion = SettingsAccordionController(
            this,
            settings,
            nateqVoices,
            engineCatalog,
            onNavigate = { group, sectionId ->
                // تأجيل اكتشاف المحركات حتى فتح قسمها أو مجموعة المحركات
                if (group == 0 ||
                    sectionId == R.id.ll_caller_announcement_settings ||
                    sectionId == R.id.ll_sms_reading_settings
                ) {
                    ensureVoiceCatalogDiscovered()
                }
            }
        ).apply {
            setup(view, viewLifecycleOwner)
        }
        engineSection = EngineSectionController(
            this, settings, engines, voicePreview
        ).apply {
                onStatusChanged = { accordion.updateSectionStatuses() }
            }
        engineSection.setupEngineDiscovery(viewLifecycleOwner.lifecycleScope)

        // لوحة فئات الأصوات بعد إعادة التصميم: فئة واحدة معروضة في كل
        // مرة (لغة/محرك/صوت/أشرطة) تختارها من سبnner علوي واحد، أو لوحة
        // إعلان المتصل — والتعديلات لا تُحفظ إلا بضغط زر الحفظ أسفل
        // الشاشة (مع تأكيدٍ عند مغادرة فئة بتعديلات غير محفوظة).
        categoryVoicePanel = CategoryVoicePanelController(
            this,
            settings,
            engineCatalog,
            // معاينة المتصل باللغة المختارة (عربي/إنجليزي). الضابط يُنشأ
            // قبل callerSection فيُحال النداء إليه متى جُهّز.
            onPreviewCaller = { langTag ->
                if (::callerSection.isInitialized) {
                    callerSection.previewCaller(langTag)
                }
            }
        ).apply { setup(view) }

        // زر الحفظ الصريح: يكتب الفئة المعروضة فقط (لا كل الفئات دفعة),
        // فيستقر الصوت المحفوظ بلا اعتماد على الاستدلال اللغوي.
        val btnSaveCategories:
            com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_save_categories)
        btnSaveCategories.setOnClickListener {
            if (performExplicitVoiceSave()) {
                view.announceCompat(getString(R.string.categories_saved))
                accordion.updateSectionStatuses()
            } else {
                view.announceCompat(
                    getString(R.string.settings_save_failed)
                )
            }
        }

        val btnRefreshVoices = view.findViewById<
            com.google.android.material.button.MaterialButton
        >(R.id.btn_refresh_voices)
        btnRefreshVoices?.setOnClickListener {
            view.announceCompat(getString(R.string.convert_languages_loading))
            Toast.makeText(
                requireContext(),
                R.string.convert_languages_loading,
                Toast.LENGTH_SHORT
            ).show()
            refreshVoiceCatalogDiscovery {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.voices_refreshed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

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
                openDictLauncher.launch(jsonOnlyMimeTypes)
            }
        }
        btnExportDict = view.findViewById(R.id.btn_export_dict)
        btnExportDict.setOnClickListener {
            runCatching { createDictLauncher.launch(DICT_EXPORT_FILE_NAME) }
        }
        val btnClearDict = view.findViewById<MaterialButton>(
            R.id.btn_clear_dict
        )
        btnClearDict.setOnClickListener { showClearDictConfirmDialog() }

        val btnDictStandalone = view.findViewById<MaterialButton>(
            R.id.btn_dict_standalone_max_len
        )
        fun updateDictStandaloneButton() {
            val maxLen = runCatching {
                settings.getDictionaryStandaloneMaxLength()
            }.getOrDefault(1)
            btnDictStandalone?.text = getString(
                R.string.dict_standalone_max_length_summary, maxLen
            )
        }
        updateDictStandaloneButton()
        btnDictStandalone?.setOnClickListener {
            val current = runCatching {
                settings.getDictionaryStandaloneMaxLength()
            }.getOrDefault(1)
            val options = arrayOf(
                "0 (معطّل - في أي موضع)",
                "1 (حرف واحد أو أقل - الافتراضي)",
                "2 (حرفان أو أقل)",
                "3 (3 أحرف أو أقل)"
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dict_standalone_max_length_title)
                .setSingleChoiceItems(
                    options, current.coerceIn(0, 3)
                ) { dialog, which ->
                    runCatching {
                        settings.setDictionaryStandaloneMaxLength(which)
                    }
                    updateDictStandaloneButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        val switchCaseInsensitive = view.findViewById<SwitchMaterial>(
            R.id.switch_dict_case_insensitive
        )
        switchCaseInsensitive?.isChecked = runCatching {
            settings.isDictionaryCaseInsensitive()
        }.getOrDefault(true)
        switchCaseInsensitive?.setOnCheckedChangeListener { _, isChecked ->
            runCatching {
                settings.setDictionaryCaseInsensitive(isChecked)
                pronunciationDict.invalidate()
            }
        }

        val btnTimeSuffixM = view.findViewById<MaterialButton>(
            R.id.btn_time_suffix_m_mode
        )
        val mModes = listOf(
            SettingsRepository.TIME_SUFFIX_M_CONTEXT to
                getString(R.string.time_suffix_m_context),
            SettingsRepository.TIME_SUFFIX_M_LETTER to
                getString(R.string.time_suffix_m_letter),
            SettingsRepository.TIME_SUFFIX_M_EVENING to
                getString(R.string.time_suffix_m_evening)
        )
        fun updateTimeSuffixMButton() {
            val cur = runCatching {
                settings.getTimeSuffixMMode()
            }.getOrDefault(SettingsRepository.TIME_SUFFIX_M_CONTEXT)
            val label = mModes.find { it.first == cur }?.second
                ?: mModes[0].second
            btnTimeSuffixM?.text = getString(R.string.time_suffix_m_btn, label)
        }
        updateTimeSuffixMButton()
        btnTimeSuffixM?.setOnClickListener {
            val cur = runCatching {
                settings.getTimeSuffixMMode()
            }.getOrDefault(SettingsRepository.TIME_SUFFIX_M_CONTEXT)
            val curIdx = mModes.indexOfFirst { it.first == cur }
                .coerceAtLeast(0)
            val labels = mModes.map { it.second }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.time_suffix_m_title)
                .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                    runCatching {
                        settings.setTimeSuffixMMode(mModes[which].first)
                    }
                    updateTimeSuffixMButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        val btnTestDictText = view.findViewById<MaterialButton>(
            R.id.btn_test_dict_text
        )
        btnTestDictText?.setOnClickListener {
            showDictProcessTestDialog()
        }

        val btnTraceDictText = view.findViewById<MaterialButton>(
            R.id.btn_trace_dict_text
        )
        btnTraceDictText?.setOnClickListener {
            showDictTraceDialog()
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

        val btnEmojiLanguage = view.findViewById<MaterialButton>(
            R.id.btn_emoji_language
        )
        fun updateEmojiLanguageButton() {
            val current = runCatching {
                settings.getEmojiPronunciationLanguage()
            }.getOrDefault("AUTO")
            val label = when (current.uppercase()) {
                "AR" -> getString(R.string.emoji_reading_language_arabic)
                "EN" -> getString(R.string.emoji_reading_language_english)
                else -> getString(R.string.emoji_reading_language_auto)
            }
            btnEmojiLanguage?.text = getString(
                R.string.emoji_reading_language_btn, label
            )
        }
        updateEmojiLanguageButton()
        btnEmojiLanguage?.setOnClickListener {
            val current = runCatching {
                settings.getEmojiPronunciationLanguage()
            }.getOrDefault("AUTO")
            val currentIdx = when (current.uppercase()) {
                "AR" -> 1
                "EN" -> 2
                else -> 0
            }
            val options = arrayOf(
                getString(R.string.emoji_reading_language_auto),
                getString(R.string.emoji_reading_language_arabic),
                getString(R.string.emoji_reading_language_english)
            )
            val codes = listOf("AUTO", "AR", "EN")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.emoji_reading_language_title)
                .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                    val chosen = codes[which]
                    runCatching {
                        settings.setEmojiPronunciationLanguage(chosen)
                    }
                    updateEmojiLanguageButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        // زر جعل ناطق المحرك الافتراضي (يفتح شاشة TTS النظامية لاختياره
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

        // زر الإبلاغ عن خطأ: الضغطة الأولى تبدأ مراقبة السجل التقني ثم
        // «تم» تجمع التقرير من نافذة الالتقاط الحية وتشاركه (مراجعة
        // تقرير «أبو أحمد»: التقاط لحظة الضغط فقط كان يُفرغ التقرير).
        btnReportError = view.findViewById(R.id.btn_report_error)
        btnReportError.setOnClickListener { onReportErrorClicked() }
        syncReportErrorButtonText()

        // زر آخر التحديثات: يعرض ملخص أحدث إصدار وتغييراته في حوار
        btnChangelog = view.findViewById(R.id.btn_changelog)
        btnChangelog.setOnClickListener { showChangelogDialog() }

        view.findViewById<View>(R.id.btn_read_later_open)
            .setOnClickListener { showReadLaterDialog() }

        // زر دليل الميزات والإيماءات (ع٤١-ب14.3): شرح مختصر يقرؤه TalkBack
        view.findViewById<View>(R.id.btn_a11y_guide).setOnClickListener {
            showA11yGuideDialog()
        }

        // زر التشخيص: يعرض معلومات شاملة عن حالة التطبيق والمحركات والأذونات
        // مع إمكانية النسخ إلى الحافظة
        val btnDiagnostics = view.findViewById<View>(R.id.btn_diagnostics)
        btnDiagnostics.setOnClickListener { showDiagnosticsDialog() }

        // إنشاء ضابطات الأقسام وربطها (المرحلة ج): كل ضابط يسحب عناصره
        // ويبني مستمعيه عند setup()، وonStatusChanged تُحدّث أسطر حالة
        // الأكورديون.
        timeSection = TimeAnnouncementController(
            this, settings, nateqVoices,
            { accordion.navigateToSection(R.id.ll_oem_guidance_content) },
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        batterySection = BatteryAnnouncementController(
            this, settings,
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        notificationSection = NotificationReadingController(
            this, settings, nateqVoices, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        callerSection = CallerAnnouncementController(
            this, settings, engineCatalog,
            onStatusChanged = { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        smsSection = SmsReadingController(
            this, settings, engineCatalog,
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        generalSection = GeneralSettingsController(
            this, settings, voicePreview,
            { accordion.updateSectionStatuses() }
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
        textProcessingSection = TextProcessingController(
            settings, requireContext(), { accordion.updateSectionStatuses() }
        ).apply { bind(view) }
        instantSilenceSection = InstantSilenceController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        a11yServicesSection = ScreenServicesCardController(
            requireContext(), settings
        ) { accordion.updateSectionStatuses() }.apply {
            contactPickerLauncher = { callback ->
                pendingContactPickerCallback = callback
                val pickIntent = Intent(
                    Intent.ACTION_PICK,
                    android.provider.ContactsContract.CommonDataKinds
                        .Phone.CONTENT_URI
                )
                this@VoiceSelectionFragment.contactPickerLauncher
                    .launch(pickIntent)
            }
            setup(view)
        }
        embeddedDownloaderSection = EmbeddedEngineDownloaderController(
            this, settings, voicePreview
        ).apply {
            onStatusChanged = { accordion.updateSectionStatuses() }
            setup(view)
        }
        accordion.embeddedDownloaderStatusSupplier = {
            embeddedDownloaderSection.buildStatusSummary()
        }
        oemGuidance = OemGuidanceController(this).apply { setup(view) }

        view.findViewById<View>(R.id.btn_emergency_voice)?.setOnClickListener {
            EmergencyVoiceController.show(
                requireContext(),
                settings,
                engineCatalog,
                voicePreview
            )
        }
        EngineFilterController(
            requireContext(),
            settings
        ) {
            accordion.updateSectionStatuses()
            refreshVoiceCatalogDiscovery {
                if (::categoryVoicePanel.isInitialized) {
                    categoryVoicePanel.refreshCategoryEngines()
                    categoryVoicePanel.refreshLanguages()
                }
                if (::callerSection.isInitialized) {
                    callerSection.refreshCallerVoices()
                }
                if (::smsSection.isInitialized) {
                    smsSection.refreshSmsVoices()
                }
            }
        }.setup(view)
        setupLanguageToggle()
        setupCheckUpdates()
        engineSection.setupAutoConvertUI(view)
        setupSaveAndResetButtons()
        setupBackupRestoreButtons()
        setupToolsAppShortcuts()
        accordion.updateSectionStatuses()
        // بند 4.6: إعادة فتح الشاشة (مجموعة/قسم) التي كان يعدّلها المستخدم
        // قبل تدوير الجهاز بدل طردِه إلى الصفحة الرئيسية.
        if (savedInstanceState != null) {
            accordion.restoreState(savedInstanceState)
            if (::timeSection.isInitialized) {
                timeSection.restoreInstanceState(savedInstanceState)
            }
        }

        // التحديث التفاعلي: المراجعة الابتدائية (0) لا تُحدّث شيئاً،
        // وأي مراجعة لاحقة (استعادة/إعادة ضبط) تُعيد بناء كل أقسام
        // الواجهة تلقائياً.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.settingsRevision.collect { revision ->
                        if (revision > 0) refreshAllSettingsUi()
                    }
                }
                launch {
                    vm.operationEvents.collect(::handleOperationEvent)
                }
            }
        }

            // فحص تلقائي عند فتح التطبيق: يُنبه بوجود تحديث (صامت إن لم يوجد)
            checkForUpdatesOnStart()
            VoiceReadinessCoordinator.prepareActiveVoices(requireContext())
        } finally {
            Trace.endSection()
        }
    }

    /** بند 4.7: عند العودة من شاشات النظام (منح إذن المنبهات الدقيقة /
     *  إشعارات) تُعاد حالة صفوف طلب الإذن ومُلخّصات الأقسام فوراً بدل
     *  الانتظار حتى يعود الفصيل من الخلفية. */
    override fun onResume() {
        super.onResume()
        if (::timeSection.isInitialized) timeSection.refreshExactAlarmRow()
        if (::oemGuidance.isInitialized) {
            oemGuidance.refreshInstantIdentityStatus()
        }
        if (::a11yServicesSection.isInitialized) {
            a11yServicesSection.updateUiState()
        }
        if (::accordion.isInitialized) accordion.updateSectionStatuses()
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.onResume()
        }
        refreshEmbeddedUiIfStorageChanged()
        ensureVoiceCatalogDiscovered()
        resumeUpdateDownloadIfNeeded()
        refreshToolsShortcuts()
    }

    /**
     * ع٢٩-9: عند عودة الشاشة للمقدمة، إن تغيّر رقم إصدار التخزين
     * (تنزيل/حذف/هجرة حدثت وهي في الخلفية) يُبطل الكاش وتُعاد قوائم
     * المحركات فوراً بدل انتظار مهلة الكاش.
     */
    internal fun refreshEmbeddedUiIfStorageChanged() {
        val version = EmbeddedStorage.version
        if (version == lastSeenStorageVersion) return
        val isFirstRead = lastSeenStorageVersion == -1
        lastSeenStorageVersion = version
        if (isFirstRead) return

        EnginePicker.invalidateAfterStorageChange()
        if (::engineSection.isInitialized) {
            engineSection.setupEngineDiscovery(
                viewLifecycleOwner.lifecycleScope
            )
        }
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.refreshCategoryEngines()
        }
        if (::accordion.isInitialized) accordion.updateSectionStatuses()
        view?.let { root ->
            if (::embeddedDownloaderSection.isInitialized) {
                embeddedDownloaderSection.refresh(root)
            }
        }
        refreshVoiceCatalogDiscovery()
    }

    /** بند 4.6: حفظ موضع التنقّل في الأكورديون لاستعادته بعد التدوير. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::accordion.isInitialized) {
            outState.putAll(accordion.saveState())
        }
        if (::timeSection.isInitialized) {
            timeSection.saveInstanceState(outState)
        }
    }

    override fun onDestroyView() {
        // تفريغ مساند القوائم لمنع تسريب المراجع والواجهات
        if (::rvPronunciationDict.isInitialized) {
            rvPronunciationDict.adapter = null
        }
        pronunciationDictAdapter = null

        // إغلاق المتحدث المستقل الخاص بالمعاينة (إن أُنشئ) حتى لا يبقى محرك
        // TTS مفتوحاً بعد مغادرة الشاشة. المثيل هنا خاص بالشاشة وليس المشترك
        // (getInstance) الذي تُدار حياته في مستقبلات الإعلانات التلقائية.
        // **بند 4.4:** shutdown() لا stop() — المعاينة الخاصة تُغلَق تاماً
        // (محرك TTS نفسه) عند مغادرة الشاشة بدل بقائها قيد الحياة حتى
        // تحرر المحرك وتتوقف تسريبات مؤقتات الاسترداد.
        announcementSpeaker?.shutdown()
        announcementSpeaker = null
        // إغلاق معاينة النطق المشتركة (بند 4.2) — أي محرك TTS مؤقت فُتح
        // للمعاينة يُغلق تماماً عند مغادرة الشاشة.
        if (::voicePreview.isInitialized) voicePreview.release()
        if (::oemGuidance.isInitialized) oemGuidance.release()
        // إغلاق كل النوافذ المفتوحة حتى لا تتسرب مراجع الواجهة (WindowLeaked)
        // عند تدوير الشاشة أو مغادرتها.
        val open = activeDialogs.toList()
        activeDialogs.clear()
        open.forEach { runCatching { it.dismiss() } }
        // تصفير مراجع العرض في كل الضوابط (بند 4.1) وحتى لا تبقى شجرة العرض
        // القديمة محتجزة عبر أي ضابط بعد تدوير الشاشة أو مغادرتها.
        if (::categoryVoicePanel.isInitialized) categoryVoicePanel.cleanup()
        if (::engineSection.isInitialized) engineSection.cleanup()
        if (::timeSection.isInitialized) timeSection.cleanup()
        if (::batterySection.isInitialized) batterySection.cleanup()
        if (::notificationSection.isInitialized) notificationSection.cleanup()
        if (::callerSection.isInitialized) callerSection.cleanup()
        if (::smsSection.isInitialized) smsSection.cleanup()
        if (::generalSection.isInitialized) generalSection.cleanup()
        if (::numberSection.isInitialized) numberSection.cleanup()
        if (::deviceHealthSection.isInitialized) deviceHealthSection.cleanup()
        if (::textReadingSection.isInitialized) textReadingSection.cleanup()
        if (::instantSilenceSection.isInitialized) {
            instantSilenceSection.cleanup()
        }
        if (::embeddedDownloaderSection.isInitialized) {
            embeddedDownloaderSection.detach()
        }
        updateReceiver?.let { receiver ->
            runCatching {
                requireContext().applicationContext
                    .unregisterReceiver(receiver)
            }
            updateReceiver = null
        }
        if (::accordion.isInitialized) accordion.cleanup()
        catalogDiscoveryJob?.cancel()
        catalogDiscoveryJob = null
        updatePollingJob?.cancel()
        updatePollingJob = null
        updateProgressDialog = null
        super.onDestroyView()
    }

    /**
     * ع٤٠-2: اكتشاف متدرج ومحدث للمحركات والأصوات بتزامن واحد:
     * يلغي أي عملية جارية سابقة حتى لا تتسابق نتيجتان، ويسجل الأخطاء
     * دون ابتلاعها، ويمرر unavailableEngines إلى EngineVoicesCatalog.
     */
    internal fun buildActiveEnginesSet(): Set<String>? {
        if (!VisibleEngines.isFilterEnabled()) {
            return null
        }
        // [بناء 10728] لا يُضاف محرك أخفاه المستخدم: كل إضافة تمرّ على
        // VisibleEngines.isVisible، وإلا عاد المحرك المخفي إلى الاكتشاف
        // عبر تفضيلات اللغة أو الفئات.
        val candidates = mutableListOf<String?>(
            settings.getEnginePreferenceForLanguage("ar").engine,
            // «ع٤٠-2»: تفضيل اللغة الإنجليزية (الخريطة الديناميكية)
            // يشارك في المحركات النشطة
            settings.getEnginePreferenceForLanguage("en").engine
        )

        val categories = SettingsRepository.ALL_VOICE_CATEGORIES +
            listOf("emoji")
        for (cat in categories) {
            candidates.add(settings.getEngineForCategory(cat))
        }
        for (lang in listOf("ar", "en")) {
            candidates.add(settings.getEngineForLanguage(lang))
        }
        return activeEnginesOrNull(
            allowed = VisibleEngines.visibleSet(),
            candidates = candidates,
            isVisible = VisibleEngines::isVisible
        )
    }

    /** ع٤٠-2: كشف عدم اتساق أزواج (محرك، صوت) في لغات نقطة الدخول. */
    internal fun checkEntrypointVoicesConsistency():
        List<Triple<String, String, String>> {
        val entries = listOf("ar", "en").map { lang ->
            val prefs = settings.getEnginePreferenceForLanguage(lang)
            Triple(lang, prefs.engine, prefs.voiceName)
        }
        return EngineLanguageConsistency.mismatchedLanguages(entries)
    }

    internal fun checkMismatchedCategoryVoices():
        List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val categories = SettingsRepository.ALL_VOICE_CATEGORIES +
            listOf("emoji")
        for (cat in categories) {
            val eng = settings.getEngineForCategory(cat)
            val voice = settings.getPreferredVoiceIdForCategory(cat)
            if (!eng.isNullOrBlank() && !voice.isNullOrBlank()) {
                if (!VoiceIdContract.isVoiceBelongingToEngine(eng, voice)) {
                    result.add(Triple(cat, voice, eng))
                }
            }
        }
        return result
    }

    internal fun checkAndPromptVoiceMismatchFix() {
        if (!isAdded) return
        val mismatched = checkMismatchedCategoryVoices()
        if (mismatched.isEmpty()) return
        val msgList = mismatched.joinToString("\n") { (cat, voice, eng) ->
            "فئة $cat: الصوت $voice لا يتبع المحرك $eng"
        }
        val announcement = "تنبيه: " + mismatched.joinToString("، ") {
            (cat, voice, eng) -> "فئة $cat: الصوت $voice لا يتبع المحرك $eng"
        }
        A11ySpeechCoordinator.speakAnnouncement(
            requireContext(),
            announcement
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_mismatch_title)
            .setMessage(msgList)
            .setPositiveButton(R.string.voice_mismatch_fix) { _, _ ->
                for ((cat, _, _) in mismatched) {
                    settings.setPreferredVoiceIdForCategory(cat, "")
                }
                Toast.makeText(
                    requireContext(),
                    R.string.voice_mismatch_fixed_toast,
                    Toast.LENGTH_LONG
                ).show()
                refreshVoiceCatalogDiscovery()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun refreshVoiceCatalogDiscovery(
        onComplete: (() -> Unit)? = null
    ) {
        catalogDiscoveryJob?.cancel()
        catalogDiscoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val active = buildActiveEnginesSet()
            val discovery = withContext(AppDispatchers.io) {
                try {
                    VoiceCatalog.discoverAllLanguagesAcrossEngines(
                        requireContext(),
                        active
                    )
                } catch (t: Throwable) {
                    Log.w(
                        "VoiceSelectionFragment",
                        "Voice catalog discovery failed: ${t.message}",
                        t
                    )
                    DiscoveryResult(
                        languages = emptyMap<String, List<EngineWithVoices>>(),
                        unavailableEngines = listOf(
                            UnavailableEngine(
                                enginePackage = "all",
                                engineLabel = "Discovery Error",
                                message = t.localizedMessage ?: t.message
                            )
                        )
                    )
                }
            }
            val unavailMap = discovery.unavailableEngines.associate { row ->
                row.enginePackage to (row.message ?: row.code ?: "غير متاح")
            }
            val rows = discovery.languages.mapValues { (_, engines) ->
                engines.map { row ->
                    EngineVoicesRow(
                        row.enginePackage,
                        row.engineLabel,
                        row.voices.map { voice ->
                            VoiceOption(
                                voice.name,
                                voice.name,
                                row.enginePackage
                            )
                        }
                    )
                }
            }
            engineCatalog.update(rows, unavailMap)
            if (!isAdded) return@launch
            if (::categoryVoicePanel.isInitialized) {
                categoryVoicePanel.refreshCategoryEngines()
                categoryVoicePanel.refreshLanguages()
            }
            if (::smsSection.isInitialized) smsSection.refreshSmsVoices()
            if (::callerSection.isInitialized) {
                callerSection.refreshCallerEngines()
                callerSection.refreshCallerVoices()
            }
            checkAndPromptVoiceMismatchFix()
            onComplete?.invoke()
        }
    }

    internal fun refreshCategoryEngines() {
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.refreshCategoryEngines()
        }
    }

    internal fun ensureVoiceCatalogDiscovered() {
        if (!::engineCatalog.isInitialized ||
            !engineCatalog.hasDiscoveredData()
        ) {
            refreshVoiceCatalogDiscovery()
        }
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
        ToastDebouncer.show(requireContext(), stats, duration)
        view?.announceCompat(getString(stats))
    }

    // ===== اختيار المحرك وحوار التحويل التلقائي: انتقلا إلى
    // EngineSectionController =====
    /** نسخة حصرية للفصيل من محلّل الـ Spinner الأساسي (المنفَّذ في
     *  EngineSectionController). */
    internal fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        simpleAdapter(requireContext(), items)

    /** محوّل قائمة إدخالات القاموس — يُعاد إنشاؤه عند كل تحديث
     *  (إضافة/تعديل/حذف/استيراد) — يتجنب خطأ submitList على نفس مرجع
     *  القائمة. */
    internal var pronunciationDictAdapter: PronunciationDictAdapter? = null

    internal fun openDeveloperSupport() {
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

    // ===== آخر التحديثات / دليل الوصولية (مفوّض) =====
    internal val infoDialogs by lazy { SettingsInfoDialogsController(this) }

    // ===== اقرأ لاحقاً =====
    fun openReadLater() {
        accordion.openGroupByIndex(2)
        showReadLaterDialog()
    }

    private fun showReadLaterDialog() {
        val items = runCatching { settings.getReadLaterItems() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) {
            val emptyDialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext()
                )
                    .setTitle(R.string.read_later_title)
                    .setMessage(R.string.read_later_empty)
                    .setPositiveButton(R.string.read_later_back, null)
                    .setOnDismissListener {
                        view?.findViewById<View>(R.id.btn_read_later_open)
                            ?.requestFocus()
                    }
                    .create()
            trackDialog(emptyDialog)
            emptyDialog.show()
            return
        }
        val labels = items.map { item ->
            val preview = item.text.replace("\n", " ").trim()
            if (preview.length > 80) {
                preview.take(80) + "…"
            } else {
                preview
            }
        }.toTypedArray()

        val listDialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext()
            )
                .setTitle(R.string.read_later_title)
                .setItems(labels) { _, which ->
                    val item = items.getOrNull(which) ?: return@setItems
                    showReadLaterItemActions(item)
                }
                .setNeutralButton(R.string.read_later_clear_all) { _, _ ->
                    runCatching { settings.clearReadLater() }
                    showReadLaterDialog()
                }
                .setNegativeButton(R.string.read_later_back, null)
                .setOnDismissListener {
                    view?.findViewById<View>(R.id.btn_read_later_open)
                        ?.requestFocus()
                }
                .create()
        trackDialog(listDialog)
        listDialog.show()
    }

    private fun showReadLaterItemActions(
        item: com.aymankhattab.nateq.core.data.SettingsRepository
            .ReadLaterItem
    ) {
        val options = arrayOf(
            getString(R.string.read_later_play),
            getString(R.string.read_later_stop),
            getString(R.string.read_later_delete)
        )
        val actionsDialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext()
            )
                .setTitle(R.string.read_later_item_actions_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val playText = settings.getReadLaterFullText(
                                item.id
                            ) ?: item.text
                            A11ySpeechCoordinator.speakAnnouncement(
                                context = requireContext(),
                                text = playText,
                                isInterruptAllowed = true,
                                source = com.aymankhattab.nateq.core.audio
                                    .engine.SpeechEntryPoints.Source
                                    .SCREEN_READER
                            )
                        }
                        1 -> {
                            runCatching {
                                com.aymankhattab.nateq.core.audio
                                    .announcement.AnnouncementSpeaker
                                    .getInstance(requireContext())
                                    .stop()
                            }
                        }
                        2 -> {
                            runCatching {
                                settings.removeReadLaterItem(item.id)
                            }
                            showReadLaterDialog()
                        }
                    }
                }
                .setNegativeButton(R.string.read_later_back) { _, _ ->
                    showReadLaterDialog()
                }
                .create()
        trackDialog(actionsDialog)
        actionsDialog.show()
    }

    internal fun showChangelogDialog() = infoDialogs.showChangelogDialog()

    internal fun showA11yGuideDialog() = infoDialogs.showA11yGuideDialog()




    // مساعدات التشخيص (dirSize/formatSize/maskRegistration ووصف المحرك)
    // فُصلت إلى VoiceSelectionDiagnostics.kt في الحزمة نفسها، فتبقى
    // الاستدعاءات القديمة هنا تعمل بلا تغيير سلوك.
}

/**
 * [بناء 10728] بناء مجموعة المحركات النشطة للاكتشاف: كل محرك (مسموح أو
 * مرشَّح من تفضيلات اللغة/الفئة) يمرّ على [isVisible]، فلا يعود محرك
 * أخفاه المستخدم إلى الاكتشاف. القائمة الفارغة تعود `null` (بلا تقييد)
 * حمايةً من إفراغ قوائم الأصوات إذا كان الفلتر مفعّلاً بلا اختيار.
 */
internal fun activeEnginesOrNull(
    allowed: Iterable<String>,
    candidates: Iterable<String?>,
    isVisible: (String) -> Boolean
): Set<String>? {
    val set = mutableSetOf<String>()
    allowed.forEach { engine ->
        if (engine.isNotBlank() && isVisible(engine)) set.add(engine)
    }
    candidates.forEach { engine ->
        if (!engine.isNullOrBlank() && isVisible(engine)) set.add(engine)
    }
    return set.ifEmpty { null }
}
