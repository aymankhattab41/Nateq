package com.aymankhattab.nateq.settings

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceItem
import com.aymankhattab.nateq.core.audio.providers.PiperCatalogItem
import com.aymankhattab.nateq.core.audio.providers.PiperDownloader
import com.aymankhattab.nateq.core.audio.providers.PiperModelCatalog
import com.aymankhattab.nateq.core.audio.providers.SherpaOnnxVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerCatalogItem
import com.aymankhattab.nateq.core.audio.providers.VocalizerDownloader
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ضابط شاشة تنزيل وإدارة أصوات ومحركات
 * Piper / Sherpa-ONNX و Vocalizer Expressive 2 المدمجة.
 */
internal class EmbeddedEngineDownloaderController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val preview: VoicePreviewHelper
) {

    var onStatusChanged: () -> Unit = {}

    private var activeEngine = "piper"
    private var activeFilter = "all"
    private val downloadingJobs = ConcurrentHashMap<String, Job>()
    private val downloadingProgress = ConcurrentHashMap<String, Int>()

    fun setup(root: View) {
        val engineToggle = root.findViewById<MaterialButtonToggleGroup>(
            R.id.toggle_group_engine_filter
        )
        val langToggle = root.findViewById<MaterialButtonToggleGroup>(
            R.id.toggle_group_lang_filter
        )

        engineToggle?.check(
            when (activeEngine) {
                "vocalizer" -> R.id.btn_engine_vocalizer
                "acapela" -> R.id.btn_engine_acapela
                else -> R.id.btn_engine_piper
            }
        )
        engineToggle?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                activeEngine = when (checkedId) {
                    R.id.btn_engine_vocalizer -> "vocalizer"
                    R.id.btn_engine_acapela -> "acapela"
                    else -> "piper"
                }
                renderVoicesList(root)
            }
        }

        val btnImport = root.findViewById<MaterialButton>(
            R.id.btn_import_voice_zip
        )
        btnImport?.setOnClickListener {
            fragment.requestVoiceZipImport()
        }

        langToggle?.check(R.id.btn_filter_all)
        langToggle?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                activeFilter = when (checkedId) {
                    R.id.btn_filter_ar -> "ar"
                    R.id.btn_filter_en -> "en"
                    R.id.btn_filter_other -> "other"
                    else -> "all"
                }
                renderVoicesList(root)
            }
        }

        renderVoicesList(root)
    }

    fun refresh(root: View) {
        renderVoicesList(root)
    }

    /** بناء نص ملخص حالة الأصوات المثبتة للبطاقة */
    fun buildStatusSummary(): String {
        val ctx = fragment.context ?: return ""

        val piperDir = PiperDownloader.getPiperBaseDir(ctx)
        val piperCount = PiperModelCatalog.ITEMS.count { item ->
            PiperModelCatalog.isModelInstalled(piperDir, item)
        }

        val vocDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
        val vocCount = VocalizerModelCatalog.ITEMS.count { item ->
            VocalizerModelCatalog.isVoiceInstalled(vocDir, item)
        }

        val acapelaCount = AcapelaEmbeddedVoiceProvider
            .getInstalledVoices(ctx).size

        val total = piperCount + vocCount + acapelaCount
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
            R.id.ll_piper_voices_list
        ) ?: return
        val emptyView = root.findViewById<TextView>(
            R.id.tv_embedded_empty
        )

        container.removeAllViews()

        when (activeEngine) {
            "acapela" -> renderAcapelaVoices(container, emptyView)
            "vocalizer" -> renderVocalizerVoices(container, emptyView)
            else -> renderPiperVoices(container, emptyView)
        }
    }

    private fun renderPiperVoices(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val filtered = when (activeFilter) {
            "ar" -> PiperModelCatalog.ITEMS.filter {
                it.languageCategory == "ar"
            }
            "en" -> PiperModelCatalog.ITEMS.filter {
                it.languageCategory == "en"
            }
            "other" -> PiperModelCatalog.ITEMS.filter {
                it.languageCategory == "other"
            }
            else -> PiperModelCatalog.ITEMS
        }

        if (filtered.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val inflater = LayoutInflater.from(fragment.requireContext())
        for (item in filtered) {
            val itemView = inflater.inflate(
                R.layout.item_piper_voice,
                container,
                false
            )
            bindPiperCard(itemView, item)
            container.addView(itemView)
        }
    }

    private fun renderVocalizerVoices(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val filtered = when (activeFilter) {
            "ar" -> VocalizerModelCatalog.ITEMS.filter {
                it.languageCategory == "ar"
            }
            "en" -> VocalizerModelCatalog.ITEMS.filter {
                it.languageCategory == "en"
            }
            "other" -> VocalizerModelCatalog.ITEMS.filter {
                it.languageCategory == "other"
            }
            else -> VocalizerModelCatalog.ITEMS
        }

        if (filtered.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val inflater = LayoutInflater.from(fragment.requireContext())
        for (item in filtered) {
            val itemView = inflater.inflate(
                R.layout.item_piper_voice,
                container,
                false
            )
            bindVocalizerCard(itemView, item)
            container.addView(itemView)
        }
    }

    private fun bindPiperCard(view: View, item: PiperCatalogItem) {
        val ctx = fragment.requireContext()
        val isAr = LanguageCode.isArabic(Locale.getDefault().language)

        val tvTitle = view.findViewById<TextView>(R.id.tv_voice_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_voice_subtitle)
        val badge = view.findViewById<TextView>(R.id.tv_voice_status_badge)
        val btnDownload = view.findViewById<MaterialButton>(
            R.id.btn_voice_download
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

        tvTitle.text = if (isAr) item.displayNameAr else item.displayNameEn
        val langTag = item.locale.toLanguageTag()
        tvSubtitle.text =
            "Piper • $langTag • ${item.sizeMb} MB • ${item.quality}"

        val baseDir = PiperDownloader.getPiperBaseDir(ctx)
        val isInstalled = PiperModelCatalog.isModelInstalled(baseDir, item)
        val isDownloading = downloadingJobs.containsKey(item.id)

        if (isDownloading) {
            badge.visibility = View.GONE
            btnDownload.visibility = View.GONE
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.VISIBLE
            val pct = downloadingProgress[item.id] ?: 0
            pbDownload.progress = pct
            tvProgress.text = fragment.getString(
                R.string.embedded_downloading,
                pct
            )
        } else if (isInstalled) {
            badge.visibility = View.VISIBLE
            btnDownload.visibility = View.GONE
            btnPreview.visibility = View.VISIBLE
            btnDelete.visibility = View.VISIBLE
            llProgress.visibility = View.GONE

            btnPreview.setOnClickListener {
                previewPiperVoice(item)
            }

            btnDelete.setOnClickListener {
                confirmDeletePiperVoice(item)
            }
        } else {
            badge.visibility = View.GONE
            btnDownload.visibility = View.VISIBLE
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.GONE

            btnDownload.setOnClickListener {
                startPiperDownload(item)
            }
        }
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

        tvTitle.text = if (isAr) item.displayNameAr else item.displayNameEn
        val langTag = item.locale.toLanguageTag()
        tvSubtitle.text =
            "Vocalizer • $langTag • ${item.sizeMb} MB • ${item.quality}"

        val baseDir = VocalizerDownloader.getVocalizerBaseDir(ctx)
        val isInstalled = VocalizerModelCatalog.isVoiceInstalled(baseDir, item)
        val isDownloading = downloadingJobs.containsKey(item.id)

        if (isDownloading) {
            badge.visibility = View.GONE
            btnDownload.visibility = View.GONE
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.VISIBLE
            val pct = downloadingProgress[item.id] ?: 0
            pbDownload.progress = pct
            tvProgress.text = fragment.getString(
                R.string.embedded_downloading,
                pct
            )
        } else if (isInstalled) {
            badge.visibility = View.VISIBLE
            btnDownload.visibility = View.GONE
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
            btnPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
            llProgress.visibility = View.GONE

            btnDownload.setOnClickListener {
                startVocalizerDownload(item)
            }
        }
    }

    private fun startPiperDownload(item: PiperCatalogItem) {
        val ctx = fragment.requireContext()
        val root = fragment.view ?: return

        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            downloadingProgress[item.id] = 0
            renderVoicesList(root)

            val result = PiperDownloader.downloadVoice(ctx, item) {
                _, _, percent ->
                downloadingProgress[item.id] = percent
                fragment.activity?.runOnUiThread {
                    updateProgressUi(root, item.id, percent)
                }
            }

            downloadingJobs.remove(item.id)
            downloadingProgress.remove(item.id)

            if (result.isSuccess) {
                Toast.makeText(
                    ctx,
                    R.string.embedded_download_success,
                    Toast.LENGTH_SHORT
                ).show()
                onStatusChanged()
            } else {
                val err = result.exceptionOrNull()?.message ?: ""
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.embedded_download_error, err),
                    Toast.LENGTH_LONG
                ).show()
            }
            renderVoicesList(root)
        }
        downloadingJobs[item.id] = job
    }

    private fun startVocalizerDownload(item: VocalizerCatalogItem) {
        val ctx = fragment.requireContext()
        val root = fragment.view ?: return

        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            downloadingProgress[item.id] = 0
            renderVoicesList(root)

            val result = VocalizerDownloader.downloadVoice(ctx, item) { pct ->
                downloadingProgress[item.id] = pct
                fragment.activity?.runOnUiThread {
                    updateProgressUi(root, item.id, pct)
                }
            }

            downloadingJobs.remove(item.id)
            downloadingProgress.remove(item.id)

            if (result.isSuccess) {
                Toast.makeText(
                    ctx,
                    R.string.embedded_download_success,
                    Toast.LENGTH_SHORT
                ).show()
                onStatusChanged()
            } else {
                val err = result.exceptionOrNull()?.message ?: ""
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.embedded_download_error, err),
                    Toast.LENGTH_LONG
                ).show()
            }
            renderVoicesList(root)
        }
        downloadingJobs[item.id] = job
    }

    private fun updateProgressUi(root: View, itemId: String, percent: Int) {
        val container = root.findViewById<LinearLayout>(
            R.id.ll_piper_voices_list
        ) ?: return
        val count = container.childCount
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            val tvTitle = child.findViewById<TextView>(R.id.tv_voice_title)
            val matchingTitle = if (activeEngine == "piper") {
                val match = PiperModelCatalog.ITEMS.firstOrNull {
                    it.id == itemId
                }
                val isAr = LanguageCode.isArabic(Locale.getDefault().language)
                if (isAr) match?.displayNameAr else match?.displayNameEn
            } else {
                val match = VocalizerModelCatalog.ITEMS.firstOrNull {
                    it.id == itemId
                }
                val isAr = LanguageCode.isArabic(Locale.getDefault().language)
                if (isAr) match?.displayNameAr else match?.displayNameEn
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

    private fun previewPiperVoice(item: PiperCatalogItem) {
        val sampleText = if (LanguageCode.isArabic(item.locale.language)) {
            "مرحباً بك، هذا نموذج صوت تجريبي لمحرك بيبر المدمج."
        } else {
            "Hello, this is a sample preview of the embedded Piper voice."
        }
        val params = PreviewParams(
            enginePkg = SherpaOnnxVoiceProvider.PROVIDER_ID,
            voiceName = item.modelFileName.removeSuffix(".onnx"),
            languageTag = item.locale.toLanguageTag(),
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            sampleText = sampleText
        )
        preview.play(params)
    }

    private fun previewVocalizerVoice(item: VocalizerCatalogItem) {
        val sampleText = if (LanguageCode.isArabic(item.locale.language)) {
            "مرحباً بك، هذا نموذج صوت تجريبي لمحرك فوكالايزر المدمج."
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

    private fun confirmDeletePiperVoice(item: PiperCatalogItem) {
        val ctx = fragment.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.embedded_delete_btn)
            .setMessage(R.string.embedded_delete_confirm)
            .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                val deleted = PiperDownloader.deleteVoice(ctx, item)
                if (deleted) {
                    Toast.makeText(
                        ctx,
                        R.string.embedded_delete_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    onStatusChanged()
                    fragment.view?.let { renderVoicesList(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderAcapelaVoices(
        container: LinearLayout,
        emptyView: TextView?
    ) {
        val ctx = fragment.requireContext()
        val installed = AcapelaEmbeddedVoiceProvider.getInstalledVoices(ctx)

        val filtered = when (activeFilter) {
            "ar" -> installed.filter {
                LanguageCode.isArabic(it.locale.language)
            }
            "en" -> installed.filter {
                it.locale.language.startsWith("en", ignoreCase = true)
            }
            "other" -> installed.filter {
                !LanguageCode.isArabic(it.locale.language) &&
                    !it.locale.language.startsWith("en", ignoreCase = true)
            }
            else -> installed
        }

        if (filtered.isEmpty()) {
            emptyView?.text = fragment.getString(
                R.string.embedded_acapela_empty_hint
            )
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        val inflater = LayoutInflater.from(ctx)
        for (item in filtered) {
            val itemView = inflater.inflate(
                R.layout.item_piper_voice,
                container,
                false
            )
            bindAcapelaCard(itemView, item)
            container.addView(itemView)
        }
    }

    private fun bindAcapelaCard(view: View, item: AcapelaVoiceItem) {
        val tvTitle = view.findViewById<TextView>(R.id.tv_voice_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_voice_subtitle)
        val badge = view.findViewById<TextView>(R.id.tv_voice_status_badge)
        val btnDownload = view.findViewById<MaterialButton>(
            R.id.btn_voice_download
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

        tvTitle.text = item.displayName
        val langTag = item.locale.toLanguageTag()
        tvSubtitle.text = "Acapela • $langTag • ${item.sizeMb} MB"

        badge.visibility = View.VISIBLE
        btnDownload.visibility = View.GONE
        btnPreview.visibility = View.VISIBLE
        btnDelete.visibility = View.VISIBLE
        llProgress.visibility = View.GONE

        btnPreview.setOnClickListener {
            previewAcapelaVoice(item)
        }

        btnDelete.setOnClickListener {
            confirmDeleteAcapelaVoice(item)
        }
    }

    private fun previewAcapelaVoice(item: AcapelaVoiceItem) {
        val sampleText = if (LanguageCode.isArabic(item.locale.language)) {
            "مرحباً بك، هذا نموذج صوت تجريبي لمحرك أكابيلا المدمج."
        } else {
            "Hello, this is a sample preview of the embedded Acapela voice."
        }
        val params = PreviewParams(
            enginePkg = AcapelaEmbeddedVoiceProvider.PROVIDER_ID,
            voiceName = item.voiceName,
            languageTag = item.locale.toLanguageTag(),
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            sampleText = sampleText
        )
        preview.play(params)
    }

    private fun confirmDeleteAcapelaVoice(item: AcapelaVoiceItem) {
        val ctx = fragment.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.embedded_delete_btn)
            .setMessage(R.string.embedded_delete_confirm)
            .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                val deleted = AcapelaEmbeddedVoiceProvider.deleteVoice(item)
                if (deleted) {
                    Toast.makeText(
                        ctx,
                        R.string.embedded_delete_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    onStatusChanged()
                    fragment.view?.let { renderVoicesList(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteVocalizerVoice(item: VocalizerCatalogItem) {
        val ctx = fragment.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.embedded_delete_btn)
            .setMessage(R.string.embedded_delete_confirm)
            .setPositiveButton(R.string.embedded_delete_btn) { _, _ ->
                val deleted = VocalizerDownloader.deleteVoice(ctx, item)
                if (deleted) {
                    Toast.makeText(
                        ctx,
                        R.string.embedded_delete_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    onStatusChanged()
                    fragment.view?.let { renderVoicesList(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
