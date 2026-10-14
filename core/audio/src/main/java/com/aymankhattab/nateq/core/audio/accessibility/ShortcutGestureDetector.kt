package com.aymankhattab.nateq.core.audio.accessibility

/** مفتاحُ الصوت المادي المرصود. */
enum class VolumeKey { UP, DOWN }

/**
 * كاشف إيماءات مفاتيح الصوت (ع٤١-ب1).
 * منطقٌ نقيٌّ بلا اعتمادٍ على أندرويد (قابل لاختبار JVM). القرار الحاكم:
 * **يُستهلك المفتاح فقط عند تحقّق إيماءةٍ مربوطةٍ بفعل** (نمط «الاستهلاك
 * عند اكتمال الإيماءة») — فالنقرات الأولى تمرّ للنظام كما هي.
 * استمرارُ المفتاحين معاً (BOTH_HOLD) لا يُنتجه الكاشف لأنّ مؤقّت الطوارئ
 * في الخدمة يتولّاه.
 * الضغطُ المطوّل: مرشّحُ مفاتيح a11y يدمج تكرارات مفتاح الصوت فلا تصل
 * أحداثُ `repeatCount`/`isLongPress` (مُثبَتٌ ميدانياً)؛ لذلك يُقاس
 * الضغطُ المطوّل بزمن الاستمرار بين الضغط والرفع. ويبقى مسارُ التكرار
 * مدعوماً لو سلّمته بعض الأجهزة.
 */
class ShortcutGestureDetector(
    private val isGestureBound: (ShortcutGesture) -> Boolean,
    private val doublePressWindowMs: Long = DEFAULT_DOUBLE_PRESS_WINDOW_MS,
    private val longPressMs: Long = DEFAULT_LONG_PRESS_MS
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

        fun reset() {
            isDown = false
            lastDownTime = 0L
            pressCount = 0
            captureUp = false
            multiHold = false
        }
    }

    private val upState = KeyState()
    private val downState = KeyState()

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
        }

        if (isLongPress || repeatCount >= 1) {
            self.pressCount = 0
            self.lastDownTime = 0L
            if (peer.isDown) {
                return Detection(null, false)
            }
            return fire(longGesture(key), self)
        }

        val withinWindow = self.pressCount > 0 &&
            eventTimeMs - self.lastDownTime <= doublePressWindowMs
        self.pressCount = if (withinWindow) self.pressCount + 1 else 1
        self.lastDownTime = eventTimeMs

        val gesture = when (self.pressCount) {
            2 -> doubleGesture(key)
            3 -> tripleGesture(key)
            else -> null
        }
        if (gesture == null) return Detection(null, false)
        val detection = fire(gesture, self)
        if (detection.consume) {
            self.pressCount = 0
            self.lastDownTime = 0L
        }
        return detection
    }

    /**
     * رفعُ المفتاح: يحسم الضغطَ المطوّل بزمن الاستمرار (لأنّ التكرارات
     * لا تصل من مرشّح a11y). @return الإيماءةٌ المتحققة (أو null) وهل
     * يُستهلك حدث الرفع.
     */
    fun onKeyUp(key: VolumeKey, eventTimeMs: Long): Detection {
        val self = stateOf(key)
        self.isDown = false
        val multi = self.multiHold
        self.multiHold = false
        val consumedUp = self.captureUp
        self.captureUp = false

        if (!consumedUp && !multi && self.lastDownTime > 0L) {
            val heldMs = eventTimeMs - self.lastDownTime
            if (heldMs >= longPressMs) {
                self.pressCount = 0
                self.lastDownTime = 0L
                if (isGestureBound(longGesture(key))) {
                    return Detection(longGesture(key), true)
                }
            }
        }
        return Detection(null, consumedUp)
    }

    fun reset() {
        upState.reset()
        downState.reset()
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
        /** نافذةُ تجميع النقرات المتتابعة (جزء من ع٤١-ب1). */
        const val DEFAULT_DOUBLE_PRESS_WINDOW_MS = 300L

        /** عتبةُ الضغط المطوّل بالزمن (بديلُ التكرارات المدمَجة). */
        const val DEFAULT_LONG_PRESS_MS = 600L
    }
}
