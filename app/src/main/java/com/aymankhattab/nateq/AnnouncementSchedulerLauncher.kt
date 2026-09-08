package com.aymankhattab.nateq

import android.content.Context
import com.aymankhattab.nateq.core.common.AnnouncementLauncher
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService

/**
 * تنفيذ :app لواجهة [AnnouncementLauncher] — يغلّف استدعاءات خدمة الإعلانات
 * الفعلية حتى لا يعتمد :core:audio على مراجع :app المباشرة (البند 4).
 */
object AnnouncementSchedulerLauncher : AnnouncementLauncher {

    override val isSchedulerRunning: Boolean
        get() = AnnouncementSchedulerService.isRunning

    override fun startSchedulerIfNeeded(context: Context) {
        AnnouncementSchedulerService.startIfNeeded(context)
    }
}