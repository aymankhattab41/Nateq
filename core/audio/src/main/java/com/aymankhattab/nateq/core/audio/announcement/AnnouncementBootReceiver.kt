package com.aymankhattab.nateq.core.audio.announcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يعيد تشغيل [AnnouncementSchedulerService] بعد إقلاع الجهاز، حتى تبقى
 * إعلانات الوقت/البطارية تعمل دون فتح التطبيق.
 *
 * لا يعمل إلا بعد فتح القفل الأول للمستخدم (BOOT_COMPLETED)، وإذا كان
 * أي إعلان مفعّلاً؛ الخدمة نفسها نوعها specialUse فلا يقيدها أندرويد 15
 * عند الإقلاع (بخلاف mediaPlayback).
 */
class AnnouncementBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val started = AnnouncementSchedulerService.startIfNeeded(context)
            // منبهات النظام لا تصمد بعد الإقلاع: إن لم تُشغَّل الخدمة
            // (كان إعلان الوقت هو الوحيد المفعل — بند 16.2)
            // نعيد جدولة منبه الوقت مباشرةً.
            if (!started) {
                AnnouncementSchedulerService.ensureTimeAlarm(context)
            }
        } catch (t: Throwable) {
            android.util.Log.e("NATEQ_ANNOUNCE", "boot restart failed", t)
        }
    }
}