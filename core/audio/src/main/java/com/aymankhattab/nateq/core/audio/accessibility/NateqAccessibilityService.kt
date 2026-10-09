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

import android.accessibilityservice.AccessibilityButtonController
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

/**
 * واجهة معالج ميزة إمكانية الوصول — كل معالج يغلف صنفاً قائماً
 * ويتحمّل مسئولية نوع حدث معين + مفتاح ميزة.
 */
interface A11yFeatureHandler {
    val feature: String
    val acceptedEventTypes: Int
    fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    )
}

/**
 * معالج تتبع التطبيق الأمامي — يعالج TYPE_WINDOW_STATE_CHANGED.
 */
object ForegroundTracker : A11yFeatureHandler {
    override val feature = "foreground_tracking"
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        if (!pkg.isNullOrBlank() && !AppPolicy.isSystemSensitive(pkg)) {
            AccessibilityBridge.updateForeground(pkg, cls)
        }
    }
}

/**
 * معالج صدى الكتابة — يعالج TYPE_VIEW_TEXT_CHANGED.
 */
object TypingEchoHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_TYPING_ECHO
    override val acceptedEventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val pkg = event.packageName?.toString()
        val echoText = TypingEcho.getEchoText(
            event.beforeText,
            event.text?.map { it.toString() }?.joinToString("") ?: "",
            TypingEcho.EchoMode.CHARACTERS
        )
        if (!echoText.isNullOrBlank()) {
            A11ySpeechCoordinator.speakAnnouncement(
                context = service,
                text = echoText,
                isInterruptAllowed = true
            )
        }
    }
}

/**
 * معالج توسيع قراءة المزيد — يسجل نوع الحدث فقط.
 */
object ReadMoreHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_READ_MORE_EXPAND
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        // التوسيع الفعلي يتم عبر AccessibilityBridge عند الطلب
    }
}

/**
 * معالج قراءة المحادثات الحية — يغلف ChatLiveReader.
 */
object ChatLiveHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_CHAT_LIVE_READING
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val pkg = event.packageName?.toString()
        val source = event.source ?: return
        try {
            val text = source.text?.map { it.toString() }
                ?.joinToString("") ?: ""
            if (text.isNullOrBlank()) return
            val context = service.applicationContext
            val repo = SettingsRepository.create(context)
            val isFeatureActive = FeatureFlags.isActive(
                FeatureFlags.FEATURE_CHAT_LIVE_READING,
                packageName = pkg,
                context = context,
                settings = settings
            )
            if (!isFeatureActive) return
            val sender = source.packageName?.toString() ?: ""
            val messageBody = source.text?.map { it.toString() }
                ?.joinToString("") ?: ""
            ChatLiveReader.getInstance().processIncomingMessage(
                context = context,
                sender = sender,
                messageBody = messageBody,
                packageName = pkg
            )
        } finally {
            SafeNodeWalker.safeRecycle(source)
        }
    }
}

/**
 * معالج النوافذ المنبثقة — يغلف PopupAnnouncer.
 */
object PopupHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_POPUP_ANNOUNCEMENT
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val pkg = event.packageName?.toString()
        val source = event.source ?: return
        try {
            val isPopup = source.windowId > 0 &&
                source.windowId != Int.MAX_VALUE
            if (!isPopup) return
            val title = source.packageName?.toString() ?: ""
            val message = source.text?.map { it.toString() }
                ?.joinToString("") ?: ""
            if (message.isNullOrBlank()) return
            PopupAnnouncer.announcePopup(
                context = service.applicationContext,
                title = title,
                message = message
            )
        } finally {
            SafeNodeWalker.safeRecycle(source)
        }
    }
}

/**
 * معالج مراقبة التقدم — يغلف ProgressMonitor.
 */
object ProgressHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_PROGRESS_MONITORING
    override val acceptedEventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        // ProgressMonitor يُستدعى من خارج (مستمع تقدم التنزيل)
    }
}

/**
 * معالج العناصر غير المسماة — يغلف UnlabeledElementStore.
 */
object UnlabeledLabelsHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_CUSTOM_UNLABELED
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        // التسميات المخصصة تُستعلم عند الحاجة من UnlabeledElementStore
    }
}

/**
 * خدمة إمكانية الوصول وقراءة الشاشة لتحسين تجربة النطق (ع٢٧-1).
 * - تعمل في العملية :tts لتشارك الكائنات ومحرك النطق مباشرة.
 * - تضبط serviceInfo ديناميكياً (ع٢٧-6) لتصفير المعالج عند الإيقاف.
 * - خصوصية تامة دون تسجيل نصوص الشاشة في release (ع٢٧-11).
 */
class NateqAccessibilityService : AccessibilityService() {

    private var stateStore: A11yStateStore? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var keyShortcutRouter: KeyShortcutRouter? = null

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private val emergencyHandler = Handler(Looper.getMainLooper())
    private val emergencyRunnable = Runnable { triggerEmergencyToggle() }

    // ع٢٩-16: مرجع المستمع لإزالته في onDestroy
    private var a11yStateListener: ((A11yStateStore) -> Unit)? = null
    private var accessibilityButtonCallback:
        AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val handlers: List<A11yFeatureHandler> = listOf(
        ForegroundTracker,
        TypingEchoHandler,
        ReadMoreHandler,
        ChatLiveHandler,
        PopupHandler,
        ProgressHandler,
        UnlabeledLabelsHandler
    )

    override fun onCreate() {
        super.onCreate()
        stateStore = A11yStateStore.getInstance(this)
        registerScreenOffReceiver()

        // ع٢٩-15: تهيئة مراقب صحة الخدمة (دائمًا)
        AccessibilityHealthMonitor.checkHealth(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "[A11yService] Connected in process :tts")
        AccessibilityBridge.registerService(this)
        stateStore?.setConnected(true)

        // ع٢٩-15: تهيئة مراقب صحة الخدمة
        AccessibilityHealthMonitor.checkHealth(this)

        // ع٢٩-15: تهيئة متحكم الإيقاف الذكي (FEATURE_SMART_PAUSE_CALL/GAME)
        val repo = SettingsRepository.create(this)
        val isCallPause = FeatureFlags.isActive(
            FeatureFlags.FEATURE_SMART_PAUSE_CALL,
            context = this,
            settings = repo,
            stateStore = stateStore
        )
        val isGamePause = FeatureFlags.isActive(
            FeatureFlags.FEATURE_SMART_PAUSE_GAME,
            context = this,
            settings = repo,
            stateStore = stateStore
        )
        if (isCallPause || isGamePause) {
            A11ySmartPauseController.getInstance(this)
        }

        // ع٢٩-16: خزن المستمع لإزالته لاحقاً
        a11yStateListener = { store: A11yStateStore ->
            applyServiceInfo()
        }
        stateStore?.addListener(a11yStateListener!!)

        applyServiceInfo()
        registerAccessibilityButton()

        // ع٢٩-13: تهيئة موجّه الاختصارات
        keyShortcutRouter = KeyShortcutRouter(
            service = this,
            settings = SettingsRepository.create(this),
            stateStore = stateStore
        )
    }

    private fun registerAccessibilityButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val btnController = accessibilityButtonController
            accessibilityButtonCallback = object :
                AccessibilityButtonController.
                AccessibilityButtonCallback() {
                override fun onClicked(
                    controller: AccessibilityButtonController?
                ) {
                    WhereAmIController.announceCurrentLocation(
                        this@NateqAccessibilityService
                    )
                }
            }
            btnController.registerAccessibilityButtonCallback(
                accessibilityButtonCallback!!
            )
        }
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

        // TYPE_WINDOW_STATE_CHANGED — لتتبع المقدمة والنوافذ المنبثقة
        types = types or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // TYPE_NOTIFICATION_STATE_CHANGED — للإشعارات والتوست (ع٢٩-11)
        types = types or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

        // TYPE_VIEW_SCROLLED — لمراقبة أشرطة التقدم والقراءة المستمرة (ع٢٩-11)
        types = types or AccessibilityEvent.TYPE_VIEW_SCROLLED

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

        // ع٢٩-11: توزيع الحدث عبر الموجّه المركزي
        val pkg = event.packageName?.toString()
        val repo = SettingsRepository.create(this)
        for (h in handlers) {
            if ((h.acceptedEventTypes and event.eventType) != 0 &&
                FeatureFlags.isActive(
                    h.feature,
                    packageName = pkg,
                    context = this,
                    settings = repo,
                    stateStore = stateStore
                )
            ) {
                runCatching { h.handle(this, event, repo, stateStore) }
                    .onFailure {
                        Log.w(TAG, "${h.javaClass.simpleName} failed", it)
                    }
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "[A11yService] onInterrupt called")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // ع٢٩-13: تفويض اختصارات المفاتيح لـ KeyShortcutRouter
        val consumedByRouter = keyShortcutRouter?.onKeyEvent(event) == true
        if (consumedByRouter) return true

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

        // ع٢٩-16: إعلان صوتي بدلاً من الاهتزاز فقط (المستخدم كفيف)
        val announcement = if (newPaused) {
            "الخدمة موقوفة"
        } else {
            "الخدمة تعمل"
        }
        A11ySpeechCoordinator.speakAnnouncement(
            context = this,
            text = announcement,
            isInterruptAllowed = true
        )

        // اهتزاز تأكيدي إضافي
        runCatching {
            val vibrator = getSystemService(Vibrator::class.java)
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

        val repo = SettingsRepository.create(this)
        val store = stateStore

        // صدى الكتابة (ع٢٧-31)
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_TYPING_ECHO,
                packageName = pkg,
                context = this,
                settings = repo,
                stateStore = store
            )
        ) {
            val echoText = TypingEcho.getEchoText(
                event.beforeText,
                event.text?.joinToString(""),
                TypingEcho.EchoMode.CHARACTERS
            )
            if (!echoText.isNullOrBlank()) {
                A11ySpeechCoordinator.speakAnnouncement(
                    context = this,
                    text = echoText,
                    isInterruptAllowed = true
                )
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
     * تنفيذ توسيع قراءة المزيد (ع٢٧-20، ع٢٧-21، ع٢٩-12).
     * - يتحقق أن العنصر المفوكس هو نفسه الذي اقتُطع نصه
     *   (مطابقة أول 40 حرفاً من البادئة).
     * - بعد النقر ينتظر TYPE_WINDOW_CONTENT_CHANGED حتى 300ms
     *   قبل قراءة النص المحدث.
     */
    internal fun expandReadMoreInternal(prefix: String): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            // ع٢٩-12: تحقق أن العنصر المفوكس هو نفسه المقتطع
            val focusSnapshot = findCurrentAccessibilityFocusSnapshot()
            val focusedText = focusSnapshot?.readableText() ?: ""
            val prefixClean = prefix.trim()
            val focusedClean = focusedText.trim()
            // مطابقة أول 40 حرفاً للتحقق من نفس العنصر
            val matchLen = minOf(40, prefixClean.length, focusedClean.length)
            if (matchLen > 0 &&
                !prefixClean.substring(0, matchLen).equals(
                    focusedClean.substring(0, matchLen),
                    ignoreCase = true
                )
            ) {
                return null // عنصر مختلف — لا تُوسّع
            }

            val expanded = ReadMoreExpander.tryExpand(root)
            if (!expanded) return null

            // ع٢٩-12: انتظر TYPE_WINDOW_CONTENT_CHANGED حتى 300ms
            // قبل قراءة النص المحدث
            var waited = 0L
            val startWait = System.currentTimeMillis()
            while (waited < 300L) {
                Thread.sleep(50)
                waited = System.currentTimeMillis() - startWait
            }

            findCurrentAccessibilityFocusSnapshot()?.readableText()
        } finally {
            SafeNodeWalker.safeRecycle(root)
        }
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

        // ع٢٩-16: إزالة مستمع حالة الخدمة
        a11yStateListener?.let { listener ->
            stateStore?.removeListener(listener)
        }
        a11yStateListener = null

        // ع٢٩-16: إلغاء تسجيل زر إمكانية الوصول
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonCallback?.let { callback ->
                accessibilityButtonController
                    ?.unregisterAccessibilityButtonCallback(callback)
            }
        }

        AccessibilityBridge.unregisterService(this)
        stateStore?.setConnected(false)
        keyShortcutRouter?.shutdown()
        stateStore = null
    }

    companion object {
        private const val TAG = "NATEQ_A11Y"
    }
}
