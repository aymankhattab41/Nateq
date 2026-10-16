package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * قائمة الإجراءات السريعة كنافذة تراكب (ع٤١-ب2).
 *
 * - تُعرض فوق التطبيقات عبر TYPE_ACCESSIBILITY_OVERLAY من سياق الخدمة.
 * - كل زر فعلٌ منفَّذ، وله contentDescription صريح لقارئ الشاشة.
 * - تُغلق بزر «إغلاق» أو عند تنفيذ فعل.
 *
 * لا تُبنى من خيط خلفي: كل استدعاء يجري على الخيط الرئيسي (كأحداث المفاتيح).
 */
object A11yQuickMenu {

    private var overlayView: View? = null

    fun isShowing(): Boolean = overlayView != null

    /** يبدّل ظهور القائمة: يعرضها إن كانت مخفية ويغلقها إن كانت ظاهرة. */
    fun toggle(host: Context, onAction: (ShortcutAction) -> Unit) {
        if (overlayView != null) {
            dismiss(host)
            return
        }
        show(host, onAction)
    }

    fun dismiss(host: Context) {
        val view = overlayView ?: return
        overlayView = null
        runCatching { windowManager(host).removeView(view) }
    }

    private fun show(host: Context, onAction: (ShortcutAction) -> Unit) {
        val density = host.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND_COLOR)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    dismiss(host)
                    true
                } else {
                    false
                }
            }
        }

        container.addView(TextView(host).apply {
            text = TITLE
            contentDescription = TITLE
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        for (action in MENU_ACTIONS) {
            val label = labelFor(action)
            container.addView(Button(host).apply {
                text = label
                contentDescription = label
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                layoutParams = childParams(dp(4))
                setOnClickListener {
                    dismiss(host)
                    onAction(action)
                }
            })
        }

        container.addView(Button(host).apply {
            text = CLOSE_LABEL
            contentDescription = CLOSE_LABEL
            isAllCaps = false
            minHeight = dp(48)
            layoutParams = childParams(dp(12))
            setOnClickListener { dismiss(host) }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        runCatching { windowManager(host).addView(container, params) }
            .onSuccess {
                overlayView = container
                container.requestFocus()
            }
    }

    private fun childParams(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun windowManager(host: Context): WindowManager =
        host.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // minSdk=24 ⇒ TYPE_ACCESSIBILITY_OVERLAY متاح دائماً.
    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    internal fun labelFor(action: ShortcutAction): String = when (action) {
        ShortcutAction.READ_SCREEN -> "اقرأ الشاشة"
        ShortcutAction.READ_CLIPBOARD -> "اقرأ الحافظة"
        ShortcutAction.REPEAT_LAST -> "أعِد آخر رسالة"
        ShortcutAction.SPELL_LAST -> "تهجَّ آخر رسالة"
        ShortcutAction.WHERE_AM_I -> "أين أنا؟"
        ShortcutAction.TOGGLE_PAUSE -> "إيقاف/تشغيل الخدمة"
        ShortcutAction.TOGGLE_CONTINUOUS -> "القراءة المستمرة"
        ShortcutAction.OCR_FOCUS -> "اقرأ الصورة"
        ShortcutAction.READ_FULL_URL -> "اقرأ الرابط كاملاً"
        ShortcutAction.REPEAT_PREVIOUS -> "اقرأ ما قبله"
        ShortcutAction.SPEECH_HISTORY -> "اقرأ السجل"
        ShortcutAction.SHOW_QUICK_MENU -> "القائمة السريعة"
        ShortcutAction.EXPAND_READ_MORE -> "قراءة المزيد"
        ShortcutAction.NEXT_ENGINE -> "المحرك التالي"
        ShortcutAction.PREVIOUS_ENGINE -> "المحرك السابق"
        ShortcutAction.NONE -> "بلا"
    }

    private const val TITLE = "الإجراءات السريعة"
    private const val CLOSE_LABEL = "إغلاق"
    private const val BACKGROUND_COLOR = 0xE6000000.toInt()

    private val MENU_ACTIONS = listOf(
        ShortcutAction.READ_SCREEN,
        ShortcutAction.READ_CLIPBOARD,
        ShortcutAction.REPEAT_LAST,
        ShortcutAction.SPELL_LAST,
        ShortcutAction.WHERE_AM_I,
        ShortcutAction.TOGGLE_PAUSE,
        ShortcutAction.TOGGLE_CONTINUOUS,
        ShortcutAction.OCR_FOCUS,
        ShortcutAction.READ_FULL_URL,
        ShortcutAction.EXPAND_READ_MORE,
        ShortcutAction.NEXT_ENGINE,
        ShortcutAction.PREVIOUS_ENGINE
    )

    /** الأفعال المعروضة في القائمة (للاكتشاف والاختبار). */
    internal fun menuActions(): List<ShortcutAction> = MENU_ACTIONS
}
