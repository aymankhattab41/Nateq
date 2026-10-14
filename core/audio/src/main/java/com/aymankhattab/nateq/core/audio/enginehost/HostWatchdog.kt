package com.aymankhattab.nateq.core.audio.enginehost

import kotlinx.coroutines.delay

/**
 * سياسة حارس المضيف: يقيس **الخمول** لا الزمن الكلّي للنطق.
 *
 * المحرّكات المتدفّقة (Vocalizer) تبثّ PCM إلى الأنبوب
 * بسرعة تشغيله تقريباً، فيصير زمن النطق الكلّي ≈ طول الصوت لا زمن
 * الحساب. فميزانية القطعة (زمن حساب) أقصر منه قطعاً، وكان حارسٌ
 * مبنيٌّ عليها يوقف نطقاً سليماً في منتصفه ويُعيد `STOPPED`.
 *
 * الفرق: ما دامت الشرائح تتوالى (تقدّمٌ حديث) لا يُوقف الطلب مهما
 * طال، وإن انقطعت لمدّة الميزانية فهو خمولٌ حقيقي يُوقف.
 */
internal object HostWatchdog {

    /** دور فحص الخمول الافتراضي (مللي ثانية). */
    const val DEFAULT_POLL_MS = 100L

    /**
     * هل انقضى [inactivityBudgetMs] بلا أيّ تقدّم؟
     *
     * @param nowMs اللحظة الحالية (uptime).
     * @param lastProgressAtMs زمن آخر ترويسة/شريحة PCM.
     * @param inactivityBudgetMs عتبة الخمول المسموحة.
     */
    fun expired(
        nowMs: Long,
        lastProgressAtMs: Long,
        inactivityBudgetMs: Long
    ): Boolean = nowMs - lastProgressAtMs >= inactivityBudgetMs

    /**
     * ينتظر زوال النشاط أو خمولَ التقدّم، أيّهما أسبق.
     *
     * @param isActive هل ما زال التوليد جارياً؟
     * @param lastProgressAtMs مزوّد زمن آخر تقدّم (ترويسة/شريحة PCM).
     * @param nowMs مزوّد اللحظة الحالية.
     * @param inactivityBudgetMs عتبة الخمول المسموحة.
     * @param pollMs دور الفحص.
     * @return `true` إن انتهى النشاط، و`false` إن خمل قبل انتهائه.
     */
    suspend fun awaitProgressOrIdle(
        isActive: () -> Boolean,
        lastProgressAtMs: () -> Long,
        nowMs: () -> Long,
        inactivityBudgetMs: Long,
        pollMs: Long = DEFAULT_POLL_MS
    ): Boolean {
        while (isActive()) {
            if (expired(nowMs(), lastProgressAtMs(), inactivityBudgetMs)) {
                return false
            }
            delay(pollMs)
        }
        return true
    }
}
