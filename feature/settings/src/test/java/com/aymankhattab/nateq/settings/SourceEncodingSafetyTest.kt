package com.aymankhattab.nateq.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبار سلامة ترميز نصوص الكود البرمجي باللغة العربية
 * لمنع تسرب محارف Mojibake مشوهة ناتجة عن فك ترميز خاطئ.
 */
class SourceEncodingSafetyTest {

    @Test
    fun sourceFiles_containZeroMojibakeCorruptedCharacters() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".")
        val ktFiles = projectRoot.walkTopDown().filter { file ->
            file.isFile &&
                file.extension.equals("kt", ignoreCase = true) &&
                !file.path.contains(".gradle") &&
                !file.path.contains("build") &&
                !file.path.contains(".git")
        }.toList()

        assertTrue(
            "يجب العثور على ملفات Kotlin لفحص سلامة الترميز",
            ktFiles.isNotEmpty()
        )

        val corruptedList = mutableListOf<String>()
        val badChars = setOf('\u00D8', '\u00D9', '\u00C3')

        for (file in ktFiles) {
            val text = file.readText(Charsets.UTF_8)
            val found = badChars.filter { it in text }
            if (found.isNotEmpty()) {
                corruptedList.add(
                    "${file.name} يحتوي على محارف مشوهة: $found"
                )
            }
        }

        assertTrue(
            "تم اكتشاف محارف تشويه ترميز في الملفات:\n" +
                corruptedList.joinToString("\n"),
            corruptedList.isEmpty()
        )
    }
}
