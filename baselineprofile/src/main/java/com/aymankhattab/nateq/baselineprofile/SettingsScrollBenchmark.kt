package com.aymankhattab.nateq.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * قياس أداء فتح وسحب شاشة الإعدادات عبر Macrobenchmark:
 * - يقيس استقرار الإطارات والتقطيع أثناء السحب عبر [FrameTimingMetric].
 * - يقيس زمن تنفيذ [onViewCreated] بدقة عبر [TraceSectionMetric].
 */
@RunWith(AndroidJUnit4::class)
class SettingsScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage: String
        get() = InstrumentationRegistry.getArguments()
            .getString("targetAppId", "com.aymankhattab.nateq")

    /**
     * يفتح شاشة الإعدادات ويسحبها بـ FrameTimingMetric ويقيس زمن
     * onViewCreated بـ TraceSectionMetric.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun openAndScrollSettings() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                FrameTimingMetric(),
                TraceSectionMetric("VoiceSelectionFragment.onViewCreated")
            ),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            pressHome()
            startActivityAndWait()

            val scrollContainer = device.wait(
                Until.findObject(By.res(targetPackage, "sv_settings_scroll")),
                5_000L
            )
            if (scrollContainer != null) {
                scrollContainer.setGestureMargin(device.displayWidth / 5)
                scrollContainer.fling(Direction.DOWN)
                device.waitForIdle()
                scrollContainer.fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }
}
