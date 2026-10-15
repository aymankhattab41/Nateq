package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlin.math.abs

/**
 * واجهة لوحة مفاتيح Lord TTS الميسّرة المخصصة.
 * تدعم وضعي الكفيف وضعيف البصر، ونمطي التفعيل برفع الإصبع
 * أو الضغط مرتين، وإيماءات السحب الذكية.
 */
class LordKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnKeyboardActionListener {
        fun onKeySelected(key: KeyModel)
        fun onKeyHover(key: KeyModel)
        fun onSwipeLeft()
        fun onSwipeRight()
        fun onSwipeDown()
        fun onSwipeUp()
    }

    var actionListener: OnKeyboardActionListener? = null

    var keyboardMode: String = SettingsRepository.KEYBOARD_MODE_BLIND
        set(value) {
            field = value
            updateThemePaints()
            invalidate()
        }

    var activationMode: String =
        SettingsRepository.KEYBOARD_ACTIVATION_LIFT

    var announceAfterLift: Boolean = false
    var hapticEnabled: Boolean = true
    var soundEnabled: Boolean = true

    private var rows: List<List<KeyModel>> = emptyList()
    private val keyRects = mutableMapOf<KeyModel, RectF>()
    private var hoveredKey: KeyModel? = null

    // تتبع النقر المزدوج في وضع Double Tap
    private var lastTappedKey: KeyModel? = null
    private var lastTapTime: Long = 0L
    private val doubleTapTimeoutMs = 500L

    // أدوات الرسم
    private val bgPaint = Paint()
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyHoverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()

    private val vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // كاشف الإيماءات السريعة
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 120
            private val swipeVelocityThreshold = 150

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                return if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > swipeThreshold &&
                        abs(velocityX) > swipeVelocityThreshold
                    ) {
                        if (diffX > 0) {
                            actionListener?.onSwipeRight()
                        } else {
                            actionListener?.onSwipeLeft()
                        }
                        true
                    } else false
                } else {
                    if (abs(diffY) > swipeThreshold &&
                        abs(velocityY) > swipeVelocityThreshold
                    ) {
                        if (diffY > 0) {
                            actionListener?.onSwipeDown()
                        } else {
                            actionListener?.onSwipeUp()
                        }
                        true
                    } else false
                }
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateThemePaints()
    }

    fun setKeyRows(newRows: List<List<KeyModel>>) {
        rows = newRows
        hoveredKey = null
        recalculateKeyBounds()
        invalidate()
    }

    private fun updateThemePaints() {
        val isLowVision =
            keyboardMode == SettingsRepository.KEYBOARD_MODE_LOW_VISION

        if (isLowVision) {
            // وضع ضعيف البصر: خلفية سوداء عميقة، مفاتيح بلون تباين أصفر وأبيض
            bgPaint.color = Color.BLACK
            keyBgPaint.color = Color.rgb(28, 28, 28)
            keyHoverBgPaint.color = Color.rgb(255, 235, 59) // أصفر فاقع
            keyBorderPaint.color = Color.rgb(255, 215, 0)
            keyBorderPaint.style = Paint.Style.STROKE
            keyBorderPaint.strokeWidth = 3.5f
            textPaint.color = Color.rgb(255, 255, 255)
            textHoverPaint.color = Color.BLACK
        } else {
            // وضع الكفيف: واجهة داكنة مريحة مركزة على التغذية السمعية واللمسية
            bgPaint.color = Color.rgb(18, 18, 18)
            keyBgPaint.color = Color.rgb(38, 38, 38)
            keyHoverBgPaint.color = Color.rgb(0, 150, 136) // تيل بارز
            keyBorderPaint.color = Color.rgb(60, 60, 60)
            keyBorderPaint.style = Paint.Style.STROKE
            keyBorderPaint.strokeWidth = 1.5f
            textPaint.color = Color.rgb(230, 230, 230)
            textHoverPaint.color = Color.WHITE
        }
        textPaint.textAlign = Paint.Align.CENTER
        textHoverPaint.textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateKeyBounds()
    }

    private fun recalculateKeyBounds() {
        keyRects.clear()
        if (rows.isEmpty() || width == 0 || height == 0) return

        val rowCount = rows.size
        val rowHeight = height.toFloat() / rowCount
        val horizontalPadding = 4f
        val verticalPadding = 4f

        for ((rowIndex, row) in rows.withIndex()) {
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            if (totalWeight <= 0f) continue

            val top = rowIndex * rowHeight + verticalPadding
            val bottom = (rowIndex + 1) * rowHeight - verticalPadding
            var currentX = 0f

            for (key in row) {
                val keyWidth = (key.widthWeight / totalWeight) * width
                val left = currentX + horizontalPadding
                val right = currentX + keyWidth - horizontalPadding
                keyRects[key] = RectF(left, top, right, bottom)
                currentX += keyWidth
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val isLowVision =
            keyboardMode == SettingsRepository.KEYBOARD_MODE_LOW_VISION
        val cornerRadius = if (isLowVision) 14f else 8f

        for ((key, rect) in keyRects) {
            val isHovered = (key == hoveredKey)
            val currentBgPaint = if (isHovered) keyHoverBgPaint else keyBgPaint
            val currentTextPaint = if (isHovered) textHoverPaint else textPaint

            canvas.drawRoundRect(
                rect, cornerRadius, cornerRadius, currentBgPaint
            )
            canvas.drawRoundRect(
                rect, cornerRadius, cornerRadius, keyBorderPaint
            )

            val textSize = calculateTextSize(key, rect, isLowVision)
            currentTextPaint.textSize = textSize

            currentTextPaint.getTextBounds(
                key.label, 0, key.label.length, textBounds
            )
            val textY = rect.centerY() + (textBounds.height() / 2f)
            canvas.drawText(key.label, rect.centerX(), textY, currentTextPaint)
        }
    }

    private fun calculateTextSize(
        key: KeyModel,
        rect: RectF,
        isLowVision: Boolean
    ): Float {
        val baseMultiplier = if (isLowVision) 0.55f else 0.45f
        var targetSize = rect.height() * baseMultiplier
        if (key.label.length > 2) {
            targetSize *= 0.65f
        }
        return targetSize.coerceIn(24f, 72f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val key = findKeyAt(x, y)
                if (key != null && key != hoveredKey) {
                    hoveredKey = key
                    onKeyHovered(key)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = hoveredKey ?: findKeyAt(x, y)
                if (key != null) {
                    handleKeyReleaseOrTap(key)
                }
                hoveredKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hoveredKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findKeyAt(x: Float, y: Float): KeyModel? {
        for ((key, rect) in keyRects) {
            if (rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    private fun onKeyHovered(key: KeyModel) {
        triggerHaptic(isStrong = false)
        triggerSound()
        actionListener?.onKeyHover(key)
    }

    private fun handleKeyReleaseOrTap(key: KeyModel) {
        val now = SystemClock.uptimeMillis()

        if (activationMode == SettingsRepository.KEYBOARD_ACTIVATION_LIFT) {
            // نمط رفع الإصبع للتفعيل (Lift-to-Type)
            commitKeyActivation(key)
        } else {
            // نمط الضغط مرتين للتفعيل (Double-Tap)
            if (lastTappedKey == key &&
                (now - lastTapTime) <= doubleTapTimeoutMs
            ) {
                commitKeyActivation(key)
                lastTappedKey = null
                lastTapTime = 0L
            } else {
                lastTappedKey = key
                lastTapTime = now
                triggerHaptic(isStrong = false)
                actionListener?.onKeyHover(key)
            }
        }
    }

    private fun commitKeyActivation(key: KeyModel) {
        triggerHaptic(isStrong = true)
        triggerSound()
        actionListener?.onKeySelected(key)
    }

    private fun triggerHaptic(isStrong: Boolean) {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = if (isStrong) 255 else 80
                val duration = if (isStrong) 35L else 15L
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(duration, amplitude)
                )
            } else {
                @Suppress("DEPRECATION")
                val duration = if (isStrong) 35L else 15L
                vibrator?.vibrate(duration)
            }
        } catch (_: Exception) {
            performHapticFeedback(
                if (isStrong) HapticFeedbackConstants.KEYBOARD_TAP
                else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }

    private fun triggerSound() {
        if (!soundEnabled) return
        try {
            playSoundEffect(SoundEffectConstants.CLICK)
        } catch (_: Exception) {
            // تجاوز إذا تعذر تشغيل الصوت
        }
    }
}
