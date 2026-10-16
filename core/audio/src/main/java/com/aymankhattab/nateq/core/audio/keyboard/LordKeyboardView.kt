package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.AccessibilityUtils
import kotlin.math.abs

/**
 * واجهة لوحة مفاتيح Lord TTS الميسّرة المخصصة.
 * تدعم الاستكشاف باللمس الكامل مع قارئات الشاشة (TalkBack)،
 * والرفع للتفعيل، والضغط مرتين، ووضعي الكفيف وضعيف البصر.
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

    private val settingsRepo = SettingsRepository(context)

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
    private val flatKeys = mutableListOf<KeyModel>()
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

    // ميسّر إمكانية الوصول لقارئ الشاشة TalkBack
    private val touchHelper = KeyboardTouchHelper(this)

    // تتبع السحب اليدوي بإصبع واحد فقط عندما لا يكون قارئ الشاشة نشطاً
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private val swipeMinDistance = 140f
    private val swipeMaxDuration = 400L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setAccessibilityDelegate(this, touchHelper)
        updateThemePaints()
    }

    fun setKeyRows(newRows: List<List<KeyModel>>) {
        rows = newRows
        flatKeys.clear()
        for (r in rows) {
            flatKeys.addAll(r)
        }
        hoveredKey = null
        recalculateKeyBounds()
        touchHelper.invalidateRoot()
        invalidate()
    }

    private fun updateThemePaints() {
        val isLowVision =
            keyboardMode == SettingsRepository.KEYBOARD_MODE_LOW_VISION

        if (isLowVision) {
            // وضع ضعيف البصر: خلفية سوداء عميقة، تباين أصفر وأبيض عريض
            bgPaint.color = Color.BLACK
            keyBgPaint.color = Color.rgb(28, 28, 28)
            keyHoverBgPaint.color = Color.rgb(255, 235, 59)
            keyBorderPaint.color = Color.rgb(255, 215, 0)
            keyBorderPaint.style = Paint.Style.STROKE
            keyBorderPaint.strokeWidth = 3.5f
            textPaint.color = Color.rgb(255, 255, 255)
            textHoverPaint.color = Color.BLACK
        } else {
            // وضع الكفيف: واجهة داكنة مريحة مركزة على التغذية السمعية
            bgPaint.color = Color.rgb(18, 18, 18)
            keyBgPaint.color = Color.rgb(38, 38, 38)
            keyHoverBgPaint.color = Color.rgb(0, 150, 136)
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
        touchHelper.invalidateRoot()
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

    /**
     * تمرير أحداث التحويم (Hover Events) لمساعد إمكانية الوصول
     * لتمكين استكشاف اللمس (Touch Exploration) الفعلي في TalkBack.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = touchHelper.dispatchHoverEvent(event)

        val isScreenReader =
            AccessibilityUtils.isScreenReaderEnabled(context)
        if (isScreenReader) {
            val key = findKeyAt(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (key != null && key != hoveredKey) {
                        hoveredKey = key
                        triggerHaptic(isStrong = false)
                        triggerSound()
                        invalidate()
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // في نمط الرفع للتفعيل أثناء استخدام قارئ الشاشة
                    if (activationMode ==
                        SettingsRepository.KEYBOARD_ACTIVATION_LIFT
                    ) {
                        val keyToCommit = hoveredKey ?: key
                        if (keyToCommit != null) {
                            commitKeyActivation(keyToCommit)
                        }
                    }
                    hoveredKey = null
                    invalidate()
                }
            }
        }

        return handled || super.dispatchHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isScreenReader =
            AccessibilityUtils.isScreenReaderEnabled(context)

        // إذا كان قارئ الشاشة نشطاً، نتجنب اعتراض لمسات أندرويد المتعددة
        if (isScreenReader && event.pointerCount > 1) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                touchDownTime = SystemClock.uptimeMillis()

                val key = findKeyAt(x, y)
                if (key != null) {
                    hoveredKey = key
                    onKeyHovered(key)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val key = findKeyAt(x, y)
                if (key != null && key != hoveredKey) {
                    hoveredKey = key
                    onKeyHovered(key)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val duration = SystemClock.uptimeMillis() - touchDownTime
                val diffX = x - touchDownX
                val diffY = y - touchDownY

                // التحقق من إيماءات السحب فقط عند إيقاف قارئ الشاشة
                if (!isScreenReader && duration <= swipeMaxDuration) {
                    if (abs(diffX) > swipeMinDistance &&
                        abs(diffX) > abs(diffY) * 1.5f
                    ) {
                        hoveredKey = null
                        invalidate()
                        if (diffX > 0) {
                            actionListener?.onSwipeRight()
                        } else {
                            actionListener?.onSwipeLeft()
                        }
                        return true
                    } else if (abs(diffY) > swipeMinDistance &&
                        abs(diffY) > abs(diffX) * 1.5f
                    ) {
                        hoveredKey = null
                        invalidate()
                        if (diffY > 0) {
                            actionListener?.onSwipeDown()
                        } else {
                            actionListener?.onSwipeUp()
                        }
                        return true
                    }
                }

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
            commitKeyActivation(key)
        } else {
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
            // تجاهل إن تعذر تشغيل الصوت
        }
    }

    /**
     * ميسّر إمكانية الوصول الافتراضي لربط مسطح الرسم بـ TalkBack.
     */
    private inner class KeyboardTouchHelper(host: View) :
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

            val isPhonetic = settingsRepo.isKeyboardPhoneticEnabled()
            val desc = if (isPhonetic && !key.phonetic.isNullOrEmpty()) {
                key.phonetic
            } else {
                key.spokenText
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
                    commitKeyActivation(key)
                    return true
                }
            }
            return false
        }
    }
}
