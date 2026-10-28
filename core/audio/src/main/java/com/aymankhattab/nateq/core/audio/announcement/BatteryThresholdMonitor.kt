package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.accessibility.ScrollCuePlayer
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Calendar

/**
 * مراقب عتبات البطارية المخصصة (الجولة 56 - إضافات 5 - أمر 1).
 * يراقب مستويات الشحن والتفريغ مع التخلف (Hysteresis) وساعات الهدوء.
 */
object BatteryThresholdMonitor {

    private const val DEBOUNCE_MS = 120_000L
    private const val HYSTERESIS_PERCENT = 2

    @Volatile
    private var lastPercent: Int? = null

    @Volatile
    private var lastCharging: Boolean? = null

    @Volatile
    private var lastTriggeredThreshold: Int? = null

    @Volatile
    private var lastTriggeredTime = 0L

    @VisibleForTesting
    internal var testCalendarProvider: (() -> Calendar)? = null

    /**
     * تصفير الحالة للاختبارات الآلية.
     */
    fun resetForTesting() {
        lastPercent = null
        lastCharging = null
        lastTriggeredThreshold = null
        lastTriggeredTime = 0L
        testCalendarProvider = null
    }

    /**
     * فحص ساعات الهدوء وفق الإعدادات القائمة.
     */
    fun isInQuietHours(
        settings: SettingsRepository,
        calendar: Calendar = testCalendarProvider?.invoke()
            ?: Calendar.getInstance()
    ): Boolean {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (!settings.isDayQuietEnabled(day)) return false

        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val quietStart = settings.getQuietStartForDay(day)
        val quietEnd = settings.getQuietEndForDay(day)

        return if (quietStart <= quietEnd) {
            currentHour in quietStart until quietEnd
        } else {
            currentHour >= quietStart || currentHour < quietEnd
        }
    }

    /**
     * استخراج قائمة العتبات مع حد أقصى 6 عتبات.
     */
    fun parseThresholds(csv: String?, defaultList: List<Int>): List<Int> {
        if (csv.isNullOrBlank()) return defaultList
        val parsed = csv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..99 }
            .distinct()
            .take(6)
        return if (parsed.isEmpty()) defaultList else parsed
    }

    /**
     * معالجة حدث تغير البطارية.
     */
    fun onBatteryChanged(
        context: Context,
        intent: Intent,
        settings: SettingsRepository
    ): Boolean {
        if (!settings.isExtraEnabled("extra_battery_thresholds")) {
            return false
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false

        val currentPercent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val triggered = evaluateCrossing(
            currentPercent = currentPercent,
            isCharging = isCharging,
            settings = settings
        )

        if (triggered != null) {
            val now = System.currentTimeMillis()
            if (now - lastTriggeredTime < DEBOUNCE_MS &&
                lastTriggeredThreshold == triggered
            ) {
                return false
            }
            lastTriggeredThreshold = triggered
            lastTriggeredTime = now

            if (isInQuietHours(settings)) {
                return false
            }

            // تشغيل نغمة العتبة
            ScrollCuePlayer.playTone(4, 0.6f)

            // نطق اختياري
            val shouldSpeak = settings.getExtraBoolean(
                "extra_battery_threshold_speak",
                true
            )
            if (shouldSpeak) {
                val text = context.getString(
                    R.string.battery_threshold_level,
                    currentPercent
                )
                A11ySpeechCoordinator.speakAnnouncement(
                    context = context,
                    text = text,
                    isInterruptAllowed = true
                )
            }
            return true
        }

        return false
    }

    /**
     * فحص عبور العتبة مع تطبيق التخلف (±2%).
     */
    internal fun evaluateCrossing(
        currentPercent: Int,
        isCharging: Boolean,
        settings: SettingsRepository
    ): Int? {
        val prevPercent = lastPercent
        val prevCharging = lastCharging

        lastPercent = currentPercent
        lastCharging = isCharging

        if (prevPercent == null) {
            return null
        }

        val chargingCsv = settings.getExtraString(
            "extra_battery_charging_thresholds",
            "80"
        )
        val dischargingCsv = settings.getExtraString(
            "extra_battery_discharging_thresholds",
            "15"
        )

        val chargingThresholds = parseThresholds(chargingCsv, listOf(80))
        val dischargingThresholds = parseThresholds(
            dischargingCsv,
            listOf(15)
        )

        if (isCharging) {
            // أثناء الشحن: فحص العبور صعوداً
            for (threshold in chargingThresholds) {
                if (prevPercent < threshold && currentPercent >= threshold) {
                    val lastTh = lastTriggeredThreshold
                    if (lastTh == threshold &&
                        currentPercent < threshold + HYSTERESIS_PERCENT
                    ) {
                        continue
                    }
                    return threshold
                }
            }
        } else {
            // أثناء التفريغ: فحص العبور هبوطاً
            for (threshold in dischargingThresholds) {
                if (prevPercent > threshold && currentPercent <= threshold) {
                    val lastTh = lastTriggeredThreshold
                    if (lastTh == threshold &&
                        currentPercent > threshold - HYSTERESIS_PERCENT
                    ) {
                        continue
                    }
                    return threshold
                }
            }
        }

        // إعادة ضبط التخلف إذا ابتعد المستوى
        val lastTh = lastTriggeredThreshold
        if (lastTh != null) {
            if (isCharging && currentPercent < lastTh - HYSTERESIS_PERCENT) {
                lastTriggeredThreshold = null
            } else if (!isCharging &&
                currentPercent > lastTh + HYSTERESIS_PERCENT
            ) {
                lastTriggeredThreshold = null
            }
        }

        return null
    }
}
