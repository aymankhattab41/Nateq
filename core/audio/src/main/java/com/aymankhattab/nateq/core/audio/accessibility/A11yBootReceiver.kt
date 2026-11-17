package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * مستقبل الإقلاع لاستئناف/إعادة جدولة خدمات الشاشة (ع٢٧-14).
 *
 * فُصل عن [A11yResumeReceiver] لأسباب أمنية وتوافقية:
 *  - مستقبل الاستئناف نفسه غير مُصدَّر كي لا يستطيع أي تطبيق إلغاء إيقاف
 *    إمكانية الوصول بإرسال الإجراء الداخلي.
 *  - بثّ BOOT_COMPLETED ضمني من النظام، ومنذ Android 14 لا يُسلَّم إلا
 *    للمكوّنات المُصدَّرة، ولذلك هذا المستقبل مُصدَّر ومحميّ بإذن
 *    النظام `RECEIVE_BOOT_COMPLETED` في المانيفست.
 */
class A11yBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        // حارس تساوي صرف: لا startsWith (قاعدة حوارس المستقبلات المُصدَّرة).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        A11yResumeReceiver.handleBootCompleted(context)
    }
}
