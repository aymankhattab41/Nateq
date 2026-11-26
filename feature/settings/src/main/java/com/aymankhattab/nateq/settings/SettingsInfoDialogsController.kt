package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * حوارات معلومات/مساعدة خفيفة في شاشة الإعدادات (آخر التحديثات، دليل
 * الوصولية) — مُستخرجة من [VoiceSelectionFragment] ضمن تفكيك (14) دون
 * تغيير سلوك.
 */
internal class SettingsInfoDialogsController(
    private val host: VoiceSelectionFragment
) {

    fun showChangelogDialog() {
        val body = runCatching {
            host.getString(R.string.changelog_text)
        }.getOrElse {
            runCatching {
                @Suppress("DiscouragedApi")
                val id = host.resources.getIdentifier(
                    "changelog_text", "string", host.requireContext().packageName
                )
                if (id != 0) host.getString(id) else ""
            }.getOrDefault("")
        }
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
