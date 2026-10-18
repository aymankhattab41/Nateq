package com.aymankhattab.nateq.core.audio.input

import android.util.Log

/**
 * مصادر أحداث التأكيد للوحة المفاتيح ولوحة الاتصال.
 */
enum class Source {
    HOVER_EXIT,
    ACCESSIBILITY_CLICK,
    RAW_UP
}

/**
 * بوابة تأكيد المفاتيح الموحدة لمنع الكتابة عند اللمس وتكرار الأحرف.
 */
class CommitGate(private val mode: () -> String) {

    private val lastCommitTimePerKey = mutableMapOf<Any, Long>()
    private val lastHoverExitTimePerKey = mutableMapOf<Any, Long>()
    private var hoverEnterTimeMs: Long = 0L
    private var isMultiTouchActive: Boolean = false

    fun onHoverEnter(nowMs: Long) {
        hoverEnterTimeMs = nowMs
    }

    fun setMultiTouchActive(active: Boolean) {
        isMultiTouchActive = active
    }

    fun resetState(clearHistory: Boolean = false) {
        hoverEnterTimeMs = 0L
        isMultiTouchActive = false
        if (clearHistory) {
            lastCommitTimePerKey.clear()
            lastHoverExitTimePerKey.clear()
        }
    }

    fun shouldCommit(
        key: Any,
        source: Source,
        nowMs: Long,
        exitedInsideKey: Boolean = true
    ): Boolean {
        val currentMode = mode()
        val isLift = currentMode.endsWith("lift", ignoreCase = true)
        val lastHoverExit = lastHoverExitTimePerKey[key] ?: 0L
        val deltaFromHoverExit = if (lastHoverExit > 0L) {
            nowMs - lastHoverExit
        } else {
            Long.MAX_VALUE
        }
        val lastCommit = lastCommitTimePerKey[key] ?: 0L
        val deltaFromCommit = if (lastCommit > 0L) {
            nowMs - lastCommit
        } else {
            Long.MAX_VALUE
        }

        val allowed: Boolean

        if (isLift) {
            when (source) {
                Source.HOVER_EXIT, Source.RAW_UP -> {
                    val dwellTime = nowMs - hoverEnterTimeMs
                    val condition = !isMultiTouchActive &&
                        exitedInsideKey &&
                        dwellTime >= 40L &&
                        deltaFromCommit >= 350L
                    if (source == Source.HOVER_EXIT) {
                        lastHoverExitTimePerKey[key] = nowMs
                    }
                    allowed = condition
                }
                Source.ACCESSIBILITY_CLICK -> {
                    allowed = deltaFromCommit >= 700L
                }
            }
        } else {
            when (source) {
                Source.HOVER_EXIT, Source.RAW_UP -> {
                    if (source == Source.HOVER_EXIT) {
                        lastHoverExitTimePerKey[key] = nowMs
                    }
                    allowed = false
                }
                Source.ACCESSIBILITY_CLICK -> {
                    val wasTouchTypingHover = deltaFromHoverExit in 0L..120L
                    allowed = !wasTouchTypingHover && deltaFromCommit >= 350L
                }
            }
        }

        if (allowed) {
            lastCommitTimePerKey[key] = nowMs
        }

        Log.i(
            TAG,
            "[CommitGate] key=$key, source=$source, mode=$currentMode, " +
                "deltaHover=${deltaFromHoverExit}ms, " +
                "inside=$exitedInsideKey, " +
                "multiTouch=$isMultiTouchActive, res=$allowed"
        )

        return allowed
    }

    companion object {
        private const val TAG = "CommitGate"
    }
}
