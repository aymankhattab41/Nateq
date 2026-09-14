package com.aymankhattab.nateq.util

import android.content.Context

/** أدوات تمييز العملية الحالية في التطبيقات متعددة العمليات. */
object ProcessUtils {

    /**
     * هل تعمل الشيفرة الآن في العملية الرئيسية؟
     * التطبيق يعمل في عمليتين: main (الواجهة والخدمات الأمامية) و :tts
     * (محرك النطق). بعض المهام مشروطة بالعملية الحالية (مثل تنظيف الملفات
     * المؤقتة في main حصراً كي لا يُحذف صوت جلسة :tts الجاري توليده) —
     * **بند 7.1:** تُوحَّد المقارنة هنا بدل تكرارها نصياً في كل موقع.
     */
    fun isMainProcess(context: Context): Boolean =
        context.applicationInfo.processName == context.packageName
}