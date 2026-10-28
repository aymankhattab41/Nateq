package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale

/**
 * حارس لغة لوحة المفاتيح وCaps Lock (ع٥٦-أمر 10).
 * يعلن لغة الإدخال وحالة مفتاح الأحرف الكبيرة لتفادي الكتابة بلغة غير مقصودة.
 */
object KeyboardGuard {

    private const val MIN_ANNOUNCE_INTERVAL_MS = 2000L
    private var lastAnnounceTime = 0L
    private var lastSubtypeHash: Int = 0

    fun onInputStarted(context: Context, isArabic: Boolean) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_keyboard_guard")) return

        val lang = if (isArabic) {
            context.getString(R.string.keyboard_lang_ar)
        } else {
            context.getString(R.string.keyboard_lang_en)
        }
        A11ySpeechCoordinator.speakAnnouncement(context, lang)
    }

    fun onLanguageToggled(context: Context, isArabic: Boolean) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_keyboard_guard")) return

        val lang = if (isArabic) {
            context.getString(R.string.keyboard_lang_ar)
        } else {
            context.getString(R.string.keyboard_lang_en)
        }
        A11ySpeechCoordinator.speakAnnouncement(context, lang)
    }

    fun onCapsLockChanged(context: Context, isCaps: Boolean) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_keyboard_guard")) return

        val msg = if (isCaps) {
            context.getString(R.string.caps_lock_enabled)
        } else {
            context.getString(R.string.caps_lock_disabled)
        }
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
    }

    fun checkExternalKeyboardSubtype(context: Context) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_keyboard_guard")) return

        val now = SystemClock.uptimeMillis()
        if (lastAnnounceTime != 0L &&
            now - lastAnnounceTime < MIN_ANNOUNCE_INTERVAL_MS
        ) return

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        val subtype = imm.currentInputMethodSubtype ?: return

        val currentHash = subtype.hashCode()
        if (currentHash == lastSubtypeHash) return

        lastSubtypeHash = currentHash
        lastAnnounceTime = now

        val langTag = subtype.languageTag.ifEmpty { subtype.locale }
        if (langTag.isNotEmpty()) {
            val loc = Locale.forLanguageTag(langTag)
            val disp = loc.getDisplayLanguage(Locale.getDefault()).ifEmpty {
                loc.displayLanguage
            }
            if (disp.isNotEmpty()) {
                A11ySpeechCoordinator.speakAnnouncement(context, disp)
            }
        }
    }

    fun resetForTesting() {
        lastAnnounceTime = 0L
        lastSubtypeHash = 0
    }
}
