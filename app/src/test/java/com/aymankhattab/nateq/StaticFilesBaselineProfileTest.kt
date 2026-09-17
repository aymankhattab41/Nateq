package com.aymankhattab.nateq

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * حارس Baseline Profile (بند 6#6): ملف app/src/main/baseline-prof.txt يجب
 * أن يبقى يحوي قواعد المسارات الحرجة (النشاطات والفصيل والخدمات) بصيغة
 * L/HSPL سليمة — فإهمالُ إصلاح بعضها قد لا يُسقط البناء لكنه صامت: البناء
 * يدمج القواعد ويعود حجمُ الملف أصغر دون أن يلاحظ أحد. الوحدةُ تقرأ الملف
 * من نظام الملفات بمسار عمل Gradle (:app) تماماً كأخيه VoiceIdContractTest.
 */
class StaticFilesBaselineProfileTest {

    private val profile: File
        get() = File("src/main/baseline-prof.txt")

    @Test
    fun baselineProfile_presentAtExpectedPath() {
        assertEquals(true, profile.exists())
    }

    @Test
    fun baselineProfile_selfContainsCriticalRules() {
        val text = profile.readText()
        // مسارات مُسجّلة يدوياً — غياب أيّ منها يُفشل مستقبلاً فوراً عند
        // تعديل أسماء/عقود R8 ويُلفت لإعادة التحديث بدل اندماج صامت.
        listOf(
            "Lcom/aymankhattab/nateq/settings/SettingsActivity;",
            // سطر القاعدة الطويل يُقسَّم عن عمد: هو محتوى baseline-prof
            // المشروح في كود دخول الخدمة — تكتبه الأداةُ والخارقُ على السطر.
            // @formatter:off
            "HSPLcom/aymankhattab/nateq/core/audio/" +
                "engine/NateqTtsService;->onCreate()V",
            "Lcom/aymankhattab/nateq/core/data/SettingsRepository;"
        ).forEach { rule ->
            assertEquals("missing rule: $rule", true, text.contains(rule))
        }
    }

    @Test
    fun baselineProfile_noClassRulesWithFlags() {
        // أغربُ خللٍ شائعٍ تصطدم به الأداة: أسطر L<class>; بلا "PL" —
        // وجود PL/HP يرفعه محلّل ART فيفشل البناء. العقدُ: L فقط للفئات.
        val text = profile.readText()
        for (line in text.lines()) {
            if (line.startsWith("PL") || line.startsWith("HP")) {
                throw AssertionError("illegal class rule: $line")
            }
        }
    }
}