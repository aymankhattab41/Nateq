package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.data.SettingsRepository

import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

/**
 * خدمة إمكانية الوصول وقراءة الشاشة لتحسين تجربة النطق (ع٢٧-1).
 * - تعمل في العملية :tts لتشارك الكائنات ومحرك النطق مباشرة.
 * - تضبط serviceInfo ديناميكياً (ع٢٧-6) لتصفير المعالج عند الإيقاف.
 * - خصوصية تامة دون تسجيل نصوص الشاشة في release (ع٢٧-11).
 */
class NateqAccessibilityService : AccessibilityService() {

    private var dispatcher: A11yEventDispatcher? = null
    private var stateStore: A11yStateStore? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private val emergencyHandler = Handler(Looper.getMainLooper())
    private val emergencyRunnable = Runnable { triggerEmergencyToggle() }

    override fun onCreate() {
        super.onCreate()
        stateStore = A11yStateStore.getInstance(this)
        dispatcher = A11yEventDispatcher { event ->
            handleEventInBackground(event)
        }
        registerScreenOffReceiver()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "[A11yService] Connected in process :tts")
        AccessibilityBridge.registerService(this)
        stateStore?.setConnected(true)
        stateStore?.addListener {
            applyServiceInfo()
        }
        applyServiceInfo()
    }

    /**
     * ضبط serviceInfo ديناميكياً (ع٢٧-6).
     * عند الإيقاف المؤقت أو تعطيل الميزات يُضبط eventTypes = 0 لتصفير الحمل.
     */
    fun applyServiceInfo() {
        val store = stateStore ?: return
        val info = serviceInfo ?: AccessibilityServiceInfo()

        if (!store.isConnected() || store.isPaused()) {
            info.eventTypes = 0
            info.packageNames = null
            serviceInfo = info
            return
        }

        var types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val repo = SettingsRepository.create(this)
        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_MORE_EXPAND,
                context = this,
                settings = repo,
                stateStore = store
            )
        ) {
            types = types or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }

        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_CHAT_LIVE_READING,
                context = this,
                settings = repo,
                stateStore = store
            )
        ) {
            types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }

        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_TYPING_ECHO,
                context = this,
                settings = repo,
                stateStore = store
            )
        ) {
            types = types or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        }

        info.eventTypes = types
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50L
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        val allowAll = repo.isA11yAllowAllApps()
        if (!allowAll) {
            val userPackages = repo.getA11yAllowedPackages()
            val effectiveWhitelist = userPackages ?: AppPolicy.DEFAULT_WHITELIST
            info.packageNames = effectiveWhitelist.toTypedArray()
        } else {
            info.packageNames = null
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val store = stateStore
        if (store != null && store.isPaused()) return

        dispatcher?.dispatch(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "[A11yService] onInterrupt called")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        isVolUpPressed = true
                    }
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        isVolDownPressed = true
                    }
                    if (isVolUpPressed && isVolDownPressed) {
                        emergencyHandler.removeCallbacks(emergencyRunnable)
                        emergencyHandler.postDelayed(emergencyRunnable, 2000L)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        isVolUpPressed = false
                    }
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        isVolDownPressed = false
                    }
                    emergencyHandler.removeCallbacks(emergencyRunnable)
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerEmergencyToggle() {
        val store = stateStore ?: return
        val currentlyPaused = store.isPaused()
        val newPaused = !currentlyPaused
        store.setPaused(newPaused)

        // اهتزاز تأكيدي
        runCatching {
            val vibrator =
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        200L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200L)
            }
        }
    }

    /**
     * معالجة أحداث إمكانية الوصول على خيط الخلفية.
     */
    private fun handleEventInBackground(event: AccessibilityEvent) {
        val eventType = event.eventType
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // تتبع التطبيق والنشاط الأمامي (ع٢٧-12)
            if (!pkg.isNullOrBlank() && !AppPolicy.isSystemSensitive(pkg)) {
                AccessibilityBridge.updateForeground(pkg, cls)
            }
        }
    }

    /**
     * العثور على العنصر المركّز عليه حالياً وإعادته كـ NodeView مسطح.
     */
    fun findCurrentAccessibilityFocusSnapshot(): NodeView? {
        val root = rootInActiveWindow ?: return null
        return try {
            val focused =
                root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                try {
                    SafeNodeWalker.extractNodeView(focused)
                } finally {
                    SafeNodeWalker.safeRecycle(focused)
                }
            } else {
                null
            }
        } finally {
            SafeNodeWalker.safeRecycle(root)
        }
    }

    /**
     * تنفيذ توسيع قراءة المزيد (ع٢٧-20، ع٢٧-21)
     */
    internal fun expandReadMoreInternal(prefix: String): String? {
        // سيتم التوسيع في أمر ع٢٧-20
        return null
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // مسح المخازن المؤقتة عند إطفاء الشاشة (ع٢٧-11)
                    AccessibilityBridge.updateLastScreenTexts(emptyList())
                }
            }
        }
        screenOffReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            runCatching { unregisterReceiver(it) }
            screenOffReceiver = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[A11yService] Destroyed")
        unregisterScreenOffReceiver()
        AccessibilityBridge.unregisterService(this)
        stateStore?.setConnected(false)
        dispatcher?.shutdown()
        dispatcher = null
    }

    companion object {
        private const val TAG = "NATEQ_A11Y"
    }
}
