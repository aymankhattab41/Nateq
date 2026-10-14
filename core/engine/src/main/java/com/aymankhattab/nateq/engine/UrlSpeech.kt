package com.aymankhattab.nateq.engine

import java.net.URI

/**
 * تهذيب الروابط للنطق الصوتي (ع٤١-ب7).
 *
 * - الافتراضي: يُقرأ الرابط «رابط <النطاق>» مع اقتطاع المسارات الطويلة.
 * - عند الطلب ([keepFullUrl] = true): يُحفظ الرابط كاملاً (إيماءة «اقرأ
 *   الرابط كاملاً»).
 *
 * **مصدرٌ واحد:** يعتمد على هذا الكائن كلٌّ من [TextProcessor] (طيّ النطق)
 * و[UrlShortenerSpoken] في `:core:audio` فلا تتكرر القاعدة في موضعين.
 *
 * لا يُستدعى إلا عندما يكون مفتاح الميزة مفعّلاً (`FEATURE_URL_SHORTENING`
 * مطفأ افتراضياً) — فالسلوك القائم (تمرير الرابط كما هو) يبقى عند الإطفاء.
 */
object UrlSpeech {

    /** نمط الروابط: بروتوكول/`www` ثم أيُّ شيء، أو نطاقٌ بلا بروتوكول
     *  (بعنوانٍ مع مسار اختياري) — يلتقط http/https/www والنطاقات المجرّدة
     *  والروابط داخل الأقواس وعلامات الترقيم النهائية. */
    /** نمط الروابط: بروتوكول/`www` ثم أيُّ شيء، أو نطاقٌ بلا بروتوكول
     *  (بعنوانٍ مع مسار اختياري) — يلتقط http/https/www والنطاقات المجرّدة
     *  والروابط داخل الأقواس وعلامات الترقيم الطرفية. قوسٌ افتتاحيٌّ اختياري
     *  في الصدارة (([{«<) يُستهلك ثم يُقتطع في [stripEdges]. */
    private val URL_PATTERN = Regex(
        """(?i)[(\[{«<]?""" +
            """(?:https?://|www\.)[^\s<>"'«»]+""" +
            """|""" +
            """(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}""" +
            """(?::\d+)?(?:/[^\s<>"'«»]*)?"""
    )

    /** علامات ترقيم/أقواس نهائية ليست من الرابط فتُقتطع من نهايته. */
    private val TRAILING = ".،,;؛:!؟?…»”’\"')]}>".toSet()

    /** أقواس/محدِّدات افتتاحية تُقتطع من بداية الرابط. */
    private val LEADING = "([{«<".toSet()

    private const val PREFIX = "رابط "

    /**
     * استبدال الروابط في النص بصيغتها المنطوقة.
     * @param keepFullUrl يُحفظ الرابط كاملاً بدل النطاق المختصر.
     */
    fun shorten(text: String?, keepFullUrl: Boolean = false): String {
        if (text.isNullOrBlank()) return text.orEmpty()
        return URL_PATTERN.replace(text) { match ->
            render(match.value, keepFullUrl)
        }
    }

    /** الروابط الواردة في النص (منقّاة من الترقيم النهائي) بترتيب ورودها. */
    fun findUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return URL_PATTERN.findAll(text)
            .map { stripEdges(it.value) }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** الصيغة المنطوقة لرابط واحد. */
    private fun render(raw: String, keepFullUrl: Boolean): String {
        val cleaned = stripEdges(raw)
        if (keepFullUrl) return PREFIX + cleaned
        val host = hostOf(cleaned) ?: return PREFIX + cleaned
        return PREFIX + host
    }

    /** اقتطاع أقواسٍ/ترقيمٍ ملتصقة بطرفَي الرابط فهي ليست منه. */
    private fun stripEdges(url: String): String {
        var start = 0
        while (start < url.length && url[start] in LEADING) start++
        var end = url.length
        while (end > start && url[end - 1] in TRAILING) end--
        return url.substring(start, end)
    }

    /** النطاق (المضيف بلا `www.`) من رابط — بلا مسار ولا منفذ. */
    private fun hostOf(url: String): String? {
        val withScheme = if ("://" in url) url else "http://$url"
        val uriHost = runCatching { URI(withScheme).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val base = uriHost ?: url
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removeSuffix(".")
        val host = if (base.startsWith("www.", ignoreCase = true)) {
            base.substring(4)
        } else {
            base
        }
        return host.takeIf { it.isNotBlank() }
    }
}
