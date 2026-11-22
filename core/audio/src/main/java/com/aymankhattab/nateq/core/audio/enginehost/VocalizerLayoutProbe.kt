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

    /** سقف بايتات فحص سلاسل ملفات الترويسة لدرء استنزاف الذاكرة (512 KB). */
    const val MAX_PROBE_READ_BYTES = 512 * 1024L

    /** سقف بايتات قراءة ملفات XML التشخيصية (512 KB). */
    const val MAX_XML_READ_BYTES = 512 * 1024L

    /**
     * استخراج كل السلاسل النصية المقروءة (ASCII 32..126) بطول
     * [minLen] على الأقل، بحد أقصى [maxStrings]، مع قراءة تدفقية
     * محدودة بسقف [maxBytesToScan] لدرء استنزاف الذاكرة (OOM).
     */
    fun extractPrintableStrings(
        file: File,
        minLen: Int = 6,
        maxStrings: Int = 60,
        maxBytesToScan: Long = MAX_PROBE_READ_BYTES
    ): List<String> {
        if (!file.exists() || !file.isFile) return emptyList()
        val strings = mutableListOf<String>()
        val sb = StringBuilder()
        var totalBytesRead = 0L

        runCatching {
            file.inputStream().buffered().use { input ->
                var byteRead = 0
                while (totalBytesRead < maxBytesToScan &&
                    input.read().also { byteRead = it } != -1
                ) {
                    totalBytesRead++
                    val c = byteRead.toChar()
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
            }
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
                xmlFile.inputStream().bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(MAX_XML_READ_BYTES.toInt())
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
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
