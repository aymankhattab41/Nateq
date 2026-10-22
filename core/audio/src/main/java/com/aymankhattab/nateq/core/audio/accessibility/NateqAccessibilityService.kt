package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository

import android.accessibilityservice.AccessibilityButtonController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
        if (pkg.isNullOrBlank() || AppPolicy.isSystemSensitive(pkg)) return

        // تجاهل نوافذ لوحات المفاتيح وتراكب النظام وقارئ الشاشة (ع٤٥)
        if (pkg.contains("talkback", ignoreCase = true) ||
            pkg.contains("accessibility", ignoreCase = true)
        ) {
            return
        }
        if (pkg == service.packageName) return

        val isIgnoredWindow = runCatching {
            val winId = event.windowId
            val win = service.windows?.firstOrNull { it.id == winId }
            win?.type == android.view.accessibility.AccessibilityWindowInfo
                .TYPE_INPUT_METHOD ||
                win?.type == android.view.accessibility.AccessibilityWindowInfo
                    .TYPE_SYSTEM ||
                win?.type == android.view.accessibility.AccessibilityWindowInfo
                    .TYPE_ACCESSIBILITY_OVERLAY
        }.getOrDefault(false)

        if (isIgnoredWindow) return

        AccessibilityBridge.updateForeground(pkg, cls)
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
 * معالج توسيع قراءة المزيد (ع٤٣-1).
 * يستقبل أحداث التركيز وتغير المحتوى لإشعار الجسر فورياً بالتحديثات.
 */
object ReadMoreHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_READ_MORE_EXPAND
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                AccessibilityBridge.notifyContentChanged()
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                AccessibilityBridge.notifyContentChanged()
            }
        }
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
 * معالج ملخّص المحادثة عند الدخول (ع٤١-ب5) — يغلف [ChatSummaryTracker].
 * عند فتح شاشة محادثة في تطبيق دردشة مسموح: يجمع رسائل الشاشة،
 * يحسب الجديدة منذ آخر زيارة، وينطق جملة الملخص مرة واحدة.
 */
object ChatSummaryHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_CHAT_SUMMARY
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val pkg = event.packageName?.toString()
        if (!AppPolicy.isChatPackage(pkg)) return
        val svc = service as? NateqAccessibilityService ?: return
        val summary = svc.summarizeChat(
            packageName = pkg!!,
            chatScreen = event.className?.toString()
        ) ?: return
        // ع٤١-ب5.3: لا تُنطق نصوص الرسائل — العدد والأسماء فقط.
        val text = ChatSummarizer.summarize(
            summary.senders,
            summary.messageCount
        )
        if (text.isBlank()) return
        A11ySpeechCoordinator.speakAnnouncement(
            context = service,
            text = text,
            isInterruptAllowed = true
        )
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
    private var protectedDisableReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var shortcutTestReceiver: BroadcastReceiver? = null
    private var bindingsObserver: ContentObserver? = null
    private var keyShortcutRouter: KeyShortcutRouter? = null

    // ع٤١-ب9: بوّابة تثبيط أحداث البلوتوث ومسارات الصوت
    private val deviceEventGate = BluetoothEventGate()

    // ع٤١-ب5: متتبّع ملخّصات المحادثات (ذاكرة فقط)
    private val chatSummaryTracker = ChatSummaryTracker()

    // ع٤١-ب8: كاشف أوامر المكالمة بالمفاتيح وبوّابة تنفيذها
    private val callKeyDetector = CallKeyDetector()
    private val callGateway: CallGateway by lazy {
        AndroidCallGateway(applicationContext)
    }

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var lastContentChangeAt = 0L
    private val emergencyHandler = Handler(Looper.getMainLooper())
    private val emergencyRunnable = Runnable { triggerEmergencyToggle() }

    @Volatile
    var lastFocusedPackage: String? = null
        internal set

    // ع٢٩-16: مرجع المستمع لإزالته في onDestroy
    private var a11yStateListener: ((A11yStateStore) -> Unit)? = null
    private var accessibilityButtonCallback:
        AccessibilityButtonController.AccessibilityButtonCallback? = null

    // ع٤٣-1: نطاق كوروتينات الخدمة لتنفيذ إيماءات إمكانية الوصول غير الحاجزة
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val handlers: List<A11yFeatureHandler> = listOf(
        ForegroundTracker,
        TypingEchoHandler,
        ReadMoreHandler,
        ChatLiveHandler,
        ChatSummaryHandler,
        PopupHandler,
        ProgressHandler,
        UnlabeledLabelsHandler
    )

    override fun onCreate() {
        super.onCreate()
        stateStore = A11yStateStore.getInstance(this)
        registerScreenOffReceiver()
        registerProtectedDisableReceiver()
        registerBluetoothReceiver()
        registerAudioRouteCallback()

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
            if (store.isPaused()) ContinuousReader.stopSilently()
            applyServiceInfo()
        }
        stateStore?.addListener(a11yStateListener!!)

        applyServiceInfo()
        applyAccessibilityButton()
        registerBluetoothReceiver()

        // ع٢٩-13: تهيئة موجّه الاختصارات
        keyShortcutRouter = KeyShortcutRouter(
            service = this,
            settings = SettingsRepository.create(this),
            stateStore = stateStore
        )
        registerShortcutTestReceiver()
        registerBindingsObserver()
    }

    /** ع٤١-ب1.6: يستقبل طلب «اختبار» إيماءةٍ من شاشة الإعدادات. */
    private fun registerShortcutTestReceiver() {
        if (shortcutTestReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != KeyShortcutRouter.ACTION_TEST_SHORTCUT) {
                    return
                }
                val name = intent.getStringExtra(
                    KeyShortcutRouter.EXTRA_GESTURE
                )
                val gesture = ShortcutGesture.fromName(name) ?: return
                keyShortcutRouter?.reloadBindings()
                keyShortcutRouter?.executeGesture(gesture)
            }
        }
        shortcutTestReceiver = receiver
        val filter = IntentFilter(KeyShortcutRouter.ACTION_TEST_SHORTCUT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /** ع٤١-ب1.6: يعيد تحميل جدول الربط عند تغيّره من الإعدادات. */
    private fun registerBindingsObserver() {
        if (bindingsObserver != null) return
        val observer = object : ContentObserver(
            Handler(Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                keyShortcutRouter?.reloadBindings()
                applyAccessibilityButton()
                // ع٤١-ب9: إعادة محاولة تسجيل مستقبل البلوتوث بعد منح الإذن
                registerBluetoothReceiver()
            }
        }
        bindingsObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                SettingsChangeProvider.uri(),
                true,
                observer
            )
        }
    }

    /**
     * ع٤١-ب2: يسجّل مستمع زر إمكانية الوصول أو يزيله بحسب المفتاح
     * والتوفّر — عند النقر تظهر قائمة الإجراءات السريعة ([A11yQuickMenu])
     * بنفس مسار الإيماءات ([KeyShortcutRouter.executeAction]).
     */
    private fun applyAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val controller = accessibilityButtonController ?: return
        val active = FeatureFlags.isActive(
            FeatureFlags.FEATURE_ACCESSIBILITY_BUTTON,
            context = this,
            settings = SettingsRepository.create(this),
            stateStore = stateStore
        )
        val available = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            controller.isAccessibilityButtonAvailable
        val shouldRegister = AccessibilityButtonGate.shouldRegister(
            featureActive = active,
            sdkInt = Build.VERSION.SDK_INT,
            buttonAvailable = available
        )
        val registered = accessibilityButtonCallback != null
        if (shouldRegister && !registered) {
            val callback = object :
                AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(
                    controller: AccessibilityButtonController?
                ) {
                    A11yQuickMenu.toggle(this@NateqAccessibilityService) {
                        keyShortcutRouter?.executeAction(it)
                    }
                }
            }
            accessibilityButtonCallback = callback
            controller.registerAccessibilityButtonCallback(callback)
        } else if (!shouldRegister && registered) {
            accessibilityButtonCallback?.let {
                runCatching {
                    controller.unregisterAccessibilityButtonCallback(it)
                }
            }
            accessibilityButtonCallback = null
        }
    }

    /**
     * ضبط serviceInfo ديناميكياً (ع٢٧-6).
     * عند الإيقاف المؤقت أو تعطيل الميزات يُضبط eventTypes = 0 لتصفير الحمل.
     */
    fun applyServiceInfo() {
        val store = stateStore ?: return
        val info = serviceInfo ?: AccessibilityServiceInfo()

        if (AppPolicy.isAutomationActive()) {
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.packageNames = null
            serviceInfo = info
            return
        }

        if (!store.isConnected() ||
            store.isPaused() ||
            ProtectedAppManager.isPartiallySuspended
        ) {
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

        // التطبيقات المحمية: نقرة أيقونة المشغّل تسبق انطلاق التطبيق
        // بمقدار تشغيل Process كامل، فبها نُعطل الخدمة قبل شاشة البداية
        // — أما أحداث النافذة فتصل بعد بدء التطبيق ولا تمنع انهياره.
        val protectedApps = repo.getProtectedApps()
        if (protectedApps.isNotEmpty()) {
            types = types or AccessibilityEvent.TYPE_VIEW_CLICKED
        }

        info.eventTypes = types
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

        val liveReading = FeatureFlags.isActive(
            FeatureFlags.FEATURE_CHAT_LIVE_READING,
            context = this,
            settings = repo,
            stateStore = store
        )
        val readMore = FeatureFlags.isActive(
            FeatureFlags.FEATURE_READ_MORE_EXPAND,
            context = this,
            settings = repo,
            stateStore = store
        )
        val fastRequired = liveReading || readMore
        info.notificationTimeout = if (fastRequired) 50L else 150L

        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        val allowAll = repo.isA11yAllowAllApps()
        if (!allowAll) {
            val userPackages = repo.getA11yAllowedPackages()
            val effective = ProtectedAppManager.effectiveEventPackages(
                allowed = userPackages ?: AppPolicy.DEFAULT_WHITELIST,
                protectedApps = protectedApps,
                homeLauncher = ProtectedAppManager
                    .homeLauncherPackage(this)
            )
            info.packageNames = effective.toTypedArray()
        } else {
            info.packageNames = null
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        BatteryDiagnosticsTracker.recordAccessibilityEvent(event.eventType)

        if (AppPolicy.isAutomationActive()) {
            TtsSettingsAutomation.onAccessibilityEvent(event)
            return
        }
        val store = stateStore
        if (store != null && store.isPaused()) return

        val eventPkg = event.packageName?.toString()

        if (ProtectedAppTimelineTracker.isInQuietPeriod()) {
            if (ProtectedAppTimelineTracker.isQuietPackage(eventPkg)) {
                if (ProtectedAppTimelineTracker.isBiometricPackage(eventPkg)) {
                    val txt = event.text.joinToString(" ")
                    if (txt.contains("بصمة") ||
                        txt.contains("fingerprint") ||
                        txt.contains("تم") || txt.contains("خطأ") ||
                        txt.contains("حاول") ||
                        txt.contains("success") ||
                        txt.contains("error")
                    ) {
                        val success = !txt.contains("خطأ") &&
                            !txt.contains("error") &&
                            !txt.contains("حاول")
                        ProtectedAppTimelineTracker.recordBiometricFinished(
                            eventPkg.orEmpty(), success
                        )
                    }
                }
                return
            }
        }

        // خروج مبكر لأي حزمة غير مسموحة قبل أي عمل
        val isLauncher = event.eventType ==
            AccessibilityEvent.TYPE_VIEW_CLICKED &&
            ProtectedAppManager.isHomeLauncher(this, eventPkg)
        val isProtected = event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPkg != null &&
            SettingsRepository.create(this).isProtectedApp(eventPkg)
        val isOwnApp = eventPkg == packageName

        val repoSettings = SettingsRepository.create(this)
        val isAllowed = isLauncher || isProtected || isOwnApp ||
            AppPolicy.isAllowed(
                eventPkg,
                repoSettings.getA11yAllowedPackages(),
                repoSettings.isA11yAllowAllApps()
            )

        if (!isAllowed) return

        // ع٤١-ب0.4: خنق أحداث تغيّر محتوى النافذة (≥80ms) فلا تُغرق
        // الخيطَ الرئيسي بالمعالجة أثناء التمرير أو البثّ الحيّ.
        if (event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastContentChangeAt < CONTENT_CHANGE_THROTTLE_MS) {
                return
            }
            lastContentChangeAt = now
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType ==
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        ) {
            val focusedPkg = event.packageName?.toString()
            if (!focusedPkg.isNullOrBlank() &&
                !AccessibilityBridge.isIgnoredPackage(focusedPkg)
            ) {
                lastFocusedPackage = focusedPkg
            }
        }

        // الجولة 48 - الأمر 7: نقرة أيقونة التطبيق المحمي في المشغّل
        // تصل قبل انطلاقه — نعطل الخدمة هنا قبل شاشة البداية.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (ProtectedAppManager.isHomeLauncher(this, eventPkg)) {
                val candidate =
                    ProtectedAppManager.launcherCandidateLabel(event)
                val matched =
                    ProtectedAppManager.matchProtectedApp(this, candidate)
                if (matched != null) {
                    ProtectedAppTimelineTracker.recordLauncherClick(
                        matched.packageName
                    )
                    ProtectedAppTimelineTracker.recordLordDetected(
                        matched.packageName
                    )
                    ProtectedAppTimelineTracker.startQuietPeriod(
                        matched.packageName
                    )
                    ProtectedAppManager.handleAppEntry(this, this, matched)
                    return
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (ProtectedAppTimelineTracker.isInQuietPeriod()) {
                if (ProtectedAppTimelineTracker.isBiometricPackage(eventPkg)) {
                    ProtectedAppTimelineTracker.recordBiometricWindowAppeared(
                        eventPkg.orEmpty()
                    )
                    return
                }
            }
            val repoSettings = SettingsRepository.create(this)
            val matched = eventPkg?.let { repoSettings.getProtectedApp(it) }
            if (matched != null) {
                ProtectedAppTimelineTracker.recordLordDetected(
                    matched.packageName
                )
                ProtectedAppTimelineTracker.recordAppWindowAppeared(
                    matched.packageName
                )
                ProtectedAppTimelineTracker.startQuietPeriod(
                    matched.packageName
                )
                // النافذة ظهرت أصلاً: التعطيل يأتي متأخراً عن شاشة
                // البداية، فنعيد فتح التطبيق بعد التعطيل حتى لا ينهار.
                ProtectedAppManager.handleAppEntry(
                    context = this,
                    service = this,
                    app = matched,
                    relaunchIfForeground = true
                )
                return
            }
        }

        if (ProtectedAppTimelineTracker.isInQuietPeriod()) {
            return
        }

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

        // ع٤١-ب6: تقدّم القراءة المستمرة مع تمرير المحتوى أو تبديل النافذة
        if (ContinuousReader.isReading()) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    ContinuousReader.onContentChanged()
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    ContinuousReader.onWindowChanged(pkg)
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "[A11yService] onInterrupt called")
        ContinuousReader.stopSilently()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // ع٤١-ب8: أوامر المكالمة بالمفاتيح أثناء الرنين
        val volumeKey = volumeKeyOf(event.keyCode)
        if (volumeKey != null && isCallKeyActionsActive()) {
            val ringing = CallStateReader.isRinging(this)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (callKeyDetector.onDown(
                            volumeKey,
                            ringing,
                            event.eventTime
                        )
                    ) {
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val action = callKeyDetector.onUp(
                        volumeKey,
                        ringing,
                        event.eventTime,
                        isCallKeysSwapped()
                    )
                    if (action != null) {
                        performCallAction(action)
                        return true
                    }
                    if (ringing) return true
                }
            }
        }

        // ع٢٩-١٣: تفويض اختصارات المفاتيح لـ KeyShortcutRouter
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

    /** ع٤١-ب8: هل ميزة أوامر المكالمة بالمفاتيح مفعّلة؟ */
    private fun isCallKeyActionsActive(): Boolean = FeatureFlags.isActive(
        FeatureFlags.FEATURE_CALL_KEY_ACTIONS,
        context = this,
        settings = SettingsRepository.create(this),
        stateStore = stateStore
    )

    /** ع٤١-ب8: هل مُبدَّلٌ ربطُ الردّ/الرفض؟ (افتراضياً: صوت+ ردّ). */
    private fun isCallKeysSwapped(): Boolean =
        SettingsRepository.create(this).isA11yCallKeysSwapped()

    /** ع٤١-ب8: ينفّذ فعل المكالمة وينطق تأكيداً قصيراً. */
    private fun performCallAction(action: CallAction) {
        val success = CallActionInvoker.invoke(action, callGateway)
        val text = if (success) {
            CallActionInvoker.confirmationText(action)
        } else {
            "تعذّر تنفيذ الأمر — تحقّق من إذن المكالمة"
        }
        A11ySpeechCoordinator.speakAnnouncement(
            context = this,
            text = text,
            isInterruptAllowed = true
        )
    }

    private fun volumeKeyOf(keyCode: Int): VolumeKey? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
        else -> null
    }

    private fun triggerEmergencyToggle() {
        // ع٤١-ب1: استمرارُ المفتاحين إيماءةٌ قابلةٌ للتخصيص، الافتراضي
        // فيها تبديلُ الإيقاف (TOGGLE_PAUSE) مع الإعلان الصوتي.
        keyShortcutRouter?.executeGesture(ShortcutGesture.BOTH_HOLD)
    }

    /**
     * العثور على العنصر المركّز عليه حالياً وإعادته كـ NodeView مسطح.
     * يستخرج النص العميق إن كانت العقدة المفوكسة حاوية (ع٤٣-1).
     */
    fun findCurrentAccessibilityFocusSnapshot(): NodeView? {
        val focused = findAccessibilityFocusAcrossWindows() ?: return null
        return try {
            val nodeView = SafeNodeWalker.extractNodeView(focused)
            if (nodeView.text.isNullOrBlank() &&
                nodeView.contentDescription.isNullOrBlank()
            ) {
                val deepText = SafeNodeWalker.extractSubtreeText(focused)
                if (deepText.isNotEmpty()) {
                    return nodeView.copy(text = deepText)
                }
            }
            nodeView
        } finally {
            SafeNodeWalker.safeRecycle(focused)
        }
    }

    /**
     * استخراج تلميح اسم المرسل من العقدة المفوكسة أو رأس المحادثة
     * في التطبيقات المسموحة (ع٤٥ - الأمر 3).
     */
    fun extractCurrentSenderHint(): String? {
        return runCatching {
            val focus = findCurrentAccessibilityFocusSnapshot()
            val focusText = focus?.readableText().orEmpty().trim()
            if (focusText.contains(':')) {
                val candidate = focusText.substringBefore(':').trim()
                if (candidate.length in 2..35 && !candidate.contains('\n')) {
                    return@runCatching candidate
                }
            }

            val root = rootInActiveWindow ?: return@runCatching null
            try {
                val nodes = SafeNodeWalker.walk(root)
                for (node in nodes) {
                    val id = node.viewIdResourceName.orEmpty()
                    if (id.contains("contact_name") ||
                        id.contains("conversation_title") ||
                        id.contains("action_bar_title") ||
                        id.contains("toolbar")
                    ) {
                        val t = node.readableText().trim()
                        if (t.isNotBlank() && t.length in 2..40 &&
                            !t.contains('\n')
                        ) {
                            return@runCatching t
                        }
                    }
                }
                val topCandidate = nodes.firstOrNull { n ->
                    n.boundsInScreen.top <= 240 &&
                        n.boundsInScreen.height() in 20..150 &&
                        n.readableText().isNotBlank() &&
                        n.readableText().length in 2..35 &&
                        !n.isClickable &&
                        !n.readableText().contains('\n')
                }?.readableText()?.trim()

                topCandidate
            } finally {
                SafeNodeWalker.safeRecycle(root)
            }
        }.getOrNull()
    }

    /**
     * العثور على عقدة التركيز عبر النافذة النشطة وباقي النوافذ (ع٤٣-1).
     */
    private fun findAccessibilityFocusAcrossWindows(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null) {
            val focus = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            } finally {
                SafeNodeWalker.safeRecycle(root)
            }
            if (focus != null) return focus
        }

        val windowList = runCatching { windows }.getOrNull().orEmpty()
        for (w in windowList) {
            val wRoot = runCatching { w.root }.getOrNull() ?: continue
            val focus = try {
                wRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            } finally {
                SafeNodeWalker.safeRecycle(wRoot)
            }
            if (focus != null) return focus
        }
        return null
    }

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        if (ProtectedAppTimelineTracker.isInQuietPeriod()) {
            return null
        }
        return super.getRootInActiveWindow()
    }

    override fun getWindows(): List<AccessibilityWindowInfo> {
        if (ProtectedAppTimelineTracker.isInQuietPeriod()) {
            return emptyList()
        }
        return super.getWindows()
    }

    /**
     * نصوص الشاشة الحالية مرتّبة بصرياً (ع٤١-ب6، ب7).
     * تعتمد [SafeNodeWalker] للاجتياز الآمن و[ScreenTextOrder] للترتيب.
     */
    fun readScreenTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            ScreenTextOrder.orderedTexts(
                views = SafeNodeWalker.walk(root),
                rtl = isRtlLayout()
            )
        } finally {
            SafeNodeWalker.safeRecycle(root)
        }
    }

    /** ع٤١-ب5: عُقد الشاشة الحالية مسطّحةً (لقراءة لقطة المحادثة). */
    fun readScreenViews(): List<NodeView> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            SafeNodeWalker.walk(root)
        } finally {
            SafeNodeWalker.safeRecycle(root)
        }
    }

    /**
     * ع٤١-ب5: يحسب ملخّص جديد رسائل المحادثة الحالية، أو null إن لم
     * تكن هناك رسائل جديدة (أو كانت مثبَّطة). المفتاح يمزج الحزمة مع
     * شاشة المحادثة للتمييز بين المحادثات.
     */
    internal fun summarizeChat(
        packageName: String,
        chatScreen: String?
    ): ChatSummary? {
        val messages = ChatSnapshotReader.messagesFrom(readScreenViews())
        if (messages.isEmpty()) return null
        val chatKey = packageName + "#" + chatScreen.orEmpty()
        return chatSummaryTracker.onVisit(
            chatKey = chatKey,
            messages = messages,
            nowMs = SystemClock.elapsedRealtime()
        )
    }

    /** يمرّر النافذة النشطة للأمام عبر أول عقدة قابلة للتمرير (ع٤١-ب6). */
    fun scrollForwardActiveWindow(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val target = findScrollableNode(root) ?: return false
            try {
                target.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
            } finally {
                SafeNodeWalker.safeRecycle(target)
            }
        } finally {
            SafeNodeWalker.safeRecycle(root)
        }
    }

    private fun findScrollableNode(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
                ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                if (found !== child) SafeNodeWalker.safeRecycle(child)
                return found
            }
            SafeNodeWalker.safeRecycle(child)
        }
        return null
    }

    private fun isRtlLayout(): Boolean =
        TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
            View.LAYOUT_DIRECTION_RTL

    /**
     * تنفيذ توسيع قراءة المزيد (ع٢٧-20، ع٢٧-21، ع٢٩-12، ع٤٣-1).
     * - يتحقق من تطابق العنصر المفوكس مع النص عبر [SelectorPacks].
     * - ينفّذ التوسيع عبر [ReadMoreExpander] محصوراً بالشجرة المفوكسة.
     * - ينتظر تغيّر محتوى النافذة لاتزامناً دون أي نوم حاجز.
     * - يوثّق كل مرحلة في سجلات [READMORE].
     */
    internal suspend fun expandReadMoreInternal(prefix: String): String? {
        val focusSnapshot = findCurrentAccessibilityFocusSnapshot()
        val appPkg = focusSnapshot?.packageName?.toString().orEmpty()
        val settings = SettingsRepository.create(this)
        val allowedApps = settings.getReadMoreAllowedPackages()
        if (appPkg.isNotBlank() && !allowedApps.contains(appPkg)) {
            Log.w(
                TAG,
                "[READMORE] App $appPkg is not in allowed packages"
            )
            return null
        }

        val focusedText = focusSnapshot?.readableText().orEmpty()
        val prefixClean = prefix.trim()

        // ع٤٣-1: فحص تطابق مرن بين نص الطلب والعنصر المفوكس
        val isTruncatedPrefix = SelectorPacks.looksTruncated(prefixClean)
        val isTruncatedFocus = SelectorPacks.looksTruncated(focusedText)
        if (prefixClean.isNotEmpty() &&
            focusedText.isNotEmpty() &&
            !SelectorPacks.isTextMatchingMessage(prefixClean, focusedText) &&
            !isTruncatedPrefix &&
            !isTruncatedFocus
        ) {
            Log.w(TAG, "[READMORE] Focused node does not match target text")
            return null
        }

        var focusedNode = findAccessibilityFocusAcrossWindows()
        if (focusedNode == null) {
            delay(50L)
            focusedNode = findAccessibilityFocusAcrossWindows()
        }

        val oldLen = focusedText.length

        // ع٤٣-3: تجهيز إشارة انتظار تغيّر المحتوى قبل تنفيذ التوسيع
        // حتى لا يضيع حدث TYPE_WINDOW_CONTENT_CHANGED المتزامن مع النقر
        val signal = AccessibilityBridge.prepareContentChangeSignal()

        val expanded = if (focusedNode != null) {
            try {
                ReadMoreExpander.tryExpand(focusedNode)
            } finally {
                SafeNodeWalker.safeRecycle(focusedNode)
            }
        } else false

        if (!expanded) {
            AccessibilityBridge.cancelContentChangeSignal(signal)
            Log.w(TAG, "[READMORE] tryExpand returned false")
            return null
        }

        // ع٤٣-3: انتظار استجابة إشارة التغير بمهلة سريعة (250ms كحد أقصى)
        val signaled = AccessibilityBridge.awaitContentChange(signal, 250L)

        // التحقق من النص المحدّث
        val updatedSnapshot = findCurrentAccessibilityFocusSnapshot()
        val updatedText = updatedSnapshot?.readableText().orEmpty()
        if (updatedText.length > oldLen) {
            ReadMoreExpander.onExpandAttemptResult(
                pkg = appPkg,
                increased = true,
                oldLength = oldLen,
                newLength = updatedText.length
            )
            Log.w(
                TAG,
                "[READMORE] Expand successful: $oldLen -> " +
                    "${updatedText.length} chars"
            )
            return updatedText
        }

        // مهلة تعليق إضافية قصيرة جداً (60ms) فقط إن لم تكن الإشارة قد وصلت
        if (!signaled) {
            delay(60L)
        }
        val finalSnapshot = findCurrentAccessibilityFocusSnapshot()
        val finalText = finalSnapshot?.readableText().orEmpty()
        val didIncrease = finalText.length > oldLen
        ReadMoreExpander.onExpandAttemptResult(
            pkg = appPkg,
            increased = didIncrease,
            oldLength = oldLen,
            newLength = finalText.length
        )

        return if (didIncrease) {
            Log.w(
                TAG,
                "[READMORE] Expand successful after grace delay: " +
                    "$oldLen -> ${finalText.length} chars"
            )
            finalText
        } else if (finalText.isNotEmpty() &&
            (SelectorPacks.isTextMatchingMessage(prefixClean, finalText) ||
                !SelectorPacks.looksTruncated(finalText))
        ) {
            finalText
        } else {
            Log.w(TAG, "[READMORE] Text did not grow after click")
            null
        }
    }

    /** ع٤٣-1: تنفيذ صريح لإيماءة قراءة المزيد من KeyShortcutRouter. */
    fun launchExpandReadMoreExplicit(onResult: (String?) -> Unit) {
        serviceScope.launch {
            val text = expandReadMoreInternal(prefix = "")
            onResult(text)
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // مسح المخازن المؤقتة عند إطفاء الشاشة (ع٢٧-11، ع٤١-ب4)
                    AccessibilityBridge.updateLastScreenTexts(emptyList())
                    RepeatSpellController.clear()
                    chatSummaryTracker.clear()
                    ContinuousReader.stopSilently()
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

    /**
     * يسجّل مستقبِل طلب التعطيل الذاتي للتطبيقات المحمية: مسار
     * «تشغيل عبر لورد» لا يحمل مثيل الخدمة، فيطلب التعطيل ببثٍّ
     * تستقبله الخدمة هنا وتنفّذ disableSelf().
     */
    private fun registerProtectedDisableReceiver() {
        if (protectedDisableReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action ==
                    ProtectedAppManager.ACTION_DISABLE_SELF
                ) {
                    Log.i(TAG, "[A11yService] disable-self requested")
                    disableSelf()
                }
            }
        }
        protectedDisableReceiver = receiver
        val filter =
            IntentFilter(ProtectedAppManager.ACTION_DISABLE_SELF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterProtectedDisableReceiver() {
        protectedDisableReceiver?.let {
            runCatching { unregisterReceiver(it) }
            protectedDisableReceiver = null
        }
    }

    /**
     * ع٤١-ب9: يسجّل مستقبل اتصال/فصل أجهزة البلوتوث. يتطلّب إذن
     * BLUETOOTH_CONNECT على API 31+؛ وإن غاب لا يُسجَّل (يُعاد المحاولة
     * عند كل اتصال خدمة)، فتقع الإعلانات باسمٍ عام دون اسم الجهاز.
     */
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        val granted = checkSelfPermission(
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleBluetoothEvent(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) {
            bluetoothReceiver = receiver
        }
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            runCatching { unregisterReceiver(it) }
            bluetoothReceiver = null
        }
    }

    private fun handleBluetoothEvent(intent: Intent?) {
        val action = intent?.action ?: return
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (!isBluetoothAnnouncementActive()) return
        val device = deviceFrom(intent)
        val key = device?.address ?: UNKNOWN_DEVICE_KEY
        val name = runCatching { device?.name }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        if (deviceEventGate.shouldAnnounce(key, connected, now)) {
            BluetoothAnnouncer.announceDeviceState(this, name, connected)
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /** ع٤١-ب9: يسجّل مستمع تغيّر مسار الصوت (سماعة/مكبّر). */
    private fun registerAudioRouteCallback() {
        if (audioDeviceCallback != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(
                addedDevices: Array<out AudioDeviceInfo>
            ) {
                announceRouteAdded(addedDevices)
            }

            override fun onAudioDevicesRemoved(
                removedDevices: Array<out AudioDeviceInfo>
            ) {
                announceRouteRemoved(removedDevices)
            }
        }
        val ok = runCatching {
            manager.registerAudioDeviceCallback(callback, emergencyHandler)
        }
        if (ok.isSuccess) {
            audioDeviceCallback = callback
        }
    }

    private fun unregisterAudioRouteCallback() {
        audioDeviceCallback?.let { callback ->
            runCatching {
                getSystemService(AudioManager::class.java)
                    ?.unregisterAudioDeviceCallback(callback)
            }
        }
        audioDeviceCallback = null
    }

    private fun announceRouteAdded(devices: Array<out AudioDeviceInfo>) {
        if (!isBluetoothAnnouncementActive()) return
        val device = devices.firstOrNull {
            AudioRouteLabel.nameOf(it.type) != null
        } ?: return
        val name = AudioRouteLabel.nameOf(device.type) ?: return
        announceRoute(device.type, name, connected = true)
    }

    private fun announceRouteRemoved(devices: Array<out AudioDeviceInfo>) {
        if (!isBluetoothAnnouncementActive()) return
        val device = devices.firstOrNull {
            AudioRouteLabel.shouldAnnounceRemoval(it.type)
        } ?: return
        val name = AudioRouteLabel.nameOf(device.type) ?: return
        announceRoute(device.type, name, connected = false)
    }

    private fun announceRoute(type: Int, name: String, connected: Boolean) {
        val key = "route:$type"
        val now = SystemClock.elapsedRealtime()
        if (deviceEventGate.shouldAnnounce(key, connected, now)) {
            BluetoothAnnouncer.announceDeviceState(this, name, connected)
        }
    }

    private fun isBluetoothAnnouncementActive(): Boolean =
        FeatureFlags.isActive(
            FeatureFlags.FEATURE_BLUETOOTH_ANNOUNCEMENT,
            context = this,
            stateStore = stateStore
        )

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[A11yService] Destroyed")
        serviceJob.cancel()
        ContinuousReader.stopSilently()
        unregisterScreenOffReceiver()
        unregisterProtectedDisableReceiver()
        unregisterBluetoothReceiver()
        unregisterAudioRouteCallback()
        deviceEventGate.clear()
        chatSummaryTracker.clear()
        callKeyDetector.reset()

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
        accessibilityButtonCallback = null

        // ع٤١-ب1.6: إزالة مستمع تغيّر جدول الربط ومستقبل اختبار الإيماءة
        bindingsObserver?.let { observer ->
            runCatching {
                contentResolver.unregisterContentObserver(observer)
            }
        }
        bindingsObserver = null
        shortcutTestReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        shortcutTestReceiver = null

        AccessibilityBridge.unregisterService(this)
        stateStore?.setConnected(false)
        stateStore = null
    }

    companion object {
        private const val TAG = "NATEQ_A11Y"

        /** حدّ خنق أحداث TYPE_WINDOW_CONTENT_CHANGED (ع٤١-ب0.4). */
        private const val CONTENT_CHANGE_THROTTLE_MS = 80L

        /** مفتاح جهاز البلوتوث عند تعذّر قراءة العنوان (ع٤١-ب9). */
        private const val UNKNOWN_DEVICE_KEY = "unknown"
    }
}
