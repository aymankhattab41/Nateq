package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبار جسر القراءة عبر العمليات: يكتب عبر SharedPreferences الفعلية ثم
 * يقرأ ملف القرص عبر [MultiProcessPrefsBridge] ويطابق القيم (بما فيها
 * مجموعات النصوص string-set) ويغطي حارس تغيّر التوقيت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiProcessPrefsBridgeTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun parse_readsRealPrefsFileValues() {
        writeSamplePrefs()

        val bridge = MultiProcessPrefsBridge(context)
        val parsed = bridge.parse("bridge_test_settings")

        assertEquals(true, parsed["a_bool"])
        assertEquals(42, parsed["an_int"])
        assertEquals("hello", parsed["a_string"])
        assertEquals(1.5f, parsed["a_float"])
        assertEquals(1_234_567_890L, parsed["a_long"])
        @Suppress("UNCHECKED_CAST")
        val set = parsed["a_set"] as? Set<String>
        assertEquals(setOf("x", "y"), set)
    }

    @Test
    fun lastModified_tracksFileChanges() {
        val bridge = MultiProcessPrefsBridge(context)
        val before = bridge.lastModified("bridge_touch_test")
        writeTouchPrefs()
        // الكتابة الفعلية ترفع زمن التعديل فوق الصفر (ملفٌ جديد).
        assertTrue(bridge.lastModified("bridge_touch_test") > before)
        // بعد القراءة لا يتغير التوقيت ما لم يُكتب الملف من جديد.
        val seen = bridge.lastModified("bridge_touch_test")
        assertFalse(bridge.isChanged("bridge_touch_test", seen))
    }

    @Test
    fun parse_readsRawStringSetTag() {
        // **بند 5.2:** AOSP يكتب مجموعات النصوص بوسم <string-set> لا <set>
        // (إزاحةً من SharedPreferences الفعلية على الجهاز) — كان الجسر
        // يهضم <set> فقط ويُسقط <string-set> صامتاً فتُفقد قوائم البطارية
        // وفحوصات الجهاز في عملية :tts. نكتب الملف يدوياً بالوسم الفعلي
        // الذي ينتجه النظام ونتأكد من قراءته.
        val name = "bridge_stringset_test"
        val file = java.io.File(
            context.applicationInfo.dataDir,
            "shared_prefs/$name.xml"
        )
        file.parentFile?.mkdirs()
        file.writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <string-set name=\"battery_announcement_levels\">\n" +
                "        <string>20</string>\n" +
                "        <string>50</string>\n" +
                "    </string-set>\n" +
                "    <string-set name=\"device_health_items\">\n" +
                "        <string>battery</string>\n" +
                "        <string>storage</string>\n" +
                "    </string-set>\n" +
                "    <int name=\"an_int\" value=\"42\" />\n" +
                "</map>"
        )
        try {
            val bridge = MultiProcessPrefsBridge(context)
            val parsed = bridge.parse(name)
            @Suppress("UNCHECKED_CAST")
            val levels = parsed["battery_announcement_levels"] as? Set<String>
            assertEquals(setOf("20", "50"), levels)
            @Suppress("UNCHECKED_CAST")
            val items = parsed["device_health_items"] as? Set<String>
            assertEquals(setOf("battery", "storage"), items)
            assertEquals(42, parsed["an_int"])
        } finally {
            file.delete()
        }
    }

    private fun writeSamplePrefs() {
        context.getSharedPreferences("bridge_test_settings", 0).edit()
            .putBoolean("a_bool", true)
            .putInt("an_int", 42)
            .putString("a_string", "hello")
            .putFloat("a_float", 1.5f)
            .putLong("a_long", 1_234_567_890L)
            .putStringSet("a_set", setOf("x", "y"))
            .commit()
    }

    private fun writeTouchPrefs() {
        context.getSharedPreferences("bridge_touch_test", 0).edit()
            .putInt("v", 7)
            .commit()
    }
}