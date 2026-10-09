package com.aymankhattab.nateq.settings

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils

/**
 * بلاط الإعدادات السريعة (Quick Settings Tile) — ع٢٩-14.
 * يتيح للمستخدم:
 * - رؤية حالة الخدمة (نشطة/موقوفة/معطّلة)
 * - تبديل الإيقاف المؤقت بنقرة واحدة
 * - فتح إعدادات الخدمة بالضغط المطوّل
 */
class ScreenServicesTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // نقرة واحدة: تبديل الإيقاف المؤقت
        togglePause()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun togglePause() {
        val context = applicationContext
        val enabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(context)
        if (!enabled) {
            // الخدمة غير مفعّلة في النظام — نفتح شاشة التفعيل
            startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        // تبديل الإيقاف المؤقت عبر A11yStateStore
        val store =
            com.aymankhattab.nateq.core.audio.accessibility
                .A11yStateStore.getInstance(context)
        val currentlyPaused = store?.isPaused() == true
        store?.setPaused(!currentlyPaused)

        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val context = applicationContext
        val enabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(context)

        val store =
            com.aymankhattab.nateq.core.audio.accessibility
                .A11yStateStore.getInstance(context)
        val isPaused = store?.isPaused() == true

        when {
            !enabled -> {
                tile.label = getString(R.string.tile_service_disabled)
                tile.state = Tile.STATE_UNAVAILABLE
                tile.icon = Icon.createWithResource(
                    context,
                    R.drawable.ic_launcher_foreground
                )
            }
            isPaused -> {
                tile.label = getString(R.string.tile_service_paused)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(
                    context,
                    R.drawable.ic_launcher_foreground
                )
            }
            else -> {
                tile.label = getString(R.string.tile_service_active)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(
                    context,
                    R.drawable.ic_launcher_foreground
                )
            }
        }

        tile.updateTile()
    }
}