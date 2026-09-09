package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope

/**
 * واجهة سياق التطبيق المشترك التي تحتاجها طبقة الإعلانات في :core:audio
 * (المتحدث، مستقبلات البطارية/الرسائل/المتصل) دون الاعتماد المباشر على
 * فئة :app. تُنفَّذ من NateqApplication (الحقول المحقونة بواسطة Hilt)
 * وتُلتَقط عبر cast على سياق التطبيق:
 * `applicationContext as? AnnouncementAppContext`.
 */
interface AnnouncementAppContext {
    /** مصدر الإعدادات الوحيد المحقون — كائن مشترك عبر عملية الواجهة. */
    val settingsRepository: SettingsRepository

    /** نطاق عام يعيش مع التطبيق — بديل GlobalScope للمستقبلات. */
    val appScope: CoroutineScope
}