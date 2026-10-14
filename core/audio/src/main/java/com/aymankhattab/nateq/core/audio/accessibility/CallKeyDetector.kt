package com.aymankhattab.nateq.core.audio.accessibility

/** فعلٌ مطلوبٌ على المكالمة الرنّانة (ع٤١-ب8). */
enum class CallAction { ANSWER, REJECT }

/**
 * كاشف أوامر المكالمة بالمفاتيح (ع٤١-ب8).
 * أثناء الرنين فقط: ضغطٌ مطوّلٌ (≥ [longPressMs]) على مفتاح الصوت ينفّذ
 * الفعل المقابل (UP⇒ردّ، DOWN⇒رفض، قابلان للتبديل). تُستهلك مفاتيح الصوت
 * كلها أثناء الرنين فلا يتغيّر مستوى الصوت، وتُهمل خارج الرنين.
 *
 * منطقٌ نقيّ بلا اعتمادٍ على أندرويد (قابل للاختبار بساعةٍ مُمرَّرة).
 */
class CallKeyDetector(
    private val longPressMs: Long = LONG_PRESS_MS
) {

    private val downAt = HashMap<VolumeKey, Long>()

    /**
     * عند الضغط. @return هل يُستهلك الحدث (أي أثناء الرنين)؟
     */
    fun onDown(
        key: VolumeKey,
        ringing: Boolean,
        eventTimeMs: Long
    ): Boolean {
        if (!ringing) {
            downAt.remove(key)
            return false
        }
        downAt[key] = eventTimeMs
        return true
    }

    /**
     * عند الرفع. @return الفعل المطلوب (ردّ/رفض) إن كان ضغطاً مطوّلاً
     * أثناء الرنين، وإلا null.
     */
    fun onUp(
        key: VolumeKey,
        ringing: Boolean,
        eventTimeMs: Long,
        swapped: Boolean = false
    ): CallAction? {
        val start = downAt.remove(key) ?: return null
        if (!ringing) return null
        if (eventTimeMs - start < longPressMs) return null
        return resolve(key, swapped)
    }

    /** مسح الحالة (عند توقّف الخدمة أو خروج الرنين). */
    fun reset() {
        downAt.clear()
    }

    private fun resolve(key: VolumeKey, swapped: Boolean): CallAction {
        val upIsAnswer = !swapped
        return when (key) {
            VolumeKey.UP -> if (upIsAnswer) {
                CallAction.ANSWER
            } else {
                CallAction.REJECT
            }
            VolumeKey.DOWN -> if (upIsAnswer) {
                CallAction.REJECT
            } else {
                CallAction.ANSWER
            }
        }
    }

    companion object {
        /** حماية من الضغط العرضي: ضغطٌ مطوّل ≥ 700ms (ع٤١-ب8.4). */
        const val LONG_PRESS_MS = 700L
    }
}
