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

    override fun apply(input: String): String {
        val matcher = PATTERN_URL.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(1)!!
            // نستخرج اسم النطاق: www.example.com أو example.com أو example.com:8080/path
            var host = raw
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("www.")
            // قطع كل ما بعد أول / أو ? أو # (المسار/الاستعلام/الربط)
            val slash = host.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (slash >= 0) host = host.substring(0, slash)
            // إزالة المنفذ إن وجد (example.com:8080) وعلامات الترقيم الختامية
            host = host.substringBefore(":").trimEnd('.', ',', '،', ')', ';', '!', '؟')
            if (host.isBlank()) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(raw))
                continue
            }
            // نطق «موقع» + اسم النطاق مقروءاً (أنسب للمواقع المكتوبة بحروف لاتينية
            // من القراءة حرفاً حرفاً). تُحذف اللواحق الشائعة (com/net/org) للاختصار.
            val name = when {
                host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".org") ->
                    host.substringBeforeLast('.')
                else -> host
            }
            val spoken = "موقع $name"
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(spoken))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }
}