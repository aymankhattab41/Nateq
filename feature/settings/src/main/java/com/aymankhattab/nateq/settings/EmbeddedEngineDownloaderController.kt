package com.aymankhattab.nateq.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.DownloadError
import com.aymankhattab.nateq.core.audio.providers.DownloadHttpHelper
import com.aymankhattab.nateq.core.audio.providers.DownloadLog
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.providers.VocalizerCatalogItem
import com.aymankhattab.nateq.core.audio.providers.VocalizerDownloader
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.aymankhattab.nateq.core.audio.providers.VoiceDownloadManager
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * ضابط شاشة تنزيل وإدارة أصوات ومحركات
 * Vocalizer Expressive 2 و Vocalizer المدمجة.
 */
internal class EmbeddedEngineDownloaderController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val preview: VoicePreviewHelper
) {

    var onStatusChanged: () -> Unit = {}

    private var activeEngine = "vocalizer"

    /** يمنع تكرار إضافة المستمع ويكفل إزالته عند التدمير. */
    private var listenerAttached = false

    private enum class NavLevel { LANGUAGES, VOICES, QUALITIES }
    private var navLevel = NavLevel.LANGUAGES
    private var selectedLocale: Locale? = null
    private var selectedVoiceKey: String? = null

    private val downloadListener =
        VoiceDownloadManager.DownloadListener { voiceId, state ->
            fragment.activity?.runOnUiThread {
                val root = fragment.view ?: return@runOnUiThread
                when (state) {
                    is VoiceDownloadManager.DownloadState.Downloading -> {
                        updateProgressUi(root, voiceId, state.percent)
                    }
                    is VoiceDownloadManager.DownloadState.Success -> {
                        Toast.makeText(
                            fragment.requireContext(),
                            R.string.embedded_download_success,
                            Toast.LENGTH_SHORT
                        ).show()
                        onStatusChanged()
                        renderVoicesList(root)
                        notifyCatalogChanged()
                    }
                    is VoiceDownloadManager.DownloadState.Error -> {
                        val err = state.error
                        val details = err.details.ifBlank {
                            err.message ?: err.code
                        }
                        val displayMsg = "[${err.code}] $details"
                        Toast.makeText(
                            fragment.requireContext(),
                            fragment.getString(
                                R.string.embedded_download_error,
                                displayMsg
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        renderVoicesList(root)
                    }
                    is VoiceDownloadManager.DownloadState.Cancelled -> {
                        Toast.makeText(
                            fragment.requireContext(),
                            R.string.embedded_download_cancelled,
                            Toast.LENGTH_SHORT
                        ).show()
                        renderVoicesList(root)
                    }
                    VoiceDownloadManager.DownloadState.Idle -> {
                        renderVoicesList(root)
                    }
                }
            }
        }

    fun setup(root: View) {
        if (!listenerAttached) {
            VoiceDownloadManager.addListener(downloadListener)
            listenerAttached = true
        }

        // ع٢٩-8 بند 2: تهيئة قائمة حالة المحركات الثلاثة
        // (دائماً ظاهرة)
        renderEngineStatusList(root)

        val engineToggle = root.findViewById<MaterialButtonToggleGroup>(
            R.id.toggle_group_engine_filter
        )

        engineToggle?.check(
            when (activeEngine) {
                else -> R.id.btn_engine_vocalizer
            }
        )
        engineToggle?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                activeEngine = when (checkedId) {
                    else -> "vocalizer"
                }
                renderVoicesList(root)
            }
        }


        renderVoicesList(root)
    }

    fun refresh(root: View) {
        renderVoicesList(root)
    }

    /** يفصل مستمع التنزيل عند تدمير الواجهة (منع تسريب الفصيل). */
    fun detach() {
        if (listenerAttached) {
            VoiceDownloadManager.removeListener(downloadListener)
            listenerAttached = false
        }
    }

    /**
     * بناء نص ملخص حالة الأصوات المثبتة للبطاقة
     */
    fun buildStatusSummary(): String {
        val ctx = fragment.context ?: return ""

        val vocDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
        val vocCount = VocalizerModelCatalog.ITEMS.count { item ->
            VocalizerModelCatalog.isVoiceInstalled(vocDir, item)
        }

        val total = vocCount
        val isAr = LanguageCode.isArabic(Locale.getDefault().language)
        return if (total > 0) {
            if (isAr) {
                "$total أصوات مثبتة"
            } else {
                "$total voices installed"
            }
        } else {
            if (isAr) {
                "لا توجد أصوات مثبتة"
            } else {
                "No voices installed"
            }
        }
    }

    private fun renderVoicesList(root: View) {
        val container = root.findViewById<LinearLayout>(
            R.id.ll_embedded_voices_list
        ) ?: return
        val emptyView = root.findViewById<TextView>(
            R.id.tv_embedded_empty
        )

        container.removeAllViews()

        when (activeEngine) {
            else -> renderVocalizerNav(container, emptyView)
        }
        renderNavHeader(root)
    }

    /** توزيع المستوى الحالي في الهرم: لغة ← صوت ← جودة. */
    private fun renderVocalizerNav(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        when (navLevel) {
            NavLevel.LANGUAGES -> renderLanguages(container, emptyView)
            NavLevel.VOICES -> renderVoices(container, emptyView)
            NavLevel.QUALITIES -> renderQualities(container, emptyView)
        }
    }

    /** المستوى ١: اللغات، وكل صفٍّ له أيقونة تفتح أصواتها. */
    private fun renderLanguages(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val locales = EmbeddedVoiceTree.vocalizerLanguages()
        if (locales.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val inflater = LayoutInflater.from(fragment.requireContext())
        for (locale in locales) {
            val itemView = inflater.inflate(
                R.layout.item_embedded_language,
                container,
                false
            )
            itemView.findViewById<TextView>(
                R.id.tv_language_title
            ).text = languageTitle(locale)
            itemView.findViewById<TextView>(
                R.id.tv_language_tag
            ).text = locale.toLanguageTag()
            itemView.setOnClickListener {
                selectedLocale = locale
                selectedVoiceKey = null
                navLevel = NavLevel.VOICES
                fragment.view?.let { renderVoicesList(it) }
            }
            container.addView(itemView)
        }
    }

    /** المستوى ٢: أصوات اللغة، وكل صوتٍ له أيقونة تفتح جوداته. */
    private fun renderVoices(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val locale = selectedLocale ?: return
        val voices = EmbeddedVoiceTree.vocalizerVoices(locale)
        if (voices.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val ctx = fragment.requireContext()
        val isAr = LanguageCode.isArabic(Locale.getDefault().language)
        val baseDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
        val inflater = LayoutInflater.from(ctx)
        for (node in voices) {
            val installed = node.variants.count {
                VocalizerModelCatalog.isVoiceInstalled(baseDir, it)
            }
            val itemView = inflater.inflate(
                R.layout.item_embedded_voice_group,
                container,
                false
            )
            itemView.findViewById<TextView>(
                R.id.tv_voice_group_title
            ).text = if (isAr) node.titleAr else node.titleEn
            itemView.findViewById<TextView>(
                R.id.tv_voice_group_subtitle
            ).text = fragment.getString(
                R.string.embedded_voice_installed_count,
                installed,
                node.variants.size
            )
            itemView.setOnClickListener {
                selectedVoiceKey = node.key
                navLevel = NavLevel.QUALITIES
                fragment.view?.let { renderVoicesList(it) }
            }
            container.addView(itemView)
        }
    }

    /** المستوى ٣: جودات الصوت المختار (مضغوط/عالية) كبطاقات تنزيل. */
    private fun renderQualities(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val locale = selectedLocale ?: return
        val key = selectedVoiceKey ?: return
        val node = EmbeddedVoiceTree.vocalizerVoices(locale)
            .firstOrNull { it.key == key }
        if (node == null || node.variants.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val inflater = LayoutInflater.from(fragment.requireContext())
        for (item in node.variants) {
            val itemView = inflater.inflate(
                R.layout.item_embedded_voice,
                container,
                false
            )
            bindVocalizerCard(itemView, item)
            container.addView(itemView)
        }
    }

    /** شريط التنقّل: زر رجوع + مسار المستوى الحالي. */
    private fun renderNavHeader(root: View) {
        val header = root.findViewById<LinearLayout>(
            R.id.ll_embedded_nav_header
        ) ?: return
        val breadcrumb = root.findViewById<TextView>(
            R.id.tv_embedded_nav_breadcrumb
        )
        val back = root.findViewById<MaterialButton>(
            R.id.btn_embedded_nav_back
        )
        back?.setOnClickListener { navigateUp() }

        when (navLevel) {
            NavLevel.LANGUAGES -> {
                header.visibility = View.GONE
            }
            NavLevel.VOICES -> {
                header.visibility = View.VISIBLE
                breadcrumb?.text = languageTitle(selectedLocale)
            }
            NavLevel.QUALITIES -> {
                header.visibility = View.VISIBLE
                val voiceTitle = selectedVoiceTitle()
                breadcrumb?.text = if (voiceTitle != null) {
                    fragment.getString(
                        R.string.embedded_nav_breadcrumb,
                        languageTitle(selectedLocale),
                        voiceTitle
                    )
                } else {
                    languageTitle(selectedLocale)
                }
            }
        }
    }

    private fun languageTitle(locale: Locale?): String {
        if (locale == null) return ""
        return locale.getDisplayLanguage(Locale.getDefault())
    }

    private fun selectedVoiceTitle(): String? {
        val locale = selectedLocale ?: return null
        val key = selectedVoiceKey ?: return null
        val isAr = LanguageCode.isArabic(Locale.getDefault().language)
        val node = EmbeddedVoiceTree.vocalizerVoices(locale)
            .firstOrNull { it.key == key } ?: return null
        return if (isAr) node.titleAr else node.titleEn
    }

    /** الرجوع درجةً واحدة في الهرم. */
    private fun navigateUp() {
        when (navLevel) {
            NavLevel.QUALITIES -> {
                navLevel = NavLevel.VOICES
                selectedVoiceKey = null
            }
            NavLevel.VOICES -> {
                navLevel = NavLevel.LANGUAGES
                selectedLocale = null
            }
            NavLevel.LANGUAGES -> return
        }
        fragment.view?.let { renderVoicesList(it) }
    }


    private fun bindVocalizerCard(view: View, item: VocalizerCatalogItem) {
        val ctx = fragment.requireContext()
        val isAr = LanguageCode.isArabic(Locale.getDefault().language)

        val tvTitle = view.findViewById<TextView>(R.id.tv_voice_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_voice_subtitle)
        val badge = view.findViewById<TextView>(R.id.tv_voice_status_badge)
        val btnDownload = view.findViewById<MaterialButton>(
            R.id.btn_voice_download
        )
        val btnTestConn = view.findViewById<MaterialButton>(
            R.id.btn_voice_test_connection
        )
        val btnPreview = view.findViewById<MaterialButton>(
            R.id.btn_voice_preview
        )
        val btnDelete = view.findViewById<MaterialButton>(
            R.id.btn_voice_delete
        )
        val llProgress = view.findViewById<LinearLayout>(
            R.id.ll_download_progress
        )
        val pbDownload = view.findViewById<ProgressBar>(R.id.pb_download)
        val tvProgress = view.findViewById<TextView>(
            R.id.tv_download_progress_text
        )
        val btnCancel = view.findViewById<MaterialButton>(
            R.id.btn_voice_cancel
        )

        tvTitle.text = if (isAr) item.displayNameAr else item.displayNameEn
        val langTag = item.locale.toLanguageTag()

        val baseDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
        val isInstalled = VocalizerModelCatalog.isVoiceInstalled(baseDir, item)
        val isDownloading = VoiceDownloadManager.isDownloading(item.id)
        val downloadState = VoiceDownloadManager.getState(item.id)
        var partialBytes = VoiceDownloadManager.getPartialBytes(ctx, item.id)
        val expectedBytes = item.sizeMb.toLong() * 1024L * 1024L
        // ملفُّ الجزء الناقص لا يجوز أن يتجاوز حجم الصوت المتوقع بفارق
        // كبير؛ فإن حدث فهو تالفٌ أو من صوت آخر فيُطرح فوراً.
        if (partialBytes > expectedBytes + expectedBytes / 2) {
            VoiceDownloadManager.deletePartial(ctx, item.id)
            partialBytes = 0L
        }

        if (isDownloading) {
            badge.visibility = View.GONE
            btnDownload.visibility = View.GONE
            btnTestConn?.visibility = View.GONE
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.VISIBLE
            val pct = when (downloadState) {
                is VoiceDownloadManager.DownloadState.Downloading -> {
                    downloadState.percent
                }
                else -> 0
            }
            pbDownload.progress = pct
            tvProgress.text = fragment.getString(
                R.string.embedded_downloading,
                pct
            )
            btnCancel?.setOnClickListener {
                VoiceDownloadManager.cancelDownload(ctx, item.id)
            }
        } else if (isInstalled) {
            tvSubtitle.text =
                "Vocalizer • $langTag • ${item.sizeMb} MB • " +
                item.quality
            badge.visibility = View.VISIBLE
            btnDownload.visibility = View.GONE
            btnTestConn?.visibility = View.GONE
            btnPreview.visibility = View.VISIBLE
            btnDelete.visibility = View.VISIBLE
            llProgress.visibility = View.GONE

            btnPreview.setOnClickListener {
                previewVocalizerVoice(item)
            }

            btnDelete.setOnClickListener {
                confirmDeleteVocalizerVoice(item)
            }
        } else {
            badge.visibility = View.GONE
            btnDownload.visibility = View.VISIBLE
            btnTestConn?.visibility = View.VISIBLE
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.GONE

            if (partialBytes > 0) {
                btnDownload.text = fragment.getString(
                    R.string.embedded_resume_download_btn
                )
                val hint = fragment.getString(
                    R.string.embedded_partial_download_hint,
                    formatSize(partialBytes)
                )
                tvSubtitle.text =
                    "Vocalizer • $langTag • ${item.sizeMb} MB • $hint"
            } else {
                btnDownload.text = fragment.getString(
                    R.string.embedded_download_btn
                )
                tvSubtitle.text =
                    "Vocalizer • $langTag • ${item.sizeMb} MB • " +
                    item.quality
            }

            btnTestConn?.setOnClickListener {
                testVoiceConnection(item.voiceName, item.downloadUrl)
            }

            btnDownload.setOnClickListener {
                startVocalizerDownload(item)
            }
        }
    }

    private fun testVoiceConnection(voiceName: String, url: String) {
        val ctx = fragment.requireContext()
        Toast.makeText(
            ctx,
            R.string.testing_connection,
            Toast.LENGTH_SHORT
        ).show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = DownloadHttpHelper.testConnection(url)
            if (result.isSuccess) {
                val data = result.getOrThrow()
                val sizeStr = formatSize(data.contentLength)
                val msg = ctx.getString(
                    R.string.embedded_test_connection_result,
                    data.finalUrl,
                    data.responseCode,
                    sizeStr,
                    data.contentType
                )
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.embedded_test_connection_title)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                val ex = result.exceptionOrNull()
                val (code, details) = if (ex is DownloadError) {
                    ex.code to (ex.details.ifBlank { ex.message ?: ex.code })
                } else {
                    "NETWORK" to (ex?.message ?: "Unknown error")
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.embedded_test_connection_failed)
                    .setMessage("[$code]\n$details")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun startVocalizerDownload(item: VocalizerCatalogItem) {
        val ctx = fragment.requireContext()
        val root = fragment.view ?: return
        VoiceDownloadManager.startVocalizerDownload(ctx, item)
        renderVoicesList(root)
    }

    private fun updateProgressUi(root: View, itemId: String, percent: Int) {
        val container = root.findViewById<LinearLayout>(
            R.id.ll_embedded_voices_list
        ) ?: return
        val count = container.childCount
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            val tvTitle = child.findViewById<TextView>(R.id.tv_voice_title)
            val match = VocalizerModelCatalog.ITEMS.firstOrNull {
                it.id == itemId
            }
            val isAr = LanguageCode.isArabic(Locale.getDefault().language)
            val matchingTitle = if (isAr) {
                match?.displayNameAr
            } else {
                match?.displayNameEn
            }
            if (matchingTitle != null && tvTitle?.text == matchingTitle) {
                val pb = child.findViewById<ProgressBar>(R.id.pb_download)
                val tvProg = child.findViewById<TextView>(
                    R.id.tv_download_progress_text
                )
                pb?.progress = percent
                tvProg?.text = fragment.getString(
                    R.string.embedded_downloading,
                    percent
                )
                break
            }
        }
    }

    private fun previewVocalizerVoice(item: VocalizerCatalogItem) {
        val sampleText = if (LanguageCode.isArabic(item.locale.language)) {
            "مرحباً بك، هذا نموذج صوت تجريبي " +
                "لمحرك فوكالايزر المدمج."
        } else {
            "Hello, this is a sample preview of the embedded Vocalizer voice."
        }
        val params = PreviewParams(
            enginePkg = VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
            voiceName = item.voiceName,
            languageTag = item.locale.toLanguageTag(),
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            sampleText = sampleText
        )
        preview.play(params)
    }

    private fun confirmDeleteVocalizerVoice(item: VocalizerCatalogItem) {
        val ctx = fragment.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.embedded_delete_btn)
            .setMessage(R.string.embedded_delete_confirm)
            .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                val deleted = VocalizerDownloader.deleteVoice(ctx, item)
                VoiceDownloadManager.deletePartial(ctx, item.id)
                if (deleted) {
                    Toast.makeText(
                        ctx,
                        R.string.embedded_delete_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    onStatusChanged()
                    fragment.view?.let { renderVoicesList(it) }
                    notifyCatalogChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== ع٢٩-8 بند 2: قائمة حالة المحركات
    // + حوار التفاصيل =====

    private fun renderEngineStatusList(root: View) {
        val container = root.findViewById<LinearLayout>(
            R.id.ll_engine_status_list
        ) ?: return
        val ctx = fragment.requireContext()

        // نضمن فحص المحركات لملء السجل
        // قبل البناء
        EnginePicker.installedEngines(ctx)

        container.removeAllViews()
        val engines = listOf(
            EngineEntry(
                id = VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                name = ctx.getString(R.string.embedded_engine_vocalizer),
                storageEngine = EmbeddedStorage.Engine.VOCALIZER
            )
        )

        val inflater = LayoutInflater.from(ctx)
        for (entry in engines) {
            val status = EngineStatusRegistry.statusOf(entry.id)
            val view = inflater.inflate(
                R.layout.item_engine_status,
                container,
                false
            )
            val tvName = view.findViewById<TextView>(R.id.tv_engine_name)
            val tvBadge = view.findViewById<TextView>(
                R.id.tv_engine_state_badge
            )
            tvName.text = entry.name
            val stateLabel = status?.state?.let {
                EngineStatusRegistry.labelAr(it)
            } ?: ctx.getString(R.string.engine_state_unknown)
            tvBadge.text = stateLabel
            view.setOnClickListener {
                showEngineDetailsDialog(entry)
            }
            container.addView(view)
        }
    }

    private data class EngineEntry(
        val id: String,
        val name: String,
        val storageEngine: EmbeddedStorage.Engine
    )

    private fun resolveEngineDataDir(
        ctx: Context,
        engineId: String
    ): File? {
        return when (HostProtocol.normalizeEngineId(engineId)) {
            HostProtocol.ENGINE_VOCALIZER ->
                VocalizerEmbeddedVoiceProvider.resolveDataDir(ctx)
            else -> null
        }
    }

    private fun showEngineDetailsDialog(entry: EngineEntry) {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(
            R.layout.dialog_engine_details,
            null
        )

        MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // ع٣١-2: حساب الجرد الحي وتحديث الحالة
        val dataDir = resolveEngineDataDir(ctx, entry.id)
        if (dataDir != null) {
            EngineStatusRegistry.noteFilesPresent(entry.id)
        } else {
            EngineStatusRegistry.noteFilesAbsent(entry.id)
        }

        val status = EngineStatusRegistry.statusOf(entry.id)

        renderDialogContent(dialogView, entry, status, "")

        // جلب سجل المضيف فوراً
        fragment.lifecycleScope.launch {
            val log = EngineHostClient.getLog(ctx, entry.id, maxLines = 40)
            renderDialogContent(
                dialogView,
                entry,
                EngineStatusRegistry.statusOf(entry.id),
                log
            )
        }

        // إن كانت الحالة غير READY وفيها صوت مكتمل، نشغل init تلقائياً
        if (status?.state != EngineStatusRegistry.State.READY &&
            dataDir != null
        ) {
            EngineStatusRegistry.noteInitPending(entry.id)
            fragment.view?.let { renderEngineStatusList(it) }
            renderDialogContent(
                dialogView,
                entry,
                EngineStatusRegistry.statusOf(entry.id),
                ""
            )

            fragment.lifecycleScope.launch {
                val result = EngineHostClient.init(
                    ctx,
                    entry.id,
                    dataDir.absolutePath,
                    force = false
                )
                if (result.ok) {
                    EngineStatusRegistry.noteReady(entry.id)
                } else {
                    EngineStatusRegistry.noteFailure(
                        entry.id,
                        result.code,
                        result.message
                    )
                }
                fragment.view?.let { renderEngineStatusList(it) }
                val updatedStatus = EngineStatusRegistry.statusOf(entry.id)
                val updatedLog = EngineHostClient.getLog(
                    ctx,
                    entry.id,
                    maxLines = 40
                )
                renderDialogContent(
                    dialogView,
                    entry,
                    updatedStatus,
                    updatedLog
                )
            }
        }

        val btnRetry = dialogView.findViewById<MaterialButton>(
            R.id.btn_retry_init
        )
        btnRetry.setOnClickListener {
            fragment.lifecycleScope.launch {
                val resolvedDir = resolveEngineDataDir(ctx, entry.id)
                if (resolvedDir == null) {
                    EngineStatusRegistry.record(
                        entry.id,
                        EngineStatusRegistry.State.NOT_INSTALLED,
                        code = "NOT_INSTALLED",
                        message = ctx.getString(
                            R.string.engine_no_installed_voices_for_init
                        )
                    )
                    fragment.view?.let { renderEngineStatusList(it) }
                    val updatedStatus = EngineStatusRegistry.statusOf(entry.id)
                    val updatedLog = EngineHostClient.getLog(
                        ctx,
                        entry.id,
                        maxLines = 40
                    )
                    renderDialogContent(
                        dialogView,
                        entry,
                        updatedStatus,
                        updatedLog
                    )
                    return@launch
                }

                EngineStatusRegistry.noteInitPending(entry.id)
                fragment.view?.let { renderEngineStatusList(it) }
                renderDialogContent(
                    dialogView,
                    entry,
                    EngineStatusRegistry.statusOf(entry.id),
                    ""
                )

                val result = EngineHostClient.init(
                    ctx,
                    entry.id,
                    resolvedDir.absolutePath,
                    force = true
                )
                if (result.ok) {
                    EngineStatusRegistry.noteReady(entry.id)
                    Toast.makeText(
                        ctx,
                        ctx.getString(
                            R.string.engine_retry_success,
                            entry.name
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    EngineStatusRegistry.noteFailure(
                        entry.id,
                        result.code,
                        result.message
                    )
                    Toast.makeText(
                        ctx,
                        ctx.getString(
                            R.string.engine_retry_failed,
                            result.code ?: "",
                            result.message ?: ""
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                fragment.view?.let { renderEngineStatusList(it) }
                val updatedStatus = EngineStatusRegistry.statusOf(entry.id)
                val updatedLog = EngineHostClient.getLog(
                    ctx,
                    entry.id,
                    maxLines = 40
                )
                renderDialogContent(
                    dialogView,
                    entry,
                    updatedStatus,
                    updatedLog
                )
            }
        }

        val btnCopy = dialogView.findViewById<MaterialButton>(
            R.id.btn_copy_diagnostics
        )
        btnCopy.setOnClickListener {
            fragment.lifecycleScope.launch {
                val currentStatus = EngineStatusRegistry.statusOf(entry.id)
                val log = EngineHostClient.getLog(
                    ctx,
                    entry.id,
                    maxLines = 40
                )
                val diagText = buildDiagnostics(
                    ctx,
                    entry,
                    currentStatus,
                    log
                )
                copyDiagnosticsToClipboard(ctx, entry.id, diagText)
            }
        }
        val btnDeleteAll = dialogView.findViewById<MaterialButton>(
            R.id.btn_delete_engine_data
        )
        btnDeleteAll.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.engine_delete_data_confirm_title)
                .setMessage(
                    ctx.getString(
                        R.string.engine_delete_data_confirm_msg,
                        entry.name
                    )
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                    val base = EmbeddedStorage.baseDir(
                        ctx,
                        entry.storageEngine
                    )
                    if (base.exists()) base.deleteRecursively()
                    val statusFile = EngineStatusRegistry.statusFile(
                        ctx.filesDir,
                        entry.id
                    )
                    if (statusFile.exists()) statusFile.delete()
                    EngineStatusRegistry.record(
                        entry.id,
                        EngineStatusRegistry.State.NOT_INSTALLED
                    )

                    EmbeddedStorage.bumpVersion()
                    EnginePicker.invalidateAfterStorageChange()
                    fragment.view?.let { renderEngineStatusList(it) }

                    val updatedStatus = EngineStatusRegistry.statusOf(
                        ctx,
                        entry.id
                    )
                    renderDialogContent(dialogView, entry, updatedStatus, "")
                    Toast.makeText(
                        ctx,
                        R.string.engine_data_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
        }
    }

    private fun renderDialogContent(
        dialogView: View,
        entry: EngineEntry,
        status: EngineStatusRegistry.EngineStatus?,
        hostLog: String
    ) {
        val ctx = dialogView.context
        val stateLabel = status?.state?.let {
            EngineStatusRegistry.labelAr(it)
        } ?: ctx.getString(R.string.engine_state_unknown)

        val code = status?.code ?: ctx.getString(R.string.no_code)

        val rawError = when {
            status?.lastError != null -> status.lastError
            status?.state == EngineStatusRegistry.State.READY ->
                ctx.getString(R.string.engine_no_errors)
            status?.state == EngineStatusRegistry.State.VOICES_PRESENT ->
                ctx.getString(R.string.engine_not_attempted)
            else -> ctx.getString(R.string.no_failure_recorded)
        }

        val downloadErrors = DownloadLog.getAllErrorsForEngine(entry.id)
        val downloadErrorsText = if (downloadErrors.isNotEmpty()) {
            val title = ctx.getString(
                R.string.embedded_download_errors_title
            )
            val lines = downloadErrors.joinToString("\n") {
                "• ${it.voiceId}: [${it.errorCode}] ${it.errorMessage}"
            }
            "$title\n$lines"
        } else {
            null
        }

        val fullErrorDisplay = if (downloadErrorsText != null) {
            if (status?.lastError != null) {
                "$rawError\n\n$downloadErrorsText"
            } else {
                downloadErrorsText
            }
        } else {
            rawError
        }

        val searchDirs = EmbeddedStorage.allSearchDirs(
            ctx,
            entry.storageEngine
        )
        val paths = searchDirs.map { it.absolutePath }.joinToString("\n")
        val inv = EmbeddedStorage.inventory(ctx, entry.storageEngine)
        val sizeText = formatSize(inv.totalSize)

        dialogView.findViewById<TextView>(
            R.id.tv_detail_engine_name
        ).text = entry.name
        dialogView.findViewById<TextView>(
            R.id.tv_detail_state
        ).text = ctx.getString(R.string.engine_detail_state, stateLabel)
        dialogView.findViewById<TextView>(
            R.id.tv_detail_code
        ).text = ctx.getString(R.string.engine_detail_code, code)
        dialogView.findViewById<TextView>(
            R.id.tv_detail_error
        ).text = ctx.getString(R.string.engine_detail_error, fullErrorDisplay)
        dialogView.findViewById<TextView>(
            R.id.tv_detail_paths
        ).text = ctx.getString(R.string.engine_detail_paths, paths)
        dialogView.findViewById<TextView>(
            R.id.tv_detail_size
        ).text = ctx.getString(R.string.engine_detail_size, sizeText)

        // ع٣١-4: عرض قائمة الملفات وتمييز غير المعروف منها
        val tvFilesTitle = dialogView.findViewById<TextView>(
            R.id.tv_detail_files_title
        )
        val llFilesContainer = dialogView.findViewById<LinearLayout>(
            R.id.ll_detail_files_container
        )
        llFilesContainer.removeAllViews()

        if (inv.files.isNotEmpty()) {
            tvFilesTitle.visibility = View.VISIBLE
            tvFilesTitle.text = ctx.getString(
                R.string.engine_detail_files_title,
                inv.files.size
            )
            llFilesContainer.visibility = View.VISIBLE

            val displayFiles = inv.files.take(30)
            for (fileItem in displayFiles) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                }
                val tvFile = TextView(ctx).apply {
                    val sizeStr = formatSize(fileItem.size)
                    val label = if (!fileItem.isRecognized) {
                        "• ${fileItem.name} ($sizeStr) — " +
                            ctx.getString(R.string.engine_detail_file_unknown)
                    } else {
                        "• ${fileItem.name} ($sizeStr)"
                    }
                    text = label
                    setTextAppearance(
                        com.google.android.material.R.style
                            .TextAppearance_MaterialComponents_Caption
                    )
                    if (!fileItem.isRecognized) {
                        setTextColor(
                            androidx.core.content.ContextCompat.getColor(
                                ctx,
                                android.R.color.holo_red_dark
                            )
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                row.addView(tvFile)

                val btnDel = MaterialButton(ctx).apply {
                    text = ctx.getString(R.string.engine_detail_file_delete)
                    textSize = 12f
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            ctx,
                            android.R.color.holo_red_dark
                        )
                    )
                    setPadding(12, 0, 12, 0)
                    minHeight = 0
                    minimumHeight = 0
                    setOnClickListener {
                        val msg: CharSequence = ctx.getString(
                            R.string.engine_delete_file_confirm,
                            fileItem.name
                        )
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(
                                R.string.engine_delete_data_confirm_title
                            )
                            .setMessage(msg)
                            .setNegativeButton(
                                android.R.string.cancel,
                                null
                            )
                            .setPositiveButton(
                                R.string.embedded_delete_btn
                            ) { _, _ ->
                                fileItem.file.delete()
                                EmbeddedStorage.bumpVersion()
                                EnginePicker.invalidateAfterStorageChange()
                                fragment.view?.let {
                                    renderEngineStatusList(it)
                                }
                                val updatedStatus =
                                    EngineStatusRegistry.statusOf(
                                        ctx,
                                        entry.id
                                    )
                                renderDialogContent(
                                    dialogView,
                                    entry,
                                    updatedStatus,
                                    hostLog
                                )
                                Toast.makeText(
                                    ctx,
                                    R.string.engine_file_deleted,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .show()
                    }
                }
                row.addView(btnDel)
                llFilesContainer.addView(row)
            }

            if (inv.files.size > 30) {
                val tvMore = TextView(ctx).apply {
                    text = ctx.getString(
                        R.string.engine_more_files_format,
                        inv.files.size - 30
                    )
                    setTextAppearance(
                        com.google.android.material.R.style
                            .TextAppearance_MaterialComponents_Caption
                    )
                }
                llFilesContainer.addView(tvMore)
            }
        } else {
            tvFilesTitle.visibility = View.GONE
            llFilesContainer.visibility = View.GONE
        }

        val tvLogTitle = dialogView.findViewById<TextView>(
            R.id.tv_detail_host_log_title
        )
        val tvLog = dialogView.findViewById<TextView>(
            R.id.tv_detail_host_log
        )
        if (hostLog.isNotBlank()) {
            tvLogTitle.visibility = View.VISIBLE
            tvLog.visibility = View.VISIBLE
            tvLog.text = hostLog
        } else {
            tvLogTitle.visibility = View.GONE
            tvLog.visibility = View.GONE
        }
    }

    private fun buildDiagnostics(
        ctx: Context,
        entry: EngineEntry,
        status: EngineStatusRegistry.EngineStatus?,
        hostLog: String
    ): String {
        val stateLabel = status?.state?.let {
            EngineStatusRegistry.labelAr(it)
        } ?: ctx.getString(R.string.engine_state_unknown)

        val code = status?.code ?: ctx.getString(R.string.no_code)

        val rawError = when {
            status?.lastError != null -> status.lastError
            status?.state == EngineStatusRegistry.State.READY ->
                ctx.getString(R.string.engine_no_errors)
            status?.state == EngineStatusRegistry.State.VOICES_PRESENT ->
                ctx.getString(R.string.engine_not_attempted)
            else -> ctx.getString(R.string.no_failure_recorded)
        }

        val downloadErrors = DownloadLog.getAllErrorsForEngine(entry.id)
        val downloadErrorsText = if (downloadErrors.isNotEmpty()) {
            val title = ctx.getString(
                R.string.embedded_download_errors_title
            )
            val lines = downloadErrors.joinToString("\n") {
                "• ${it.voiceId}: [${it.errorCode}] ${it.errorMessage}"
            }
            "$title\n$lines"
        } else {
            null
        }

        val fullErrorDisplay = if (downloadErrorsText != null) {
            if (status?.lastError != null) {
                "$rawError\n\n$downloadErrorsText"
            } else {
                downloadErrorsText
            }
        } else {
            rawError
        }

        val searchDirs = EmbeddedStorage.allSearchDirs(
            ctx,
            entry.storageEngine
        )
        val paths = searchDirs.map { it.absolutePath }.joinToString("\n")
        val inv = EmbeddedStorage.inventory(ctx, entry.storageEngine)
        val sizeText = formatSize(inv.totalSize)

        val filesSummary = if (inv.files.isNotEmpty()) {
            val list = inv.files.joinToString("\n") { f ->
                val flag = if (f.isRecognized) "" else " [غير معروف]"
                "  - ${f.name} (${formatSize(f.size)})$flag"
            }
            "\nالملفات المثبتة (${inv.files.size}):\n$list"
        } else {
            "\nالملفات المثبتة: لا توجد"
        }

        val baseDiag = "محرك: ${entry.name} (${entry.id})\n" +
            "الحالة: $stateLabel\n" +
            "الرمز: $code\n" +
            "الخطأ/الحالة: $fullErrorDisplay\n" +
            "المسارات:\n$paths\n" +
            "الحجم الكلي: $sizeText" +
            filesSummary

        return if (hostLog.isNotBlank()) {
            "$baseDiag\n\n--- سجل المضيف (:engine_host) ---\n$hostLog"
        } else {
            baseDiag
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walk().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "${kb} KB"
        val mb = kb / 1024
        if (mb < 1024) return "${mb} MB"
        val gb = mb / 1024
        return "${gb} GB"
    }

    private fun copyDiagnosticsToClipboard(
        ctx: Context,
        engineId: String,
        text: String
    ) {
        val clipboard = ctx.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager
        val clipData = ClipData.newPlainText(
            "engine_diagnostics_$engineId",
            text
        )
        clipboard.setPrimaryClip(clipData)
        Toast.makeText(
            ctx,
            R.string.copied_to_clipboard,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ===== ع٢٩-8 بند 3: شاشة التخزين =====

    fun showStorageScreen() {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(
            R.layout.dialog_storage_screen, null
        )
        val container = dialogView.findViewById<LinearLayout>(
            R.id.ll_storage_engine_list
        )

        val engines = listOf(
            StorageEngineEntry(
                id = VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                name = ctx.getString(R.string.embedded_engine_vocalizer),
                storageEngine = EmbeddedStorage.Engine.VOCALIZER,
                voiceProvider = "vocalizer"
            )
        )

        val inflater = LayoutInflater.from(ctx)
        for (entry in engines) {
            val searchDirs = EmbeddedStorage.allSearchDirs(
                ctx,
                entry.storageEngine
            )
            val paths = searchDirs.map { it.absolutePath }.joinToString("\n")
            val totalSize = searchDirs.sumOf { dirSize(it) }
            val sizeText = formatSize(totalSize)

            val installedVoices = getInstalledVoices(ctx, entry)
            val voiceCount = installedVoices.size

            val engineView = inflater.inflate(
                R.layout.item_storage_engine,
                container,
                false
            )
            engineView.findViewById<TextView>(
                R.id.tv_storage_engine_name
            ).text = entry.name
            engineView.findViewById<TextView>(
                R.id.tv_storage_engine_paths
            ).text = ctx.getString(R.string.storage_engine_paths, paths)
            engineView.findViewById<TextView>(
                R.id.tv_storage_engine_size
            ).text = ctx.getString(R.string.storage_engine_size, sizeText)
            engineView.findViewById<TextView>(
                R.id.tv_storage_voice_count
            ).text = ctx.getString(R.string.voice_count_format, voiceCount)

            val voicesContainer = engineView.findViewById<LinearLayout>(
                R.id.ll_storage_voices
            )
            if (installedVoices.isEmpty()) {
                val emptyTv = TextView(ctx)
                emptyTv.text = ctx.getString(R.string.no_voices_installed)
                emptyTv.setTextAppearance(android.R.style.TextAppearance_Small)
                emptyTv.setTextColor(ctx.getColor(android.R.color.darker_gray))
                emptyTv.setPadding(16, 8, 16, 8)
                voicesContainer.addView(emptyTv)
            } else {
                for (voice in installedVoices) {
                    val voiceView = inflater.inflate(
                        R.layout.item_storage_voice,
                        voicesContainer,
                        false
                    )
                    voiceView.findViewById<TextView>(
                        R.id.tv_storage_voice_name
                    ).text = voice.name
                    voiceView.findViewById<TextView>(
                        R.id.tv_storage_voice_size
                    ).text = ctx.getString(
                        R.string.voice_size_format,
                        voice.name,
                        formatSize(voice.size)
                    )
                    voiceView.findViewById<MaterialButton>(
                        R.id.btn_storage_voice_delete
                    ).setOnClickListener {
                        confirmDeleteStorageVoice(
                            ctx,
                            entry,
                            voice,
                            dialogView
                        )
                    }
                    voicesContainer.addView(voiceView)
                }
            }

            val btnMigrate = engineView.findViewById<MaterialButton>(
                R.id.btn_storage_migrate
            )
            btnMigrate.setOnClickListener {
                startMigration(ctx, entry.storageEngine, btnMigrate)
            }

            val copyPathsBtn = engineView.findViewById<MaterialButton>(
                R.id.btn_storage_copy_paths
            )
            copyPathsBtn.setOnClickListener {
                copyDiagnosticsToClipboard(ctx, entry.id, paths)
            }

            container.addView(engineView)
        }


        MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private data class StorageEngineEntry(
        val id: String,
        val name: String,
        val storageEngine: EmbeddedStorage.Engine,
        val voiceProvider: String
    )

    private data class VoiceInfo(
        val name: String,
        val size: Long
    )

    private fun getInstalledVoices(
        ctx: Context,
        entry: StorageEngineEntry
    ): List<VoiceInfo> {
        return when (entry.voiceProvider) {
            "vocalizer" -> {
                val baseDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
                VocalizerModelCatalog.ITEMS
                    .filter {
                        VocalizerModelCatalog.isVoiceInstalled(baseDir, it)
                    }
                    .map {
                        VoiceInfo(
                            it.displayNameEn,
                            it.sizeMb.toLong() * 1024 * 1024
                        )
                    }
            }
            else -> emptyList()
        }
    }

    private fun confirmDeleteStorageVoice(
        ctx: Context,
        entry: StorageEngineEntry,
        voice: VoiceInfo,
        dialogView: View
    ) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.embedded_delete_btn)
            .setMessage(
                ctx.getString(
                    R.string.storage_voice_delete_confirm,
                    voice.name
                )
            )
            .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                when (entry.voiceProvider) {
                    "vocalizer" -> {
                        val item = VocalizerModelCatalog.ITEMS
                            .firstOrNull { it.displayNameEn == voice.name }
                        item?.let {
                            VocalizerDownloader.deleteVoice(ctx, it)
                        }
                    }
                }
                Toast.makeText(
                    ctx,
                    R.string.embedded_delete_success,
                    Toast.LENGTH_SHORT
                ).show()
                onStatusChanged()
                notifyCatalogChanged()
                // إعادة بناء الحوار
                (dialogView.parent as? android.view.ViewGroup)?.let {
                    it.removeAllViews()
                }
                fragment.view?.let { showStorageScreen() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startMigration(
        ctx: Context,
        engine: EmbeddedStorage.Engine,
        btn: MaterialButton
    ) {
        btn.isEnabled = false
        btn.text = ctx.getString(R.string.storage_migration_progress, 0, 0)

        EmbeddedStorage.setMigrationListener { _, done, total ->
            fragment.activity?.runOnUiThread {
                btn.text = ctx.getString(
                    R.string.storage_migration_progress,
                    done,
                    total
                )
            }
        }
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val moved = EmbeddedStorage.migrateExternalToInternal(
                ctx,
                engine
            ) { _, _ -> }
            fragment.activity?.runOnUiThread {
                EmbeddedStorage.setMigrationListener(null)
                btn.isEnabled = true
                if (moved > 0) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.storage_migration_done, moved),
                        Toast.LENGTH_SHORT
                    ).show()
                    onStatusChanged()
                    fragment.view?.let { showStorageScreen() }
                } else {
                    Toast.makeText(
                        ctx,
                        R.string.already_migrated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun notifyCatalogChanged() {
        (fragment as? VoiceSelectionFragment)
            ?.refreshVoiceCatalogDiscovery()
    }
}
