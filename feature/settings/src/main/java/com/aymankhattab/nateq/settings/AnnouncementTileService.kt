package com.aymankhattab.nateq.settings

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * بلاطة الإعدادات السريعة «إعلانات ناطق»:
 * ضغطة واحدة تُطفئ/تُشغّل كل الإعلانات التلقائية (المفتاح الرئيسي)، ومع كل
 * تشغيل تحاول تشغيل الخدمة الأمامية فوراً ليعمل الوضع معطّلاً/مفعّلاً لحظياً.
 */
@AndroidEntryPoint
class AnnouncementTileService : TileService() {

    companion object {
        private const val TAG = "NATEQ_TILE"
    }

    /** مصدر الإعدادات المحقون — كائن مشترك عبر عمليات التطبيق. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        try {
            val settings = settingsRepository
            val enabled = settings.isAllAnnouncementsEnabled()
            settings.setAllAnnouncementsEnabled(!enabled)
            if (enabled) {
                // كان مفعّلاً وأصبح معطّلاً: أوقف أي نطق جارٍ وصفّر الخدمة.
                AnnouncementSpeaker.getInstance(this).stop()
                try {
                    stopService(android.content.Intent(this, AnnouncementSchedulerService::class.java))
                } catch (t: Throwable) {
                    Log.w(TAG, "stopService failed", t)
                }
            } else {
                // كان معطّلاً وأصبح مفعّلاً: عُد لتشغيل الإعلانات فوراً.
                AnnouncementSchedulerService.requestStart(this)
            }
            updateTile()
        } catch (t: Throwable) {
            Log.e(TAG, "onClick failed", t)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val settings = settingsRepository
        val enabled = settings.isAllAnnouncementsEnabled()
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val label = getString(if (enabled) R.string.tile_label_on else R.string.tile_label_off)
        tile.label = label
        // الوصف الثابت (مضبوط من السلاسل) يُغني عن الصوتية المتغيرة في flags.
        tile.subtitle = getString(R.string.tile_label_description)
        // بدون contentDescription: يقرأ النظام label تلقائياً (تسمية) + حالة STATE —
        // إضافته تُكرّر القراءة لنفس النص.
        tile.updateTile()
    }
}
