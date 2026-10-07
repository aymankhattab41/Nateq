package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * مخزن حالة خدمة إمكانية الوصول المشترك بين العمليات (ع٢٧-2).
 * - يخزّن: connected, paused, pausedUntilEpoch, pauseLevel.
 * - يقرأ من الذاكرة فورياً دون حظر الخيط الرئيسي.
 * - يتزامن عبر ContentObserver مع SettingsChangeProvider.
 */
class A11yStateStore private constructor(private val appContext: Context) {

    private val settings = SettingsRepository.create(appContext)

    private val isConnected = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val pausedUntilEpoch = AtomicLong(0L)
    private val pauseLevel = AtomicInteger(1)

    private val listeners = CopyOnWriteArrayList<(A11yStateStore) -> Unit>()

    private val observer = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            reloadFromSettings()
        }
    }

    init {
        reloadFromSettings()
        runCatching {
            appContext.contentResolver.registerContentObserver(
                SettingsChangeProvider.uri(),
                true,
                observer
            )
        }
    }

    /**
     * إعادة قراءة الحالة من المستودع وتحديث القيم الذرية.
     */
    fun reloadFromSettings() {
        val paused = settings.isA11yPaused()
        val until = settings.getA11yPausedUntil()
        val level = settings.getA11yPauseLevel()

        val now = System.currentTimeMillis()
        val effectivePaused = if (paused && until > 0L && now >= until) {
            // انقضت مدة الإيقاف المؤقت، استئناف تلقائي
            setPaused(false, 0L, level)
            false
        } else {
            paused
        }

        isPaused.set(effectivePaused)
        pausedUntilEpoch.set(until)
        pauseLevel.set(level)

        notifyListeners()
    }

    fun isConnected(): Boolean = isConnected.get()

    fun setConnected(connected: Boolean) {
        if (isConnected.getAndSet(connected) != connected) {
            notifyListeners()
        }
    }

    fun isPaused(): Boolean = isPaused.get()

    fun getPausedUntilEpoch(): Long = pausedUntilEpoch.get()

    fun getPauseLevel(): Int = pauseLevel.get()

    fun setPaused(paused: Boolean, untilEpoch: Long = 0L, level: Int = 1) {
        isPaused.set(paused)
        pausedUntilEpoch.set(untilEpoch)
        pauseLevel.set(level)

        settings.setA11yPausedState(paused, untilEpoch, level)

        notifyListeners()
    }

    fun addListener(listener: (A11yStateStore) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (A11yStateStore) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it(this) } }
    }

    companion object {
        const val KEY_A11Y_PAUSED = "a11y_service_paused"
        const val KEY_A11Y_PAUSED_UNTIL = "a11y_service_paused_until_epoch"
        const val KEY_A11Y_PAUSE_LEVEL = "a11y_service_pause_level"

        @Volatile
        private var instance: A11yStateStore? = null

        fun getInstance(context: Context?): A11yStateStore? {
            if (context == null) return instance
            return instance ?: synchronized(this) {
                instance ?: A11yStateStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
