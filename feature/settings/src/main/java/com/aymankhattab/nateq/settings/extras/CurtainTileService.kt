package com.aymankhattab.nateq.settings.extras

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * بلاطة الإعدادات السريعة لستارة الشاشة (ع٥٦-أمر 19 / ع٥٧-أمر 3).
 * تتيح تشغيل/إيقاف ستارة الشاشة السوداء بضغطة زر واحدة
 * من لوحة الإعدادات السريعة وتقرأ الحالة عبر العمليات من SettingsRepository.
 */
class CurtainTileService : TileService() {

    companion object {
        const val ACTION_TOGGLE_CURTAIN =
            "com.aymankhattab.nateq.action.TOGGLE_SCREEN_CURTAIN"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(ACTION_TOGGLE_CURTAIN).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        val qsTile = qsTile ?: return
        val newState = if (qsTile.state == Tile.STATE_ACTIVE) {
            Tile.STATE_INACTIVE
        } else {
            Tile.STATE_ACTIVE
        }
        qsTile.state = newState
        qsTile.updateTile()
    }

    private fun updateTileState() {
        val qsTile = qsTile ?: return
        val repo = SettingsRepository.create(this)
        qsTile.state = if (repo.isScreenCurtainActive()) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        qsTile.updateTile()
    }
}
