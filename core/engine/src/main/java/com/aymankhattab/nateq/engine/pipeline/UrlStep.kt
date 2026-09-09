package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الروابط: تحويل «https://example.com/path?q=1» إلى نطق دال على
 * اسم النطاق («موقع example.com») بدل قراءة الشعار والمحارف حرفاً حرفاً.
 * يُحفظ اسم النطاق ليُنطق كما هو (مقروء، فذلك أفضل لمواقع مكتوبة بحروف
 * لاتينية) وتُحذف بقية أجزاء الرابط بصمت.
 */
internal object UrlStep : TextProcessingStep {

    private val PATTERN_URL = Pattern.compile(
        """(?i)\b((?:https?://|www\.)[^\s<>"']+)"""
    )

    // شعار الرابط (http/https) وبادئة www. — يُحذفان بلا تمييز حالة الأحرف حتى
    // تُقرأ الروابط المكتوبة بحروف كبيرة (HTTPS://GOOGLE.COM) نطاقاً مقروءاً.
    private val PATTERN_SCHEME = Pattern.compile("""(?i)^https?://""")
    private val PATTERN_WWW = Pattern.compile("""(?i)^www\.""")

    override fun apply(input: String): String {
        val matcher = PATTERN_URL.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(1)!!
            // نستخرج اسم النطاق (HTTPS://WWW.GOOGLE.COM) بلا تمييز حالة الأحرف:
            // يُحذف الشعار ثم يحذف www. اللاحقة — كلٌّ بنمطه المستقل.
            var host = PATTERN_SCHEME.matcher(raw).replaceFirst("")
            host = PATTERN_WWW.matcher(host).replaceFirst("")
            // قطع كل ما بعد أول / أو ? أو # (المسار/الاستعلام/الربط)
            val slash = host.indexOfFirst {
                it == '/' || it == '?' || it == '#'
            }
            if (slash >= 0) host = host.substring(0, slash)
            // إزالة المنفذ إن وجد (example.com:8080) وعلامات الترقيم الختامية
            host = host.substringBefore(":")
                .trimEnd('.', ',', '،', ')', ';', '!', '؟')
            if (host.isBlank()) {
                val quoted = java.util.regex.Matcher
                    .quoteReplacement(raw)
                matcher.appendReplacement(buffer, quoted)
                continue
            }
            // تُحذف لواحق com/net/org — بلا تمييز حالة الأحرف حتى تُقرأ
            // «GOOGLE.COM» نطاقاً مختصراً «GOOGLE» لا «GOOGLE.COM».
            val name = when {
                host.endsWith(".com", ignoreCase = true) ||
                    host.endsWith(".net", ignoreCase = true) ||
                    host.endsWith(".org", ignoreCase = true) ->
                    host.substringBeforeLast('.')
                else -> host
            }
            val spoken = "موقع $name"
            val quoted = java.util.regex.Matcher
                .quoteReplacement(spoken)
            matcher.appendReplacement(buffer, quoted)
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }
}