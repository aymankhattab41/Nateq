package com.aymankhattab.nateq.core.data

/**
 * ربطُ مستوى رنّة الساعة بمستوى نطق الساعة — خالصٌ بلا `Context`
 * ولا `SharedPreferences` فيُختبر وحده على JVM.
 *
 * الطلب: أن تتبع الرنّةُ صوتَ النطق تنزلاً وصعوداً، ما لم يحدّد
 * المستخدمُ لها نسبةً بنفسه.
 *
 * فصلُه عن [SettingsRepository] عمداً: القراءةُ من المخزن عملٌ
 * جانبيٌّ لا يُختبر، والحسابُ هو العقدُ الحاكم فيُختبر وحده.
 */
internal object TimeChimeVolumeLink {

    /** أدنى مستوى في مدى الرنّة — يُقصّ إليه الربط. */
    const val MIN = 0.1f

    /** أقصى مستوى في مدى الرنّة. */
    const val MAX = 1f

    /**
     * المستوى الفعّال لرنّة الساعة.
     *
     * @param followsAnnouncement هل تتبع الرنّةُ النطقَ (الافتراضي)
     *   أم لها نسبةٌ يدوية مستقلة.
     * @param announcementVolume مستوى نطق الساعة (0..1).
     * @param manualVolume المستوى اليدويّ المحفوظ للرنّة (0.1..1).
     */
    fun effective(
        followsAnnouncement: Boolean,
        announcementVolume: Float,
        manualVolume: Float
    ): Float =
        if (followsAnnouncement) {
            announcementVolume.coerceIn(MIN, MAX)
        } else {
            manualVolume.coerceIn(MIN, MAX)
        }
}