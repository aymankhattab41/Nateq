package com.aymankhattab.nateq

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aymankhattab.nateq.settings.SettingsActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * أول اختبار مدمج (بند 6#7) على الأجهزة/المحاكي: قاعدة ActivityScenarioRule
 * تطلق نشاط الإعدادات الحقيقي وتسهر على وصوله لحالة RESUMED — إن تعذّر
 * الإقلاع (تحطم Hilt، خطأ تخطيط...) تُفشَل القاعدةُ الاختبارَ تلقائياً قبل
 * أي إغراء. جسدُ الاختبار صامت لأن قاعدة الإطلاق تتحقق فعلياً؛ النجاح هنا
 * يعني أن مسار القشرة الرسومية الحقيقيَ يقلع على الجِهاز.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityAndroidTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<SettingsActivity> =
        ActivityScenarioRule(SettingsActivity::class.java)

    @Test
    fun settingsActivity_launchesToResumed() {
        // لا جسم: قاعدة ActivityScenarioRule أطلقت النشاط وانتظرت حالة
        // RESUMED، أيّ فشل إقلاع يجعل الاختبار يفشل هنا تلقائياً.
    }
}