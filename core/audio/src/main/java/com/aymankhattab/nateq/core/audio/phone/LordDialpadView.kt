package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.AccessibilityUtils

/**
 * واجهة لوحة اتصال Lord TTS الميسّرة لذوي الإعاقة البصرية.
 * تدعم استكشاف اللمس، الرفع للتفعيل، الضغط مرتين، ووضعي الكفيف وضعيف البصر.
 */
@Suppress("DEPRECATION")
class LordDialpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnDialpadActionListener {
        fun onKeySelected(key: DialpadKey)
        fun onKeyHover(key: DialpadKey)
    }

    var actionListener: OnDialpadActionListener? = null

    private val settingsRepo = SettingsRepository(context)

    var phoneMode: String = SettingsRepository.PHONE_MODE_BLIND
        set(value) {
            field = value
            updateThemePaints()
            invalidate()
        }

    var activationMode: String =
        SettingsRepository.PHONE_ACTIVATION_LIFT

    var announceAfterLift: Boolean = false
    var hapticEnabled: Boolean = true
    var soundEnabled: Boolean = true

    private val rows: List<List<DialpadKey>> =
        DialpadLayoutHelper.getStandardDialpadKeys()
    private val flatKeys = mutableListOf<DialpadKey>()
    private val keyRects = mutableMapOf<DialpadKey, RectF>()
    private var hoveredKey: DialpadKey? = null

    // تتبع حالة استكشاف اللمس ومنع التفعيل المبكر
    private var isExploring: Boolean = false
    private var hoverStartTime: Long = 0L
    private var lastCommitTime: Long = -1000L
    private val minHoverDurationMs: Long = 40L
    private val duplicateCommitCooldownMs: Long = 250L

    // تتبع النقر المزدوج
    private var lastTappedKey: DialpadKey? = null
    private var lastTapTime: Long = 0L
    private val doubleTapTimeoutMs = 500L

    // تتبع الضغط المطول على الصفر لكتابة +
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (hoveredKey?.digit == '0') {
            longPressTriggered = true
            val plusKey = DialpadKey(
                digit = '+',
                label = "+",
                spokenLabel = "زائد",
                subText = "",
                dtmfTone = ToneGenerator.TONE_DTMF_0
            )
            triggerHaptic(isStrong = true)
            actionListener?.onKeySelected(plusKey)
        }
    }

    // نغمات الهاتف DTMF
    private var toneGenerator: ToneGenerator? = null

    // أدوات الرسم
    private val bgPaint = Paint()
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyHoverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()

    private val vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val touchHelper = DialpadTouchHelper(this)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setAccessibilityDelegate(this, touchHelper)
        for (r in rows) {
            flatKeys.addAll(r)
        }
        initToneGenerator()
        updateThemePaints()
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        } catch (_: Exception) {
            toneGenerator = null
        }
    }

    private fun updateThemePaints() {
        val isLowVision =
            phoneMode == SettingsRepository.PHONE_MODE_LOW_VISION

        if (isLowVision) {
            // وضع ضعيف البصر: أسود عميق، حدود ذهبية عريضة، نص أصفر فاقع
            bgPaint.color = Color.BLACK
            keyBgPaint.color = Color.rgb(24, 24, 24)
            keyHoverBgPaint.color = Color.rgb(255, 235, 59)
            keyBorderPaint.color = Color.rgb(255, 215, 0)
            keyBorderPaint.style = Paint.Style.STROKE
            keyBorderPaint.strokeWidth = 4.0f
            textPaint.color = Color.rgb(255, 255, 255)
            textHoverPaint.color = Color.BLACK
            subTextPaint.color = Color.rgb(255, 215, 0)
        } else {
            // وضع الكفيف: تركيز سمعي ولمسي عالي
            bgPaint.color = Color.rgb(18, 18, 18)
            keyBgPaint.color = Color.rgb(34, 34, 34)
            keyHoverBgPaint.color = Color.rgb(0, 150, 136)
            keyBorderPaint.color = Color.rgb(65, 65, 65)
            keyBorderPaint.style = Paint.Style.STROKE
            keyBorderPaint.strokeWidth = 2.0f
            textPaint.color = Color.rgb(240, 240, 240)
            textHoverPaint.color = Color.WHITE
            subTextPaint.color = Color.rgb(180, 180, 180)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textHoverPaint.textAlign = Paint.Align.CENTER
        subTextPaint.textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateKeyBounds()
        touchHelper.invalidateRoot()
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        super.onLayout(changed, left, top, right, bottom)
        recalculateKeyBounds()
        touchHelper.invalidateRoot()
    }

    private fun recalculateKeyBounds() {
        val totalWidth = width.toFloat()
        val totalHeight = height.toFloat()
        if (totalWidth <= 0f || totalHeight <= 0f) return

        keyRects.clear()
        val numRows = rows.size
        val numCols = 3
        val colWidth = totalWidth / numCols
        val rowHeight = totalHeight / numRows

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val top = rowIndex * rowHeight
            val bottom = top + rowHeight

            for (colIndex in row.indices) {
                val key = row[colIndex]
                val left = colIndex * colWidth
                val right = left + colWidth
                keyRects[key] = RectF(left, top, right, bottom)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val isLowVision =
            phoneMode == SettingsRepository.PHONE_MODE_LOW_VISION
        val cornerRadius = if (isLowVision) 18f else 14f
        val keyDrawRect = RectF()

        for ((key, rect) in keyRects) {
            val isHovered = (key == hoveredKey)
            val currentBgPaint = if (isHovered) keyHoverBgPaint else keyBgPaint
            val currentTextPaint = if (isHovered) textHoverPaint else textPaint

            keyDrawRect.set(
                rect.left + 6f,
                rect.top + 6f,
                rect.right - 6f,
                rect.bottom - 6f
            )

            canvas.drawRoundRect(
                keyDrawRect, cornerRadius, cornerRadius, currentBgPaint
            )
            canvas.drawRoundRect(
                keyDrawRect, cornerRadius, cornerRadius, keyBorderPaint
            )

            val mainTextSize = keyDrawRect.height() * 0.42f
            currentTextPaint.textSize = mainTextSize.coerceIn(28f, 76f)
            currentTextPaint.getTextBounds(
                key.label, 0, key.label.length, textBounds
            )

            val textY = if (key.subText.isNotEmpty()) {
                rect.centerY() + (textBounds.height() * 0.1f)
            } else {
                rect.centerY() + (textBounds.height() / 2f)
            }
            canvas.drawText(key.label, rect.centerX(), textY, currentTextPaint)

            if (key.subText.isNotEmpty()) {
                val subSize = (mainTextSize * 0.35f).coerceIn(16f, 32f)
                subTextPaint.textSize = subSize
                val subY = rect.bottom - (keyDrawRect.height() * 0.15f)
                canvas.drawText(key.subText, rect.centerX(), subY, subTextPaint)
            }
        }
    }

    public override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = touchHelper.dispatchHoverEvent(event)
        val eventTime = if (event.eventTime > 0L) event.eventTime
        else SystemClock.uptimeMillis()
        val key = findKeyAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                if (key != null) {
                    isExploring = true
                    hoverStartTime = eventTime
                    hoveredKey = key
                    playTone(key.dtmfTone)
                    triggerHaptic(isStrong = false)
                    actionListener?.onKeyHover(key)
                    invalidate()
                }
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (key != null && key != hoveredKey) {
                    isExploring = true
                    hoverStartTime = eventTime
                    hoveredKey = key
                    playTone(key.dtmfTone)
                    triggerHaptic(isStrong = false)
                    actionListener?.onKeyHover(key)
                    invalidate()
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (isExploring && hoveredKey != null &&
                    activationMode ==
                        SettingsRepository.PHONE_ACTIVATION_LIFT &&
                    (eventTime - hoverStartTime) >= minHoverDurationMs
                ) {
                    val keyToCommit = hoveredKey!!
                    commitKey(keyToCommit, eventTime)
                }
                isExploring = false
                hoveredKey = null
                stopTone()
                invalidate()
            }
        }
        return handled || super.dispatchHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isScreenReader = AccessibilityUtils.isScreenReaderEnabled(context)
        // عند تشغيل قارئ الشاشة، لا نعترض إيماءات التمرير المتعددة
        if (isScreenReader && event.pointerCount > 1) {
            return false
        }

        val x = event.x
        val y = event.y
        val eventTime = if (event.eventTime > 0L) event.eventTime
        else SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                val key = findKeyAt(x, y)
                if (key != null) {
                    hoveredKey = key
                    onKeyHovered(key)
                    if (key.digit == '0') {
                        mainHandler.postDelayed(longPressRunnable, 700L)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val key = findKeyAt(x, y)
                if (key != null && key != hoveredKey) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressTriggered = false
                    hoveredKey = key
                    onKeyHovered(key)
                    if (key.digit == '0') {
                        mainHandler.postDelayed(longPressRunnable, 700L)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                stopTone()
                val key = hoveredKey ?: findKeyAt(x, y)
                if (key != null && !longPressTriggered) {
                    handleKeySelection(key, eventTime)
                }
                hoveredKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                stopTone()
                hoveredKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeySelection(
        key: DialpadKey,
        eventTime: Long = SystemClock.uptimeMillis()
    ) {
        if (activationMode == SettingsRepository.PHONE_ACTIVATION_LIFT) {
            commitKey(key, eventTime)
        } else {
            // نمط الضغط مرتين
            val now = if (eventTime > 0L) eventTime
            else SystemClock.uptimeMillis()
            if (lastTappedKey == key &&
                (now - lastTapTime) <= doubleTapTimeoutMs
            ) {
                commitKey(key, now)
                lastTappedKey = null
                lastTapTime = 0L
            } else {
                lastTappedKey = key
                lastTapTime = now
                actionListener?.onKeyHover(key)
            }
        }
    }

    private fun onKeyHovered(key: DialpadKey) {
        playTone(key.dtmfTone)
        triggerHaptic(isStrong = false)
        actionListener?.onKeyHover(key)
    }

    fun commitKey(
        key: DialpadKey,
        eventTime: Long = SystemClock.uptimeMillis()
    ) {
        val now = if (eventTime > 0L) eventTime else SystemClock.uptimeMillis()
        if (now - lastCommitTime < duplicateCommitCooldownMs) {
            return
        }
        lastCommitTime = now
        triggerHaptic(isStrong = true)
        actionListener?.onKeySelected(key)
    }

    private fun findKeyAt(x: Float, y: Float): DialpadKey? {
        for ((key, rect) in keyRects) {
            if (rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    private fun playTone(tone: Int) {
        if (!soundEnabled) return
        try {
            toneGenerator?.startTone(tone, 120)
        } catch (_: Exception) {}
    }

    private fun stopTone() {
        try {
            toneGenerator?.stopTone()
        } catch (_: Exception) {}
    }

    private fun triggerHaptic(isStrong: Boolean) {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isStrong) {
                    VibrationEffect.createOneShot(
                        40L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                } else {
                    VibrationEffect.createOneShot(18L, 90)
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isStrong) 40L else 18L)
            }
        } catch (_: Exception) {
            performHapticFeedback(
                if (isStrong) {
                    HapticFeedbackConstants.KEYBOARD_TAP
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(longPressRunnable)
        toneGenerator?.release()
        toneGenerator = null
    }

    private inner class DialpadTouchHelper(host: View) :
        ExploreByTouchHelper(host) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            for ((index, key) in flatKeys.withIndex()) {
                val rect = keyRects[key]
                if (rect != null && rect.contains(x, y)) {
                    return index
                }
            }
            return HOST_ID
        }

        override fun getVisibleVirtualViews(
            virtualViewIds: MutableList<Int>
        ) {
            for (i in flatKeys.indices) {
                virtualViewIds.add(i)
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            if (virtualViewId !in flatKeys.indices) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            val key = flatKeys[virtualViewId]
            val rectF = keyRects[key] ?: RectF()
            val rect = Rect(
                rectF.left.toInt(),
                rectF.top.toInt(),
                rectF.right.toInt(),
                rectF.bottom.toInt()
            )

            val desc = if (key.subText.isNotEmpty()) {
                "${key.spokenLabel}، ${key.subText}"
            } else {
                key.spokenLabel
            }

            node.contentDescription = desc
            node.text = key.label
            node.className = "android.widget.Button"
            node.isClickable = true
            node.isFocusable = true
            node.addAction(AccessibilityActionCompat.ACTION_CLICK)
            node.setBoundsInParent(rect)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                if (virtualViewId in flatKeys.indices) {
                    val key = flatKeys[virtualViewId]
                    commitKey(key)
                    return true
                }
            }
            return false
        }
    }
}
