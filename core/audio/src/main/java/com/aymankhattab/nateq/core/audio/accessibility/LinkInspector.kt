package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.net.Uri
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * مفتش الروابط (ع٥٦-أمر 18).
 * يفحص الروابط المستهدفة عند تركيزها وينبه للروابط المشبوهة ومطابقة النص.
 */
object LinkInspector {

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "is.gd", "cutt.ly", "rb.gy"
    )

    private val IP_REGEX =
        Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    private val URL_FINDER_REGEX =
        Regex("""(?i)\b(?:https?://|www\.)[^\s/$.?#].[^\s]*""")

    enum class LinkWarning {
        SHORTENED,
        NUMERIC_IP,
        SUSPICIOUS_PUNYCODE,
        TEXT_MISMATCH,
        NONE
    }

    data class InspectionResult(
        val host: String,
        val warning: LinkWarning
    )

    fun extractHost(rawUrl: String): String? {
        val clean = rawUrl.trim()
        val withScheme = if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            "https://$clean"
        } else {
            clean
        }

        return try {
            val uri = Uri.parse(withScheme)
            uri.host?.lowercase()
        } catch (_: Throwable) {
            null
        }
    }

    fun inspect(
        linkUrl: String,
        displayText: String? = null
    ): InspectionResult? {
        val host = extractHost(linkUrl) ?: return null

        val warning = when {
            SHORTENERS.contains(host) -> LinkWarning.SHORTENED
            host.matches(IP_REGEX) -> LinkWarning.NUMERIC_IP
            host.startsWith("xn--") || host.contains(".xn--") ->
                LinkWarning.SUSPICIOUS_PUNYCODE
            displayText != null && isTextMismatch(displayText, host) ->
                LinkWarning.TEXT_MISMATCH
            else -> LinkWarning.NONE
        }

        return InspectionResult(host, warning)
    }

    private fun isTextMismatch(displayText: String, host: String): Boolean {
        val foundInText = URL_FINDER_REGEX.find(displayText)?.value
            ?: return false
        val textHost = extractHost(foundInText) ?: return false
        return textHost != host
    }

    fun inspectAndAnnounce(
        context: Context,
        linkUrl: String,
        displayText: String? = null
    ): Boolean {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_link_inspector")) return false

        val result = inspect(linkUrl, displayText) ?: return false

        val warningMsg = when (result.warning) {
            LinkWarning.SHORTENED -> context.getString(R.string.link_shortened)
            LinkWarning.NUMERIC_IP ->
                context.getString(R.string.link_numeric_ip)
            LinkWarning.SUSPICIOUS_PUNYCODE ->
                context.getString(R.string.link_suspicious_punycode)
            LinkWarning.TEXT_MISMATCH ->
                context.getString(R.string.link_mismatch)
            LinkWarning.NONE -> null
        }

        val speech = if (warningMsg != null) {
            "$warningMsg: ${result.host}"
        } else {
            result.host
        }

        A11ySpeechCoordinator.speakAnnouncement(context, speech)
        return true
    }
}
