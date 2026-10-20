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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * أتمتة شاشة إعدادات النطق (TTS) عبر خدمة إمكانية الوصول
 * لتبديل محرك النظام بخفاء وأمان تام ودون غلق أي شاشات أخرى (الجولة 48).
 */
object TtsSettingsAutomation {

    private const val TAG = "TtsSettingsAutomation"
    private const val TIMEOUT_MS = 10_000L

    private val automationMutex = Mutex()
    private val contentChangeSignal =
        AtomicReference<CompletableDeferred<Unit>?>()

    val pendingTargetPackage = AtomicReference<String?>(null)

    data class AutomationResult(
        val success: Boolean,
        val reason: String? = null
    )

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
        // حذف علامات الاتجاه
        str = str.replace(Regex("[\u200E\u200F\u202A-\u202E\uFEFF]"), "")
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
     * تنفيذ دورة الأتمتة كاملة لاختيار [targetPackage] كمحرك افتراضي.
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

        // إظهار نافذة الغطاء المعتمة لامتصاص اللمس وإخفاء الأتمتة
        AutomationOverlay.show(service)

        var lastStep = "init"
        var lastActivePkg: String? = null
        val candidates = resolveTargetCandidateLabels(context, targetPackage)

        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                lastStep = "launch_intent"
                val launchOptions =
                    ActivityOptions.makeCustomAnimation(context, 0, 0)
                        .toBundle()

                val pathsToTry = listOf(
                    Intent("com.android.settings.TTS_SETTINGS"),
                    Intent("com.samsung.android.settings.TTS_SETTINGS"),
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )

                var root: AccessibilityNodeInfo? = null
                var successfulIntent: Intent? = null

                for (intent in pathsToTry) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent, launchOptions)
                    } catch (_: Exception) {
                        continue
                    }

                    root = waitFor(4000L) {
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
                        successfulIntent = intent
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
                    }

                    lastStep = "locate_target_or_preferred_engine"
                    var targetNode = findNodeByCandidates(
                        root = root,
                        candidates = candidates,
                        excludePatterns = listOf("إعدادات", "settings", "gear")
                    )

                    if (targetNode == null) {
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

                        lastStep = "search_target_in_list"
                        targetNode = findNodeByCandidates(
                            root = root,
                            candidates = candidates,
                            excludePatterns = listOf(
                                "إعدادات", "settings", "gear"
                            )
                        )

                        var scrollAttempts = 0
                        while (targetNode == null && scrollAttempts < 5) {
                            scrollAttempts++
                            val scrolled = AccessibilityBridge.scrollForward()
                            if (!scrolled) break
                            delay(300L)
                            SafeNodeWalker.safeRecycle(root)
                            root = service.rootInActiveWindow ?: break
                            targetNode = findNodeByCandidates(
                                root = root,
                                candidates = candidates,
                                excludePatterns = listOf(
                                    "إعدادات", "settings", "gear"
                                )
                            )
                        }
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

                    // فحص حوار التأكيد التحذيري مع حلقة تحقق سريعة (4 ثوانٍ)
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

                    // التنقل للخلف بدقة: فقط ما دامت النافذة تابعة للإعدادات
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
                dumpFailureTree(
                    context = context,
                    service = service,
                    step = reasonStr,
                    activePkg = lastActivePkg,
                    candidates = candidates
                )
                navigateBackOutOfSettings(service)
                return AutomationResult(
                    success = false,
                    reason = reasonStr
                )
            }

            return result
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled exception in TtsSettingsAutomation", t)
            dumpFailureTree(
                context = context,
                service = service,
                step = "exception: ${t.message} ($lastStep)",
                activePkg = lastActivePkg,
                candidates = candidates
            )
            navigateBackOutOfSettings(service)
            return AutomationResult(
                success = false,
                reason = "استثناء أثناء الأتمتة: ${t.message}"
            )
        } finally {
            AutomationOverlay.hide(service)
            AppPolicy.AutomationSession.stop(sessionToken)
            service.applyServiceInfo()
            A11ySpeechCoordinator.isSuppressed = false
            automationMutex.unlock()
        }
    }

    /**
     * العودة للخلف بدقة طالما أن حزمة الواجهة الحالية تتبع الإعدادات فقط.
     */
    private suspend fun navigateBackOutOfSettings(
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

    private fun resolvePreferredEngineTitles(
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
    ) {
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
                    return
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
        } catch (_: Exception) {}
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
