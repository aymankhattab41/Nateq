package com.aymankhattab.nateq.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * مانع تكرار رسائل Toast وضابط معدل ظهورها (ع٣٢-1):
 * - يمنع الرسائل المتطابقة خلال نافذة زمنية (ثانيتين).
 * - يلغي أي Toast سابق قيد العرض قبل إظهار Toast جديد.
 * - يضمن عدم ظهور أكثر من Toast واحد في الثانية الواحدة.
 * - آمن للاستدعاء من أي خيط (Main أو خيط خلفية).
 */
object ToastDebouncer {

    private const val DEDUPE_WINDOW_MS = 2000L
    private const val MIN_INTERVAL_MS = 1000L

    private var activeToastRef: WeakReference<Toast>? = null
    private var lastMessage: String? = null
    private var lastMessageTimeMs: Long = 0L
    private var lastShowTimeMs: Long = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * يعرض رسالة [text] مع منع التكرار وإلغاء السابق وضبط المعدل.
     */
    fun show(
        context: Context,
        text: CharSequence,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val appCtx = context.applicationContext
            mainHandler.post { show(appCtx, text, duration) }
            return
        }

        val msgStr = text.toString().trim()
        if (msgStr.isEmpty()) return

        val now = SystemClock.uptimeMillis()

        // 1. إسقاط الرسائل المتطابقة خلال نافذة ثانيتين
        if (lastMessage != null &&
            msgStr == lastMessage &&
            (now - lastMessageTimeMs) < DEDUPE_WINDOW_MS
        ) {
            return
        }

        // 2. ضبط المعدل: لا أكثر من Toast واحد في الثانية
        if (lastShowTimeMs > 0L && (now - lastShowTimeMs) < MIN_INTERVAL_MS) {
            return
        }

        // 3. إلغاء أي Toast سابق قيد العرض
        activeToastRef?.get()?.cancel()
        activeToastRef = null

        // 4. إنشاء وإظهار الجديد
        val toast = Toast.makeText(
            context.applicationContext,
            text,
            duration
        )
        activeToastRef = WeakReference(toast)
        lastMessage = msgStr
        lastMessageTimeMs = now
        lastShowTimeMs = now
        toast.show()
    }

    /**
     * يعرض رسالة من الموارد [resId] مع منع التكرار.
     */
    fun show(
        context: Context,
        resId: Int,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        val text = runCatching {
            context.getString(resId)
        }.getOrNull() ?: return
        show(context, text, duration)
    }

    /**
     * تصفير الحالة (للاختبارات فقط).
     */
    fun resetForTesting() {
        activeToastRef?.get()?.cancel()
        activeToastRef = null
        lastMessage = null
        lastMessageTimeMs = 0L
        lastShowTimeMs = 0L
    }
}
