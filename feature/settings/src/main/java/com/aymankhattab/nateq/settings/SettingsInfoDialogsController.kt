package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * حوارات معلومات/مساعدة خفيفة في شاشة الإعدادات (اقرأ لاحقاً، آخر التحديثات،
 * دليل الوصولية) — مُستخرجة من [VoiceSelectionFragment] ضمن تفكيك (14) دون
 * تغيير سلوك.
 */
internal class SettingsInfoDialogsController(
    private val host: VoiceSelectionFragment
) {

    fun showReadLaterDialog() {
        val repo = host.settings
        val items = repo.getReadLaterItems()
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(R.string.read_later_title)
                .setMessage(R.string.read_later_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = items.map { item ->
            val preview = item.text.replace("\n", " ").trim()
            if (preview.length > 80) preview.take(80) + "…" else preview
        }.toTypedArray()
        MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.read_later_title)
            .setItems(labels) { _, which ->
                val item = items.getOrNull(which) ?: return@setItems
                showReadLaterItemActions(item.id, item.text)
            }
            .setNeutralButton(R.string.read_later_clear_all) { _, _ ->
                repo.clearReadLater()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showReadLaterItemActions(id: Long, text: String) {
        val repo = host.settings
        val options = arrayOf(
            host.getString(R.string.read_later_play),
            host.getString(R.string.read_later_delete)
        )
        MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.read_later_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        A11ySpeechCoordinator.speakAnnouncement(
                            context = host.requireContext(),
                            text = text,
                            isInterruptAllowed = true,
                            source = SpeechEntryPoints.Source.SCREEN_READER
                        )
                    }
                    1 -> {
                        repo.removeReadLaterItem(id)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showChangelogDialog() {
        val versionName = runCatching {
            host.requireContext().packageManager.getPackageInfo(
                host.requireContext().packageName, 0
            ).versionName
        }.getOrNull() ?: "?"
        val body = host.getString(R.string.changelog_text, versionName)
        val dialog = MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also(host::trackDialog)
        dialog.show()
        host.view?.announceCompat(host.getString(R.string.changelog_title))
    }

    /**
     * دليل الميزات والإيماءات: يُعرض للمبتدئ وللمتقدّم من TalkBack ليشرح
     * قبل الإيماءة وظيفتها وطريقة تفعيل الخدمة.
     */
    fun showA11yGuideDialog() {
        val dialog = MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.a11y_guide_title)
            .setMessage(R.string.a11y_guide_text)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also(host::trackDialog)
        dialog.show()
        host.view?.announceCompat(host.getString(R.string.a11y_guide_title))
    }
}
