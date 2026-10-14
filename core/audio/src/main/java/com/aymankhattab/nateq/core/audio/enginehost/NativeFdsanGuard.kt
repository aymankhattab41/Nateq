package com.aymankhattab.nateq.core.audio.enginehost

import android.util.Log

/**
 * حارس fdsan: يعطّل مدقّق واصفات الملفات (fdsan) في عملية المحرك.
 *
 * libNuanceVocalizer.so يفتح تدفقات بيانات الصوت ثم يغلق واصفاً مملوكاً
 * لـ FILE* بـ close() خام (عبر CVocalizerEngine::vplatform_file_Close)،
 * وهو نمط لا يتوافق مع fdsan المفعّل لمن يستهدف API 29+، فيُجهض العملية
 * :host_voc بـ SIGABRT رغم نجاح تحميل الصوت.
 *
 * نستدعي android_fdsan_set_error_level(DISABLED) من مكتبة libnateqfdsan.so
 * المبنيّة محلياً عند بدء المضيف وقبل أي استخدام للمحرك.
 */
object NativeFdsanGuard {

    private const val TAG = "NativeFdsanGuard"

    @Volatile
    private var applied = false

    /**
     * يُنفَّذ مرة واحدة لكل عملية: تحميل المكتبة ثم تعطيل fdsan.
     * آمن للاستدعاء المتكرر ولو فشل (يُسجَّل التحذير ولا يُرمى).
     */
    fun ensureDisabled() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            try {
                System.loadLibrary("nateqfdsan")
                disableFdsan()
                applied = true
                Log.i(TAG, "fdsan disabled for engine host process")
            } catch (t: Throwable) {
                Log.w(TAG, "تعذّر تعطيل fdsan: ${t.message}")
            }
        }
    }

    private external fun disableFdsan()
}
