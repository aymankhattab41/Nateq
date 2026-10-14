package com.aymankhattab.nateq.core.audio.accessibility

/**
 * ع٤١-ب9: بوّابة تثبيط أحداث البلوتوث المتقلّبة (منطق نقي بلا اعتماد
 * على أندرويد). القاعدة: أوّل حدث لجهاز يُعلَن، وكل حدث لاحق خلال
 * [debounceMs] يُكبَت — فيُدمَج «انقطاعٌ ثم اتصالٌ سريع» في إعلانٍ واحد.
 *
 * @param debounceMs نافذة التثبيط بالمللي ثانية (٣ ثوانٍ افتراضاً).
 */
class BluetoothEventGate(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {

    private data class LastEvent(
        val connected: Boolean,
        val atMs: Long
    )

    private val lastByKey = HashMap<String, LastEvent>()

    /**
     * @return true إذا وجب الإعلان عن حالة [deviceKey] الآن.
     * @param deviceKey مفتاح الجهاز (العنوان غالباً).
     * @param connected حالة الاتصال الجديدة.
     * @param nowMs الوقت الحالي (ساعة أحادية تزايدية).
     */
    fun shouldAnnounce(
        deviceKey: String,
        connected: Boolean,
        nowMs: Long
    ): Boolean {
        val previous = lastByKey[deviceKey]
        lastByKey[deviceKey] = LastEvent(connected, nowMs)
        if (previous == null) return true
        return nowMs - previous.atMs >= debounceMs
    }

    /** يمسح الذاكرة (عند إطفاء الشاشة أو إيقاف الميزة). */
    fun clear() {
        lastByKey.clear()
    }

    companion object {
        /** نافذة تثبيط أحداث الجهاز الواحد (٣ ثوانٍ). */
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
