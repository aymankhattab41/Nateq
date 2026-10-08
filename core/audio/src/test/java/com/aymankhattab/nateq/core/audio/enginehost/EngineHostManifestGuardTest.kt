package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٢٩-5 — المانيفست.
 *
 * العقد: `EmbeddedEngineHostService` جالسٌ في عمليةٍ معزولة
 * `:engine_host` **ومُصدَّرٌ `false`**. فإن اختلّ واحدٌ منهما:
 * الأولى تعود المكتبات الأصلية إلى العملية الأمامية (انهيارها
 * يقتل التطبيق)، والثانية تفتح الواجهة AIDL أمام تطبيقات خارجية
 * تستطيع أمرَ النطق. يقرأ المانيفست المصدرَ المستندَ للبناء لا
 * ظلاً منه.
 */
class EngineHostManifestGuardTest {

    @Test
    fun hostService_isUnexportedAndInOwnProcess() {
        val element = serviceElement()
        assertTrue(
            "الخدمة يجب أن تكون في عملية معزولة :engine_host",
            element.contains("android:process=\":engine_host\"")
        )
        assertTrue(
            "الخدمة يجب ألّا تكون مُصدَّرة",
            element.contains("android:exported=\"false\"")
        )
        assertFalse(
            "لا يجوز قطعاً exported=true",
            element.contains("android:exported=\"true\"")
        )
    }

    @Test
    fun hostService_isDeclaredExactlyOnce() {
        assertEquals(
            "خدمة المضيف مُعلَنة مرّة واحدة فقط",
            1,
            serviceElements().size
        )
    }

    private fun serviceElements(): List<String> =
        readManifest()
            .split("<service")
            .filter { it.contains(ENGINE_HOST_SERVICE) }
            .map { it.substringBefore('>') }

    private fun serviceElement(): String {
        val elements = serviceElements()
        assertEquals(
            "لم يُعثر على خدمة المضيف في المانيفست",
            1,
            elements.size
        )
        return elements[0]
    }

    /**
     * مانيفست المصدر: مساران مقبولان حسب مجلد العمل (مجلد الوحدة
     * أو جذر المستودع).
     */
    private fun readManifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("core/audio/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error(
                "لم يُعثر على AndroidManifest.xml من " +
                    File(".").absolutePath
            )
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        const val ENGINE_HOST_SERVICE =
            "core.audio.enginehost.EmbeddedEngineHostService"
    }
}
