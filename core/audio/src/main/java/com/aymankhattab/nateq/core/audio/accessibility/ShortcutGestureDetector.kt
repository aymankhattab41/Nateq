package com.aymankhattab.nateq.core.audio.accessibility

/** مفتاحُ الصوت المادي المرصود. */
enum class VolumeKey { UP, DOWN }

/**
 * كاشف إيماءات مفاتيح الصوت (ع٤١-ب1).
 * منطقٌ نقيٌّ بلا اعتمادٍ على أندرويد (قابل لاختبار JVM).
 * - يحسم الإيماءة حسب الروابط الفعلية والمهلة (البند 10).
 * - يمنع تكرار إطلاق الطويل لنفس الضغطة (البند 11).
 * - يعالج الإلغاء وينظف الحالة والمهام المؤجلة (البند 12).
 */
class ShortcutGestureDetector(
    private val isGestureBound: (ShortcutGesture) -> Boolean,
    val doublePressWindowMs: Long = DEFAULT_DOUBLE_PRESS_WINDOW_MS,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS
) {

    /** نتيجةُ حدثِ ضغط: الإيماءة المتحققة وهل تُستهلك. */
    data class Detection(
        val gesture: ShortcutGesture?,
        val consume: Boolean
    )

    private class KeyState {
        var isDown = false
        var lastDownTime = 0L
        var pressCount = 0
        var captureUp = false
        var multiHold = false
        var longPressFired = false

        fun reset() {
            isDown = false
            lastDownTime = 0L
            pressCount = 0
            captureUp = false
            multiHold = false
            longPressFired = false
        }
    }

    private val upState = KeyState()
    private val downState = KeyState()

    private var pendingGesture: ShortcutGesture? = null
    private var pendingKey: VolumeKey? = null
    private var pendingGestureTime = 0L

    /** هل توجد إيماءة مزدوجة مؤجلة بانتظار احتمال الثلاثية؟ */
    fun hasPending(): Boolean = pendingGesture != null

    fun onKeyDown(
        key: VolumeKey,
        eventTimeMs: Long,
        isLongPress: Boolean,
        repeatCount: Int
    ): Detection {
        val self = stateOf(key)
        val peer = stateOf(other(key))
        self.isDown = true
        if (peer.isDown) {
            self.multiHold = true
            peer.multiHold = true
            cancelPending()
        }

        if (isLongPress || repeatCount >= 1) {
            cancelPending()
            self.pressCount = 0
            self.lastDownTime = 0L
            if (peer.isDown) {
                return Detection(null, false)
            }
            val gesture = longGesture(key)
            if (!isGestureBound(gesture)) {
                return Detection(null, false)
            }
            if (self.longPressFired) {
                // منع تكرار إطلاق الطويل لنفس الضغطة (البند 11)
                return Detection(null, true)
            }
            self.longPressFired = true
            return fire(gesture, self)
        }

        if (pendingKey == key) {
            cancelPending()
        }

        val withinWindow = self.pressCount > 0 &&
            eventTimeMs - self.lastDownTime <= doublePressWindowMs
        self.pressCount = if (withinWindow) self.pressCount + 1 else 1
        self.lastDownTime = eventTimeMs

        return when (self.pressCount) {
            2 -> {
                val doubleGest = doubleGesture(key)
                val tripleGest = tripleGesture(key)
                val isDoubleBound = isGestureBound(doubleGest)
                val isTripleBound = isGestureBound(tripleGest)

                if (isTripleBound && isDoubleBound) {
                    // كلا الربطين معاً (البند 10): تأجيل المزدوج
                    pendingGesture = doubleGest
                    pendingKey = key
                    pendingGestureTime = eventTimeMs + doublePressWindowMs
                    self.captureUp = true
                    Detection(null, consume = true)
                } else if (isDoubleBound) {
                    // ثنائية فقط: إطلاق فوري بلا تأجيل
                    self.pressCount = 0
                    self.lastDownTime = 0L
                    fire(doubleGest, self)
                } else if (isTripleBound) {
                    // ثلاثية فقط بدون ثنائية: استهلاك والانتظار للثالثة
                    self.captureUp = true
                    Detection(null, consume = true)
                } else {
                    Detection(null, false)
                }
            }
            3 -> {
                val tripleGest = tripleGesture(key)
                cancelPending()
                self.pressCount = 0
                self.lastDownTime = 0L
                if (isGestureBound(tripleGest)) {
                    fire(tripleGest, self)
                } else {
                    Detection(null, false)
                }
            }
            else -> Detection(null, false)
        }
    }

    /**
     * رفعُ المفتاح: يحسم الضغطَ المطوّل بزمن الاستمرار.
     */
    fun onKeyUp(key: VolumeKey, eventTimeMs: Long): Detection {
        val self = stateOf(key)
        self.isDown = false
        val multi = self.multiHold
        self.multiHold = false
        val consumedUp = self.captureUp
        self.captureUp = false
        val wasLongFired = self.longPressFired
        self.longPressFired = false

        if (!consumedUp && !multi && !wasLongFired &&
            self.lastDownTime > 0L
        ) {
            val heldMs = eventTimeMs - self.lastDownTime
            if (heldMs >= longPressMs) {
                self.pressCount = 0
                self.lastDownTime = 0L
                cancelPending()
                if (isGestureBound(longGesture(key))) {
                    return Detection(longGesture(key), true)
                }
            }
        }
        return Detection(null, consumedUp || wasLongFired)
    }

    /**
     * يحسم الإيماءة المؤجلة (الضغط المزدوج) عند انتهاء مهلة انتظار الثلاثي.
     */
    fun onTimeout(currentTimeMs: Long, key: VolumeKey? = null): Detection {
        val target = pendingGesture ?: return Detection(null, false)
        if (key != null && pendingKey != key) {
            return Detection(null, false)
        }
        if (currentTimeMs >= pendingGestureTime) {
            val gesture = target
            val pKey = pendingKey
            cancelPending()
            if (pKey != null) {
                val s = stateOf(pKey)
                s.pressCount = 0
                s.lastDownTime = 0L
            }
            return Detection(gesture, true)
        }
        return Detection(null, false)
    }

    /**
     * معالجة حدث ملغى (البند 12): تنظيف حالة المفتاح والمهام المؤجلة.
     */
    fun onKeyCancel(key: VolumeKey) {
        val self = stateOf(key)
        self.reset()
        if (pendingKey == key) {
            cancelPending()
        }
    }

    fun reset() {
        upState.reset()
        downState.reset()
        cancelPending()
    }

    private fun cancelPending() {
        pendingGesture = null
        pendingKey = null
        pendingGestureTime = 0L
    }

    private fun fire(
        gesture: ShortcutGesture,
        self: KeyState
    ): Detection {
        if (!isGestureBound(gesture)) return Detection(null, false)
        self.captureUp = true
        return Detection(gesture, true)
    }

    private fun stateOf(key: VolumeKey): KeyState =
        if (key == VolumeKey.UP) upState else downState

    private fun other(key: VolumeKey): VolumeKey =
        if (key == VolumeKey.UP) VolumeKey.DOWN else VolumeKey.UP

    private fun longGesture(key: VolumeKey): ShortcutGesture =
        if (key == VolumeKey.UP) {
            ShortcutGesture.VOL_UP_LONG
        } else {
            ShortcutGesture.VOL_DOWN_LONG
        }

    private fun doubleGesture(key: VolumeKey): ShortcutGesture =
        if (key == VolumeKey.UP) {
            ShortcutGesture.VOL_UP_DOUBLE
        } else {
            ShortcutGesture.VOL_DOWN_DOUBLE
        }

    private fun tripleGesture(key: VolumeKey): ShortcutGesture =
        if (key == VolumeKey.UP) {
            ShortcutGesture.VOL_UP_TRIPLE
        } else {
            ShortcutGesture.VOL_DOWN_TRIPLE
        }

    companion object {
        const val DEFAULT_DOUBLE_PRESS_WINDOW_MS = 300L
        const val DEFAULT_LONG_PRESS_MS = 600L
    }
}
