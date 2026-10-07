package com.aymankhattab.nateq.core.audio.accessibility

import java.net.URI

/**
 * تهذيب الروابط للنطق الصوتي (ع٢٧-40).
 * يستبدل الروابط الطويلة والمعقدة باسم النطاق فقط لتسريع القراءة ووضوحها.
 */
object UrlShortenerSpoken {

    private val URL_REGEX =
        Regex("""https?://[^\s/$.?#].[^\s]*""", RegexOption.IGNORE_CASE)

    /**
     * استبدال الروابط في النص بنطاقاتها المقروءة
     */
    fun sanitizeUrlsForSpeech(text: String?): String {
        if (text.isNullOrBlank()) return ""

        return URL_REGEX.replace(text) { matchResult ->
            val urlString = matchResult.value
            val domain = runCatching {
                val uri = URI(urlString)
                val host = uri.host ?: urlString
                host.removePrefix("www.")
            }.getOrDefault(urlString)
            "رابط $domain"
        }
    }
}
