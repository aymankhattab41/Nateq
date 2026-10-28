package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.graphics.Rect
import com.aymankhattab.nateq.core.audio.R

/**
 * شبكة افتراضية تنطق موضع العنصر على الشاشة (ع٥٦-أمر 5).
 * تقسم النافذة إلى 9 أو 4 مناطق مع مراعاة RTL.
 */
object ScreenGridAnnouncer {

    enum class GridMode {
        ALWAYS,
        ON_CHANGE,
        ON_DEMAND
    }

    private var lastAnnouncedZone: String? = null

    /**
     * حساب المنطقة من إحداثيات مستطيل العنصر وأبعاد النافذة.
     */
    fun calculateZone(
        context: Context,
        elementBounds: Rect,
        windowBounds: Rect,
        isRtl: Boolean = true,
        sectionsCount: Int = 9
    ): String {
        val winWidth = windowBounds.width().coerceAtLeast(1)
        val winHeight = windowBounds.height().coerceAtLeast(1)

        val relX = (elementBounds.centerX() - windowBounds.left)
            .coerceIn(0, winWidth)
        val relY = (elementBounds.centerY() - windowBounds.top)
            .coerceIn(0, winHeight)

        return if (sectionsCount == 4) {
            calculate4Zones(context, relX, relY, winWidth, winHeight, isRtl)
        } else {
            calculate9Zones(context, relX, relY, winWidth, winHeight, isRtl)
        }
    }

    private fun calculate9Zones(
        context: Context,
        relX: Int,
        relY: Int,
        width: Int,
        height: Int,
        isRtl: Boolean
    ): String {
        val col = when {
            relX < width / 3 -> if (isRtl) 2 else 0 // 2=Right, 0=Left
            relX < 2 * width / 3 -> 1 // Center
            else -> if (isRtl) 0 else 2
        }

        val row = when {
            relY < height / 3 -> 0 // Top
            relY < 2 * height / 3 -> 1 // Middle
            else -> 2 // Bottom
        }

        val resId = when (row to col) {
            0 to 2 -> R.string.grid_top_right
            0 to 1 -> R.string.grid_top_center
            0 to 0 -> R.string.grid_top_left
            1 to 2 -> R.string.grid_mid_right
            1 to 1 -> R.string.grid_center
            1 to 0 -> R.string.grid_mid_left
            2 to 2 -> R.string.grid_bottom_right
            2 to 1 -> R.string.grid_bottom_center
            2 to 0 -> R.string.grid_bottom_left
            else -> R.string.grid_center
        }
        return context.getString(resId)
    }

    private fun calculate4Zones(
        context: Context,
        relX: Int,
        relY: Int,
        width: Int,
        height: Int,
        isRtl: Boolean
    ): String {
        val isTop = relY < height / 2
        val isRight = if (isRtl) relX < width / 2 else relX >= width / 2

        val resId = when {
            isTop && isRight -> R.string.grid_top_right
            isTop && !isRight -> R.string.grid_top_left
            !isTop && isRight -> R.string.grid_bottom_right
            else -> R.string.grid_bottom_left
        }
        return context.getString(resId)
    }

    /**
     * التحقق مما إذا كان يجب نطق الموضع بحسب النمط المختار.
     */
    fun shouldAnnounce(zone: String, mode: GridMode): Boolean {
        return when (mode) {
            GridMode.ALWAYS -> true
            GridMode.ON_CHANGE -> {
                val should = (zone != lastAnnouncedZone)
                lastAnnouncedZone = zone
                should
            }
            GridMode.ON_DEMAND -> false
        }
    }

    fun onDemandZone(
        context: Context,
        elementBounds: Rect,
        windowBounds: Rect,
        isRtl: Boolean = true
    ): String {
        val zone = calculateZone(context, elementBounds, windowBounds, isRtl)
        lastAnnouncedZone = zone
        return zone
    }

    @androidx.annotation.VisibleForTesting
    fun resetLastZoneForTesting() {
        lastAnnouncedZone = null
    }
}
