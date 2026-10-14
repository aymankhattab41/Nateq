package com.aymankhattab.nateq.core.audio.accessibility

import com.aymankhattab.nateq.engine.UrlSpeech

/**
 * تهذيب الروابط للنطق الصوتي (ع٤١-ب7).
 * واجهةُ `:core:audio` الرفيعة على [UrlSpeech] في `:core:engine` — مصدرٌ
 * واحدٌ للقاعدة، بلا ازدواج. يُستدعى من إيماءة «اقرأ الرابط كاملاً»،
 * بينما يطبّقه محرّكُ النطق (TextProcessor) عبر [UrlSpeech] مباشرة.
 */
object UrlShortenerSpoken {

    /**
     * استبدال الروابط في النص بنطاقاتها المقروءة.
     * @param keepFullUrl الاحتفاظ بالرابط كاملاً بدل النطاق المختصر.
     */
    fun sanitizeUrlsForSpeech(
        text: String?,
        keepFullUrl: Boolean = false
    ): String = UrlSpeech.shorten(text, keepFullUrl)

    /** الروابط الواردة في النص (منقّاة من الترقيم النهائي). */
    fun findUrlsForSpeech(text: String?): List<String> =
        UrlSpeech.findUrls(text)
}
