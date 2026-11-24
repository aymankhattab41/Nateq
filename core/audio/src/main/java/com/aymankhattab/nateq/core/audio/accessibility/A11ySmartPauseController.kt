package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تحكم الإيقاف المؤقت الذكي لخدمات الشاشة (ع٢٧-18).
 * يوقف الخدمة مؤقتاً تلقائياً في الحالات التالية ويعيدها عند زوال السبب:
 * 1. أثناء المكالمة الهاتفية النشطة (إن فُعّل).
 * 2. أثناء تشغيل الألعاب وتطبيقات ملء الشاشة (إن فُعّل).
 * 3. عند قفل الشاشة أو التطبيقات الحساسة أو انخفاض البطارية.
 */
class A11ySmartPauseController private constructor(
    private val appContext: Context
) {

    private val activeReasons = CopyOnWriteArraySet<String>()
    private val isSmartPaused = AtomicBoolean(false)
    private val smartPauseApplied = AtomicBoolean(false)
    private var phoneReceiver: BroadcastReceiver? = null

    private val settingsObserver = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            evaluateSmartPause()
        }
    }

    init {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                SettingsChangeProvider.uri(),
                true,
                settingsObserver
            )
        }
        A11yStateStore.getInstance(appContext)?.addListener { store ->
            if (!store.isPaused() && isSmartPaused.get()) {
                smartPauseApplied.set(false)
            }
        }
    }

    fun setReasonActive(reason: String, active: Boolean) {
        if (active) {
            activeReasons.add(reason)
        } else {
            activeReasons.remove(reason)
        }
        evaluateSmartPause()
    }

    fun isReasonActive(reason: String): Boolean =
        activeReasons.contains(reason)

    fun hasAnyActiveReason(): Boolean =
        activeReasons.isNotEmpty()

    fun isSmartPaused(): Boolean = isSmartPaused.get()

    private fun isReasonEnabledInSettings(reason: String): Boolean {
        val repo = SettingsRepository.create(appContext)
        return when (reason) {
            REASON_IN_CALL -> repo.isAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_SMART_PAUSE_CALL
            )
            REASON_GAME, REASON_FULLSCREEN_VIDEO -> {
                repo.isAccessibilityFeatureEnabled(
                    FeatureFlags.FEATURE_SMART_PAUSE_GAME
                )
            }
            else -> true
        }
    }

    fun evaluateSmartPause() {
        val shouldPause = activeReasons.any { isReasonEnabledInSettings(it) }
        val store = A11yStateStore.getInstance(appContext)

        if (shouldPause) {
            if (isSmartPaused.compareAndSet(false, true)) {
                if (store != null) {
                    if (store.isPaused()) {
                        smartPauseApplied.set(false)
                    } else {
                        smartPauseApplied.set(true)
                        store.setPaused(true)
                    }
                }
            }
        } else {
            if (isSmartPaused.compareAndSet(true, false)) {
                if (smartPauseApplied.compareAndSet(true, false)) {
                    if (store != null && store.isPaused()) {
                        store.setPaused(false)
                    }
                }
            }
        }
    }

    fun registerPhoneStateReceiver() {
        if (phoneReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action ==
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED
                ) {
                    val state = intent.getStringExtra(
                        TelephonyManager.EXTRA_STATE
                    )
                    val inCall = state ==
                        TelephonyManager.EXTRA_STATE_OFFHOOK ||
                        state == TelephonyManager.EXTRA_STATE_RINGING
                    setReasonActive(REASON_IN_CALL, inCall)
                }
            }
        }
        phoneReceiver = receiver
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        runCatching {
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun unregisterPhoneStateReceiver() {
        phoneReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
            phoneReceiver = null
        }
    }

    companion object {
        const val REASON_IN_CALL = "reason_in_call"
        const val REASON_GAME = "reason_game"
        const val REASON_FULLSCREEN_VIDEO = "reason_fullscreen_video"
        const val REASON_SCREEN_LOCKED = "reason_screen_locked"
        const val REASON_SENSITIVE_APP = "reason_sensitive_app"
        const val REASON_LOW_BATTERY = "reason_low_battery"

        @Volatile
        private var instance: A11ySmartPauseController? = null

        fun getInstance(context: Context): A11ySmartPauseController {
            return instance ?: synchronized(this) {
                instance ?: A11ySmartPauseController(
                    context.applicationContext
                ).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance = null
        }

        fun isGamePackage(context: Context, packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            if (packageName == context.packageName ||
                packageName == "android"
            ) {
                return false
            }
            return runCatching {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val isGameCategory = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    false
                }
                @Suppress("DEPRECATION")
                val isGameFlag = (
                    appInfo.flags and ApplicationInfo.FLAG_IS_GAME
                ) != 0
                isGameCategory || isGameFlag
            }.getOrDefault(false)
        }
    }
}
