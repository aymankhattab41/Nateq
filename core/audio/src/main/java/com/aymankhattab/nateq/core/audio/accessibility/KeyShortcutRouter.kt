package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode

/**
 * موجّه اختصارات المفاتيح (ع٢٩-13).
 * يدعم:
 * - ضغط مطوّل صوت+ + صوت- : تبديل الطوارئ (موجود في NateqAccessibilityService)
 * - ضغطتان سريعتان لصوت+ : «اقرأ الشاشة» (FEATURE_READ_SCREEN_NOW)
 * - ضغطتان سريعتان لصوت- : «ماذا قلتَ للتو» (FEATURE_CLIPBOARD_READING)
 * - تخصيص كل اختصار من إعدادات الخدمة.
 * لا يستهلك مفاتيح الصوت (return false) إلا عند تحقق اختصار مفعّل فعلاً.
 */
class KeyShortcutRouter(
    private val service: AccessibilityService,
    private val settings: SettingsRepository,
    private val stateStore: A11yStateStore?
) {

    private var volUpPressCount = 0
    private var volDownPressCount = 0
    private var lastVolUpTime = 0L
    private var lastVolDownTime = 0L
    private val DOUBLE_PRESS_WINDOW_MS = 300L

    private val volUpRunnable = Runnable { volUpPressCount = 0 }
    private val volDownRunnable = Runnable { volDownPressCount = 0 }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val keyCode = event.keyCode
        val action = event.action

        // ع٢٩-16: في حالة الإيقاف المؤقت لا تعالج مفاتيح الصوت
        // إلا اختصار الاستئناف (ضغطتان لصوت-)
        val isPaused = stateStore?.isPaused() == true
        if (isPaused && (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            // في حالة الإيقاف: نسمح فقط بضغطتين لصوت- للاستئناف
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
                action == KeyEvent.ACTION_DOWN
            ) {
                val now = System.currentTimeMillis()
                if (now - lastVolDownTime <= DOUBLE_PRESS_WINDOW_MS) {
                    volDownPressCount++
                } else {
                    volDownPressCount = 1
                }
                lastVolDownTime = now
                handler.removeCallbacks(volDownRunnable)
                handler.postDelayed(volDownRunnable, DOUBLE_PRESS_WINDOW_MS)
                if (volDownPressCount == 2) {
                    return handleDoubleVolDown()
                }
            }
            // تجاهل باقي مفاتيح الصوت في حالة الإيقاف
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val now = System.currentTimeMillis()
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (now - lastVolUpTime <= DOUBLE_PRESS_WINDOW_MS) {
                        volUpPressCount++
                    } else {
                        volUpPressCount = 1
                    }
                    lastVolUpTime = now
                    handler.removeCallbacks(volUpRunnable)
                    handler.postDelayed(volUpRunnable, DOUBLE_PRESS_WINDOW_MS)
                    if (volUpPressCount == 2) {
                        return handleDoubleVolUp()
                    }
                } else {
                    if (now - lastVolDownTime <= DOUBLE_PRESS_WINDOW_MS) {
                        volDownPressCount++
                    } else {
                        volDownPressCount = 1
                    }
                    lastVolDownTime = now
                    handler.removeCallbacks(volDownRunnable)
                    handler.postDelayed(volDownRunnable, DOUBLE_PRESS_WINDOW_MS)
                    if (volDownPressCount == 2) {
                        return handleDoubleVolDown()
                    }
                }
            }
        }
        return false // لا نستهلك المفتاح افتراضياً
    }

    private fun handleDoubleVolUp(): Boolean {
        val context = service.applicationContext
        val repo = SettingsRepository.create(context)
        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_SCREEN_NOW,
                context = context,
                settings = repo,
                stateStore = stateStore
            )
        ) {
            readScreenNow()
            return true // تم استهلاك الاختصار
        }
        return false
    }

    private fun handleDoubleVolDown(): Boolean {
        val context = service.applicationContext
        val repo = SettingsRepository.create(context)
        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_CLIPBOARD_READING,
                context = context,
                settings = repo,
                stateStore = stateStore
            )
        ) {
            readClipboard()
            return true
        }
        return false
    }

    private fun readScreenNow() {
        // TODO: استدعاء قراءة الشاشة الحالية عبر AccessibilityBridge
        // AccessibilityBridge.focusSnapshot()?.readableText()?.let { text ->
        //     A11ySpeechCoordinator.speakAnnouncement(
        //         context = service,
        //         text = text,
        //         isInterruptAllowed = true
        //     )
        // }
    }

    private fun readClipboard() {
        // TODO: قراءة محتوى الحافظة
    }

    fun shutdown() {
        handler.removeCallbacks(volUpRunnable)
        handler.removeCallbacks(volDownRunnable)
    }
}