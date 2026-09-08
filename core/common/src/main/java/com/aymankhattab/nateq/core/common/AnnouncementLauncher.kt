package com.aymankhattab.nateq.core.common

import android.content.Context

/**
 * واجهة تشغيل خدمة الإعلانات الأمامية من طبقة النطق — تفصل :core:audio عن
 * مراجع :app المباشرة ([AnnouncementSchedulerService]) لتفكيك الوحدات (البند 4).
 * ينفّذها :app بتغليف استدعاءات الخدمة الفعلية.
 */
interface AnnouncementLauncher {

    /** هل الخدمة الأمامية للإعلانات قائمة الآن؟ */
    val isSchedulerRunning: Boolean

    /** تشغيلها إن استوجبتها أي إعلانات مفعّلة (شرط صوت الخلفية أندرويد 15+). */
    fun startSchedulerIfNeeded(context: Context)
}