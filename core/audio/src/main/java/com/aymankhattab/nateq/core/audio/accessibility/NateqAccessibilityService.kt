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
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.audio.announcement.RingCallerIdentity
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
        val isGame = A11ySmartPauseController.isGamePackage(service, pkg)
        A11ySmartPauseController.getInstance(service)
            .setReasonActive(A11ySmartPauseController.REASON_GAME, isGame)
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
        val newTextStr = event.text
            ?.map { it.toString() }
            ?.joinToString("") ?: ""
        val rawEcho = TypingEcho.getEchoText(
            oldText = event.beforeText,
            newText = newTextStr,
            mode = TypingEcho.EchoMode.CHARACTERS,
            fromIndex = event.fromIndex,
            addedCount = event.addedCount,
            removedCount = event.removedCount
        )
        val echoText = A11ySensitivityGate.sanitizeEchoText(event, rawEcho)
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
            if (source.isPassword) return
            val messageBody = source.text?.toString()?.trim().orEmpty()
            if (messageBody.isBlank()) return

            val context = service.applicationContext
            val isFeatureActive = FeatureFlags.isActive(
                FeatureFlags.FEATURE_CHAT_LIVE_READING,
                packageName = pkg,
                context = context,
                settings = settings
            )
            if (!isFeatureActive) return

            val sender = extractSender(source)
            val conversationId = extractConversationId(source, service, pkg)

            ChatLiveReader.getInstance().processIncomingMessage(
                context = context,
                sender = sender,
                messageBody = messageBody,
                packageName = pkg,
                conversationId = conversationId
            )
        } finally {
            SafeNodeWalker.safeRecycle(source)
        }
    }

    private fun extractSender(node: AccessibilityNodeInfo): String? {
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        if (cd.isNotBlank()) {
            val colon = cd.indexOfAny(charArrayOf(':', '\u061B', '\uFF1A'))
            if (colon > 0) {
                val candidate = cd.substring(0, colon).trim()
                if (isValidSender(candidate)) return candidate
            }
        }
        val parent = node.parent ?: return null
        try {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                try {
                    val viewId = child.viewIdResourceName.orEmpty()
                    if (viewId.contains("name", ignoreCase = true) ||
                        viewId.contains("sender", ignoreCase = true) ||
                        viewId.contains("author", ignoreCase = true)
                    ) {
                        val name = child.text?.toString()?.trim()
                        if (!name.isNullOrBlank() && isValidSender(name)) {
                            return name
                        }
                    }
                } finally {
                    SafeNodeWalker.safeRecycle(child)
                }
            }
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
        return null
    }

    private fun extractConversationId(
        node: AccessibilityNodeInfo,
        service: AccessibilityService,
        pkg: String?
    ): String {
        val root = service.rootInActiveWindow
        if (root != null) {
            try {
                for (i in 0 until root.childCount) {
                    val child = root.getChild(i) ?: continue
                    try {
                        val viewId = child.viewIdResourceName.orEmpty()
                        if (viewId.contains("toolbar", ignoreCase = true) ||
                            viewId.contains("action_bar", ignoreCase = true) ||
                            viewId.contains("conversation_title", true)
                        ) {
                            val title = child.text?.toString()?.trim()
                            if (!title.isNullOrBlank()) {
                                return "${pkg.orEmpty()}:$title"
                            }
                        }
                    } finally {
                        SafeNodeWalker.safeRecycle(child)
                    }
                }
            } finally {
                SafeNodeWalker.safeRecycle(root)
            }
        }
        return "${pkg.orEmpty()}:win_${node.windowId}"
    }

    private fun isValidSender(candidate: String): Boolean {
        if (candidate.isBlank() || candidate.length > 50) return false
        if (candidate.startsWith("com.") ||
            candidate.startsWith("android.")
        ) {
            return false
        }
        return true
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
 * معالج مراقبة وإعلان نسب التقدم لعناصر التحميل والعمليات (البند 26).
 */
object ProgressHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_PROGRESS_MONITORING
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
        AccessibilityEvent.TYPE_VIEW_SELECTED

    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        if (stateStore?.isPaused() == true) return
        if (!settings.isAccessibilityFeatureEnabled(feature)) return

        val node = event.source ?: return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return
            if (settings.isProtectedApp(pkg)) return

            val cls = event.className?.toString().orEmpty()
            val isProgress = cls.contains("ProgressBar", true) ||
                cls.contains("SeekBar", true) ||
                node.rangeInfo != null

            if (!isProgress) return

            val range = node.rangeInfo
            val (current, max) = when {
                range != null && range.max > 0 -> {
                    range.current.toInt() to range.max.toInt()
                }
                event.itemCount > 0 -> {
                    event.currentItemIndex to event.itemCount
                }
                else -> return
            }

            if (max <= 0) return

            val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
                ?: cls.ifBlank { "progress" }
            val trackerKey = "$pkg#$viewId"

            ProgressMonitor.onProgressChanged(
                context = service.applicationContext,
                trackerKey = trackerKey,
                current = current,
                max = max
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}

/**
 * ينطق التسمية المخصصة المحفوظة لعنصر غير مُسمّى عند التركيز (البند 5).
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
        val node = event.source ?: return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val viewId = node.viewIdResourceName
            val label = UnlabeledElementStore.getInstance(service)
                .getCustomLabel(pkg, viewId)
            if (label.isNullOrBlank()) return
            if (!UnlabeledElementHelper.isUnlabeled(node)) return
            A11ySpeechCoordinator.speakAnnouncement(
                context = service.applicationContext,
                text = label,
                isInterruptAllowed = true
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}

/**
 * وصف تلقائي للعناصر غير المسماة عند التركيز (البند 4، أفضل جهد).
 * يستدل من خصائص العقدة، ويحفظ الوصف تلقائياً عند تفعيل الخيار.
 */
object AutoDescribeUnlabeledHandler : A11yFeatureHandler {
    override val feature = FeatureFlags.FEATURE_AUTO_DESCRIBE_UNLABELED
    override val acceptedEventTypes =
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED

    override fun handle(
        service: AccessibilityService,
        event: AccessibilityEvent,
        settings: SettingsRepository,
        stateStore: A11yStateStore?
    ) {
        val node = event.source ?: return
        try {
            val pkg = event.packageName?.toString().orEmpty()
            val viewId = node.viewIdResourceName
            val store = UnlabeledElementStore.getInstance(service)
            // إن وُجدت تسمية مخصصة محفوظة فالأولوية لها (ينطقها المعالج الآخر)
            val existing = store.getCustomLabel(pkg, viewId)
            if (!existing.isNullOrBlank()) return
            if (!UnlabeledElementHelper.isUnlabeled(node)) return

            val description = UnlabeledElementHelper.describeNode(node)
            if (description.isBlank()) return

            A11ySpeechCoordinator.speakAnnouncement(
                context = service.applicationContext,
                text = description,
                isInterruptAllowed = true
            )

            if (settings.isAutoSaveUnlabeledDescriptionsEnabled() &&
                !pkg.isBlank() && !viewId.isNullOrBlank()
            ) {
                store.setCustomLabel(pkg, viewId, description)
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}

/** مساعدات توصيف/كشف العناصر غير المسماة (استدلال خصائص العقدة). */
internal object UnlabeledElementHelper {

    fun isUnlabeled(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty()) return false
        // اعتبره مُسمّى إن كان لأي ابن نص/وصف مباشر ظاهر
        val childLabel = firstDirectChildLabel(node)
        return childLabel.isNullOrBlank()
    }

    private fun firstDirectChildLabel(
        node: AccessibilityNodeInfo
    ): String? {
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            try {
                val t = child.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) return t
                val d = child.contentDescription?.toString()?.trim().orEmpty()
                if (d.isNotEmpty()) return d
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
        return null
    }

    fun describeNode(node: AccessibilityNodeInfo): String {
        val role = roleLabel(node)
        val state = stateLabel(node)
        return buildString {
            if (role.isNotBlank()) append(role)
            if (state.isNotBlank()) {
                if (isNotEmpty()) append('،')
                append(' ')
                append(state)
            }
        }.trim()
    }

    private fun roleLabel(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty()
        return when {
            cls.endsWith("Button") ||
                cls.endsWith("ImageButton") -> "زر"
            cls.endsWith("ImageView") -> "صورة"
            cls.endsWith("EditText") -> "حقل تحرير"
            cls.endsWith("CheckBox") -> "خانة اختيار"
            cls.endsWith("RadioButton") -> "زر اختيار"
            cls.endsWith("Switch") ||
                cls.endsWith("SwitchCompat") ||
                cls.endsWith("ToggleButton") -> "مفتاح تبديل"
            cls.endsWith("SeekBar") -> "شريط تمرير"
            cls.endsWith("ProgressBar") -> "شريط تقدم"
            cls.endsWith("Spinner") -> "قائمة منسدلة"
            cls.endsWith("TextView") -> "نص"
            node.isClickable -> "عنصر قابل للنقر"
            node.isEditable -> "حقل تحرير"
            node.isCheckable -> "عنصر قابل للتحديد"
            else -> "عنصر"
        }
    }

    private fun stateLabel(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        if (node.isCheckable) {
            @Suppress("DEPRECATION")
            val checked = node.isChecked
            parts += if (checked) "محدد" else "غير محدد"
        }
        if (node.isSelected) parts += "مُختار"
        if (!node.isEnabled) parts += "غير مفعّل"
        return parts.joinToString("، ")
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
    private val bluetoothTrailingJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // ع٤١-ب5: متتبّع ملخّصات المحادثات (ذاكرة فقط)
    private val chatSummaryTracker = ChatSummaryTracker()

    // بند 13: تخطي إعلانات يوتيوب (يوتيوب فقط، تحقق دوري خفيف)
    @Volatile
    private var lastYoutubeAdSkipAt = 0L
    private val youtubeAdSkipRunnable = object : Runnable {
        override fun run() {
            trySkipYoutubeAdIfActive()
            if (shouldRunYoutubeAdSkipLoop()) {
                emergencyHandler.postDelayed(this, YOUTUBE_AD_SKIP_POLL_MS)
            }
        }
    }

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
        UnlabeledLabelsHandler,
        AutoDescribeUnlabeledHandler
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

        // ع٢٩-16: خزن المستمع لإزالته لاحقاً
        a11yStateListener = { store: A11yStateStore ->
            if (store.isPaused()) ContinuousReader.stopSilently()
            if (!isBluetoothAnnouncementActive()) {
                clearBluetoothDebounce()
            }
            applyServiceInfo()
        }
        stateStore?.addListener(a11yStateListener!!)

        applyServiceInfo()
        registerBluetoothReceiver()
        registerShortcutTestReceiver()
        registerBindingsObserver()

        val connIntent = Intent(ProtectedAppManager.ACTION_A11Y_CONNECTED)
            .setPackage(packageName)
        runCatching { sendBroadcast(connIntent) }

        // الأمر 1 - البند 6: إرجاء كل تهيئة ثقيلة إلى خلفية تبدأ بعد 500ms
        CoroutineScope(Dispatchers.Default).launch {
            delay(500L)
            AccessibilityHealthMonitor.checkHealth(
                this@NateqAccessibilityService
            )

            val repo = SettingsRepository.create(
                this@NateqAccessibilityService
            )
            val isCallPause = FeatureFlags.isActive(
                FeatureFlags.FEATURE_SMART_PAUSE_CALL,
                context = this@NateqAccessibilityService,
                settings = repo,
                stateStore = stateStore
            )
            val isGamePause = FeatureFlags.isActive(
                FeatureFlags.FEATURE_SMART_PAUSE_GAME,
                context = this@NateqAccessibilityService,
                settings = repo,
                stateStore = stateStore
            )
            if (isCallPause || isGamePause) {
                A11ySmartPauseController.getInstance(
                    this@NateqAccessibilityService
                ).registerPhoneStateReceiver()
            }

            keyShortcutRouter = KeyShortcutRouter(
                service = this@NateqAccessibilityService,
                settings = repo,
                stateStore = stateStore
            )
            applyAccessibilityButton()

            runCatching {
                com.aymankhattab.nateq.engine.PronunciationDictionary
                    .shared(this@NateqAccessibilityService).reloadIfChanged()
            }
            RestoreDiagnosticsTracker.recordFeaturesReady(
                this@NateqAccessibilityService
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    fun onServiceConnectedForTesting() {
        onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val disconnIntent = Intent(
            ProtectedAppManager.ACTION_A11Y_DISCONNECTED
        ).setPackage(packageName)
        runCatching { sendBroadcast(disconnIntent) }
        return super.onUnbind(intent)
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
                // بند 2: تحديث اشتراك الأحداث فور تبديل الميزات.
                applyServiceInfo()
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

        // بند 1: يلزم حدث التركيز أيضاً عند تفعيل التسميات المخصصة
        // أو الوصف التلقائي منفردين، وإلا لا تصل الأحداث لمعالجاتهما.
        if (FeatureFlags.isActive(
                FeatureFlags.FEATURE_CUSTOM_UNLABELED,
                context = this,
                settings = repo,
                stateStore = store
            ) ||
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_AUTO_DESCRIBE_UNLABELED,
                context = this,
                settings = repo,
                stateStore = store
            )
        ) {
            types = types or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
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
        val youtubeAdSkip = FeatureFlags.isActive(
            FeatureFlags.FEATURE_YOUTUBE_AD_SKIP,
            context = this,
            settings = repo,
            stateStore = store
        )
        if (youtubeAdSkip) {
            types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            types = types or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }

        info.eventTypes = types
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

        val fastRequired = liveReading || readMore
        // بند 3: رفع مهلة الإشعار في وضع القراءة الحية/اقرأ المزيد إلى 100ms
        // مع الحفاظ على سلوك الخنق الحالي (CONTENT_CHANGE_THROTTLE_MS).
        info.notificationTimeout = if (fastRequired) 100L else 150L

        // بند 2: تقييد إدراج العروض غير المهمة بالحالات التي تحتاج اجتيازاً
        // أوسع (القراءة الحية/اقرأ المزيد) لتقليل الحمل مع بقاء عمل الميزات.
        var flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (fastRequired) {
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        info.flags = flags

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
            // بند 3: حفظ آخر عنصر مركّز عليه (خارج التطبيق) لالتقاط
            // الحزمة ومعرّف العنصر تلقائياً في إدارة التسميات المخصصة.
            if (!focusedPkg.isNullOrBlank() && focusedPkg != packageName) {
                val src = runCatching { event.source }.getOrNull()
                if (src != null) {
                    try {
                        val viewId = src.viewIdResourceName
                        if (!viewId.isNullOrBlank()) {
                            UnlabeledElementStore.getInstance(this)
                                .setLastFocusedElement(focusedPkg, viewId)
                        }
                    } finally {
                        @Suppress("DEPRECATION")
                        runCatching { src.recycle() }
                    }
                }
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
                // النافذة ظهرت أصلاً: التعطيل يأتي متأخراً عن
                // شاشة البداية، فنعيد فتح التطبيق بعد التعطيل
                // حتى لا ينهار.
                ProtectedAppManager.handleAppEntry(
                    context = this,
                    service = this,
                    app = matched,
                    relaunchIfForeground = true
                )
                return
            } else if (ProtectedAppManager.isPartiallySuspended) {
                val suspendedPkg =
                    ProtectedAppManager.partiallySuspendedPkg
                if (eventPkg != null && eventPkg != suspendedPkg) {
                    serviceScope.launch {
                        ProtectedAppManager.handleAppExit(
                            context = this@NateqAccessibilityService,
                            pkg = suspendedPkg.orEmpty(),
                            label = ""
                        )
                    }
                }
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

        // بند 13: تخطي إعلانات يوتيوب (يوتيوب فقط)
        maybeScheduleYoutubeAdSkip(eventPkg, event.eventType)
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
            if (!ringing) {
                callKeyDetector.reset()
            } else {
                val isCanceled = event.isCanceled ||
                    (event.flags and KeyEvent.FLAG_CANCELED != 0) ||
                    (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS != 0)
                if (isCanceled) {
                    callKeyDetector.onCancel(volumeKey)
                    return true
                }
                val sessionId = RingCallerIdentity.currentSessionId()
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (callKeyDetector.onDown(
                                volumeKey,
                                ringing,
                                event.eventTime,
                                sessionId
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
                            isCallKeysSwapped(),
                            sessionId
                        )
                        if (action != null) {
                            performCallAction(action)
                            return true
                        }
                        if (ringing) return true
                    }
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
            val isCanceled = event.isCanceled ||
                (event.flags and KeyEvent.FLAG_CANCELED != 0) ||
                (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS != 0)
            if (isCanceled) {
                isVolUpPressed = false
                isVolDownPressed = false
                emergencyHandler.removeCallbacks(emergencyRunnable)
                return true
            }
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

    @Volatile private var cachedSenderHint: String? = null
    @Volatile private var cachedSenderHintTime: Long = 0L

    /**
     * استخراج تلميح اسم المرسل من العقدة المفوكسة أو رأس المحادثة
     * في التطبيقات المسموحة (ع٤٥ - الأمر 3).
     */
    fun extractCurrentSenderHint(): String? {
        val now = SystemClock.uptimeMillis()
        if (now - cachedSenderHintTime < SENDER_HINT_CACHE_TTL_MS &&
            cachedSenderHint != null
        ) {
            return cachedSenderHint
        }
        val hint = runCatching {
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
                val nodes = SafeNodeWalker.walk(
                    root,
                    maxNodes = SENDER_HINT_MAX_NODES,
                    maxTimeoutMs = SENDER_HINT_TIMEOUT_MS
                )
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

        if (hint != null) {
            cachedSenderHint = hint
            cachedSenderHintTime = now
        }
        return hint
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
        val views = readScreenViews()
        val messages = ChatSnapshotReader.messagesFrom(views)
        if (messages.isEmpty()) return null
        val title = ChatSnapshotReader.extractConversationTitle(views)
        if (title.isNullOrBlank()) {
            // إن تعذر التمييز فلا تدمج تاريخ محادثتين
            // أو تخمّن هوية شخص (البند 15)
            return null
        }
        val chatKey = "$packageName#${chatScreen.orEmpty()}#$title"
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
        val settings = SettingsRepository.create(this)
        val allowedApps = settings.getReadMoreAllowedPackages()

        var focusedNode = findAccessibilityFocusAcrossWindows()
        if (focusedNode == null) {
            delay(READ_MORE_FOCUS_RETRY_MS)
            focusedNode = findAccessibilityFocusAcrossWindows()
        }
        if (focusedNode == null) {
            Log.w(TAG, "[READMORE] لا يوجد عنصر مفوكس")
            return null
        }

        val appPkg = focusedNode.packageName?.toString().orEmpty()
        if (appPkg.isNotBlank() && !allowedApps.contains(appPkg)) {
            Log.w(TAG, "[READMORE] التطبيق غير مسموح")
            SafeNodeWalker.safeRecycle(focusedNode)
            return null
        }

        val prefixClean = SelectorPacks.cleanBidi(prefix).trim()
        val snapshot = snapshotFromNode(focusedNode)
        val containerNode = findMessageContainerNode(focusedNode)
        val containerSnapshot = snapshotFromNode(containerNode)
        val initialIdentity = messageIdentity(
            containerSnapshot ?: snapshot, prefixClean
        )

        if (!isSameTargetMessage(
                snapshot, prefixClean, initialIdentity, requirePrefix = true
            ) && prefixClean.isNotEmpty()
        ) {
            Log.w(TAG, "[READMORE] العنصر المفوكس لا يطابق الرسالة المطلوبة")
            recycleIfDifferent(containerNode, focusedNode)
            SafeNodeWalker.safeRecycle(focusedNode)
            return null
        }

        val initialFull = extractMessageBodyText(containerNode ?: focusedNode)
            .ifBlank { snapshot?.readableText().orEmpty() }
        val initialTrusted = isSameTargetMessage(
            containerSnapshot ?: snapshot,
            prefixClean,
            initialIdentity,
            requirePrefix = prefixClean.isNotEmpty()
        )

        // استخدام النص الكامل المتاح بلا نقرة بعد تحقق الهوية.
        if (initialTrusted &&
            shouldUseAvailableTextWithoutClick(prefixClean, initialFull)
        ) {
            Log.i(TAG, "[READMORE] استخدام النص الكامل المتاح دون نقرة")
            recycleIfDifferent(containerNode, focusedNode)
            SafeNodeWalker.safeRecycle(focusedNode)
            return SelectorPacks.cleanBidi(initialFull).trim()
        }

        val hasExplicit = hasExplicitExpandControl(
            containerNode ?: focusedNode
        )
        if (!hasExplicit) {
            recycleIfDifferent(containerNode, focusedNode)
            SafeNodeWalker.safeRecycle(focusedNode)
            if (initialTrusted &&
                isMeaningfulImprovement(prefixClean, initialFull)
            ) {
                return SelectorPacks.cleanBidi(initialFull).trim()
            }
            Log.w(TAG, "[READMORE] لا يوجد إجراء/زر توسيع صريح")
            return null
        }

        if (!ReadMoreExpander.canAttempt(
                appPkg, containerNode ?: focusedNode, initialIdentity
            )
        ) {
            Log.w(TAG, "[READMORE] محاولة مكررة/مقيّدة مُتجاوزة")
            recycleIfDifferent(containerNode, focusedNode)
            SafeNodeWalker.safeRecycle(focusedNode)
            return null
        }

        // تجهيز إشارة التغيّر قبل النقر لمنع فقدان حدث متزامن.
        val signal = AccessibilityBridge.prepareContentChangeSignal()
        val clicked = ReadMoreExpander.tryExpandConfined(
            containerNode ?: focusedNode, focusedNode
        )
        if (!clicked) {
            AccessibilityBridge.cancelContentChangeSignal(signal)
            ReadMoreExpander.onAttemptFinished(
                appPkg, initialIdentity, false, 0, 0
            )
            recycleIfDifferent(containerNode, focusedNode)
            SafeNodeWalker.safeRecycle(focusedNode)
            Log.w(TAG, "[READMORE] تعذّر تنفيذ نقرة توسيع صريحة")
            return null
        }
        ReadMoreExpander.onClickIssued(appPkg, initialIdentity)

        val oldLen = SelectorPacks.cleanBidi(initialFull).trim().length
        val signaled = AccessibilityBridge.awaitContentChange(
            signal, READ_MORE_WAIT_MS
        )
        if (!signaled) {
            delay(READ_MORE_GRACE_MS)
        }

        // إلغاء عند تغير التركيز/الرسالة مع الاحتفاظ بالأصل (بإرجاع null).
        val afterFocus = findAccessibilityFocusAcrossWindows()
        val afterSnapshot = snapshotFromNode(afterFocus)
        val afterContainer = if (afterFocus != null) {
            findMessageContainerNode(afterFocus)
        } else {
            null
        }
        val afterText = extractMessageBodyText(
            afterContainer ?: afterFocus ?: containerNode ?: focusedNode
        ).ifBlank { afterSnapshot?.readableText().orEmpty() }
        val afterIdentity = messageIdentity(
            snapshotFromNode(afterContainer) ?: afterSnapshot, prefixClean
        )
        val sameMessage = afterFocus == null || isSameTargetMessage(
            snapshotFromNode(afterContainer) ?: afterSnapshot,
            prefixClean,
            initialIdentity,
            requirePrefix = false,
            observedIdentity = afterIdentity
        )
        val cleanedAfter = SelectorPacks.cleanBidi(afterText).trim()
        val increased = sameMessage && cleanedAfter.length > oldLen &&
            isMeaningfulImprovement(prefixClean, cleanedAfter)

        recycleIfDifferent(afterContainer, afterFocus)
        SafeNodeWalker.safeRecycle(afterFocus)
        recycleIfDifferent(containerNode, focusedNode)
        SafeNodeWalker.safeRecycle(focusedNode)

        ReadMoreExpander.onAttemptFinished(
            appPkg, initialIdentity, increased, oldLen, cleanedAfter.length
        )

        if (!sameMessage) {
            Log.w(TAG, "[READMORE] أُلغي التوسيع لتغيّر التركيز/الرسالة")
            return null
        }
        if (increased) {
            Log.i(TAG, "[READMORE] اكتمل التوسيع لنفس الرسالة")
            return cleanedAfter
        }
        if (shouldUseAvailableTextWithoutClick(prefixClean, cleanedAfter)) {
            return cleanedAfter
        }
        Log.w(TAG, "[READMORE] لم يتحقق تحديث ناتج للرسالة نفسها")
        return null
    }

    private fun snapshotFromNode(node: AccessibilityNodeInfo?): NodeView? =
        node?.let { SafeNodeWalker.extractNodeView(it) }

    private fun recycleIfDifferent(
        candidate: AccessibilityNodeInfo?,
        other: AccessibilityNodeInfo?
    ) {
        if (candidate != null && candidate !== other) {
            SafeNodeWalker.safeRecycle(candidate)
        }
    }

    /**
     * هوية الرسالة: نافذة + موضع + حزمة/معرّف/نوع + بادئة منظفة.
     */
    fun messageIdentity(
        snapshot: NodeView?,
        prefix: String
    ): String {
        if (snapshot == null) return ""
        val b = snapshot.boundsInScreen
        val pos = "${b.left},${b.top},${b.right},${b.bottom}"
        val pref = SelectorPacks.cleanBidi(prefix).trim()
            .take(80).lowercase()
        return listOf(
            snapshot.windowId.toString(),
            pos,
            snapshot.packageName.orEmpty(),
            snapshot.viewIdResourceName.orEmpty(),
            snapshot.className.orEmpty(),
            pref
        ).joinToString("|")
    }

    /** توافق: هوية مستقرة أساسية للعقدة (بدون بادئة/موضع). */
    fun stableNodeIdentity(snapshot: NodeView?): String {
        if (snapshot == null) return ""
        return "${snapshot.packageName.orEmpty()}|" +
            "${snapshot.viewIdResourceName.orEmpty()}|" +
            snapshot.className.orEmpty()
    }

    private fun isSameTargetMessage(
        snapshot: NodeView?,
        prefix: String,
        initialIdentity: String,
        requirePrefix: Boolean,
        observedIdentity: String? = null
    ): Boolean {
        val currentId = observedIdentity ?: messageIdentity(snapshot, prefix)
        if (initialIdentity.isNotEmpty() && currentId.isNotEmpty() &&
            currentId != initialIdentity
        ) {
            // السماح بتطابق الهوية دون البادئة حين تغيّر النص بعد التوسيع
            val baseInit = initialIdentity.substringBeforeLast('|')
            val baseCur = currentId.substringBeforeLast('|')
            if (baseInit.isEmpty() || baseInit != baseCur) return false
        }
        val text = snapshot?.readableText().orEmpty()
        val cleanedPrefix = SelectorPacks.cleanBidi(prefix).trim()
        if (cleanedPrefix.isBlank()) return !requirePrefix || text.isNotBlank()
        if (text.isBlank()) return false
        return SelectorPacks.hasPrefixContinuity(cleanedPrefix, text) ||
            SelectorPacks.isTextMatchingMessage(cleanedPrefix, text) ||
            SelectorPacks.cleanBidi(text).trim()
                .startsWith(cleanedPrefix, ignoreCase = true)
    }

    /** يتحقق أن النص الموسّع تابع لرسالة الهوية/البادئة نفسها. */
    fun expandedTextMatchesIdentity(
        snapshot: NodeView?,
        text: String,
        prefix: String,
        initialIdentity: String
    ): Boolean {
        if (initialIdentity.isNotEmpty() &&
            messageIdentity(snapshot, prefix) != initialIdentity
        ) {
            // هوية الرسالة قد تحافظ على النافذة/الموضع/المعرّف وتتغير البادئة
            val baseInit = initialIdentity.substringBeforeLast('|')
            val baseCur = messageIdentity(snapshot, prefix)
                .substringBeforeLast('|')
            if (baseInit.isEmpty() || baseInit != baseCur) {
                Log.w(TAG, "[READMORE] تغيّر هوية الرسالة أثناء التوسيع")
                return false
            }
        }
        val p = SelectorPacks.cleanBidi(prefix).trim()
        val t = SelectorPacks.cleanBidi(text).trim()
        if (p.isBlank() || t.isBlank()) return t.isNotBlank() || p.isBlank()
        return SelectorPacks.hasPrefixContinuity(p, t) ||
            SelectorPacks.isTextMatchingMessage(p, t) ||
            t.startsWith(p, ignoreCase = true)
    }

    private fun shouldUseAvailableTextWithoutClick(
        prefix: String,
        text: String
    ): Boolean {
        val t = SelectorPacks.cleanBidi(text).trim()
        if (t.isBlank()) return false
        val p = SelectorPacks.cleanBidi(prefix).trim()
        if (p.isNotBlank() && !isMeaningfulImprovement(p, t) &&
            !SelectorPacks.hasPrefixContinuity(p, t) &&
            !SelectorPacks.isTextMatchingMessage(p, t)
        ) {
            return false
        }
        // إذا لم يُعثر على مؤشر اقتطاع أو محفّز توسعة صريح في النص المتاح،
        // فالنص كامل/قابل للاستخدام دون نقرة.
        return !SelectorPacks.hasTruncationHint(t) &&
            !SelectorPacks.hasExplicitExpandCue(t)
    }

    private fun isMeaningfulImprovement(prefix: String, text: String): Boolean {
        val p = SelectorPacks.cleanBidi(prefix).trim()
        val t = SelectorPacks.cleanBidi(text).trim()
        if (t.isBlank()) return false
        if (p.isBlank()) return true
        if (t.length > p.length) return true
        return !SelectorPacks.hasTruncationHint(t) &&
            (SelectorPacks.hasPrefixContinuity(p, t) ||
                SelectorPacks.isTextMatchingMessage(p, t))
    }

    /** بحث محدود عن إجراء/زر توسعة صريح داخل حاوية الرسالة. */
    private fun hasExplicitExpandControl(
        container: AccessibilityNodeInfo
    ): Boolean {
        return findExplicitExpandTarget(container, maxNodes = 40) != null
    }

    internal fun findExplicitExpandTarget(
        root: AccessibilityNodeInfo,
        maxNodes: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (isExplicitExpandNode(node)) return node
            if (depth >= 4) continue
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited + queue.size >= maxNodes) break
                val child = runCatching { node.getChild(i) }.getOrNull()
                    ?: continue
                // حصر داخل الحاوية: نتجاهل الأبناء غير المرئيين بوضوح
                if (runCatching { child.windowId }.getOrDefault(-1) > 0 &&
                    !runCatching { child.isVisibleToUser }.getOrDefault(true)
                ) {
                    SafeNodeWalker.safeRecycle(child)
                    continue
                }
                queue.add(child to depth + 1)
            }
        }
        // لا نُعيد تدوير العقد هنا لأن المالك (المستدعي) يدير الشجرة؛
        // children المضافة لم تُعاد كقيمة.
        return null
    }

    private fun isExplicitExpandNode(node: AccessibilityNodeInfo): Boolean {
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val hasExpandAction =
            (node.actions and AccessibilityNodeInfo.ACTION_EXPAND) != 0 ||
                actions.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }
        val labeledAction = actions.any { action ->
            val label = action.label?.toString().orEmpty()
            label.isNotBlank() && SelectorPacks.isReadMoreActionLabel(label)
        }
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val explicitText = SelectorPacks.isExplicitExpandText(text) ||
            SelectorPacks.isExplicitExpandText(desc)
        val clickableSpanTarget = hasExplicitClickableSpan(node)
        if (hasExpandAction || labeledAction) return true
        if (clickable && explicitText) return true
        if (clickableSpanTarget) return true
        return false
    }

    private fun hasExplicitClickableSpan(
        node: AccessibilityNodeInfo
    ): Boolean {
        val text = node.text
        val sp = text as? android.text.Spanned ?: return false
        val spans = runCatching {
            sp.getSpans(
                0, sp.length, android.text.style.ClickableSpan::class.java
            )
        }.getOrNull().orEmpty()
        if (spans.isEmpty()) return false
        return spans.any { span ->
            val start = sp.getSpanStart(span).coerceAtLeast(0)
            val end = sp.getSpanEnd(span).coerceAtMost(sp.length)
            if (end <= start) return@any false
            val spanText = sp.subSequence(start, end).toString()
            SelectorPacks.isExplicitExpandText(spanText)
        }
    }

    /** حاوية رسالة محصورة لتقليل استهداف عناصر مجاورة/عامة. */
    private fun findMessageContainerNode(
        focused: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        // تفضيل عقدة نصية/قابلة للتركيز داخل الفقاعة؛ وإلا صعود محدود.
        var current: AccessibilityNodeInfo = focused
        var best: AccessibilityNodeInfo = focused
        var depth = 0
        while (depth < 4) {
            val cls = current.className?.toString().orEmpty()
            val id = runCatching { current.viewIdResourceName }
                .getOrNull().orEmpty()
            val likelyMessage = id.contains("message", true) ||
                id.contains("conversation", true) ||
                cls.contains("TextView", true) ||
                cls.contains("ViewGroup", true)
            if (likelyMessage) best = current
            val parent = runCatching { current.parent }.getOrNull() ?: break
            // لا نصعد إلى جذر نافذة عام جداً
            val pCls = parent.className?.toString().orEmpty()
            if (pCls.contains("RecyclerView", true) ||
                pCls.contains("ListView", true) ||
                pCls.contains("ScrollView", true)
            ) {
                SafeNodeWalker.safeRecycle(parent)
                break
            }
            if (current !== focused && current !== best) {
                SafeNodeWalker.safeRecycle(current)
            }
            current = parent
            depth++
        }
        if (current !== focused && current !== best) {
            SafeNodeWalker.safeRecycle(current)
        }
        return best
    }

    /**
     * استخراج متن الرسالة الكامل داخل الحاوية مع استبعاد المرسل/الوقت/
     * الاقتباس/وصف الحاوية قدر الإمكان، بتنظيف موحّد.
     */
    private fun extractMessageBodyText(
        container: AccessibilityNodeInfo?
    ): String {
        if (container == null) return ""
        val parts = mutableListOf<String>()
        collectMessageTexts(container, parts, depth = 0)
        if (parts.isEmpty()) {
            val direct = container.text?.toString().orEmpty()
            if (direct.isNotBlank()) parts.add(direct)
        }
        val joined = parts
            .asSequence()
            .map { SelectorPacks.cleanBidi(it).trim() }
            .filter { it.isNotBlank() }
            .filterNot { isLikelyMessageMeta(it) }
            .distinct()
            .joinToString(" ")
            .trim()
        return joined
    }

    private fun collectMessageTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int
    ) {
        if (depth > 6 || out.size > 30) return
        val vis = runCatching { node.isVisibleToUser }.getOrDefault(true)
        if (runCatching { node.windowId }.getOrDefault(-1) > 0 && !vis) return
        val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (isLikelyQuoteOrMetaContainer(id, cls)) return

        val text = node.text?.toString().orEmpty().trim()
        // نفضّل نص العقدة؛ نتجنب وصف الحاوية ونصوص أزرار التوسعة الصريحة.
        if (text.isNotBlank() && !isLikelyUiLabel(text) &&
            !SelectorPacks.isExplicitExpandText(text)
        ) {
            out.add(text)
        } else if (text.isBlank()) {
            val desc = node.contentDescription?.toString().orEmpty().trim()
            if (desc.isNotBlank() && cls.contains("TextView", true) &&
                !isLikelyUiLabel(desc) &&
                !SelectorPacks.isExplicitExpandText(desc)
            ) {
                out.add(desc)
            }
        }
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collectMessageTexts(child, out, depth + 1)
            } finally {
                SafeNodeWalker.safeRecycle(child)
            }
        }
    }

    private fun isLikelyQuoteOrMetaContainer(id: String, cls: String): Boolean {
        val l = (id + " " + cls).lowercase()
        return l.contains("quote") || l.contains("quoted") ||
            l.contains("reply_bar") || l.contains("forward_bar") ||
            l.contains("status_container") || l.contains("date_divider")
    }

    private fun isLikelyMessageMeta(text: String): Boolean {
        val t = SelectorPacks.cleanBidi(text).trim()
        if (t.isEmpty()) return true
        if (isLikelyTimeOnly(t)) return true
        val lower = t.lowercase()
        return lower in setOf(
            "sent", "delivered", "read", "seen",
            "تم الإرسال", "تم التسليم", "تمت القراءة", "شوهد"
        )
    }

    private fun isLikelyUiLabel(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // تسميات عامة قصيرة جداً ليست متن رسالة
        return t.length <= 1
    }

    private fun isLikelyTimeOnly(text: String): Boolean {
        val t = text.trim()
        if (t.length > 12) return false
        return t.matches(
            Regex(
                "^[\\d\\u0660-\\u0669]{1,2}:" +
                    "[\\d\\u0660-\\u0669]{2}" +
                    "(\\s*[AaPp][Mm]|\\s*[صم])?$"
            )
        )
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

    @VisibleForTesting
    internal var lastBluetoothReceiverFlags: Int = -1

    /**
     * ع٤١-ب9: يسجّل مستقبل اتصال/فصل أجهزة البلوتوث. يتطلّب إذن
     * BLUETOOTH_CONNECT على API 31+؛ وإن غاب لا يُسجَّل (يُعاد المحاولة
     * عند كل اتصال خدمة)، فتقع الإعلانات باسمٍ عام دون اسم الجهاز.
     *
     * على API 33+ يُسجَّل بعلم RECEIVER_EXPORTED لأن مكدس البلوتوث
     * (com.android.bluetooth) تطبيق مميز يعمل تحت UID خاص (BLUETOOTH_UID) لا
     * system UID، فاستخدام RECEIVER_NOT_EXPORTED يمنع تسليم البث.
     * البث نظامي محمي مقتصر على أحداث ACL ومستقل تماماً عن أوامر التطبيق.
     */
    @VisibleForTesting
    internal fun registerBluetoothReceiver() {
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
                lastBluetoothReceiverFlags = RECEIVER_EXPORTED
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                lastBluetoothReceiverFlags = 0
                registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) {
            bluetoothReceiver = receiver
        }
    }

    @VisibleForTesting
    internal fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            runCatching { unregisterReceiver(it) }
            bluetoothReceiver = null
        }
        lastBluetoothReceiverFlags = -1
        clearBluetoothDebounce()
    }

    @VisibleForTesting
    internal fun isBluetoothReceiverRegistered(): Boolean =
        bluetoothReceiver != null

    @VisibleForTesting
    internal fun handleBluetoothEvent(intent: Intent?) {
        val action = intent?.action ?: return
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (!isBluetoothAnnouncementActive()) {
            clearBluetoothDebounce()
            return
        }
        val device = deviceFrom(intent)
        val key = device?.address ?: UNKNOWN_DEVICE_KEY
        val name = runCatching { device?.name }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        processDeviceAnnouncement(key, name, connected, now)
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
        if (!isBluetoothAnnouncementActive()) {
            clearBluetoothDebounce()
            return
        }
        val key = "route:$type"
        val now = SystemClock.elapsedRealtime()
        processDeviceAnnouncement(key, name, connected, now)
    }

    private fun processDeviceAnnouncement(
        key: String,
        name: String?,
        connected: Boolean,
        now: Long
    ) {
        when (val decision = deviceEventGate.evaluate(key, connected, now)) {
            is BluetoothEventGate.Action.AnnounceImmediate -> {
                bluetoothTrailingJobs.remove(key)?.cancel()
                BluetoothAnnouncer.announceDeviceState(this, name, connected)
            }
            is BluetoothEventGate.Action.ScheduleTrailing -> {
                bluetoothTrailingJobs.remove(key)?.cancel()
                bluetoothTrailingJobs[key] = serviceScope.launch {
                    delay(decision.delayMs)
                    if (!isBluetoothAnnouncementActive()) return@launch
                    val finalState = deviceEventGate.onTrailingDue(
                        key, SystemClock.elapsedRealtime()
                    )
                    if (finalState != null) {
                        BluetoothAnnouncer.announceDeviceState(
                            this@NateqAccessibilityService,
                            name,
                            finalState
                        )
                    }
                    bluetoothTrailingJobs.remove(key)
                }
            }
            is BluetoothEventGate.Action.CancelTrailing -> {
                bluetoothTrailingJobs.remove(key)?.cancel()
            }
            is BluetoothEventGate.Action.Drop -> {
                // حدث مكرر تم تثبيطه
            }
        }
    }

    private fun clearBluetoothDebounce() {
        bluetoothTrailingJobs.values.forEach { it.cancel() }
        bluetoothTrailingJobs.clear()
        deviceEventGate.clear()
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
        runCatching {
            A11ySmartPauseController.getInstance(this)
                .unregisterPhoneStateReceiver()
        }
        emergencyHandler.removeCallbacks(youtubeAdSkipRunnable)
        clearBluetoothDebounce()
        chatSummaryTracker.clear()
        callKeyDetector.reset()
        keyShortcutRouter?.reset()
        OcrScreenReader.shutdown()

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
        val disconnIntent = Intent(
            ProtectedAppManager.ACTION_A11Y_DISCONNECTED
        ).setPackage(packageName)
        runCatching { sendBroadcast(disconnIntent) }
        stateStore?.setConnected(false)
        stateStore = null
    }

    private fun maybeScheduleYoutubeAdSkip(pkg: String?, eventType: Int) {
        if (pkg != YOUTUBE_PACKAGE) {
            emergencyHandler.removeCallbacks(youtubeAdSkipRunnable)
            return
        }
        if (!FeatureFlags.isActive(
                FeatureFlags.FEATURE_YOUTUBE_AD_SKIP,
                packageName = pkg,
                context = this,
                settings = SettingsRepository.create(this),
                stateStore = stateStore
            )
        ) {
            emergencyHandler.removeCallbacks(youtubeAdSkipRunnable)
            return
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            trySkipYoutubeAdIfActive()
            if (shouldRunYoutubeAdSkipLoop()) {
                emergencyHandler.removeCallbacks(youtubeAdSkipRunnable)
                emergencyHandler.postDelayed(
                    youtubeAdSkipRunnable, YOUTUBE_AD_SKIP_POLL_MS
                )
            }
        }
    }

    private fun shouldRunYoutubeAdSkipLoop(): Boolean {
        if (!FeatureFlags.isActive(
                FeatureFlags.FEATURE_YOUTUBE_AD_SKIP,
                packageName = YOUTUBE_PACKAGE,
                context = this,
                settings = SettingsRepository.create(this),
                stateStore = stateStore
            )
        ) return false
        val rootPkg = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        val focusedPkg = lastFocusedPackage
        return rootPkg == YOUTUBE_PACKAGE || focusedPkg == YOUTUBE_PACKAGE
    }

    private fun trySkipYoutubeAdIfActive() {
        if (!shouldRunYoutubeAdSkipLoop()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastYoutubeAdSkipAt < YOUTUBE_AD_SKIP_DEBOUNCE_MS) return
        val clicked = trySkipYoutubeAdOnce()
        if (clicked) {
            lastYoutubeAdSkipAt = now
            val announcement = if (
                Locale.getDefault().language.startsWith("ar")
            ) {
                "تم تخطي الإعلان"
            } else {
                "Ad skipped"
            }
            A11ySpeechCoordinator.speakAnnouncement(
                context = this,
                text = announcement,
                isInterruptAllowed = true
            )
        }
    }

    private fun trySkipYoutubeAdOnce(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        return try {
            pending.add(root)
            var visited = 0
            while (pending.isNotEmpty() && visited < 250) {
                val node = pending.removeFirst()
                visited++
                val isRoot = node === root
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val vid = runCatching { node.viewIdResourceName }.getOrNull()
                    .orEmpty()
                val matched = isYoutubeSkipLabel(text) ||
                    isYoutubeSkipLabel(desc) ||
                    vid.contains("skip_ad", ignoreCase = true) ||
                    vid.contains("skipAd", ignoreCase = true)
                if (matched) {
                    val ok = clickNodeOrClickableAncestor(node)
                    if (ok) {
                        if (!isRoot) {
                            SafeNodeWalker.safeRecycle(node)
                        }
                        return true
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull()
                        ?: continue
                    pending.addLast(child)
                }
                if (!isRoot) {
                    SafeNodeWalker.safeRecycle(node)
                }
            }
            false
        } finally {
            while (pending.isNotEmpty()) {
                val n = pending.removeFirst()
                if (n !== root) SafeNodeWalker.safeRecycle(n)
            }
            SafeNodeWalker.safeRecycle(root)
        }
    }

    private fun isYoutubeSkipLabel(value: String): Boolean {
        if (value.isBlank()) return false
        val v = value.lowercase(Locale.getDefault())
        return v == "skip ad" || v == "skip ads" ||
            v == "تخطي الإعلان" || v == "تخطي الإعلانات" ||
            (v.startsWith("تخطي") && v.contains("إعلان")) ||
            v.contains("skip ad")
    }

    private fun clickNodeOrClickableAncestor(
        node: AccessibilityNodeInfo
    ): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                val ok = runCatching {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (current !== node) {
                    SafeNodeWalker.safeRecycle(current)
                }
                return ok
            }
            val parent = runCatching { current.parent }.getOrNull()
            if (current !== node) {
                SafeNodeWalker.safeRecycle(current)
            }
            current = parent
            depth++
        }
        if (current != null && current !== node) {
            SafeNodeWalker.safeRecycle(current)
        }
        return false
    }


    companion object {
        private const val TAG = "NATEQ_A11Y"

        private const val SENDER_HINT_CACHE_TTL_MS = 1500L
        private const val SENDER_HINT_MAX_NODES = 60
        private const val SENDER_HINT_TIMEOUT_MS = 8L

        /** حدّ خنق أحداث TYPE_WINDOW_CONTENT_CHANGED (ع٤١-ب0.4). */
        private const val CONTENT_CHANGE_THROTTLE_MS = 80L

        /** مفتاح جهاز البلوتوث عند تعذّر قراءة العنوان (ع٤١-ب9). */
        private const val UNKNOWN_DEVICE_KEY = "unknown"

        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_AD_SKIP_POLL_MS = 1500L
        private const val YOUTUBE_AD_SKIP_DEBOUNCE_MS = 2500L

        /** مهلة موحدة لانتظار تحديث الرسالة بعد نقرة توسعة صريحة. */
        internal const val READ_MORE_WAIT_MS = 320L
        internal const val READ_MORE_FOCUS_RETRY_MS = 50L
        internal const val READ_MORE_GRACE_MS = 40L
    }
}
