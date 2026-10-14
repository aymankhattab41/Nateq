package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import java.io.InputStream

/**
 * معلومات ترويسة خط أنابيب Vocalizer المستخرجة من ملف الـ HDR (ع٣٦-1).
 */
data class VocalizerHdrInfo(
    val vopversion: String? = null,
    val langversion: String? = null,
    val voiceoperatingpoint: String? = null,
    val datapackagename: String? = null,
    val fecfg: String? = null,
    val extclccfg: String? = null,
    val sampleRateHz: Int? = null,
    val components: List<String> = emptyList(),
    val rawText: String = ""
)

/**
 * قارئ وسوم ملفات .hdr (وهي نصوص XML) لمحرك Nuance Vocalizer (ع٣٦-1).
 */
object VocalizerHdrParser {

    private val COMPONENT_REGEX =
        Regex("<COMPONENT>([^<]+)</COMPONENT>", RegexOption.IGNORE_CASE)

    private fun tagRegex(tag: String): Regex =
        Regex("<$tag>([^<]*)</$tag>", RegexOption.IGNORE_CASE)

    /**
     * فك وسوم ترويسة الـ HDR من نص XML.
     */
    fun parse(xmlContent: String): VocalizerHdrInfo {
        fun extract(tag: String): String? =
            tagRegex(tag).find(xmlContent)?.groupValues?.getOrNull(1)?.trim()

        val components = COMPONENT_REGEX.findAll(xmlContent)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        return VocalizerHdrInfo(
            vopversion = extract("vopversion"),
            langversion = extract("langversion"),
            voiceoperatingpoint = extract("voiceoperatingpoint"),
            datapackagename = extract("datapackagename"),
            fecfg = extract("fecfg"),
            extclccfg = extract("extclccfg"),
            sampleRateHz = extract("frequencyhz")?.toIntOrNull(),
            components = components,
            rawText = xmlContent
        )
    }

    /**
     * فك ترويسة من ملف.
     */
    fun parse(file: File): VocalizerHdrInfo? {
        if (!file.exists() || !file.isFile) return null
        val text = runCatching {
            file.readText(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return parse(text)
    }

    /**
     * فك ترويسة من تدفق إدخال (InputStream).
     */
    fun parse(stream: InputStream): VocalizerHdrInfo? {
        val text = runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return parse(text)
    }

    /**
     * تنسيق تقرير تشخيصي لوسوم الترويسة.
     */
    fun formatDiagnostic(info: VocalizerHdrInfo, fileName: String): String =
        buildString {
            appendLine("--- ترويسة ملف $fileName (ع٣٦-1) ---")
            appendLine("vopversion: ${info.vopversion ?: "(غير محدد)"}")
            appendLine("langversion: ${info.langversion ?: "(غير محدد)"}")
            appendLine(
                "voiceoperatingpoint: " +
                    (info.voiceoperatingpoint ?: "(غير محدد)")
            )
            appendLine(
                "datapackagename: " +
                    (info.datapackagename ?: "(غير محدد)")
            )
            appendLine("fecfg: ${info.fecfg ?: "(غير محدد)"}")
            appendLine("extclccfg: ${info.extclccfg ?: "(غير محدد)"}")
            appendLine(
                "frequencyhz: " +
                    (info.sampleRateHz?.toString() ?: "(غير محدد)")
            )
            appendLine("قائمة COMPONENTS (${info.components.size}):")
            for (comp in info.components) {
                appendLine("  • $comp")
            }
        }

    /**
     * فحص وتسجيل تفاصيل ترويسات HDR وملفات XML في مجلد البيانات (ع٣٦-1).
     */
    fun logHdrAndXmlDetails(engineId: String, dataDir: File) {
        if (!dataDir.exists() || !dataDir.isDirectory) return

        // 1. فحص ملفات .hdr
        val allFiles = dataDir.walkTopDown().maxDepth(2).toList()
        val hdrFiles = allFiles.filter {
            it.isFile && it.extension.equals("hdr", ignoreCase = true)
        }
        for (hdr in hdrFiles) {
            val info = parse(hdr)
            if (info != null) {
                val rel = hdr.relativeToOrSelf(dataDir).path
                HostLog.i(engineId, formatDiagnostic(info, rel))
            }
        }

        // 2. فحص محتوى voice_embedded_*.xml
        val xmlFiles = allFiles.filter {
            it.isFile &&
                it.extension.equals("xml", ignoreCase = true) &&
                it.name.contains("embedded", ignoreCase = true)
        }
        for (xml in xmlFiles) {
            val rel = xml.relativeToOrSelf(dataDir).path
            val content = runCatching {
                xml.readText(Charsets.UTF_8)
            }.getOrNull().orEmpty()
            HostLog.i(
                engineId,
                "--- محتوى ملف $rel كاملًا (${content.length} حرفًا) ---\n" +
                    content
            )
        }
    }
}
