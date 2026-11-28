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
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.UpdateChecker
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.BuildConfig
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
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation
import com.aymankhattab.nateq.core.audio.accessibility.NextEngineSwitcher
import com.aymankhattab.nateq.core.audio.accessibility.TtsSettingsAutomation

internal fun VoiceSelectionFragment.onReportErrorClicked() {
    if (DiagnosticLogRecorder.isRecording()) {
        finishErrorReportCollection()
    } else {
        startErrorReportCollection()
    }
}

internal fun VoiceSelectionFragment.syncReportErrorButtonText() {
    btnReportError?.text = getString(
        if (DiagnosticLogRecorder.isRecording()) {
            R.string.report_error_finish
        } else {
            R.string.report_error
        }
    )
}

/** الضغطة الأولى: إطلاق مراقبة السجل (يستمر عبر كل الشاشات حتى
 *  يضغط المستخدم «تم» بعد إعادة إنتاج المشكلة). */
internal fun VoiceSelectionFragment.startErrorReportCollection() {
    if (!DiagnosticLogRecorder.start()) return
    syncReportErrorButtonText()
    val msg = getString(R.string.report_error_started)
    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    view?.announceCompat(msg)
}

/** الضغطة الثانية: إيقاف المراقبة وحصاد لقطة السجل الحية وبناء
 *  التقرير الكامل منها (يصرّف عبر الحارس لئلا يكتب كوروتينان
 *  نفس ملف الكاش). */
internal fun VoiceSelectionFragment.finishErrorReportCollection() {
    val captured = DiagnosticLogRecorder.stop()
    val currentContext = context ?: return
    // حارس منع التكرار: ضغطة «تم» المتتالية أثناء جمعٍ جارٍ تُرفض
    // حتى لا يتوازى كوروتينان على نفس ملف الكاش.
    if (!errorReportGate.tryBegin()) return
    Toast.makeText(
        currentContext,
        R.string.report_error_collecting,
        Toast.LENGTH_SHORT
    ).show()
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val report = runCatching {
                buildErrorReport(currentContext, captured)
            }.getOrNull()
            if (!isAdded ||
                activity == null ||
                activity?.isFinishing == true
            ) {
                return@launch
            }
            val ctx = context ?: return@launch
            if (report.isNullOrBlank()) {
                Toast.makeText(
                    ctx,
                    R.string.report_error_empty,
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(
                    getString(R.string.report_error_empty)
                )
                return@launch
            }
            val logFile = writeLogToCacheFile(ctx, report)
            showErrorReportDialog(ctx, report, logFile)
        } finally {
            // يُفتح الباب مجدداً بعد اكتمال/فشل/إلغاء رحلة الجمع (ينفذ
            // هذا الحظر حتى عند إلغاء النطاق أو الإرجاع المبكر أعلاه).
            errorReportGate.finish()
            if (isAdded) {
                syncReportErrorButtonText()
            }
        }
    }
}

/**
 * يبني تقريراً شاملاً يجمع بين معلومات التشخيص وحالة المحركات والأجهزة
 * وأحدث سطور logcat لعملية التطبيق (أخطاء وتحذيرات ونشاط حديث).
 */
internal suspend fun VoiceSelectionFragment.buildErrorReport(
    context: android.content.Context,
    capturedLines: List<String>
): String =
    kotlinx.coroutines.withContext(AppDispatchers.io) {
        val diagnostics = buildDiagnosticsInfo(context)
        val logLines = capturedLines.toMutableList()
        val ttsLogLines = mutableListOf<String>()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val procs = am?.runningAppProcesses?.filter {
            it.processName.startsWith(context.packageName)
        }.orEmpty()
        val ttsProc = procs.firstOrNull { it.processName.endsWith(":tts") }

        if (logLines.isEmpty()) {
            // احتياط: لم تطلق المراقبة الحية أسطراً — سحب لحظة
            // مباشر لأحدث 600 سطر للعملية الرئيسية.
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d",
                        "-t", "600",
                        "--pid", android.os.Process.myPid().toString()
                    )
                )
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        logLines.add(line)
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                android.util.Log.e(
                    "NATEQ_APP", "logcat collect main failed", e
                )
            }
        }

        if (ttsProc != null) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d",
                        "-t", "600",
                        "--pid", ttsProc.pid.toString()
                    )
                )
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        ttsLogLines.add(line)
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                android.util.Log.e(
                    "NATEQ_APP", "logcat collect :tts failed", e
                )
            }
        }

        val rawReport = buildString {
            appendLine(
                context.getString(R.string.error_report_header_title)
            )
            appendLine(
                context.getString(
                    R.string.error_report_time,
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(java.util.Date())
                )
            )
            appendLine("==========================================")
            appendLine("")
            appendLine(diagnostics)
            appendLine("")
            val lastRealFail = AcapelaVoiceDiagnostics
                .readLastRealSpeechFailure(context)
            if (!lastRealFail.isNullOrBlank() &&
                !diagnostics.contains("=== آخر محاولة نطق حقيقية فاشلة ===") &&
                !diagnostics.contains(
                    "=== [فشل سابق بتاريخه — لا نجاح حالي] ==="
                )
            ) {
                if (!lastRealFail.contains(
                        "=== [فشل سابق بتاريخه — لا نجاح حالي] ==="
                    )
                ) {
                    val stamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(java.util.Date())
                    appendLine("=== [فشل سابق بتاريخه — لا نجاح حالي] ===")
                    appendLine("(ختم وقت الفصل: $stamp)")
                }
                appendLine(
                    AcapelaVoiceDiagnostics.redactSensitiveText(lastRealFail)
                )
                appendLine("")
            }
            appendLine("==========================================")
            appendLine("=== أحدث أسطر السجل التقني (Logcat) ===")
            if (logLines.isEmpty()) {
                appendLine("(لم يُسجَّل نشاط في logcat للعملية الحالية)")
            } else {
                // **بصمةُ التطبيق أولاً:** أسطرُه التشخيصية هي كل ما
                // يهمّ التشخيص، وقد تغرق في ضجيج النظام ضمن آلاف
                // الأسطر. تُوضع في المقدمة فلا يحتاج القارئ أن يبحث عنها.
                val diagnostic = logLines.filter {
                    DiagnosticLogRecorder.isDiagnosticLine(it)
                }
                if (diagnostic.isNotEmpty()) {
                    appendLine(
                        "--- أسطر Lord TTS التشخيصية" +
                            " (${diagnostic.size}) ---"
                    )
                    diagnostic.takeLast(
                        VoiceSelectionFragment.MAX_DIAGNOSTIC_LINES
                    )
                        .forEach {
                            appendLine(
                                AcapelaVoiceDiagnostics
                                    .redactSensitiveLine(it)
                            )
                        }
                    appendLine("")
                }
                val errors = logLines.filter {
                    val level = DiagnosticLogRecorder.severityOf(it)
                    level == 'E' || level == 'F'
                }
                val warnings = logLines.filter {
                    DiagnosticLogRecorder.severityOf(it) == 'W'
                }
                if (errors.isNotEmpty()) {
                    appendLine(
                        "--- أسطر الأخطاء الحرجة (${errors.size}) ---"
                    )
                    errors.takeLast(300).forEach {
                        appendLine(
                            AcapelaVoiceDiagnostics
                                .redactSensitiveLine(it)
                        )
                    }
                    appendLine("")
                }
                if (warnings.isNotEmpty()) {
                    appendLine(
                        "--- أسطر التحذيرات (${warnings.size}) ---"
                    )
                    warnings.takeLast(200).forEach {
                        appendLine(
                            AcapelaVoiceDiagnostics
                                .redactSensitiveLine(it)
                        )
                    }
                    appendLine("")
                }
                val appLogLines = logLines.filter { line ->
                    line.contains("NATEQ") ||
                        line.contains("Lord") ||
                        line.contains("Acapela") ||
                        line.contains("Vocalizer") ||
                        line.contains("Microsoft") ||
                        line.contains("TTS") ||
                        line.contains("Audio") ||
                        line.contains("Voice") ||
                        line.contains("EngineHost") ||
                        line.contains("Speech") ||
                        DiagnosticLogRecorder.isDiagnosticLine(line) ||
                        DiagnosticLogRecorder.severityOf(line) in
                        listOf('E', 'F', 'W')
                }.takeLast(250)
                appendLine(
                    "--- سجل نشاط التطبيق والصوت " +
                        "(${appLogLines.size} سطر) ---"
                )
                appLogLines.forEach {
                    appendLine(AcapelaVoiceDiagnostics.redactSensitiveLine(it))
                }
            }

            // الأمر 2 - البند 4: قسم مستقل بعنوان «سجل :tts»
            appendLine("")
            appendLine("==========================================")
            appendLine("=== سجل عملية :tts (Logcat) ===")
            if (ttsLogLines.isEmpty()) {
                appendLine(
                    "(لم يُعثر على أسطر logcat لعملية :tts " +
                        "أو العملية متوقفة)"
                )
            } else {
                val ttsFiltered = ttsLogLines.filter { line ->
                    line.contains("NATEQ_TTS") ||
                        line.contains("ProtectedApp") ||
                        line.contains("A11y") ||
                        line.contains("AndroidRuntime") ||
                        line.contains("acattsandroid") ||
                        line.contains("tts_function") ||
                        line.contains("BABILE") ||
                        line.contains("eb=-") ||
                        line.contains("Acapela") ||
                        line.contains("Vocalizer") ||
                        line.contains("Microsoft") ||
                        line.contains("TTS") ||
                        line.contains("Audio") ||
                        line.contains("Voice")
                }
                if (ttsFiltered.isNotEmpty()) {
                    appendLine(
                        "--- أسطر :tts المستهدفة " +
                            "(${ttsFiltered.size}) ---"
                    )
                    ttsFiltered.takeLast(250).forEach {
                        appendLine(
                            AcapelaVoiceDiagnostics
                                .redactSensitiveLine(it)
                        )
                    }
                    appendLine("")
                }
                val ttsRecent = ttsLogLines.filter { line ->
                    line.contains("NATEQ") ||
                        line.contains("TTS") ||
                        line.contains("Audio") ||
                        line.contains("Voice") ||
                        DiagnosticLogRecorder.severityOf(line) in
                        listOf('E', 'F', 'W')
                }.takeLast(250)
                appendLine(
                    "--- أحدث أسطر عملية :tts " +
                        "(${ttsRecent.size}) ---"
                )
                ttsRecent.forEach {
                    appendLine(AcapelaVoiceDiagnostics.redactSensitiveLine(it))
                }
            }
        }
        // [بناء 10729] سقف التقرير المنسوخ/المُشارَك: يُبقى على
        // الصدارة (تشخيص Acapela أولاً) ويُقتطع ذيل أسطر logcat.
        AcapelaVoiceDiagnostics.redactSensitiveText(
            AcapelaVoiceDiagnostics.capSharedReport(rawReport)
        )
    }

internal fun VoiceSelectionFragment.writeLogToCacheFile(
    context: android.content.Context,
    content: String
): File? =
    runCatching {
        val redacted =
            AcapelaVoiceDiagnostics.redactSensitiveText(content)
        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val logFile = File(logsDir, "nateq_diagnostic_log.txt")
        logFile.writeText(redacted, Charsets.UTF_8)
        logFile
    }.getOrNull()

internal object WhatsAppDiagnosticsSharing {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val DEFAULT_SUPPORT_WHATSAPP_NUMBER = "201012659989"

    /**
     * تطبيع رقم الهاتف:
     * - يُبقي الأرقام فقط.
     * - يحذف الصفرين البادئين "00".
     * - يرفض ما كان طوله خارج 8 إلى 15 رقماً (يُرجع نصاً فارغاً).
     */
    fun normalizeNumber(number: String?): String {
        if (number.isNullOrBlank()) return ""
        var digits = number.filter { it.isDigit() }
        if (digits.startsWith("00")) {
            digits = digits.removePrefix("00")
        }
        return if (digits.length in 8..15) digits else ""
    }

    fun isNumberConfigured(number: String?): Boolean {
        return normalizeNumber(number).isNotEmpty()
    }

    /**
     * استخراج رقم واتساب المعتمد:
     * يستعمل overrideNumber إن كان صالحاً بعد التطبيع، وإلا الثابت الافتراضي.
     */
    fun resolveSupportNumber(overrideNumber: String?): String {
        val clean = normalizeNumber(overrideNumber)
        return if (clean.isNotEmpty()) {
            clean
        } else {
            DEFAULT_SUPPORT_WHATSAPP_NUMBER
        }
    }

    /**
     * بناء رابط wa.me مع تحية ورقم الدعم بدون نص السجل.
     */
    fun buildWaMeUrl(number: String?, greeting: String): String? {
        val cleanNumber = resolveSupportNumber(number)
        return if (cleanNumber.isNotEmpty()) {
            "https://wa.me/$cleanNumber?text=${Uri.encode(greeting)}"
        } else {
            null
        }
    }

    fun isPackageInstalled(
        context: Context,
        packageName: String
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * اختيار حزمة واتساب المثبتة بالترتيب: العادي ثم الأعمال.
     */
    fun findTargetWhatsAppPackage(
        context: Context,
        packageChecker: (Context, String) -> Boolean = ::isPackageInstalled
    ): String? {
        return if (packageChecker(context, WHATSAPP_PACKAGE)) {
            WHATSAPP_PACKAGE
        } else if (packageChecker(context, WHATSAPP_BUSINESS_PACKAGE)) {
            WHATSAPP_BUSINESS_PACKAGE
        } else {
            null
        }
    }

    /**
     * إطلاق Intent إرسال الملف إلى واتساب دون أي إضافة jid.
     */
    fun launchSendFileToWhatsApp(
        context: Context,
        targetPackage: String,
        logFile: File,
        uriProvider: (Context, File) -> Uri? = { ctx, file ->
            runCatching {
                FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    file
                )
            }.getOrNull()
        },
        activityLauncher: (Context, Intent) -> Boolean = { ctx, intent ->
            runCatching {
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    ): Boolean {
        if (!logFile.exists() || logFile.length() <= 0L) return false
        val uri = uriProvider(context, logFile) ?: return false

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            `package` = targetPackage
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return activityLauncher(context, sendIntent)
    }

    fun getAppVersionName(context: Context): String {
        return runCatching {
            val pInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        0
                    )
                }
            pInfo.versionName ?: ""
        }.getOrDefault("")
    }

    /**
     * تنفيذ دورة مشاركة التشخيص عبر واتساب:
     * 1) فتح محادثة wa.me الموجهة لرقم الدعم مع التحية ورقم الإصدار.
     * 2) إتاحة إرفاق ملف السجل بعدها عبر حوار التأكيد أو استدعاء مباشر.
     * 3) في حال غياب واتساب أو فشل الفتح، يتم التحويل لقائمة المشاركة العامة.
     */
    fun performShare(
        context: Context,
        reportText: String,
        logFile: File?,
        developerNumber: String = BuildConfig.DEVELOPER_WHATSAPP_NUMBER,
        appVersion: String = getAppVersionName(context),
        packageChecker: (Context, String) -> Boolean = ::isPackageInstalled,
        activityLauncher: (Context, Intent) -> Boolean = { ctx, intent ->
            runCatching {
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
        },
        onShowAttachDialog: ((targetPackage: String, logFile: File) -> Unit)? =
            null,
        onAnnounce: (String) -> Unit = {},
        onFallbackChooser: (Context, String, File?) -> Unit
    ) {
        val targetPackage = findTargetWhatsAppPackage(context, packageChecker)
        if (targetPackage == null) {
            val fallbackMsg = context.getString(
                R.string.whatsapp_sharing_fallback_notice
            )
            Toast.makeText(context, fallbackMsg, Toast.LENGTH_LONG).show()
            onAnnounce(fallbackMsg)
            onFallbackChooser(context, reportText, logFile)
            return
        }

        val greeting = context.getString(
            R.string.whatsapp_support_greeting,
            appVersion
        )
        val waUrl = buildWaMeUrl(developerNumber, greeting)
        if (waUrl == null) {
            val fallbackMsg = context.getString(
                R.string.whatsapp_sharing_fallback_notice
            )
            Toast.makeText(context, fallbackMsg, Toast.LENGTH_LONG).show()
            onAnnounce(fallbackMsg)
            onFallbackChooser(context, reportText, logFile)
            return
        }

        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
            `package` = targetPackage
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val waOpened = activityLauncher(context, waIntent)
        if (!waOpened) {
            val fallbackMsg = context.getString(
                R.string.whatsapp_sharing_fallback_notice
            )
            Toast.makeText(context, fallbackMsg, Toast.LENGTH_LONG).show()
            onAnnounce(fallbackMsg)
            onFallbackChooser(context, reportText, logFile)
            return
        }

        // تم فتح محادثة wa.me بنجاح
        if (logFile != null && logFile.exists()) {
            if (onShowAttachDialog != null) {
                onShowAttachDialog(targetPackage, logFile)
            } else {
                launchSendFileToWhatsApp(
                    context = context,
                    targetPackage = targetPackage,
                    logFile = logFile,
                    activityLauncher = activityLauncher
                )
            }
        }
    }
}

internal fun VoiceSelectionFragment.shareReportToWhatsApp(
    context: android.content.Context,
    reportText: String,
    logFile: File?,
    developerNumber: String = BuildConfig.DEVELOPER_WHATSAPP_NUMBER
) {
    WhatsAppDiagnosticsSharing.performShare(
        context = context,
        reportText = reportText,
        logFile = logFile,
        developerNumber = developerNumber,
        appVersion = WhatsAppDiagnosticsSharing.getAppVersionName(context),
        onShowAttachDialog = { targetPackage, file ->
            showAttachLogConfirmationDialog(targetPackage, file)
        },
        onAnnounce = { msg -> view?.announceCompat(msg) },
        onFallbackChooser = { ctx, text, file ->
            shareErrorReport(ctx, text, file)
        }
    )
}

internal fun VoiceSelectionFragment.showAttachLogConfirmationDialog(
    targetPackage: String,
    logFile: File
) {
    val ctx = context ?: return
    val title = getString(R.string.whatsapp_attach_log_dialog_title)
    val message = getString(R.string.whatsapp_attach_log_dialog_message)
    val btnAttach = getString(R.string.whatsapp_attach_log_btn_attach)
    val btnCancel = getString(R.string.whatsapp_attach_log_btn_cancel)

    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(btnAttach) { _, _ ->
            WhatsAppDiagnosticsSharing.launchSendFileToWhatsApp(
                context = ctx,
                targetPackage = targetPackage,
                logFile = logFile
            )
        }
        .setNegativeButton(btnCancel, null)
        .create()
        .also(::trackDialog)
    dialog.show()
    view?.announceCompat(message)
}

internal fun VoiceSelectionFragment.showErrorReportDialog(
    context: android.content.Context,
    reportText: String,
    logFile: File?
) {
    val options = arrayOf(
        getString(R.string.error_report_send_whatsapp),
        getString(R.string.error_report_send_telegram),
        getString(R.string.error_report_share_file)
    )
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.error_report_dialog_title)
        .setItems(options) { _, which ->
            when (which) {
                0 -> shareReportToWhatsApp(context, reportText, logFile)
                1 -> sendReportToTelegram(context, reportText)
                2 -> shareErrorReport(context, reportText, logFile)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .also(::trackDialog)
    dialog.show()
    view?.announceCompat(getString(R.string.error_report_dialog_title))
}

internal fun VoiceSelectionFragment.sendReportToTelegram(
    context: android.content.Context,
    reportText: String
) {
    val redacted =
        AcapelaVoiceDiagnostics.redactSensitiveText(reportText)
    copyToClipboard(redacted)
    Toast.makeText(
        context,
        R.string.error_report_copied_telegram_hint,
        Toast.LENGTH_LONG
    ).show()
    view?.announceCompat(
        getString(R.string.error_report_copied_telegram_hint)
    )
    openDeveloperSupport()
}

/** يفتح وسائل المشاركة (اقتراح نصوص، بريد…) بالمحتوى المجمّع. */
internal fun VoiceSelectionFragment.shareErrorReport(
    context: android.content.Context,
    body: String,
    logFile: File?
) {
    val redactedBody =
        AcapelaVoiceDiagnostics.redactSensitiveText(body)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_SUBJECT, getString(R.string.report_error_subject)
        )
        putExtra(Intent.EXTRA_TEXT, redactedBody)
        if (logFile != null && logFile.exists()) {
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )
            }.getOrNull()
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
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



// ===== التشخيص =====
internal fun VoiceSelectionFragment.showDiagnosticsDialog() {
    val currentContext = context ?: return
    viewLifecycleOwner.lifecycleScope.launch {
        val info = buildDiagnosticsInfo(currentContext)
        if (!isAdded || activity == null ||
            activity?.isFinishing == true
        ) {
            return@launch
        }
        val ctx = context ?: return@launch
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.diagnostics_title)
            .setMessage(info)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                copyToClipboard(info)
                Toast.makeText(
                    ctx, R.string.diagnostics_copied,
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(
                    getString(R.string.diagnostics_copied)
                )
            }
            .setNeutralButton("خيارات تبديل المحرك") { _, _ ->
                showEngineDiagnosticsActionsDialog(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also(::trackDialog)
        dialog.show()
        view?.announceCompat(getString(R.string.diagnostics_title))
    }
}

/**
 * يبني نص التشخيص الشامل — يعمل على خيط IO لأنه يستدعي بعض
 * واجهات النظام (مثل getInstalledEngines) ويمكن أن يبطئ الواجهة.
 *
 * الأقسام المُغطاة (بحسب بند د.6.2 + بند د.1 + بند د.2):
 * - إصدار التطبيق والجهاز
 * - محركات TTS المثبَّتة
 * - المحرك/الصوت المختار لكل فئة (مع سرعة/نبرة/مستوى)
 * - آخر فشل تراجع (يُعرض إن أمكن)
 * - حالة الأذونات الحرجة
 * - تصنيف OEM
 * - سجل تركيز الصوت (إرشاد جمع Logcat — بند د.1)
 * - حالة إعلان المتصل (إرشاد اختبار — بند د.2)
 */
internal suspend fun VoiceSelectionFragment.buildDiagnosticsInfo(
    context: android.content.Context
): String =
    kotlinx.coroutines.withContext(AppDispatchers.io) {
        val sb = StringBuilder()
        val info = context.packageManager.getPackageInfo(
            context.packageName, 0
        )
        val versionName = info.versionName ?: "?"
        val versionCode = if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.P
        ) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }

        sb.appendLine(
            "الأقسام المضافة: (1) مسارات نطق Acapela المستقلة مع " +
                "سجل logcat وlogfile.txt وجرد الملفات، " +
                "(2) أكواد JNI واستدعاءات فحص الأصوات، " +
                "(3) تتبع صوت النطق والبدائل (Fallback) في Lord TTS."
        )
        sb.appendLine("")
        sb.appendLine("=== Lord TTS — التشخيص ===")
        val buildTimeStr = runCatching {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US
            ).format(java.util.Date(info.lastUpdateTime))
        }.getOrDefault("غير معروف")
        sb.appendLine("رقم الإصدار: $versionName | البناء: $versionCode")
        sb.appendLine("وقت التثبيت/البناء: $buildTimeStr")
        sb.appendLine(
            getString(
                R.string.error_report_version,
                versionName,
                versionCode
            )
        )
        sb.appendLine(
            getString(
                R.string.error_report_device,
                android.os.Build.MANUFACTURER,
                android.os.Build.MODEL
            )
        )
        sb.appendLine(
            getString(
                R.string.error_report_android_os,
                android.os.Build.VERSION.RELEASE,
                android.os.Build.VERSION.SDK_INT
            )
        )
        sb.appendLine("")

        val ttsUri = android.net.Uri.parse(
            "content://${context.packageName}.tts_diagnostics"
        )
        val ttsSnapshot = kotlinx.coroutines.withTimeoutOrNull(1500L) {
            runCatching {
                context.contentResolver.call(
                    ttsUri,
                    TtsDiagnosticsProvider.METHOD_SNAPSHOT,
                    null,
                    null
                )
            }.getOrNull()
        }

        val mismatched = checkMismatchedCategoryVoices()
        if (mismatched.isNotEmpty()) {
            sb.appendLine("=== تناقض محرك وصوت الفئات ===")
            for ((cat, voice, eng) in mismatched) {
                sb.appendLine("فئة $cat: الصوت $voice لا يتبع المحرك $eng")
            }
            sb.appendLine("")
        }

        // ── محركات TTS المثبَّتة ──────────────────────────────────
        sb.appendLine("--- محركات TTS المثبَّتة ---")
        val engines = EnginePicker.allInstalledEngines(context)
        if (engines.isEmpty()) {
            sb.appendLine("• لم يُعثر على محركات TTS")
        } else {
            engines.forEach { engine: InstalledEngine ->
                val rms = runCatching {
                    settings.getEngineRmsCalibration(
                        engine.packageName
                    )
                }.getOrNull()
                val rmsText = when {
                    rms != null -> "RMS=${"%.3f".format(rms)}"
                    else -> "RMS=غير معايَر"
                }
                val isVis = VisibleEngines.isVisible(engine.packageName)
                val visLabel = if (isVis) "مرئي" else "مخفي بتصفية المستخدم"
                sb.appendLine(
                    "• ${engine.label} " +
                        "(${engine.packageName})  $rmsText [$visLabel]"
                )
            }
        }
        sb.appendLine("")

        // ── المحركات المدمجة (ع٢٩-8 بند 5) ──────────────────────────────
        sb.appendLine("--- المحركات المدمجة ---")
        val embeddedEngines = listOf(
            EmbeddedEngineDiag(
                id = VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                name = "Vocalizer Ex 2",
                storageEngine =
                    com.aymankhattab.nateq.core.audio.providers
                        .EmbeddedStorage.Engine.VOCALIZER
            ),
            EmbeddedEngineDiag(
                id = MicrosoftEmbeddedVoiceProvider.PROVIDER_ID,
                name = "Microsoft Neural",
                storageEngine =
                    com.aymankhattab.nateq.core.audio.providers
                        .EmbeddedStorage.Engine.MICROSOFT
            ),
            EmbeddedEngineDiag(
                id = AcapelaEmbeddedVoiceProvider.PROVIDER_ID,
                name = "Acapela",
                storageEngine =
                    com.aymankhattab.nateq.core.audio.providers
                        .EmbeddedStorage.Engine.ACAPELA
            )
        )
        embeddedEngines.forEach { diag ->
            val status = EngineStatusRegistry.statusOf(diag.id)
            val stateLabel = status?.state?.let {
                EngineStatusRegistry.labelAr(it)
            } ?: "غير معروف"
            var lastError = status?.lastError ?: "لا يوجد"
            var code = status?.code ?: "—"

            val searchDirs =
                com.aymankhattab.nateq.core.audio.providers
                    .EmbeddedStorage.allSearchDirs(
                        context,
                        diag.storageEngine
                    )
            val paths = searchDirs.map { it.absolutePath }
                .joinToString("\n    ")
            val totalSize = searchDirs.sumOf { dirSize(it) }
            val sizeText = formatSize(totalSize)

            val voiceReports = mutableListOf<String>()
            if (diag.id == AcapelaEmbeddedVoiceProvider.PROVIDER_ID) {
                val voiceDirs = searchDirs.asSequence()
                    .filter { it.isDirectory }
                    .flatMap { dir ->
                        dir.listFiles { f -> f.isDirectory }?.asSequence()
                            ?: emptySequence()
                    }
                    .filter { vd ->
                        !vd.name.equals("userdicos", ignoreCase = true)
                    }
                    .distinctBy { it.name.lowercase(Locale.ROOT) }
                    .toList()

                val voiceFileCounts = voiceDirs.associateWith { vd ->
                    vd.walkTopDown().filter { it.isFile }.count()
                }
                val maxFiles = voiceFileCounts.values.maxOrNull() ?: 0

                for (vDir in voiceDirs) {
                    val vName = vDir.name
                    val count = voiceFileCounts[vDir] ?: 0
                    val yamlFile = File(vDir, "config.yaml")
                    val exists = yamlFile.isFile && yamlFile.exists()
                    val yamlDesc = if (exists) {
                        "${yamlFile.length()} بايت"
                    } else {
                        "غير موجود"
                    }
                    val regVal = AcapelaVoiceManifest.resolveRegistration(vDir)
                    val masked = maskRegistration(regVal)
                    val isRegValid = regVal.isNotBlank() &&
                        AcapelaVoiceManifest.isRegistrationMetadata(regVal)
                    val regDesc = when {
                        isRegValid -> "مسجّل بنجاح ($masked)"
                        regVal.isNotBlank() ->
                            "مسجّل بقيمة غير قياسية ($masked)"
                        else -> "غير مسجّل"
                    }
                    val vStatus = EngineStatusRegistry.getVoiceStatus(
                        diag.id,
                        vName
                    )
                    val loadableDesc = when {
                        vStatus?.isReady == true -> "جاهز للتحميل"
                        vStatus?.isReady == false ->
                            "فشل التحميل (${vStatus.code ?: "خطأ"})"
                        exists && isRegValid -> "قابل للتحميل"
                        else -> "غير قابل للتحميل"
                    }
                    val diffDesc = if (maxFiles > 0 && count < maxFiles) {
                        "$count ملف (ناقص ${maxFiles - count} مقارنة " +
                            "بالأصوات الكاملة: $count/$maxFiles)"
                    } else {
                        "$count ملف"
                    }
                    voiceReports.add(
                        "  صوت $vName:\n" +
                            "    - config.yaml: موجود=" +
                            (if (exists) "نعم" else "لا") +
                            " | حجم=$yamlDesc | " +
                            "مسار=${yamlFile.absolutePath} | " +
                            "التسجيل=$masked\n" +
                            "    - حالة تسجيل $vName: $regDesc\n" +
                            "    - اكتمال الملفات: $diffDesc\n" +
                            "    - إمكانية التحميل: $loadableDesc"
                    )
                }
            }

            sb.appendLine("محرك: ${diag.name} (${diag.id})")
            sb.appendLine("  الحالة: $stateLabel")
            sb.appendLine("  الرمز: $code")
            sb.appendLine("  آخر خطأ: $lastError")
            sb.appendLine("  المسارات:\n    $paths")
            sb.appendLine("  الحجم الكلي: $sizeText")
            if (diag.id == AcapelaEmbeddedVoiceProvider.PROVIDER_ID) {
                val rawSig = AcapelaVoiceDiagnostics
                    .getAppSignatureSha256(context)
                val maskedSig = AcapelaVoiceDiagnostics
                    .maskMiddle(rawSig, 8, 8)
                val rawDevId = AcapelaVoiceDiagnostics
                    .getDeviceId(context)
                val maskedDevId = AcapelaVoiceDiagnostics
                    .maskMiddle(rawDevId, 4, 4)
                val archInfo = AcapelaVoiceDiagnostics.getArchInfo()

                sb.appendLine("  اسم حزمة التطبيق: ${context.packageName}")
                sb.appendLine(
                    "  بصمة توقيع التطبيق (SHA-256): $maskedSig"
                )
                sb.appendLine("  معرف الجهاز: $maskedDevId")
                sb.appendLine("  المعمارية الحالية: $archInfo")
                sb.appendLine(
                    "  مكتبات Acapela الأصلية (64/32 بت): " +
                        "متوفرة للمعماريتين"
                )
                val stageDir = File(context.cacheDir, "acapela_stage")
                if (stageDir.exists() && stageDir.isDirectory) {
                    val stageCount = stageDir.listFiles()?.size ?: 0
                    sb.appendLine(
                        "  مجلد تجهيز الأصوات: " +
                            "${stageDir.absolutePath} ($stageCount ملف)"
                    )
                }
                AcapelaVoiceDiagnostics.runDiagnosticProbe(context)
                val fullReport =
                    AcapelaVoiceDiagnostics.buildAcapelaFullReport(context)
                // [بناء 10730] نُحصر القسم بعلامات كي يُنقل إلى صدارة
                // التقرير المُشارَك قبل تطبيق سقف الـ 60 ألف حرف.
                sb.appendLine(
                    AcapelaVoiceDiagnostics.ACAPELA_REPORT_START
                )
                sb.appendLine(fullReport)
                sb.appendLine(AcapelaVoiceDiagnostics.ACAPELA_REPORT_END)

                val isAcapelaFallback = (ttsSnapshot?.getBoolean(
                    TtsDiagnosticsProvider.KEY_ACAPELA_FALLBACK_ACTIVE,
                    false
                ) == true) ||
                    SpeechFallbackTracker.isAcapelaFallbackActive()
                if (isAcapelaFallback) {
                    val fbVoice = ttsSnapshot?.getString(
                        TtsDiagnosticsProvider.KEY_ACAPELA_FALLBACK_VOICE
                    ) ?: SpeechFallbackTracker.acapelaFallbackVoice ?:
                        "Laila"
                    val fbEngine = ttsSnapshot?.getString(
                        TtsDiagnosticsProvider.KEY_ACAPELA_FALLBACK_ENGINE
                    ) ?: SpeechFallbackTracker.acapelaFallbackEngine ?:
                        "vocalizer_embedded"
                    val fbReason = ttsSnapshot?.getString(
                        TtsDiagnosticsProvider.KEY_ACAPELA_FALLBACK_REASON
                    ) ?: SpeechFallbackTracker.acapelaFallbackReason ?:
                        "فشل نطق Acapela"
                    sb.appendLine(
                        "  حالة الصوت الحالي: بديل (Fallback) بسبب " +
                            "تعثّر Acapela"
                    )
                    sb.appendLine(
                        "  الصوت البديل النشط: $fbVoice ($fbEngine)"
                    )
                    sb.appendLine("  سبب التحويل للبديل: $fbReason")
                    sb.appendLine(
                        "  تنبيه: لا يُخفى فشل Acapela بوجود صوت بديل"
                    )
                }
            }
            voiceReports.forEach { rep ->
                sb.appendLine(rep)
            }
            sb.appendLine("")
        }

        // ── سجل نطق محرك Lord TTS وتتبع البديل (آخر 200 نطق) ────────
        sb.appendLine(
            "--- سجل نطق محرك Lord TTS وتتبع البديل (آخر 200 نطق) ---"
        )
        val speechRecords = ttsSnapshot?.getStringArrayList(
            TtsDiagnosticsProvider.KEY_SPEECH_RECORDS
        )
        if (speechRecords.isNullOrEmpty()) {
            val localRecords = SpeechFallbackTracker.getRecentFormatted()
            if (localRecords.isEmpty()) {
                sb.appendLine("لا توجد عمليات نطق مسجلة بعد")
            } else {
                localRecords.forEach {
                    sb.appendLine(
                        AcapelaVoiceDiagnostics
                            .redactSensitiveLine(it)
                    )
                }
            }
        } else {
            speechRecords.forEach {
                sb.appendLine(AcapelaVoiceDiagnostics.redactSensitiveLine(it))
            }
        }
        sb.appendLine("")

        // ── تشخيص محرك SpeechFlow (in.visionHacks.eloquencePlus) ──────
        val sfReport = ttsSnapshot?.getString(
            TtsDiagnosticsProvider.KEY_SPEECHFLOW_REPORT
        ) ?: com.aymankhattab.nateq.core.audio.engine
            .SpeechFlowDiagnostics.buildReport()
        sb.appendLine(sfReport)
        sb.appendLine("")

        sb.appendLine("=== تقرير سرعة استجابة المحركات المدمجة ===")
        sb.appendLine(
            com.aymankhattab.nateq.core.audio.engine
                .UnifiedSpeechTracker.formatReport()
        )
        val salmaPerfReport =
            com.aymankhattab.nateq.core.audio.engine
                .SalmaSpeechTracker.formatReport()
        if (salmaPerfReport.isNotBlank()) {
            sb.appendLine(salmaPerfReport)
        }
        sb.appendLine("")

        // ── المحرك/الصوت المختار لكل فئة (مع السرعة/النبرة/المستوى) ──
        sb.appendLine("--- الاختيارات النشطة لكل فئة ---")
        sb.appendLine(
            "الأشرطة العامة: سرعة=" +
                "%.1f".format(
                    runCatching { settings.getDefaultSpeechRate() }
                        .getOrDefault(1f)
                ) + "  " +
                "نبرة=" +
                    "%.1f".format(
                        runCatching { settings.getDefaultPitch() }
                            .getOrDefault(1f)
                    ) + "  " +
                "صوت=" +
                    "%.1f".format(
                        runCatching { settings.getDefaultVolume() }
                            .getOrDefault(1f)
                    )
        )
        val categories = listOf(
            "time" to "إعلان الوقت",
            "battery" to "إعلان البطارية",
            "notifications" to "قراءة الإشعارات",
            "caller" to "إعلان المتصل",
            "caller_ar" to "إعلان المتصل (عربي)",
            "caller_en" to "إعلان المتصل (إنجليزي)",
            "sms" to "قراءة الرسائل",
            "numbers" to "الأرقام",
            "dialogue" to "الحوار",
            "narration" to "السرد",
            "emoji" to "الإيموجي"
        )
        categories.forEach { (key, label) ->
            val engine = runCatching {
                settings.getEngineForCategory(key)
            }.getOrNull()
            val voice = runCatching {
                settings.getPreferredVoiceIdForCategory(key)
            }.getOrNull()
            val rate = runCatching {
                settings.getSpeechRateForCategory(key)
            }.getOrNull()
            val pitch = runCatching {
                settings.getPitchForCategory(key)
            }.getOrNull()

            val engineResolved = if (!engine.isNullOrBlank()) {
                "صريح ($engine)"
            } else {
                "تلقائي/حسب اللغة"
            }
            val voiceResolved = if (!voice.isNullOrBlank()) {
                "صريح ($voice)"
            } else {
                "تلقائي"
            }

            sb.appendLine(
                "$label: " +
                    "محرك=[$engineResolved]  " +
                    "صوت=[$voiceResolved]  " +
                    "سرعة=${"%.1f".format(rate ?: 1f)}  " +
                    "نبرة=${"%.1f".format(pitch ?: 1f)}"
            )
        }
        // فاصل إعلان الوقت واللغة الثانية
        val interval = runCatching {
            settings.getTimeAnnouncementInterval()
        }.getOrNull()
        val secondLang = runCatching {
            settings.getSecondaryLanguage()
        }.getOrNull()
        if (interval != null) {
            sb.appendLine("فاصل إعلان الوقت: $interval دقيقة")
        }
        if (secondLang != null) {
            sb.appendLine("اللغة الثانية: $secondLang")
        }
        sb.appendLine("")

        // ── آخر فشل تراجع ────────────────────────────────────────
        sb.appendLine("--- آخر فشل تراجع ---")
        // قيمة مُقرأة من SharedPreferences إن سُجّل سابقاً
        val lastFallback = runCatching {
            settings.getLastFallbackFailureInfo()
        }.getOrNull()
        if (lastFallback.isNullOrBlank()) {
            sb.appendLine("لا يوجد فشل تراجع مُسجَّل")
        } else {
            sb.appendLine(lastFallback)
        }
        sb.appendLine("")

        // ── حالة الأذونات ─────────────────────────────────────────
        sb.appendLine("--- حالة الأذونات ---")
        val am =
            context.getSystemService(
                android.app.AlarmManager::class.java
            )
        val exactAlarm = if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        sb.appendLine(
            "التنبيهات الدقيقة: " +
                if (exactAlarm) "مُمنوح ✓" else "محجوب ✗"
        )

        val nm =
            context.getSystemService(
                android.app.NotificationManager::class.java
            )
        val notificationListener = try {
            val enabled =
                android.provider.Settings
                    .Secure.getString(
                        context.contentResolver,
                        "enabled_notification_listeners"
                    ) ?: ""
            enabled.contains(context.packageName)
        } catch (e: Exception) {
            false
        }
        sb.appendLine(
            "الوصول للإشعارات: " +
                if (notificationListener) "مُمنوح ✓" else "محجوب ✗"
        )

        val dndGranted = if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.M
        ) {
            nm?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }
        sb.appendLine(
            "الوصول لسياسة عدم الإزعاج (DND): " +
                if (dndGranted) {
                    "مُمنوح ✓"
                } else {
                    "تلقائي عبر الإنذار (ALARM) ℹ"
                }
        )
        val failureReason =
            com.aymankhattab.nateq.core.audio.announcement
                .EventLoudnessPolicy.lastFailureReason
        sb.appendLine(
            "تشخيص جهارة الأحداث: " +
                if (failureReason == "none") {
                    "سليم ✓"
                } else {
                    failureReason
                }
        )

        val powerManager =
            context.getSystemService(
                android.os.PowerManager::class.java
            )
        val isIgnoring = if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.M
        ) {
            powerManager.isIgnoringBatteryOptimizations(
                context.packageName
            )
        } else {
            true
        }
        sb.appendLine(
            "الإعفاء من تحسين البطارية: " +
                if (isIgnoring) "مُمنوح ✓" else "محجوب ✗"
        )

        // فحص READ_PHONE_STATE (لإعلان المتصل)
        val phoneState = context.checkSelfPermission(
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        sb.appendLine(
            "READ_PHONE_STATE (المتصل): " +
                if (phoneState) "مُمنوح ✓" else "محجوب ✗"
        )

        val oemVendor =
            OemVendor.detect(android.os.Build.MANUFACTURER)
        sb.appendLine("تصنيف OEM: $oemVendor")
        sb.appendLine("")

        // ── سجل تركيز الصوت (بند د.1) ────────────────────────────
        sb.appendLine("--- سجل تركيز الصوت (بند د.1) ---")
        sb.appendLine(
            "عند حدوث انخفاض مفاجئ في الصوت، أرسل Logcat بتصفية:"
        )
        sb.appendLine("  adb logcat -s NATEQ_TTS:V *:S")
        sb.appendLine(
            "أو استخدم زر «إعادة ضبط تركيز الصوت» في هذه الشاشة"
        )
        sb.appendLine("")

        // ── حالة اختبار إعلان المتصل (بند د.2) ───────────────────
        sb.appendLine("--- إعلان المتصل (بند د.2) ---")
        sb.appendLine(
            "READ_PHONE_STATE: " +
                if (phoneState) "مُمنوح ✓" else "محجوب ✗"
        )
        sb.appendLine(
            "تصنيف OEM: $oemVendor " +
                if (oemVendor.name != "GENERIC") {
                    "← قد يحتاج «التشغيل التلقائي» مفعَّلاً"
                } else {
                    ""
                }
        )
        sb.appendLine(
            "لاختبار مسار النطق مباشرةً: " +
                "استخدم «اختبر إعلان المتصل» في قسم إعلان المتصل"
        )
        sb.appendLine("")

        // ── خدمات الشاشة وإمكانية الوصول (الأمر 2) ─────────────────
        sb.appendLine("--- خدمات الشاشة وإمكانية الوصول (الأمر 2) ---")
        val a11yEnabled = com.aymankhattab.nateq.util.AccessibilityUtils
            .isNateqAccessibilityServiceEnabled(context)
        val isConnected = com.aymankhattab.nateq.core.audio.accessibility
            .ProtectedAppManager.isServiceConnected(context)

        val connStatusText = when {
            isConnected -> "متصلة ✓"
            a11yEnabled && !isConnected -> "منفصلة ✗"
            else -> "معطّلة في الإعدادات"
        }

        sb.appendLine(
            "الخدمة في الإعدادات: " +
                if (a11yEnabled) "مفعّلة ✓" else "معطّلة ✗"
        )
        sb.appendLine("حالة الاتصال: $connStatusText")

        if (ttsSnapshot != null) {
            val isPaused = ttsSnapshot.getBoolean(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_A11Y_PAUSED,
                false
            )
            val pauseLevel = ttsSnapshot.getInt(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_PAUSE_LEVEL,
                1
            )
            val untilEpoch = ttsSnapshot.getLong(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_PAUSED_UNTIL,
                0L
            )
            val fgPkg = ttsSnapshot.getString(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_FOREGROUND_PKG
            )
            sb.appendLine(
                "الإيقاف المؤقت: " +
                    if (isPaused) {
                        "نعم (مستوى $pauseLevel، حتى $untilEpoch)"
                    } else {
                        "لا (نشطة)"
                    }
            )
            sb.appendLine("تطبيق الواجهة: ${fgPkg ?: "غير معروف"}")
        } else {
            sb.appendLine(
                "بيانات :tts: تعذر قراءة بيانات :tts (العملية متوقفة)"
            )
            sb.appendLine("تطبيق الواجهة: غير معروف (العملية متوقفة)")
        }
        sb.appendLine("")

        // ── سجل حسم الصوت (ع٤٥) ──────────────────────────────────
        sb.appendLine("--- سجل حسم الصوت (ع٤٥) ---")
        if (ttsSnapshot != null) {
            val resolutions = ttsSnapshot.getStringArrayList(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_VOICE_RESOLUTIONS
            ).orEmpty()
            if (resolutions.isEmpty()) {
                sb.appendLine("• لا يوجد سجلات حسم بعد")
            } else {
                resolutions.forEach { res -> sb.appendLine(res) }
            }
        } else {
            sb.appendLine("• تعذر قراءة بيانات :tts (العملية متوقفة)")
        }
        sb.appendLine("")

        // ── استهلاك البطارية (الجولة 50) ───────────────────────────
        val batteryReport = com.aymankhattab.nateq.core.audio.accessibility
            .BatteryDiagnosticsTracker.buildBatteryReport(context)
        sb.appendLine(batteryReport)
        if (ttsSnapshot != null) {
            val a11yPerMin = ttsSnapshot.getInt(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_A11Y_EVENTS_PER_MIN,
                0
            )
            val usagePolls = ttsSnapshot.getInt(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_USAGE_STATS_POLLS,
                0
            )
            sb.appendLine("  أحداث إمكانية الوصول/د (:tts): $a11yPerMin")
            if (usagePolls > 0) {
                sb.appendLine("  استطلاعات UsageStats (:tts): $usagePolls")
            }
        }

        // ── تقرير السرعة (الجولة 50) ───────────────────────────────
        if (ttsSnapshot != null) {
            val speedReport = ttsSnapshot.getString(
                com.aymankhattab.nateq.core.audio.engine
                    .TtsDiagnosticsProvider.KEY_SPEED_REPORT
            ) ?: com.aymankhattab.nateq.core.audio.engine
                .SpeechLatencyTracker.formatSpeedReport()
            sb.appendLine(speedReport)
        } else {
            sb.appendLine("--- تقرير السرعة ---")
            sb.appendLine("• تعذر قراءة بيانات :tts (العملية متوقفة)")
        }

        // ── تشخيص تبديل المحركات (الجولة 51 - الأمر 2) ───────────
        sb.appendLine("--- تشخيص تبديل المحركات (الجولة 51) ---")
        val autoStep = com.aymankhattab.nateq.core.audio.accessibility
            .TtsSettingsAutomation.lastFailureStep
        val autoDevice = com.aymankhattab.nateq.core.audio.accessibility
            .TtsSettingsAutomation.lastFailureDevice
        val autoDump = com.aymankhattab.nateq.core.audio.accessibility
            .TtsSettingsAutomation.lastDumpPath
        val savedRecipe = settings.getEngineSwitchRecipe(
            android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT
        )
        if (autoStep != null) {
            sb.appendLine("آخر فشل تبديل: $autoStep")
            sb.appendLine("الجهاز: ${autoDevice ?: "—"}")
        } else {
            sb.appendLine("حالة التبديل: لا يوجد فشل مسجل")
        }
        if (autoDump != null) {
            sb.appendLine("ملف التفريغ: $autoDump")
        }
        if (savedRecipe != null) {
            sb.appendLine("وصفة الجهاز المحفوظة: $savedRecipe")
        }
        sb.appendLine("")

        // ── الخط الزمني للتطبيق المحمي (الأمر 7) ───────────────────
        sb.appendLine("--- الخط الزمني للتطبيق المحمي (الأمر 7) ---")
        val localTimeline = com.aymankhattab.nateq.core.audio.accessibility
            .ProtectedAppTimelineTracker.getTimelineReport()
        val ttsTimeline = ttsSnapshot?.getString(
            com.aymankhattab.nateq.core.audio.engine
                .TtsDiagnosticsProvider.KEY_TIMELINE_REPORT
        )
        val noDataMsg = "لا يوجد خط زمني مسجّل حتى الآن."
        var hasTimeline = false
        if (localTimeline != noDataMsg) {
            sb.appendLine("العملية الرئيسية:")
            sb.appendLine(localTimeline)
            hasTimeline = true
        }
        if (!ttsTimeline.isNullOrBlank() && ttsTimeline != noDataMsg) {
            sb.appendLine("عملية :tts:")
            sb.appendLine(ttsTimeline)
            hasTimeline = true
        }
        if (!hasTimeline) {
            sb.appendLine(noDataMsg)
        }
        sb.appendLine("")

        sb.toString()
    }

internal fun VoiceSelectionFragment.copyToClipboard(text: String) {
    val redacted =
        AcapelaVoiceDiagnostics.redactSensitiveText(text)
    val clipboard = requireContext().getSystemService(
        android.content.Context.CLIPBOARD_SERVICE
    ) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText(
        "Lord TTS Diagnostics", redacted
    )
    clipboard.setPrimaryClip(clip)
}

internal fun VoiceSelectionFragment.showEngineDiagnosticsActionsDialog(
    ctx: Context
) {
    val options = arrayOf(
        "مشاركة ملف تفريغ تبديل المحرك",
        "إعادة محاولة تبديل المحرك"
    )
    MaterialAlertDialogBuilder(ctx)
        .setTitle("خيارات تفريغ وتبديل المحركات")
        .setItems(options) { _, which ->
            when (which) {
                0 -> shareEngineSwitchDump(ctx)
                1 -> retryEngineSwitch(ctx)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun VoiceSelectionFragment.shareEngineSwitchDump(ctx: Context) {
    val uri = com.aymankhattab.nateq.core.audio.accessibility
        .TtsSettingsAutomation.lastDumpUri
    val path = com.aymankhattab.nateq.core.audio.accessibility
        .TtsSettingsAutomation.lastDumpPath
    if (uri != null) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(
                sendIntent, "مشاركة تفريغ تبديل المحرك"
            )
        )
    } else if (path != null) {
        val file = java.io.File(path)
        if (file.exists()) {
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(
                    sendIntent, "مشاركة تفريغ تبديل المحرك"
                )
            )
        } else {
            Toast.makeText(
                ctx, "ملف التفريغ غير متوفر", Toast.LENGTH_SHORT
            ).show()
        }
    } else {
        Toast.makeText(
            ctx,
            "لا يوجد ملف تفريغ محفوظ لتبديل المحركات",
            Toast.LENGTH_SHORT
        ).show()
    }
}

internal fun VoiceSelectionFragment.retryEngineSwitch(ctx: Context) {
    val target = com.aymankhattab.nateq.core.audio.accessibility
        .TtsSettingsAutomation.lastFailureTargetPackage
    viewLifecycleOwner.lifecycleScope.launch {
        if (target != null) {
            Toast.makeText(
                ctx, "جارٍ إعادة محاولة تبديل المحرك…", Toast.LENGTH_SHORT
            ).show()
            val res = com.aymankhattab.nateq.core.audio.accessibility
                .TtsSettingsAutomation.run(ctx, target)
            if (res.success) {
                Toast.makeText(
                    ctx, "نجح تبديل المحرك", Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    ctx, "فشلت المحاولة: ${res.reason}", Toast.LENGTH_LONG
                ).show()
            }
        } else {
            val res = com.aymankhattab.nateq.core.audio.accessibility
                .NextEngineSwitcher.switchToNext(ctx)
            if (res != null) {
                Toast.makeText(
                    ctx,
                    "تم التبديل إلى ${res.label}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

