package com.aymankhattab.nateq.settings

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aymankhattab.nateq.core.audio.accessibility.NextEngineSwitcher
import com.aymankhattab.nateq.feature.settings.R

/**
 * بلاطة مستقلة لتبديل محرك Lord TTS حتى عندما لا تكون خدمة المحرك متصلة.
 */
class NextEngineTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        NextEngineSwitcher.switchToNext(applicationContext)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val engine = NextEngineSwitcher.selectedEngineLabel(applicationContext)
        tile.label = if (engine == null) {
            getString(R.string.tile_next_engine_label)
        } else {
            getString(R.string.tile_next_engine_current, engine)
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
