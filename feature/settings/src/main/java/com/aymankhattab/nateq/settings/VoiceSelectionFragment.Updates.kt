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

// ===== البحث عن تحديثات =====
internal fun VoiceSelectionFragment.setupCheckUpdates() {
    val btn = view?.findViewById<View>(R.id.btn_check_updates) ?: return
    btn.setOnClickListener { onCheckUpdatesClicked() }
}

/** فحص تلقائي عند بدء الشاشة (صامت عند عدم وجود تحديث). */
internal fun VoiceSelectionFragment.checkForUpdatesOnStart() {
    performUpdateCheck(showFeedback = false)
}

/** فحص عند ضغط زر «البحث عن تحديثات» (مع رسائل واضحة). */
internal fun VoiceSelectionFragment.onCheckUpdatesClicked() {
    performUpdateCheck(showFeedback = true)
}

/** منطق الفحص المشترك: إن وُجد تحديث يعرض حوار «نعم/لا» قبل التنزيل. */
internal fun VoiceSelectionFragment.performUpdateCheck(showFeedback: Boolean) {
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
        val pat = settings.getGitHubUpdatePat()
        // الفحص التلقائي يفضّل الكاش (لا يضغط GitHub عند كل فتح شاشة)؛
        // الفحص اليدوي يلتفّ عليه دائماً ليُجيب فوراً عن «هل من جديد؟».
        when (
            val res = UpdateChecker.check(
                currentName, preferCache = !showFeedback, githubPat = pat
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
            is UpdateChecker.CheckResult.NoCompatibleVersion -> {
                // بند ٢٢: لا نعرض مرفق معمارية أخرى — نُعلن صراحةً.
                if (showFeedback) {
                    val msg = getString(
                        R.string.check_updates_no_compatible
                    )
                    Toast.makeText(
                        context, msg, Toast.LENGTH_SHORT
                    ).show()
                    view?.announceCompat(msg)
                }
            }
        }
    }
}

/** حوار تأكيد قبل التنزيل: يسأل المستخدم إن كان يريد تنزيل التحديث
 *  (نعم/لا). على الشبكات المدفوعة يُحذَّر أن التنزيل سيستهلك بياناته،
 *  وموافقته عليه صراحةً تسمح بالتنزيل فوقها. */
internal fun VoiceSelectionFragment.promptDownloadUpdate(
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

/**
 * ع٤٢-10: ينزّل الـ APK ويعرض حوار تقدم التنزيل الفوري.
 */
internal fun VoiceSelectionFragment.startApkDownload(
    context: android.content.Context,
    apkUrl: String,
    expectedSha256Hex: String?,
    allowMetered: Boolean
) {
    val downloadId = UpdateChecker.enqueueDownload(
        context, apkUrl, allowMetered
    )
    settings.setActiveUpdateDownload(
        downloadId, apkUrl, expectedSha256Hex
    )
    updateDialogDismissedByUser = false
    showUpdateProgressDialog(
        downloadId = downloadId,
        apkUrl = apkUrl,
        expectedSha256Hex = expectedSha256Hex,
        allowMetered = allowMetered
    )

    val appContext = context.applicationContext
    val fragmentRef = java.lang.ref.WeakReference(this)
    val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(
            ctx: android.content.Context,
            intent: Intent
        ) {
            val id = intent.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID, -1L
            )
            if (id != downloadId) return
            val currentActive = fragmentRef.get()?.settings
                ?.getActiveUpdateDownloadId() ?: -1L
            if (currentActive != downloadId) {
                // تمت معالجته بالفعل عبر حوار التقدم أو تم إلغاؤه
                return
            }
            fragmentRef.get()?.settings?.clearActiveUpdateDownload()
            try {
                ctx.applicationContext.unregisterReceiver(this)
                updateReceiver = null
            } catch (_: IllegalArgumentException) {
                /* سبق تسجيله أو فُكّ */
            }
            Thread {
                val result = UpdateChecker.processCompletedDownload(
                    ctx, id, expectedSha256Hex
                )
                Handler(Looper.getMainLooper()).post {
                    val frag = fragmentRef.get()
                    when (result) {
                        is UpdateChecker.DownloadResult.Success -> {
                            UpdateChecker.promptInstall(
                                ctx, result.apkFile
                            )
                        }
                        is UpdateChecker.DownloadResult
                            .ChecksumMismatch -> {
                            val msg = ctx.getString(
                                R.string.check_updates_checksum_failed
                            )
                            Toast.makeText(
                                ctx,
                                msg,
                                Toast.LENGTH_LONG
                            ).show()
                            if (frag?.isAdded == true) {
                                frag.view?.announceCompat(msg)
                            }
                        }
                        is UpdateChecker.DownloadResult.Failed -> {
                            val msg = ctx.getString(
                                R.string.check_updates_download_failed
                            )
                            Toast.makeText(
                                ctx,
                                msg,
                                Toast.LENGTH_LONG
                            ).show()
                            if (frag?.isAdded == true) {
                                frag.view?.announceCompat(msg)
                            }
                        }
                    }
                }
            }.start()
        }
    }
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        android.content.IntentFilter(
            DownloadManager.ACTION_DOWNLOAD_COMPLETE
        ),
        ContextCompat.RECEIVER_EXPORTED
    )
    updateReceiver = receiver
}

/**
 * ع٤٢-10: يعرض حوار تقدم تنزيل التحديث بـ LinearProgressIndicator
 * غير قابل للإلغاء بالنقر الخارجي، ويستعلم DownloadManager كل 300ms
 * مع إعلانات الوصول كل 10٪ وتحديث contentDescription.
 */
internal fun VoiceSelectionFragment.showUpdateProgressDialog(
    downloadId: Long,
    apkUrl: String,
    expectedSha256Hex: String?,
    allowMetered: Boolean
) {
    val ctx = context ?: return
    updatePollingJob?.cancel()
    updateProgressDialog?.dismiss()

    val dialogView = LayoutInflater.from(ctx).inflate(
        R.layout.dialog_update_progress, null
    )
    val tvFileName = dialogView.findViewById<TextView>(
        R.id.tv_update_file_name
    )
    val progressBar =
        dialogView.findViewById<LinearProgressIndicator>(
            R.id.progress_update_download
        )
    val tvProgressText = dialogView.findViewById<TextView>(
        R.id.tv_update_progress_text
    )
    val tvStatusMessage = dialogView.findViewById<TextView>(
        R.id.tv_update_status_message
    )
    val btnResumeMetered = dialogView.findViewById<View>(
        R.id.btn_resume_metered
    )

    tvFileName.text = getString(
        R.string.update_dialog_file_name,
        UpdateChecker.APK_NAME
    )
    progressBar.isIndeterminate = true
    tvProgressText.text = getString(R.string.update_dialog_starting)
    tvStatusMessage.visibility = View.GONE
    btnResumeMetered?.visibility = View.GONE

    var activeDownloadId = downloadId

    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.update_dialog_title)
        .setView(dialogView)
        .setCancelable(false)
        .setNegativeButton(R.string.update_dialog_btn_cancel) { _, _ ->
            updatePollingJob?.cancel()
            updatePollingJob = null
            updateProgressDialog = null
            UpdateChecker.cancelDownload(ctx, activeDownloadId)
            settings.clearActiveUpdateDownload()
            updateReceiver?.let {
                runCatching {
                    ctx.applicationContext.unregisterReceiver(it)
                }
                updateReceiver = null
            }
        }
        .setNeutralButton(R.string.update_dialog_btn_hide) { d, _ ->
            updateDialogDismissedByUser = true
            updateProgressDialog = null
            d.dismiss()
        }
        .setPositiveButton(R.string.update_dialog_btn_install, null)
        .create()

    trackDialog(dialog)
    dialog.show()
    updateProgressDialog = dialog

    val positiveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    positiveBtn?.visibility = View.GONE

    var lastAnnouncedMilestone = 0

    updatePollingJob = viewLifecycleOwner.lifecycleScope.launch {
        while (isActive) {
            val progress = withContext(AppDispatchers.io) {
                UpdateChecker.queryProgress(ctx, activeDownloadId)
            }

            if (progress == null || progress.status == -1) {
                settings.clearActiveUpdateDownload()
                break
            }

            when (progress.status) {
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING -> {
                    tvStatusMessage.visibility = View.GONE
                    btnResumeMetered?.visibility = View.GONE
                    if (progress.totalBytes > 0L) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = progress.percent
                        val dlMb = progress.downloadedBytes.toDouble() /
                            (1024.0 * 1024.0)
                        val totMb = progress.totalBytes.toDouble() /
                            (1024.0 * 1024.0)
                        tvProgressText.text = getString(
                            R.string.update_dialog_progress_text,
                            progress.percent,
                            dlMb,
                            totMb
                        )
                        val desc = getString(
                            R.string.update_dialog_progress_content_desc,
                            progress.percent
                        )
                        progressBar.contentDescription = desc

                        val milestone = (progress.percent / 10) * 10
                        if (milestone > lastAnnouncedMilestone &&
                            milestone > 0
                        ) {
                            lastAnnouncedMilestone = milestone
                            val ann = getString(
                                R.string
                                    .update_dialog_progress_announcement,
                                milestone
                            )
                            dialogView.announceCompat(ann)
                        }
                    } else {
                        progressBar.isIndeterminate = true
                        tvProgressText.text = getString(
                            R.string.update_dialog_starting
                        )
                    }
                }
                DownloadManager.STATUS_PAUSED -> {
                    tvStatusMessage.visibility = View.VISIBLE
                    if (progress.reason ==
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI
                    ) {
                        tvStatusMessage.text = getString(
                            R.string.update_dialog_paused_wifi
                        )
                        btnResumeMetered?.visibility = View.VISIBLE
                        btnResumeMetered?.setOnClickListener {
                            btnResumeMetered.visibility = View.GONE
                            val dm = ctx.getSystemService(
                                Context.DOWNLOAD_SERVICE
                            ) as? DownloadManager
                            dm?.remove(activeDownloadId)
                            val newId = UpdateChecker.enqueueDownload(
                                context = ctx,
                                apkUrl = apkUrl,
                                allowMetered = true
                            )
                            activeDownloadId = newId
                            settings.setActiveUpdateDownload(
                                newId,
                                apkUrl,
                                expectedSha256Hex
                            )
                        }
                    } else {
                        btnResumeMetered?.visibility = View.GONE
                        tvStatusMessage.text = getString(
                            R.string.update_dialog_paused_network
                        )
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    btnResumeMetered?.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    val res = withContext(AppDispatchers.io) {
                        UpdateChecker.processCompletedDownload(
                            ctx,
                            activeDownloadId,
                            expectedSha256Hex
                        )
                    }
                    settings.clearActiveUpdateDownload()
                    updateReceiver?.let {
                        runCatching {
                            ctx.applicationContext.unregisterReceiver(it)
                        }
                        updateReceiver = null
                    }

                    when (res) {
                        is UpdateChecker.DownloadResult.Success -> {
                            tvProgressText.text = getString(
                                R.string.update_dialog_complete
                            )
                            tvStatusMessage.visibility = View.GONE
                            dialogView.announceCompat(
                                getString(R.string.update_dialog_complete)
                            )
                            dialog.getButton(
                                DialogInterface.BUTTON_NEUTRAL
                            )?.visibility = View.GONE
                            positiveBtn?.visibility = View.VISIBLE
                            positiveBtn?.setText(
                                R.string.update_dialog_btn_install
                            )
                            positiveBtn?.setOnClickListener {
                                UpdateChecker.promptInstall(
                                    ctx, res.apkFile
                                )
                            }
                            dialog.getButton(
                                DialogInterface.BUTTON_NEGATIVE
                            )?.apply {
                                setText(android.R.string.cancel)
                                setOnClickListener { dialog.dismiss() }
                            }
                            UpdateChecker.promptInstall(
                                ctx, res.apkFile
                            )
                        }
                        is UpdateChecker.DownloadResult
                            .ChecksumMismatch -> {
                            val msg = getString(
                                R.string.check_updates_checksum_failed
                            )
                            tvStatusMessage.visibility = View.VISIBLE
                            tvStatusMessage.text = msg
                            dialogView.announceCompat(msg)
                            positiveBtn?.visibility = View.VISIBLE
                            positiveBtn?.setText(
                                R.string.update_dialog_btn_retry
                            )
                            positiveBtn?.setOnClickListener {
                                dialog.dismiss()
                                updateProgressDialog = null
                                UpdateChecker.cancelDownload(
                                    ctx, downloadId
                                )
                                startApkDownload(
                                    ctx,
                                    apkUrl,
                                    expectedSha256Hex,
                                    allowMetered
                                )
                            }
                        }
                        is UpdateChecker.DownloadResult.Failed -> {
                            val msg = getString(
                                R.string.check_updates_download_failed
                            )
                            tvStatusMessage.visibility = View.VISIBLE
                            tvStatusMessage.text = msg
                            dialogView.announceCompat(msg)
                            positiveBtn?.visibility = View.VISIBLE
                            positiveBtn?.setText(
                                R.string.update_dialog_btn_retry
                            )
                            positiveBtn?.setOnClickListener {
                                dialog.dismiss()
                                updateProgressDialog = null
                                UpdateChecker.cancelDownload(
                                    ctx, downloadId
                                )
                                startApkDownload(
                                    ctx,
                                    apkUrl,
                                    expectedSha256Hex,
                                    allowMetered
                                )
                            }
                        }
                    }
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    settings.clearActiveUpdateDownload()
                    tvStatusMessage.visibility = View.VISIBLE
                    val errText = if (progress.reason ==
                        DownloadManager.ERROR_INSUFFICIENT_SPACE
                    ) {
                        getString(
                            R.string.update_dialog_error_insufficient_space
                        )
                    } else {
                        getString(R.string.update_dialog_error_network)
                    }
                    tvStatusMessage.text = errText
                    dialogView.announceCompat(errText)
                    positiveBtn?.visibility = View.VISIBLE
                    positiveBtn?.setText(
                        R.string.update_dialog_btn_retry
                    )
                    positiveBtn?.setOnClickListener {
                        dialog.dismiss()
                        updateProgressDialog = null
                        UpdateChecker.cancelDownload(ctx, downloadId)
                        startApkDownload(
                            ctx,
                            apkUrl,
                            expectedSha256Hex,
                            allowMetered
                        )
                    }
                    break
                }
            }
            delay(300L)
        }
    }
}

/**
 * ع٤٢-10: استئناف عرض تقدم تنزيل نشط إن وجد عند العودة للشاشة.
 */
internal fun VoiceSelectionFragment.resumeUpdateDownloadIfNeeded() {
    val activeId = settings.getActiveUpdateDownloadId()
    if (activeId <= 0L) return
    val apkUrl = settings.getActiveUpdateApkUrl() ?: return
    val expectedSha = settings.getActiveUpdateExpectedSha256()

    val ctx = context ?: return
    val progress = UpdateChecker.queryProgress(ctx, activeId)
    if (progress == null || progress.status == -1) {
        settings.clearActiveUpdateDownload()
        return
    }

    if (progress.status == DownloadManager.STATUS_SUCCESSFUL) {
        val targetApk = UpdateChecker.downloadedApk(ctx)
        if (targetApk.exists()) {
            settings.clearActiveUpdateDownload()
            UpdateChecker.promptInstall(ctx, targetApk)
        } else {
            settings.clearActiveUpdateDownload()
        }
        return
    }

    if (progress.status == DownloadManager.STATUS_FAILED) {
        settings.clearActiveUpdateDownload()
        return
    }

    if (!updateDialogDismissedByUser &&
        (updateProgressDialog == null ||
            !updateProgressDialog!!.isShowing)
    ) {
        showUpdateProgressDialog(
            downloadId = activeId,
            apkUrl = apkUrl,
            expectedSha256Hex = expectedSha,
            allowMetered = NetworkMetering.isMetered(ctx)
        )
    }
}

