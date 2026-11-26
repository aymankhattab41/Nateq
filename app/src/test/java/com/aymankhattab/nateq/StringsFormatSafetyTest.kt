package com.aymankhattab.nateq

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Formatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * حارس أمان صيَغ الموارد: أي `%` غير مهرّب أو أي تنسيق يرمي استثناء
 * في أي `strings.xml` بأي لغة يُسقط الاختبار. يمنع انهيار شاشات
 * تستدعي `getString(..., args)` مثل «آخر التحديثات».
 */
class StringsFormatSafetyTest {

    @Test
    fun allStringsXml_haveSafePercentAndFormatting() {
        val root = repoRoot()
        val xmlFiles = root.walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .filter { it.parentFile?.name?.startsWith("values") == true }
            .filter { ".git" !in it.path && "build" !in it.path }
            .toList()
            .sortedBy { it.path }

        if (xmlFiles.isEmpty()) fail("لم يُعثر على أي strings.xml تحت ${root.path}")

        val errors = mutableListOf<String>()
        val dbf = DocumentBuilderFactory.newInstance()
        for (file in xmlFiles) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val doc = runCatching {
                dbf.newDocumentBuilder().parse(file)
            }.getOrElse { e ->
                errors += "$rel: فشل تحليل XML: ${e.message}"
                continue
            }
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: "?"
                val formatted = node.attributes?.getNamedItem("formatted")
                    ?.nodeValue?.lowercase(Locale.ROOT)
                val value = node.textContent ?: ""

                val badPercents = findUnsafePercents(value)
                if (badPercents.isNotEmpty()) {
                    errors += "$rel#$name: % غير مهرّب/غير صالح: " +
                        badPercents.joinToString { it.replace("\n", "\\n") }
                }

                if (formatted == "false") continue
                runCatching {
                    formatWithSampleArgs(value)
                }.onFailure { e ->
                    errors += "$rel#$name: تنسيق يرمي استثناء: ${e.message}"
                }
            }
        }

        if (errors.isNotEmpty()) {
            fail(
                "عُثر على ${errors.size} مشكلة صيَغ في strings.xml:\n" +
                    errors.joinToString("\n")
            )
        }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "gradlew.bat").exists() ||
                File(dir, "settings.gradle.kts").exists()
            ) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun findUnsafePercents(value: String): List<String> {
        val bad = mutableListOf<String>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '%') {
                i++
                continue
            }
            if (i + 1 < value.length && value[i + 1] == '%') {
                i += 2
                continue
            }
            if (isFormatSpecifierStart(value, i)) {
                i = skipFormatSpecifier(value, i)
                continue
            }
            val start = (i - 12).coerceAtLeast(0)
            val end = (i + 20).coerceAtMost(value.length)
            bad += value.substring(start, end)
            i++
        }
        return bad
    }

    private fun isFormatSpecifierStart(value: String, percentIndex: Int): Boolean {
        return skipFormatSpecifier(value, percentIndex) > percentIndex + 1
    }

    private fun skipFormatSpecifier(value: String, percentIndex: Int): Int {
        // Java/Android Formatter: %[argument_index$][flags][width][.precision][tT]conversion
        var i = percentIndex + 1
        if (i >= value.length) return percentIndex + 1

        // argument index
        var j = i
        while (j < value.length && value[j].isDigit()) j++
        if (j > i && j < value.length && value[j] == '$') {
            i = j + 1
        }
        // flags
        while (i < value.length && value[i] in "-#+ 0,(") i++
        // width
        while (i < value.length && value[i].isDigit()) i++
        // precision
        if (i < value.length && value[i] == '.') {
            i++
            while (i < value.length && value[i].isDigit()) i++
        }
        // time prefix
        if (i < value.length && (value[i] == 't' || value[i] == 'T')) {
            i++
            if (i < value.length) i++ // time conversion char
            return i
        }
        if (i < value.length && value[i] in "bBhHsScCdoxXeEfgGaAn%") {
            i++
            return i
        }
        return percentIndex + 1
    }

    private fun formatWithSampleArgs(value: String) {
        val specs = collectSpecs(value)
        if (specs.isEmpty()) return
        val maxPos = specs.mapNotNull { it.index }.maxOrNull()
        val argCount = maxPos ?: specs.size
        val args = arrayOfNulls<Any>(argCount)
        var sequential = 0
        for (spec in specs) {
            val idx = (spec.index ?: (++sequential)) - 1
            if (idx !in args.indices) continue
            if (args[idx] == null) args[idx] = sampleArgFor(spec.conversion)
        }
        for (i in args.indices) if (args[i] == null) args[i] = "1"
        String.format(Locale.US, value, *args.map { it ?: "1" }.toTypedArray())
    }

    private data class Spec(val index: Int?, val conversion: Char)

    private fun collectSpecs(value: String): List<Spec> {
        val specs = mutableListOf<Spec>()
        var i = 0
        while (i < value.length) {
            if (value[i] != '%') {
                i++; continue
            }
            if (i + 1 < value.length && value[i + 1] == '%') {
                i += 2; continue
            }
            val next = skipFormatSpecifier(value, i)
            if (next > i + 1) {
                val spec = value.substring(i, next)
                val conv = spec.last()
                if (conv != 'n' && conv != 'N' && conv != '%') {
                    val idx = Regex("""^%(\d+)\$""").find(spec)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    specs += Spec(idx, conv)
                }
                i = next
            } else i++
        }
        return specs
    }

    private fun sampleArgFor(conversion: Char): Any = when (conversion) {
        'd', 'o', 'x', 'X' -> 1
        'e', 'E', 'f', 'g', 'G', 'a', 'A' -> 1.5f
        'c', 'C' -> 'A'
        'b', 'B' -> true
        'h', 'H', 's', 'S' -> "test"
        't', 'T' -> java.util.Date(0)
        else -> "test"
    }
}
