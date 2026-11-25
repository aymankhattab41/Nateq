package com.aymankhattab.nateq.core.audio.accessibility

/**
 * بوابة تثبيط أحداث البلوتوث المتقلّبة (منطق نقي بلا اعتماد على أندرويد).
 *
 * تحفظ الحالة النهائية الحقيقية للجهاز عبر دمج مؤجل محسوب مع إلغاء آمن:
 * 1. أول حدث لكل جهاز يُعلَن فوراً بلا أي تأخير.
 * 2. إذا تكررت نفس الحالة المعلنة بالفعل، تُسقط بلا إجراء.
 * 3. إذا تغيرت الحالة داخل نافذة التثبيط [debounceMs]، لا تُسقط نهائياً بل
 *    يُحسب المتبقي من النافذة ويُرتَّب إعلان متأخر (trailing).
 * 4. إذا عادت الحالة داخل النافذة إلى ما أُعلن مسبقاً (انقطاع ثم اتصال سريع)،
 *    يُلغى الإعلان المتأخر بأمان منعاً للتشويش الصوتي.
 * 5. إذا انقضت النافذة كاملة، يُعلَن التغيير الجديد فوراً.
 *
 * @param debounceMs نافذة التثبيط بالمللي ثانية (٣ ثوانٍ افتراضاً).
 */
class BluetoothEventGate(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {

    /**
     * الإجراء المترتب على تقييم حدث البلوتوث.
     */
    sealed class Action {
        /** إعلان فوري الآن بالحالة المحددة. */
        object AnnounceImmediate : Action()

        /** ترتيب إعلان متأخر (trailing) بعد [delayMs] مللي ثانية. */
        data class ScheduleTrailing(val delayMs: Long) : Action()

        /** إلغاء الإعلان المتأخر لعودة الحالة إلى ما تم إعلانه مسبقاً. */
        object CancelTrailing : Action()

        /** إسقاط الحدث المكرر دون إجراء. */
        object Drop : Action()
    }

    private data class DeviceState(
        var lastAnnounced: Boolean? = null,
        var lastAnnouncedAtMs: Long = 0L,
        var pendingConnected: Boolean? = null,
        var pendingDueAtMs: Long = 0L
    )

    private val stateByKey = HashMap<String, DeviceState>()

    /**
     * يقيّم الحدث الوارد ويعيد الإجراء الواجب اتخاذه.
     *
     * @param deviceKey مفتاح الجهاز (العنوان أو مسار الصوت).
     * @param connected حالة الاتصال الجديدة.
     * @param nowMs الوقت الحالي (ساعة أحادية تزايدية).
     */
    fun evaluate(
        deviceKey: String,
        connected: Boolean,
        nowMs: Long
    ): Action {
        val state = stateByKey.getOrPut(deviceKey) { DeviceState() }
        val last = state.lastAnnounced

        if (last == null) {
            // أول حدث لهذا الجهاز: يعلن فوراً بلا تأخير
            state.lastAnnounced = connected
            state.lastAnnouncedAtMs = nowMs
            state.pendingConnected = null
            state.pendingDueAtMs = 0L
            return Action.AnnounceImmediate
        }

        if (connected == last) {
            // الحالة تطابق ما أُعلن للمستخدم
            if (state.pendingConnected != null) {
                // كان هناك إعلان متأخر مجدول، لكن الحالة عادت إلى
                // ما أُعلن مسبقاً (مثل فصل ثم اتصال سريع) ⇒ إلغاء آمن
                state.pendingConnected = null
                state.pendingDueAtMs = 0L
                return Action.CancelTrailing
            }
            return Action.Drop
        }

        // هنا: connected != last (تغيرت الحالة عما أُعلن)
        val elapsed = nowMs - state.lastAnnouncedAtMs
        if (elapsed >= debounceMs) {
            // انقضت نافذة التثبيط كاملة منذ آخر إعلان: يعلن فوراً
            state.lastAnnounced = connected
            state.lastAnnouncedAtMs = nowMs
            state.pendingConnected = null
            state.pendingDueAtMs = 0L
            return Action.AnnounceImmediate
        }

        // تغيرت الحالة داخل نافذة التثبيط: دمج مؤجل محسوب
        val remaining = (state.lastAnnouncedAtMs + debounceMs) - nowMs
        val delayMs = if (remaining > 0L) remaining else 0L
        state.pendingConnected = connected
        state.pendingDueAtMs = nowMs + delayMs
        return Action.ScheduleTrailing(delayMs)
    }

    /**
     * يُستدعى عند حلول موعد الإعلان المتأخر (trailing).
     *
     * @return الحالة النهائية الواجب إعلانها، أو null إن أُلغي
     *         أو لم يعد مستحقاً.
     */
    fun onTrailingDue(deviceKey: String, nowMs: Long): Boolean? {
        val state = stateByKey[deviceKey] ?: return null
        val pending = state.pendingConnected ?: return null
        if (pending == state.lastAnnounced) {
            state.pendingConnected = null
            state.pendingDueAtMs = 0L
            return null
        }
        state.lastAnnounced = pending
        state.lastAnnouncedAtMs = nowMs
        state.pendingConnected = null
        state.pendingDueAtMs = 0L
        return pending
    }

    /**
     * الحالة الأخيرة التي أُعلنت للجهاز (أو null إن لم يُعلن شيء).
     */
    fun getLastAnnouncedState(deviceKey: String): Boolean? =
        stateByKey[deviceKey]?.lastAnnounced

    /**
     * الحالة المعلقة في الانتظار للإعلان المتأخر (أو null).
     */
    fun getPendingState(deviceKey: String): Boolean? =
        stateByKey[deviceKey]?.pendingConnected

    /**
     * هل الجهاز لديه إعلان متأخر مجدول قيد الانتظار؟
     */
    fun hasPendingTrailing(deviceKey: String): Boolean =
        stateByKey[deviceKey]?.pendingConnected != null

    /**
     * للتوافق: يعيد true فقط إذا وجب الإعلان الفوري الآن.
     */
    fun shouldAnnounce(
        deviceKey: String,
        connected: Boolean,
        nowMs: Long
    ): Boolean =
        evaluate(deviceKey, connected, nowMs) is Action.AnnounceImmediate

    /** يمسح الذاكرة والإعلانات المعلقة (عند إيقاف الميزة أو إغلاق الخدمة). */
    fun clear() {
        stateByKey.clear()
    }

    companion object {
        /** نافذة تثبيط أحداث الجهاز الواحد (٣ ثوانٍ). */
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
