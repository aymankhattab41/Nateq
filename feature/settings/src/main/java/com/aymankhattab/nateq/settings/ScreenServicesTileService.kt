package com.aymankhattab.nateq.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils

/**
 * لوحة الإعدادات السريعة لخدمات الشاشة وإمكانية الوصول (ع٢٧-16).
 * تتيح التبديل السريع بين التشغيل والإيقاف المؤقت وعرض الحالة الحية.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ScreenServicesTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isServiceEnabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(this)

        if (!isServiceEnabled) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // startActivityAndCollapse(intent)
            }
            runCatching { startActivity(intent) }
            return
        }

        val store = A11yStateStore.getInstance(this) ?: return
        val currentlyPaused = store.isPaused()
        store.setPaused(!currentlyPaused)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceEnabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(this)

        if (!isServiceEnabled) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.a11y_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.a11y_status_disabled)
            }
        } else {
            val store = A11yStateStore.getInstance(this)
            val isPaused = store?.isPaused() ?: false

            if (isPaused) {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.a11y_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.a11y_status_paused)
                }
            } else {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.a11y_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.a11y_status_active)
                }
            }
        }
        tile.updateTile()
    }
}
