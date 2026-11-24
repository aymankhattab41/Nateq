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
 * - يحسب مدة الاستمرار من أول ضغطة down، ولا يعيد كتابتها مع تكرار
 *   down (البند 13).
 * - يربط الضغطة بجلسة المكالمة لمنع تطبيق ضغطة مكالمة منتهية على التالية.
 * - يصفّي الحالة عند الإلغاء أو انتهاء الرنين أو توقف الخدمة (البندان 12 و13).
 */
class CallKeyDetector(
    private val longPressMs: Long = LONG_PRESS_MS
) {

    private data class DownRecord(
        val downTimeMs: Long,
        val callSessionId: String? = null
    )

    private val downRecords = HashMap<VolumeKey, DownRecord>()

    /**
     * عند الضغط. @return هل يُستهلك الحدث (أي أثناء الرنين)؟
     */
    fun onDown(
        key: VolumeKey,
        ringing: Boolean,
        eventTimeMs: Long,
        callSessionId: String? = null
    ): Boolean {
        if (!ringing) {
            downRecords.remove(key)
            return false
        }
        val existing = downRecords[key]
        if (existing == null || existing.callSessionId != callSessionId) {
            downRecords[key] = DownRecord(eventTimeMs, callSessionId)
        }
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
        swapped: Boolean = false,
        callSessionId: String? = null
    ): CallAction? {
        val record = downRecords.remove(key) ?: return null
        if (!ringing) return null
        if (record.callSessionId != callSessionId) return null
        if (eventTimeMs - record.downTimeMs < longPressMs) return null
        return resolve(key, swapped)
    }

    /**
     * معالجة حدث ملغى (البند 12): إزالة تسجيل الضغطة دون اتخاذ إجراء.
     */
    fun onCancel(key: VolumeKey) {
        downRecords.remove(key)
    }

    /** مسح الحالة (عند توقّف الخدمة أو خروج الرنين). */
    fun reset() {
        downRecords.clear()
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
