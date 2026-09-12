package com.aymankhattab.nateq.core.audio.announcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يعيد تشغيل [AnnouncementSchedulerService] بعد إقلاع الجهاز، وبعد تحديث
 * التطبيق (كلاهما يمسح منبهات نظام AlarmManager)، حتى تبقى إعلانات
 * الوقت/البطارية تعمل دون فتح التطبيق.
 *
 * لا يعمل إلا بعد فتح القفل الأول للمستخدم (BOOT_COMPLETED)، وإذا كان
 * أي إعلان مفعّلاً؛ الخدمة نفسها نوعها specialUse فلا يقيدها أندرويد 15
 * عند الإقلاع (بخلاف mediaPlayback).
 */
class AnnouncementBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // **بند 5.4:** تحديث التطبيق يمسح منبهات AlarmManager كما يفعل
        // الإقلاع — يُعاد تشغيل الجدولة عند MY_PACKAGE_REPLACED حتى لا
        // تتوقف الإعلانات الدورية بعد كل تحديث حتى يعيد المستخدم تشغيل
        // الجهاز. (الفلتر في Manifest يشمل الإجراءين معاً.)
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        try {
            val started = AnnouncementSchedulerService.startIfNeeded(context)
            // منبهات النظام لا تصمد بعد الإقلاع: إن لم تُشغَّل الخدمة
            // (كان إعلان الوقت هو الوحيد المفعّل — بند 16.2)
            // نعيد جدولة منبه الوقت مباشرةً.
            if (!started) {
                AnnouncementSchedulerService.ensureTimeAlarm(context)
            }
        } catch (t: Throwable) {
            android.util.Log.e("NATEQ_ANNOUNCE", "restart failed", t)
        }
    }
}