package com.aymankhattab.nateq.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.VoicePackageImporter
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * الشاشة الرئيسية للإعدادات. بسيطة ومباشرة عمدًا (بدون Nested navigation
 * معقّد) لتقليل عدد خطوات التنقل بـ TalkBack. تحتوي على أقسام:
 * 1) اختيار الأصوات لكل لغة
 * 2) إعدادات إعلان الوقت
 * 3) الملفات الشخصية
 * 4) قاموس النطق
 * 5) إعدادات عامة
 *
 * تطلب الأذونات عند أول استخدام: إشعارات فقط (وقراءة الرسائل عند تفعيلها).
 * لا تُفتح شاشات مقيّدة بالإذن المهمل REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 * لا يطلب إمكانية الوصول أبداً (ناطق محرك TTS عادي وليس خدمة وصول).
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {

    /** مصدر الإعدادات المحقون (نفس سنجلتون التطبيق) لقراءة مفتاح
     *  اكتمال معالج الإعداد الأولي. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            if (granted) {
                findViewById<View>(android.R.id.content).announceCompat(
                    getString(R.string.permission_notifications_granted)
                )
            } else {
                showPermissionDeniedDialog(
                    R.string.permission_notifications_denied
                )
            }
        } catch (t: Throwable) {
            Log.e(
                "SettingsActivity",
                "فشل معالجة نتيجة إذن الإشعارات",
                t
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // أندرويد 15 يفرض edge-to-edge: نفعّله صراحة
        // (أشرطة شفافة وأيقونات مناسبة).
        // الوسائد (status bar/nav bar) تطبَّق يدوياً من الفصيل عبر
        // ViewCompat.setOnApplyWindowInsetsListener على جذر القائمة
        // القابلة للتمرير.
        enableEdgeToEdge()

        // معالج الإعداد الأولي: يُعرض مرة واحدة قبل استكشاف الواجهة.
        // «تخطّي» يُعلِّم الاكتمال في المخزن؛ لا يُفرض شيء على المستخدم.
        val isFirstRun = runCatching {
            !settingsRepository.isFirstRunSetupCompleted()
        }.getOrDefault(false)

        if (savedInstanceState == null && isFirstRun) {
            startActivity(Intent(this, FirstRunSetupActivity::class.java))
        }

        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            val fragment = VoiceSelectionFragment()
            supportFragmentManager.commit {
                replace(R.id.settings_container, fragment)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                EnginePicker.warmupCache(applicationContext)
            }
        }

        // طلب الأذونات عند أول تشغيل بعد اكتمال الإعداد الأولي
        if (!isFirstRun) {
            permissionsChecked = true
            checkAndRequestPermissions()
        }

        // إعادة تشغيل خدمة إعلانات الوقت/البطارية إن كان أي منها مفعّلاً
        // بعد إنجاز نظام الأندرويد (العمليات في الخلفية قد توقفت) دون أن يفتح
        // المستخدم أي إعداد.
        if (savedInstanceState == null) {
            val started = runCatching {
                AnnouncementSchedulerService.startIfNeeded(this)
            }.getOrDefault(false)
            // على أندرويد المحرك تُنفَّذ عمليات الخلفية بعد تأخر؛ إن لم تكن
            // خدمة الإعلانات مطلوبة كان الوقت هو الوحيد المفعّل (بند 16.2)
            // فنعيد جدولة منبه إعلان الوقت مباشرةً دون تشغيل خدمة أمامية.
            if (!started) {
                runCatching {
                    AnnouncementSchedulerService.ensureTimeAlarm(this)
                }
            }
            handleZipImportIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleZipImportIntent(intent)
    }

    private fun handleZipImportIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) {
            return
        }

        // استقبال نص مشارَك وحفظه في «اقرأ لاحقاً» (مع منع التكرار عند
        // إعادة تسليم النية/الدوران). لا يُكسر استيراد ZIP.
        if (action == Intent.ACTION_SEND) {
            val mime = intent.type ?: ""
            if (mime.startsWith("text/")) {
                val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                val sharedText = raw?.toString()?.trim().orEmpty()
                // استهلاك النية فوراً لمنع إعادة المعالجة عند إعادة التسليم.
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
                intent.type = null
                val result = settingsRepository.saveReadLaterText(sharedText)
                val msgRes = when (result) {
                    SettingsRepository.ReadLaterSaveResult.SAVED ->
                        R.string.read_later_saved_toast
                    SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED ->
                        R.string.read_later_saved_truncated_toast
                    SettingsRepository.ReadLaterSaveResult.DUPLICATE ->
                        R.string.read_later_duplicate_toast
                    SettingsRepository.ReadLaterSaveResult.EMPTY ->
                        R.string.read_later_empty_toast
                    SettingsRepository.ReadLaterSaveResult.FAILED ->
                        R.string.read_later_failed_toast
                }
                android.widget.Toast.makeText(
                    this,
                    msgRes,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                openReadLaterScreen()
                return
            }
        }

        val uri: Uri? = if (action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else {
            intent.data
        }

        if (uri == null) return

        val fileName = queryDisplayName(uri)
        val mimeType = intent.type ?: runCatching {
            contentResolver.getType(uri)
        }.getOrNull()

        val hasZipExtension =
            fileName?.endsWith(".zip", ignoreCase = true) == true ||
            uri.path?.endsWith(".zip", ignoreCase = true) == true

        val isZipMime =
            mimeType?.contains("zip", ignoreCase = true) == true

        val isOctetStream = mimeType == "application/octet-stream"

        val isZip = hasZipExtension || isZipMime ||
            (isOctetStream && fileName == null)

        if (!isZip) {
            return
        }

        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)

        importZipVoicePackage(uri, fileName)
    }

    private fun openReadLaterScreen() {
        val fragment = supportFragmentManager
            .findFragmentById(R.id.settings_container)
            as? VoiceSelectionFragment
        if (fragment != null && fragment.isAdded && fragment.view != null) {
            fragment.openReadLater()
        } else {
            Handler(Looper.getMainLooper()).post {
                val f = supportFragmentManager
                    .findFragmentById(R.id.settings_container)
                    as? VoiceSelectionFragment
                f?.openReadLater()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun importZipVoicePackage(uri: Uri, fileName: String?) {
        if (VoicePackageImporter.isImportInProgress()) {
            Toast.makeText(
                this,
                R.string.zip_import_session_busy,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val cancelSignal = AtomicBoolean(false)
        var inspectJob: Job? = null

        val inspectDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.zip_import_title)
            .setMessage(getString(R.string.zip_import_review_inspecting))
            .setCancelable(false)
            .setNegativeButton(R.string.zip_import_cancel_button) { dialog, _ ->
                cancelSignal.set(true)
                inspectJob?.cancel()
                dialog.dismiss()
                VoicePackageImporter.cancelActiveSession()
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.zip_import_cancelled_msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .create()
        inspectDialog.show()

        inspectJob = lifecycleScope.launch(Dispatchers.Main) {
            val inspection = withContext(Dispatchers.IO) {
                VoicePackageImporter.inspectZipPackage(
                    context = applicationContext,
                    zipUri = uri,
                    suggestedName = fileName,
                    cancellationSignal = cancelSignal
                )
            }

            if (isFinishing || isDestroyed) {
                VoicePackageImporter.cleanupInspection(inspection)
                return@launch
            }
            runCatching { inspectDialog.dismiss() }

            if (cancelSignal.get()) {
                VoicePackageImporter.cleanupInspection(inspection)
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.zip_import_cancelled_msg,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            if (!inspection.isSupported ||
                !inspection.errorMessage.isNullOrBlank()
            ) {
                VoicePackageImporter.cleanupInspection(inspection)
                val err = inspection.errorMessage
                    ?: getString(R.string.zip_import_unsupported_format)
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.zip_import_error_title)
                    .setMessage(err)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            showImportReviewDialog(inspection)
        }
    }

    private fun showImportReviewDialog(
        inspection: VoicePackageImporter.InspectionResult
    ) {
        val voicesStr = inspection.voicesFound.joinToString("، ")
        val sizeMb = "%.1f".format(
            Locale.US,
            inspection.requiredSpaceBytes.toDouble() / (1024 * 1024)
        ) + " ميجابايت"

        val msgBuilder = StringBuilder()
        msgBuilder.append("المحرك: ${inspection.engineDisplayName}\n")
        msgBuilder.append("الأصوات المكتشفة في الحزمة: $voicesStr\n")
        msgBuilder.append("المساحة التقديرية المطلوبة: $sizeMb\n")
        if (inspection.voicesToReplace.isNotEmpty()) {
            val replaceStr = inspection.voicesToReplace.joinToString("، ")
            msgBuilder.append(
                "\n⚠️ تنبيه: سيتم استبدال وتحديث الأصوات المثبتة التالية:\n" +
                    "$replaceStr\n"
            )
        } else {
            msgBuilder.append(
                "\nسيتم تثبيت الأصوات كأصوات جديدة دون استبدال " +
                    "أي أصوات سابقة.\n"
            )
        }
        msgBuilder.append("\nهل ترغب في متابعة التثبيت الآن؟")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.zip_import_review_title)
            .setMessage(msgBuilder.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.zip_import_confirm_button) { _, _ ->
                executeZipImport(inspection)
            }
            .setNegativeButton(R.string.zip_import_cancel_button) { _, _ ->
                VoicePackageImporter.cleanupInspection(inspection)
                Toast.makeText(
                    this,
                    R.string.zip_import_cancelled_msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun executeZipImport(
        inspection: VoicePackageImporter.InspectionResult
    ) {
        val cancelSignal = AtomicBoolean(false)
        var importJob: Job? = null

        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.zip_import_title)
            .setMessage(
                getString(
                    R.string.zip_import_in_progress,
                    inspection.voicesFound.joinToString("، ")
                )
            )
            .setCancelable(false)
            .setNegativeButton(R.string.zip_import_cancel_button) { dialog, _ ->
                cancelSignal.set(true)
                importJob?.cancel()
                dialog.dismiss()
                VoicePackageImporter.cancelActiveSession()
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.zip_import_cancelled_msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .create()
        progress.show()

        importJob = lifecycleScope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) {
                VoicePackageImporter.executeImport(
                    context = applicationContext,
                    inspection = inspection,
                    cancellationSignal = cancelSignal
                )
            }

            if (isFinishing || isDestroyed) return@launch
            runCatching { progress.dismiss() }

            if (result.cancelled || cancelSignal.get()) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.zip_import_cancelled_msg,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            if (result.success) {
                val voices = result.importedVoices.joinToString("، ")
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.zip_import_success_title)
                    .setMessage(
                        getString(R.string.zip_import_success_msg, voices)
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()

                val frag = supportFragmentManager
                    .findFragmentById(R.id.settings_container)
                    as? VoiceSelectionFragment
                frag?.refreshEmbeddedUiIfStorageChanged()
                frag?.refreshVoiceCatalogDiscovery()
            } else {
                val err = result.errorMessage
                    ?: getString(R.string.zip_import_unsupported_format)
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.zip_import_error_title)
                    .setMessage(err)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        // 1. إذن الإشعارات (أندرويد 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
                return
            }
        }

        // انتهت سلسلة الأذونات — لا يوجد طلب إمكانية وصول.
        // (ناطق محرك TTS عادي، وليس خدمة وصول، فلا يطلبها.)
        // إذن RECEIVE_SMS لا يُطلب هنا على الإطلاق: يُطلب فقط عندما يفعّل
        // المستخدم قراءة الرسائل فعلياً من شاشة الإعدادات (مجدداً وعلى حاجة).
        // لا يُطلب إعفاء البطارية تلقائياً: شاشته الخاصة تتطلب إذناً مقيّداً
        // (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ورمي SecurityException بدونه،
        // ويكفي أن يضبطه المستخدم يدوياً من إعدادات النظام إن أراد.
    }

    private fun showPermissionDeniedDialog(messageRes: Int) {
        permissionDeniedDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts(
                            "package", packageName, null
                        )
                    }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        permissionDeniedDialog?.show()
    }

    private var permissionDeniedDialog: AlertDialog? = null
    private var permissionsChecked = false

    override fun onResume() {
        super.onResume()
        if (!permissionsChecked) {
            val isFirstRun = runCatching {
                !settingsRepository.isFirstRunSetupCompleted()
            }.getOrDefault(false)
            if (!isFirstRun) {
                permissionsChecked = true
                checkAndRequestPermissions()
            }
        }
    }

    override fun onDestroy() {
        permissionDeniedDialog?.let { runCatching { it.dismiss() } }
        permissionDeniedDialog = null
        super.onDestroy()
    }
}
