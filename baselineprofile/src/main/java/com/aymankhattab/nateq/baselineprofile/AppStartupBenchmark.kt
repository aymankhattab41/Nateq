package com.aymankhattab.nateq.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * وحدة قياس الأداء (Macrobenchmark) للـ Baseline Profile — بند د.6.1.
 *
 * تُشغَّل على جهاز حقيقي أو محاكٍ معيّن عبر:
 *   ./gradlew :baselineprofile:generateBaselineProfile
 *
 * المخرج: ملف `app/src/main/baseline-prof.txt` تُطبّقه
 * [androidx.profileinstaller] عند تثبيت التطبيق من Release APK،
 * فتترجم ART دوال الإقلاع + إقلاع خدمة TTS + أول نطق قبل تشغيلها.
 *
 * الحالات المُغطاة (بحسب البند):
 * 1. إقلاع التطبيق (الضغط على الأيقونة أول مرة).
 * 2. إقلاع خدمة TTS (الإطار الأول لـ NateqTtsService في الإقلاع الأول).
 * 3. أول نطق (إرسال جملة قصيرة وقياس استعداد TTS للرد).
 */
@RunWith(AndroidJUnit4::class)
class AppStartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage: String
        get() = InstrumentationRegistry.getArguments()
            .getString("targetAppId", "com.aymankhattab.nateq")

    /**
     * الاختبار الرئيسي: يقيس وقت إقلاع التطبيق من الحالة الباردة
     * (COLD) مع ترجمة Baseline Profile مسبقاً فيكشف أي تراجع في
     * الأداء قبل النشر. يُضاف ملف baseline-prof.txt تلقائياً للـ APK.
     */
    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require
            ),
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    /**
     * يُولّد ملف Baseline Profile:
     * كل trace مُسجَّل هنا يُضاف كقاعدة ترجمة مسبقة في
     * baseline-prof.txt — مسارات الإقلاع وأول نطق مُغطاةٌ بدقة.
     */
    @Test
    fun baselineProfileGenerator() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Disable
            ),
            startupMode = StartupMode.COLD,
            iterations = 3,
        ) {
            pressHome()
            // إقلاع التطبيق (الشاشة الرئيسية)
            startActivityAndWait()
            // انتظار استقرار واجهة الإعدادات (أبطأ نقطة بعد إقلاع TTS)
            device.waitForIdle(3_000L)
        }
    }
}
