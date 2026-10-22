package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.aymankhattab.nateq.core.audio.phone.CallRecordManager
import com.aymankhattab.nateq.core.audio.phone.LordInCallService
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * أتمتة شاشة إعدادات النطق (TTS) عبر خدمة إمكانية الوصول
 * لتبديل محرك النظام بخفاء وأمان تام ودون غلق أي شاشات أخرى (الجولة 48 و51).
 */
object TtsSettingsAutomation {

    private const val TAG = "TtsSettingsAutomation"
    private const val SESSION_TIMEOUT_MS = 10_000L

    private val automationMutex = Mutex()
    private val contentChangeSignal =
        AtomicReference<CompletableDeferred<Unit>?>()

    val pendingTargetPackage = AtomicReference<String?>(null)

    @Volatile
    var lastFailureStep: String? = null
    @Volatile
    var lastFailureReason: String? = null
    @Volatile
    var lastFailureDevice: String? = null
    @Volatile
    var lastDumpPath: String? = null
    @Volatile
    var lastDumpUri: Uri? = null
    @Volatile
    var lastFailureTargetPackage: String? = null

    data class AutomationResult(
        val success: Boolean,
        val reason: String? = null
    )

    data class DumpResult(
        val filePath: String?,
        val uri: Uri?
    )

    data class EngineSwitchRecipe(
        val routeAction: String,
        val locateMethod: String,
        val listUiType: String
    ) {
        fun toJson(): String {
            val obj = JSONObject()
            obj.put("routeAction", routeAction)
            obj.put("locateMethod", locateMethod)
            obj.put("listUiType", listUiType)
            return obj.toString()
        }

        companion object {
            fun fromJson(str: String?): EngineSwitchRecipe? {
                if (str.isNullOrBlank()) return null
                return try {
                    val obj = JSONObject(str)
                    EngineSwitchRecipe(
                        routeAction = obj.optString("routeAction", ""),
                        locateMethod = obj.optString("locateMethod", ""),
                        listUiType = obj.optString("listUiType", "")
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * استقبال أحداث النوافذ والمحتوى من خدمة إمكانية الوصول.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            contentChangeSignal.getAndSet(null)?.complete(Unit)
        }
    }

    /**
     * فحص ما إذا كان هناك مانع يوجب تأجيل التبديل (مكالمة أو تسجيل نشط).
     */
    fun shouldPostponeSwitch(context: Context): Boolean {
        val am =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inCall = am?.mode == AudioManager.MODE_IN_CALL ||
            am?.mode == AudioManager.MODE_IN_COMMUNICATION ||
            LordInCallService.currentCall != null
        if (inCall) return true

        if (CallRecordManager.isRecording()) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && am != null) {
            try {
                if (am.activeRecordingConfigurations.isNotEmpty()) return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * استدعاء التحقق التلقائي عند انتهاء المكالمة أو التسجيل
     * لتنفيذ التبديل المؤجل.
     */
    suspend fun checkAndRunPendingSwitch(context: Context) {
        val target = pendingTargetPackage.getAndSet(null) ?: return
        if (shouldPostponeSwitch(context)) {
            pendingTargetPackage.set(target)
            return
        }
        run(context, target)
    }

    /**
     * تطبيع شامل للنصوص لمطابقة أسماء المحركات وعناوين الإعدادات بدقة تامة.
     */
    fun normalizeForMatch(s: String): String {
        if (s.isEmpty()) return ""
        var str = s
        // حذف علامات الاتجاه والمحارف غير المرئية
        str = str.replace(
            Regex("[\u200B-\u200F\u202A-\u202E\uFEFF\u00AD]"),
            ""
        )
        // حذف التشكيل والتطويل
        str = str.replace(Regex("[\u064B-\u065F\u0670\u0640]"), "")
        // توحيد الألف
        str = str.replace(Regex("[أإآٱ]"), "ا")
        // توحيد الياء والألف المقصورة
        str = str.replace(Regex("[ىي]"), "ي")
        // توحيد التاء المربوطة والهاء
        str = str.replace(Regex("[ة]"), "ه")
        // تصغير الأحرف اللاتينية
        str = str.lowercase(Locale.ROOT)
        // توحيد الشرطات
        str = str.replace(Regex("[-‐–—]"), "-")
        // توحيد المسافات
        str = str.replace(Regex("\\s+"), " ")
        return str.trim()
    }

    /**
     * جمع كافة الأسماء المرشحة للمحرك الهدف.
     */
    fun resolveTargetCandidateLabels(
        context: Context,
        targetPackage: String
    ): List<String> {
        val labels = mutableListOf<String>()
        labels.add(targetPackage)

        // 1. TextToSpeech EngineInfo label
        try {
            val tts = TextToSpeech(context, null)
            for (info in tts.engines) {
                if (info.name.equals(targetPackage, ignoreCase = true)) {
                    if (info.label.isNotBlank()) labels.add(info.label)
                }
            }
            tts.shutdown()
        } catch (_: Exception) {}

        // 2. PackageManager label
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            val appLabel = pm.getApplicationLabel(appInfo).toString()
            if (appLabel.isNotBlank()) labels.add(appLabel)
        } catch (_: Exception) {}

        // 3. SystemTtsEngineController
        try {
            val installed =
                SystemTtsEngineController.getInstalledEngines(context)
            for (item in installed) {
                if (item.packageName.equals(targetPackage, ignoreCase = true)) {
                    if (item.label.isNotBlank()) labels.add(item.label)
                }
            }
        } catch (_: Exception) {}

        return labels.distinct()
    }

    /**
     * انتظار شرط محدد على شجرة العناصر بنافذة زمنية واستطلاع متكرر.
     */
    suspend fun waitFor(
        timeoutMs: Long,
        pollMs: Long = 150L,
        predicate: () -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val node = predicate()
            if (node != null) return node
            delay(pollMs)
        }
        return null
    }

    /**
     * التحقق من هوية شاشة النطق الحالية (الأمر 2 بند 1).
     * تفحص عناوين المحرك المفضل، معدل الكلام، تشغيل، أو أسماء المحركات المثبتة.
     */
    fun isTtsSettingsScreen(
        context: Context,
        root: AccessibilityNodeInfo?,
        settingsPkg: String
    ): Boolean {
        if (root == null) return false
        val candidates = mutableListOf<String>()

        // 1. عناوين المحرك المفضل
        candidates.addAll(resolvePreferredEngineTitles(context, settingsPkg))

        // 2. عناوين من حزمة الإعدادات
        if (settingsPkg.isNotBlank()) {
            runCatching {
                val res = context.packageManager
                    .getResourcesForApplication(settingsPkg)
                val rateId = res.getIdentifier(
                    "tts_default_rate_title", "string", settingsPkg
                )
                if (rateId != 0) {
                    val s = res.getString(rateId)
                    if (s.isNotBlank()) candidates.add(s)
                }
                val playId = res.getIdentifier(
                    "tts_play_example_title", "string", settingsPkg
                )
                if (playId != 0) {
                    val s = res.getString(playId)
                    if (s.isNotBlank()) candidates.add(s)
                }
                val pitchId = res.getIdentifier(
                    "tts_default_pitch_title", "string", settingsPkg
                )
                if (pitchId != 0) {
                    val s = res.getString(pitchId)
                    if (s.isNotBlank()) candidates.add(s)
                }
            }
        }

        // 3. نصوص قياسية عربية وإنجليزية
        candidates.addAll(
            listOf(
                "المحرك المفضل",
                "محرك تحويل النص إلى كلام المفضل",
                "المحرك المعتمد",
                "Preferred engine",
                "Preferred text-to-speech engine",
                "معدل سرعة الكلام",
                "معدل الكلام",
                "سرعة الكلام",
                "Speech rate",
                "Speech speed",
                "درجة الصوت",
                "طبقة الصوت",
                "Pitch",
                "استماع إلى مثال",
                "تشغيل",
                "Play",
                "Play example",
                "Listen to an example",
                "تحويل النص إلى كلام",
                "تحويل النص الى كلام",
                "Text-to-speech",
                "Text-to-speech output",
                "مخرجات تحويل النص إلى كلام"
            )
        )

        // 4. أسماء وتسميات المحركات المثبتة
        val installed = runCatching {
            SystemTtsEngineController.getInstalledEngines(context)
        }.getOrDefault(emptyList())
        for (eng in installed) {
            if (eng.label.isNotBlank()) candidates.add(eng.label)
            if (eng.packageName.isNotBlank()) candidates.add(eng.packageName)
        }

        val match = findNodeByCandidates(root, candidates)
        val found = match != null
        if (match != null) {
            SafeNodeWalker.safeRecycle(match)
        }
        return found
    }

    /**
     * فحص ما إذا كان صف المحرك أو عقدة الراديو محددة (isChecked == true).
     */
    fun isTargetChecked(
        root: AccessibilityNodeInfo,
        candidates: List<String>
    ): Boolean {
        val node = findNodeByCandidates(root, candidates) ?: return false
        try {
            if (node.isChecked) return true
            val parent = node.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    val cls = child.className?.toString().orEmpty()
                    val isRadio =
                        cls.contains("RadioButton") || child.isCheckable
                    if (isRadio && child.isChecked) {
                        SafeNodeWalker.safeRecycle(child)
                        SafeNodeWalker.safeRecycle(parent)
                        return true
                    }
                    SafeNodeWalker.safeRecycle(child)
                }
                SafeNodeWalker.safeRecycle(parent)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isChecked) {
                    SafeNodeWalker.safeRecycle(child)
                    return true
                }
                SafeNodeWalker.safeRecycle(child)
            }
            return false
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    /**
     * العثور على أقرب حاوية قابلة للتمرير (RecyclerView/ListView/ScrollView).
     */
    fun findScrollableContainer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallbackScrollable: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val cls = curr.className?.toString().orEmpty()
            val isScrollList = cls.contains("RecyclerView") ||
                cls.contains("ListView") ||
                cls.contains("ScrollView") ||
                cls.contains("AbsListView")
            val hasAction = (curr.actions and
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0
            if (isScrollList && (curr.isScrollable || hasAction)) {
                return curr
            }
            if (fallbackScrollable == null &&
                (curr.isScrollable || hasAction)
            ) {
                fallbackScrollable = curr
            }
            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return fallbackScrollable
    }

    /**
     * تمرير القائمة للأعلى أولاً ثم البحث للأمام بخطوات قصيرة حتى 8 مرات.
     */
    suspend fun scrollAndSearchForTarget(
        service: NateqAccessibilityService,
        candidates: List<String>,
        excludePatterns: List<String>
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        var target = findNodeByCandidates(root, candidates, excludePatterns)
        if (target != null) {
            SafeNodeWalker.safeRecycle(root)
            return target
        }

        val container = findScrollableContainer(root)
        if (container == null) {
            SafeNodeWalker.safeRecycle(root)
            return null
        }

        // 1. ارجع للأعلى أولاً حتى لا يتحرك
        var backwardSteps = 0
        while (backwardSteps < 10) {
            val scrolled = container.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            if (!scrolled) break
            delay(150L)
            backwardSteps++
        }

        // فحص بعد العودة للأعلى
        val topRoot = service.rootInActiveWindow
        if (topRoot != null) {
            target = findNodeByCandidates(topRoot, candidates, excludePatterns)
            if (target != null) {
                SafeNodeWalker.safeRecycle(topRoot)
                SafeNodeWalker.safeRecycle(root)
                return target
            }
            SafeNodeWalker.safeRecycle(topRoot)
        }

        // 2. مرّر للأمام بخطوات قصيرة حتى 8 مرات مع انتظار 250ms
        for (step in 1..8) {
            val scrolled = container.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
            if (!scrolled) {
                AccessibilityBridge.scrollForward()
            }
            delay(250L)
            val curRoot = service.rootInActiveWindow ?: continue
            target = findNodeByCandidates(curRoot, candidates, excludePatterns)
            if (target != null) {
                SafeNodeWalker.safeRecycle(curRoot)
                SafeNodeWalker.safeRecycle(root)
                return target
            }
            SafeNodeWalker.safeRecycle(curRoot)
        }

        SafeNodeWalker.safeRecycle(root)
        return null
    }

    /**
     * تنفيذ دورة الأتمتة كاملة لاختيار [targetPackage] مع إعادة المحاولة
     * للجلسة كلها حتى مرتين (الأمر 2 بند 2).
     */
    suspend fun run(
        context: Context,
        targetPackage: String
    ): AutomationResult {
        // فحص موانع المقاطعة أولاً
        if (shouldPostponeSwitch(context)) {
            pendingTargetPackage.set(targetPackage)
            speakImmediate(
                context,
                "سيتم التبديل بعد انتهاء المكالمة أو التسجيل"
            )
            return AutomationResult(
                success = false,
                reason = "تم تأجيل التبديل لوجود مكالمة أو تسجيل نشط"
            )
        }

        if (!automationMutex.tryLock()) {
            return AutomationResult(
                success = false,
                reason = "جلسة أتمتة أخرى جارية حالياً"
            )
        }

        val service = AccessibilityBridge.activeService()
        if (service == null) {
            automationMutex.unlock()
            return AutomationResult(
                success = false,
                reason = "خدمة إمكانية الوصول غير متصلة"
            )
        }

        try {
            var lastResult: AutomationResult? = null
            for (attempt in 1..2) {
                val result = executeSingleSession(
                    context = context,
                    service = service,
                    targetPackage = targetPackage,
                    attempt = attempt
                )
                if (result.success) {
                    return result
                }
                lastResult = result
                if (result.reason?.contains("تأجيل") == true ||
                    result.reason?.contains("مكالمة") == true
                ) {
                    return result
                }
                if (attempt < 2) {
                    Log.w(
                        TAG,
                        "Session attempt 1 failed (${result.reason}). " +
                            "Waiting 700ms and retrying..."
                    )
                    navigateBackOutOfSettings(service)
                    delay(700L)
                }
            }

            val failReason = lastResult?.reason ?: "فشل غير معروف"
            handleAutomationFailure(
                context = context,
                service = service,
                targetPackage = targetPackage,
                reason = failReason
            )
            return lastResult ?: AutomationResult(
                success = false,
                reason = failReason
            )
        } finally {
            automationMutex.unlock()
        }
    }

    /**
     * تنفيذ جلسة أتمتة واحدة مع تسجيل مسار النجاح وتحديث الوصفة.
     */
    private suspend fun executeSingleSession(
        context: Context,
        service: NateqAccessibilityService,
        targetPackage: String,
        attempt: Int
    ): AutomationResult {
        val sessionToken = UUID.randomUUID().toString()
        val resolver = context.contentResolver

        val initialRate = runCatching {
            Settings.Secure.getString(
                resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_RATE
            )
        }.getOrNull()
        val initialPitch = runCatching {
            Settings.Secure.getString(
                resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_PITCH
            )
        }.getOrNull()
        val initialLocale = runCatching {
            Settings.Secure.getString(
                resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_LOCALE
            )
        }.getOrNull()

        A11ySpeechCoordinator.isSuppressed = true
        AppPolicy.AutomationSession.start(sessionToken)
        service.applyServiceInfo()

        AutomationOverlay.show(service)

        var lastStep = "init"
        var lastActivePkg: String? = null
        val candidates = resolveTargetCandidateLabels(context, targetPackage)

        // قراءة الوصفة المسجلة لهذا الجهاز إن وُجدت
        val settingsRepo = SettingsRepository.create(context)
        val savedRecipeStr = settingsRepo.getEngineSwitchRecipe(
            Build.MODEL, Build.VERSION.SDK_INT
        )
        val savedRecipe = EngineSwitchRecipe.fromJson(savedRecipeStr)

        var detectedLocateMethod = "default_title"
        var detectedListUiType = "page"
        var successfulAction = ""

        try {
            val result = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                lastStep = "launch_intent"
                val launchOptions =
                    ActivityOptions.makeCustomAnimation(context, 0, 0)
                        .toBundle()

                val defaultPaths = listOf(
                    Intent("com.android.settings.TTS_SETTINGS"),
                    Intent("com.samsung.android.settings.TTS_SETTINGS"),
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS)
                )

                // ترتيب المسارات بحسب الوصفة في المحاولة الأولى
                val pathsToTry = mutableListOf<Intent>()
                if (attempt == 1 && savedRecipe != null &&
                    savedRecipe.routeAction.isNotBlank()
                ) {
                    pathsToTry.add(Intent(savedRecipe.routeAction))
                    for (p in defaultPaths) {
                        if (p.action != savedRecipe.routeAction) {
                            pathsToTry.add(p)
                        }
                    }
                } else if (attempt == 2) {
                    // في المحاولة الثانية نعكس أو نجرّب مساراً بديلاً
                    pathsToTry.addAll(defaultPaths.reversed())
                } else {
                    pathsToTry.addAll(defaultPaths)
                }

                var root: AccessibilityNodeInfo? = null
                var successfulIntent: Intent? = null

                for (intent in pathsToTry) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent, launchOptions)
                    } catch (_: Exception) {
                        continue
                    }

                    root = waitFor(3500L) {
                        val r = service.rootInActiveWindow
                        val pkg = r?.packageName?.toString()
                        lastActivePkg = pkg
                        if (pkg != null && isSettingsPackage(pkg)) {
                            r
                        } else {
                            if (r != null) SafeNodeWalker.safeRecycle(r)
                            null
                        }
                    }

                    if (root != null) {
                        val pkg = root.packageName?.toString().orEmpty()
                        // التحقق من هوية الشاشة المفتوحة
                        val isDirectTts = intent.action?.contains(
                            "TTS_SETTINGS"
                        ) == true
                        if (isDirectTts &&
                            !isTtsSettingsScreen(context, root, pkg)
                        ) {
                            Log.w(
                                TAG,
                                "Direct TTS route rejected by screen " +
                                    "identity verification: ${intent.action}"
                            )
                            SafeNodeWalker.safeRecycle(root)
                            root = null
                            service.performGlobalAction(
                                AccessibilityService.GLOBAL_ACTION_BACK
                            )
                            delay(200L)
                            continue
                        }
                        successfulIntent = intent
                        successfulAction = intent.action.orEmpty()
                        break
                    }
                }

                if (root == null) {
                    return@withTimeoutOrNull AutomationResult(
                        success = false,
                        reason = "لم تُفتح شاشة إعدادات النطق عبر أي مسار"
                    )
                }

                try {
                    // فحص فوري: الخروج عن الشاشة إلى شاشة حساسة
                    if (isUnintendedSettingsScreen(service, root)) {
                        service.performGlobalAction(
                            AccessibilityService.GLOBAL_ACTION_BACK
                        )
                        return@withTimeoutOrNull AutomationResult(
                            success = false,
                            reason = "تم الخروج عن شاشة إعدادات النطق"
                        )
                    }

                    // إن فُتحت شاشة إمكانية الوصول، ابحث عن تحويل النص إلى كلام
                    if (successfulIntent?.action ==
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    ) {
                        val ttsEntryNode = findNodeByCandidates(
                            root,
                            listOf(
                                "تحويل النص الى كلام",
                                "تحويل النص إلى كلام",
                                "text-to-speech",
                                "text-to-speech output",
                                "مخرجات تحويل النص إلى كلام",
                                "tts"
                            )
                        )
                        if (ttsEntryNode != null) {
                            clickNodeOrAncestor(ttsEntryNode)
                            SafeNodeWalker.safeRecycle(ttsEntryNode)
                            delay(400L)
                            SafeNodeWalker.safeRecycle(root)
                            root = service.rootInActiveWindow
                            if (root == null) {
                                return@withTimeoutOrNull AutomationResult(
                                    success = false,
                                    reason = "فشل الانتقال إلى إعدادات النطق"
                                )
                            }
                        }
                    } else if (successfulIntent?.action ==
                        Settings.ACTION_SETTINGS
                    ) {
                        // شاشة الإعدادات الرئيسية
                        var ttsEntryNode = findNodeByCandidates(
                            root,
                            listOf(
                                "تحويل النص الى كلام",
                                "تحويل النص إلى كلام",
                                "text-to-speech",
                                "tts"
                            )
                        )
                        if (ttsEntryNode == null) {
                            val a11yNode = findNodeByCandidates(
                                root,
                                listOf(
                                    "إمكانية الوصول",
                                    "امكانية الوصول",
                                    "سهولة الاستخدام",
                                    "accessibility"
                                )
                            )
                            if (a11yNode != null) {
                                clickNodeOrAncestor(a11yNode)
                                SafeNodeWalker.safeRecycle(a11yNode)
                                delay(400L)
                                val a11yRoot = service.rootInActiveWindow
                                if (a11yRoot != null) {
                                    SafeNodeWalker.safeRecycle(root)
                                    root = a11yRoot
                                    ttsEntryNode = findNodeByCandidates(
                                        root,
                                        listOf(
                                            "تحويل النص الى كلام",
                                            "تحويل النص إلى كلام",
                                            "text-to-speech",
                                            "tts"
                                        )
                                    )
                                }
                            }
                        }
                        if (ttsEntryNode != null) {
                            clickNodeOrAncestor(ttsEntryNode)
                            SafeNodeWalker.safeRecycle(ttsEntryNode)
                            delay(400L)
                            SafeNodeWalker.safeRecycle(root)
                            root = service.rootInActiveWindow
                            if (root == null) {
                                return@withTimeoutOrNull AutomationResult(
                                    success = false,
                                    reason = "فشل الانتقال إلى إعدادات النطق"
                                )
                            }
                        }
                    }

                    lastStep = "locate_target_or_preferred_engine"
                    var targetNode = findNodeByCandidates(
                        root = root,
                        candidates = candidates,
                        excludePatterns = listOf("إعدادات", "settings", "gear")
                    )

                    if (targetNode != null) {
                        detectedLocateMethod = "direct_row"
                    } else {
                        // صف المحركات غير معروض؛ البحث عن المحرك المفضل
                        lastStep = "locate_preferred_engine"
                        val preferredTitles = resolvePreferredEngineTitles(
                            context = context,
                            settingsPkg = root.packageName?.toString().orEmpty()
                        )
                        var prefNode = findNodeByCandidates(
                            root = root,
                            candidates = preferredTitles
                        )
                        if (prefNode != null) {
                            detectedLocateMethod = "resource_title"
                        }

                        // احتياط: البحث عن صف يحوي اسم المحرك الحالي
                        if (prefNode == null) {
                            val currEngine =
                                SystemTtsEngineController
                                    .currentSystemEngine(context)
                            if (currEngine != null) {
                                val currCandidates =
                                    resolveTargetCandidateLabels(
                                        context,
                                        currEngine
                                    )
                                prefNode = findNodeByCandidates(
                                    root = root,
                                    candidates = currCandidates
                                )
                                if (prefNode != null) {
                                    detectedLocateMethod =
                                        "current_engine_summary"
                                }
                            }
                        }

                        if (prefNode == null) {
                            return@withTimeoutOrNull AutomationResult(
                                success = false,
                                reason = "لم يوجد صف المحرك المفضل"
                            )
                        }

                        lastStep = "click_preferred_engine"
                        val clickedPref = clickNodeOrAncestor(prefNode)
                        SafeNodeWalker.safeRecycle(prefNode)
                        if (!clickedPref) {
                            return@withTimeoutOrNull AutomationResult(
                                success = false,
                                reason = "فشل النقر على صف المحرك المفضل"
                            )
                        }

                        // انتظار ظهور القائمة أو الحوار
                        val listRoot = waitFor(3000L) {
                            val r = service.rootInActiveWindow
                            if (r != null) {
                                val foundAny = findNodeByCandidates(
                                    r,
                                    candidates,
                                    listOf("إعدادات", "settings", "gear")
                                )
                                if (foundAny != null) {
                                    SafeNodeWalker.safeRecycle(foundAny)
                                    r
                                } else {
                                    SafeNodeWalker.safeRecycle(r)
                                    null
                                }
                            } else null
                        }

                        if (listRoot != null) {
                            SafeNodeWalker.safeRecycle(root)
                            root = listRoot
                        } else {
                            SafeNodeWalker.safeRecycle(root)
                            root = service.rootInActiveWindow
                                ?: return@withTimeoutOrNull AutomationResult(
                                    success = false,
                                    reason = "لم تظهر قائمة المحركات بعد النقر"
                                )
                        }

                        // فحص نوع العرض: حوار أم صفحة كاملة
                        val hasConfirm = findConfirmationButton(root) != null
                        detectedListUiType =
                            if (hasConfirm) "dialog" else "page"

                        lastStep = "search_target_in_list"
                        targetNode = scrollAndSearchForTarget(
                            service = service,
                            candidates = candidates,
                            excludePatterns = listOf(
                                "إعدادات", "settings", "gear"
                            )
                        )
                    }

                    if (targetNode == null) {
                        return@withTimeoutOrNull AutomationResult(
                            success = false,
                            reason = "لم يوجد المحرك الهدف في القائمة"
                        )
                    }

                    lastStep = "click_target_engine"
                    val clicked = clickNodeOrAncestor(targetNode)
                    SafeNodeWalker.safeRecycle(targetNode)
                    if (!clicked) {
                        return@withTimeoutOrNull AutomationResult(
                            success = false,
                            reason = "فشل النقر على صف المحرك الهدف"
                        )
                    }

                    // التحقق بـ isChecked و tts_default_synth (الأمر 2 بند 4)
                    lastStep = "poll_confirmation_or_verify"
                    var verified = false
                    val pollStart = SystemClock.uptimeMillis()
                    while (SystemClock.uptimeMillis() - pollStart < 4000L) {
                        val current = Settings.Secure.getString(
                            resolver,
                            SystemTtsEngineController.KEY_TTS_DEFAULT_SYNTH
                        )
                        if (current == targetPackage) {
                            verified = true
                            break
                        }

                        val winRoot = service.rootInActiveWindow
                        if (winRoot != null) {
                            val confirmBtn = findConfirmationButton(winRoot)
                            if (confirmBtn != null) {
                                clickNodeOrAncestor(confirmBtn)
                                SafeNodeWalker.safeRecycle(confirmBtn)
                            }

                            // التحقق بـ isChecked أيضاً
                            if (isTargetChecked(winRoot, candidates)) {
                                var secureConfirmed = false
                                val confirmStart = SystemClock.uptimeMillis()
                                while (SystemClock.uptimeMillis() -
                                    confirmStart < 3000L
                                ) {
                                    val secureSynth = Settings.Secure.getString(
                                        resolver,
                                        SystemTtsEngineController
                                            .KEY_TTS_DEFAULT_SYNTH
                                    )
                                    if (secureSynth == targetPackage) {
                                        secureConfirmed = true
                                        break
                                    }
                                    delay(150L)
                                }
                                if (!secureConfirmed) {
                                    val finalSynth = Settings.Secure.getString(
                                        resolver,
                                        SystemTtsEngineController
                                            .KEY_TTS_DEFAULT_SYNTH
                                    )
                                    Log.w(
                                        TAG,
                                        "Target row isChecked=true for " +
                                            "$targetPackage, but " +
                                            "tts_default_synth remained " +
                                            "'$finalSynth'"
                                    )
                                }
                                verified = true
                                SafeNodeWalker.safeRecycle(winRoot)
                                break
                            }
                            SafeNodeWalker.safeRecycle(winRoot)
                        }
                        delay(150L)
                    }

                    if (!verified) {
                        return@withTimeoutOrNull AutomationResult(
                            success = false,
                            reason = "لم يتغير tts_default_synth"
                        )
                    }

                    // استعادة الإعدادات الصوتية إن طرأ عليها تعديل
                    restoreSecureValuesIfNeeded(
                        resolver, initialRate, initialPitch, initialLocale
                    )

                    // حفظ الوصفة الناجحة لهذا الجهاز (الأمر 2 بند 5)
                    val recipe = EngineSwitchRecipe(
                        routeAction = successfulAction,
                        locateMethod = detectedLocateMethod,
                        listUiType = detectedListUiType
                    )
                    settingsRepo.setEngineSwitchRecipe(
                        Build.MODEL, Build.VERSION.SDK_INT, recipe.toJson()
                    )

                    // التنقل للخلف بدقة
                    navigateBackOutOfSettings(service)

                    AutomationResult(success = true)
                } finally {
                    if (root != null) {
                        SafeNodeWalker.safeRecycle(root)
                    }
                }
            }

            if (result == null || !result.success) {
                val reasonStr = result?.reason ?: "انتهت المهلة عند $lastStep"
                lastFailureStep = lastStep
                return AutomationResult(
                    success = false,
                    reason = reasonStr
                )
            }

            return result
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled exception in TtsSettingsAutomation", t)
            lastFailureStep = "exception: ${t.message} ($lastStep)"
            return AutomationResult(
                success = false,
                reason = "استثناء أثناء الأتمتة: ${t.message}"
            )
        } finally {
            AutomationOverlay.hide(service)
            AppPolicy.AutomationSession.stop(sessionToken)
            service.applyServiceInfo()
            A11ySpeechCoordinator.isSuppressed = false
        }
    }

    /**
     * معالجة الفشل النهائي بعد انتهاء المحاولتين والنطق الصريح (الأمر 2 بند 7).
     */
    private fun handleAutomationFailure(
        context: Context,
        service: NateqAccessibilityService,
        targetPackage: String,
        reason: String
    ) {
        val candidates = resolveTargetCandidateLabels(context, targetPackage)
        val activePkg = service.rootInActiveWindow?.packageName?.toString()
        val dumpRes = dumpFailureTree(
            context = context,
            service = service,
            step = reason,
            activePkg = activePkg,
            candidates = candidates
        )

        lastFailureStep = reason
        lastFailureReason = reason
        lastFailureDevice = "${Build.MANUFACTURER} ${Build.MODEL} " +
            "(SDK ${Build.VERSION.SDK_INT})"
        lastFailureTargetPackage = targetPackage
        lastDumpPath = dumpRes.filePath
        lastDumpUri = dumpRes.uri

        val pathForSpeech = dumpRes.filePath ?: "التشخيص"
        val speech = "فشل تبديل المحرك عند $reason على جهاز " +
            "${Build.MANUFACTURER} ${Build.MODEL} إصدار " +
            "${Build.VERSION.SDK_INT}. مسار التفريغ $pathForSpeech"
        speakImmediate(context, speech)
    }

    /**
     * العودة للخلف بدقة طالما أن حزمة الواجهة الحالية تتبع الإعدادات فقط.
     */
    suspend fun navigateBackOutOfSettings(
        service: NateqAccessibilityService
    ) {
        var count = 0
        while (count < 4) {
            val root = service.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root != null) SafeNodeWalker.safeRecycle(root)
            if (pkg == null || !isSettingsPackage(pkg)) {
                break
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            count++
            delay(150L)
        }
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return AppPolicy.SETTINGS_PACKAGES.contains(lower) ||
            lower.endsWith(".settings")
    }

    private fun isUnintendedSettingsScreen(
        service: NateqAccessibilityService,
        root: AccessibilityNodeInfo
    ): Boolean {
        val sensitiveWords = listOf(
            "security", "امان", "password", "كلمات المرور",
            "privacy", "خصوصيه", "biometric", "بصمه"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (w in service.windows) {
                val t = w.title?.toString()
                if (!t.isNullOrEmpty()) {
                    val norm = normalizeForMatch(t)
                    if (sensitiveWords.any { norm.contains(it) }) return true
                }
            }
        }

        val collected = mutableListOf<AccessibilityNodeInfo>()
        collectTextNodes(root, collected, 10)
        for (node in collected) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val norm = normalizeForMatch("$text $desc")
            if (sensitiveWords.any { norm.contains(it) }) {
                return true
            }
        }
        return false
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        max: Int
    ) {
        if (out.size >= max) return
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (!t.isNullOrBlank() || !d.isNullOrBlank()) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out, max)
            if (out.size >= max) break
        }
    }

    fun resolvePreferredEngineTitles(
        context: Context,
        settingsPkg: String
    ): List<String> {
        val titles = mutableListOf(
            "Preferred engine",
            "Preferred text-to-speech engine",
            "المحرك المفضل",
            "محرك تحويل النص إلى كلام المفضل",
            "المحرك المعتمد"
        )
        if (settingsPkg.isNotBlank()) {
            runCatching {
                val res = context.packageManager
                    .getResourcesForApplication(settingsPkg)
                val id = res.getIdentifier(
                    "tts_default_synth_title", "string", settingsPkg
                )
                if (id != 0) {
                    val resString = res.getString(id)
                    if (resString.isNotBlank()) {
                        titles.add(0, resString)
                    }
                }
            }
        }
        return titles
    }

    fun findNodeByCandidates(
        root: AccessibilityNodeInfo,
        candidates: List<String>,
        excludePatterns: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val normCandidates = candidates.map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        val normExcludes = excludePatterns.map { normalizeForMatch(it) }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var partialMatchNode: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val text = curr.text?.toString().orEmpty()
            val desc = curr.contentDescription?.toString().orEmpty()
            val normText = normalizeForMatch("$text $desc")

            val isExcluded = normExcludes.any { normText.contains(it) }
            if (!isExcluded && normText.isNotEmpty()) {
                // أفضلية للمطابقة التامة
                for (cand in normCandidates) {
                    if (normText == cand) {
                        return curr
                    }
                }
                if (partialMatchNode == null) {
                    for (cand in normCandidates) {
                        if (normText.contains(cand)) {
                            partialMatchNode = curr
                            break
                        }
                    }
                }
            }

            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return partialMatchNode
    }

    private fun findConfirmationButton(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val positiveTexts = listOf(
            "موافق", "ok", "yes", "نعم", "استمرار",
            "متابعه", "تاكيد", "confirm", "continue"
        )

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val id = curr.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
            if (id.endsWith("button1") || id.contains("positive")) {
                return curr
            }
            val text = curr.text?.toString().orEmpty()
            val normText = normalizeForMatch(text)
            if (positiveTexts.contains(normText)) {
                return curr
            }
            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var curr: AccessibilityNodeInfo? = node
        while (curr != null) {
            if (curr.isClickable) {
                val ok = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            val parent = curr.parent
            if (curr !== node) {
                SafeNodeWalker.safeRecycle(curr)
            }
            curr = parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun restoreSecureValuesIfNeeded(
        resolver: android.content.ContentResolver,
        rate: String?,
        pitch: String?,
        locale: String?
    ) {
        if (rate != null) {
            runCatching {
                val current = Settings.Secure.getString(
                    resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_RATE
                )
                if (current != rate) {
                    Settings.Secure.putString(
                        resolver,
                        SystemTtsEngineController.KEY_TTS_DEFAULT_RATE,
                        rate
                    )
                }
            }
        }
        if (pitch != null) {
            runCatching {
                val current = Settings.Secure.getString(
                    resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_PITCH
                )
                if (current != pitch) {
                    Settings.Secure.putString(
                        resolver,
                        SystemTtsEngineController.KEY_TTS_DEFAULT_PITCH,
                        pitch
                    )
                }
            }
        }
        if (locale != null) {
            runCatching {
                val current = Settings.Secure.getString(
                    resolver, SystemTtsEngineController.KEY_TTS_DEFAULT_LOCALE
                )
                if (current != locale) {
                    Settings.Secure.putString(
                        resolver,
                        SystemTtsEngineController.KEY_TTS_DEFAULT_LOCALE,
                        locale
                    )
                }
            }
        }
    }

    private fun speakImmediate(context: Context, text: String) {
        try {
            val audioService =
                Class.forName(
                    "com.aymankhattab.nateq.core.audio.announcement" +
                        ".AnnouncementSpeaker"
                )
            val method = audioService.getMethod(
                "speakImmediate",
                Context::class.java,
                String::class.java
            )
            method.invoke(null, context, text)
        } catch (_: Exception) {}
    }

    /**
     * تفريغ شجرة العناصر وسجل التشخيص في Download/Lord TTS/diagnostics/
     */
    fun dumpFailureTree(
        context: Context,
        service: NateqAccessibilityService?,
        step: String,
        activePkg: String?,
        candidates: List<String>
    ): DumpResult {
        val timestamp = System.currentTimeMillis()
        val fileName = "engine_switch_tree_$timestamp.txt"
        val sb = StringBuilder()
        sb.appendLine("Failure step: $step")
        sb.appendLine("Active package: $activePkg")
        sb.appendLine("Candidates searched: $candidates")
        sb.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("--- First 80 Nodes ---")

        val root = service?.rootInActiveWindow
        if (root != null) {
            try {
                val collected = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(root, collected, 80)
                for ((i, n) in collected.withIndex()) {
                    sb.appendLine(
                        "[$i] text='${n.text}', class='${n.className}', " +
                            "id='${n.viewIdResourceName}', " +
                            "clickable=${n.isClickable}, " +
                            "desc='${n.contentDescription}'"
                    )
                }
            } finally {
                SafeNodeWalker.safeRecycle(root)
            }
        } else {
            sb.appendLine("rootInActiveWindow is null")
        }

        val textData = sb.toString()
        val expectedRelativePath =
            "Download/Lord TTS/diagnostics/$fileName"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "Download/Lord TTS/diagnostics/"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(textData.toByteArray(Charsets.UTF_8))
                    }
                    Log.i(TAG, "Dumped diagnostic tree to MediaStore: $uri")
                    return DumpResult(
                        filePath = expectedRelativePath,
                        uri = uri
                    )
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed writing to MediaStore Downloads: ${e.message}"
                )
            }
        }

        // المسار الخارجي المباشر
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                "Lord TTS/diagnostics"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(textData, Charsets.UTF_8)
            Log.i(TAG, "Dumped diagnostic tree to file: ${file.absolutePath}")
            return DumpResult(
                filePath = file.absolutePath,
                uri = Uri.fromFile(file)
            )
        } catch (_: Exception) {
            return DumpResult(
                filePath = expectedRelativePath,
                uri = null
            )
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        max: Int
    ) {
        if (out.size >= max) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, max)
            if (out.size >= max) break
        }
    }

    /**
     * نافذة غطاء بملء الشاشة لامتصاص اللمس وإخفاء الأتمتة تماماً.
     */
    object AutomationOverlay {
        private var overlayView: View? = null

        fun show(service: AccessibilityService) {
            if (overlayView != null) return
            val wm = service.getSystemService(
                Context.WINDOW_SERVICE
            ) as? WindowManager ?: return

            val view = FrameLayout(service).apply {
                setBackgroundColor(0xEE121212.toInt())
                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val tv = TextView(service).apply {
                    text = "جارٍ تبديل المحرك…"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    gravity = Gravity.CENTER
                }
                addView(
                    tv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                wm.addView(view, params)
                overlayView = view
            } catch (_: Exception) {}
        }

        fun hide(service: AccessibilityService) {
            val v = overlayView ?: return
            val wm = service.getSystemService(
                Context.WINDOW_SERVICE
            ) as? WindowManager
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
            overlayView = null
        }
    }
}
