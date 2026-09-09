package com.aymankhattab.nateq.core.common

import java.util.Calendar

/**
 * مصدر موحّد للوقت الحالي — يتيح استبدال ساعة النظام الحقيقية بساعة افتراضية
 * قابلة للضبط في الاختبارات بدل الاعتماد على
 * System.currentTimeMillis / Calendar.
 */
interface TimeProvider {

    /** الزمن الحالي بالميلي ثانية (توقيت الجدار الزمني). */
    fun currentTimeMillis(): Long

    /** التقويم الحالي بالمنطقة الزمنية الافتراضية. */
    fun now(): Calendar
}

/** المزوّد الحقيقي الافتراضي: يقرأ ساعة النظام الفعلية. */
object SystemTimeProvider : TimeProvider {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun now(): Calendar = Calendar.getInstance()
}