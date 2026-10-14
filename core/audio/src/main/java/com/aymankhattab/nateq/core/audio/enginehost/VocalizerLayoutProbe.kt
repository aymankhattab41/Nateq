package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import java.util.Date

/**
 * ع٣٣-4: كشف تخطيط الحزمة المتوقع لمحرك Vocalizer:
 * 1. النص الكامل لـ voice_embedded_high.xml
 * 2. السلاسل المقروءة (طولها 6 أحرف فأكثر، حتى 60) من ve_pipeline_*.hdr
 * 3. أول 64 بايت من كل ملف .dat بالست عشري
 * 4. File.canRead() و length() و lastModified() وصلاحيات المجلد
 */
object VocalizerLayoutProbe {

    /**
     * استخراج كل السلاسل النصية المقروءة (ASCII 32..126) بطول
     * [minLen] على الأقل، بحد أقصى [maxStrings].
     */
    fun extractPrintableStrings(
        file: File,
        minLen: Int = 6,
        maxStrings: Int = 60
    ): List<String> {
        if (!file.exists() || !file.isFile) return emptyList()
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return emptyList()
        val strings = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c in ' '..'~') {
                sb.append(c)
            } else {
                if (sb.length >= minLen) {
                    strings.add(sb.toString())
                    if (strings.size >= maxStrings) break
                }
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen && strings.size < maxStrings) {
            strings.add(sb.toString())
        }
        return strings
    }

    /**
     * قراءة أول [maxBytes] بايت من الملف وترميزها بالست عشري (Hex).
     */
    fun readFirstBytesHex(file: File, maxBytes: Int = 64): String {
        if (!file.exists() || !file.isFile) return ""
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf)
                if (n <= 0) {
                    ""
                } else {
                    buf.take(n).joinToString("") { "%02X".format(it) }
                }
            }
        }.getOrDefault("")
    }

    /**
     * بناء تقرير فحص تفصيلي للمجلد والملفات لمحرك Vocalizer.
     */
    fun inspect(dataDir: File): String {
        if (!dataDir.exists()) {
            return "مجلد البيانات غير موجود: ${dataDir.absolutePath}"
        }
        val sb = StringBuilder()
        sb.appendLine("=== فحص تخطيط Vocalizer ===")
        sb.appendLine("المجلد: ${dataDir.absolutePath}")
        sb.appendLine(
            "صلاحيات المجلد: r=${dataDir.canRead()}, " +
                "w=${dataDir.canWrite()}, x=${dataDir.canExecute()}"
        )

        val allFiles = if (dataDir.isDirectory) {
            dataDir.walkTopDown().filter { it.isFile }.toList()
        } else {
            emptyList()
        }
        sb.appendLine("إجمالي الملفات: ${allFiles.size}")

        // 4. canRead() و length() و lastModified() لكل ملف
        for (f in allFiles) {
            val rel = f.relativeToOrSelf(dataDir).path
            val mod = Date(f.lastModified())
            sb.appendLine(
                "  • $rel: size=${f.length()} B, canRead=${f.canRead()}, " +
                    "modified=$mod"
            )
        }

        // 1. النص الكامل لـ voice_embedded_high.xml
        val xmlFile = allFiles.firstOrNull {
            it.name.equals("voice_embedded_high.xml", ignoreCase = true)
        } ?: allFiles.firstOrNull { it.extension.equals("xml", true) }

        if (xmlFile != null) {
            sb.appendLine("\n--- محتوى ${xmlFile.name} ---")
            val content = runCatching {
                xmlFile.readText(Charsets.UTF_8)
            }.getOrElse { "تعذّرت قراءة الملف: ${it.message}" }
            sb.appendLine(content)
        } else {
            sb.appendLine("\n(لم يتم العثور على ملف XML)")
        }

        // 2. السلاسل المقروءة من ملفات ve_pipeline_*.hdr
        val hdrFiles = allFiles.filter {
            it.extension.equals("hdr", ignoreCase = true)
        }
        for (hdr in hdrFiles) {
            val strings = extractPrintableStrings(hdr)
            sb.appendLine("\n--- سلاسل ${hdr.name} (${strings.size}) ---")
            if (strings.isEmpty()) {
                sb.appendLine("(لا سلاسل مقروءة)")
            } else {
                for (s in strings) {
                    sb.appendLine("  \"$s\"")
                }
            }
        }

        // 3. أول 64 بايت من كل .dat بالست عشري
        val datFiles = allFiles.filter {
            it.extension.equals("dat", ignoreCase = true)
        }
        if (datFiles.isNotEmpty()) {
            sb.appendLine("\n--- ترويسة ملفات DAT (أول 64 بايت Hex) ---")
            for (dat in datFiles) {
                val hex = readFirstBytesHex(dat, 64)
                sb.appendLine("  ${dat.name} (${dat.length()} B):")
                sb.appendLine("    $hex")
            }
        }

        return sb.toString()
    }

    /**
     * تسجيل الفحص بالكامل في HostLog.
     */
    fun logLayoutDetails(engineId: String, dataDir: File) {
        val rep = inspect(dataDir)
        HostLog.d(engineId, rep)
    }
}
