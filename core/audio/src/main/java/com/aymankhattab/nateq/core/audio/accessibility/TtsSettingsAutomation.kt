package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * أتمتة شاشة إعدادات النطق (TTS) عبر خدمة إمكانية الوصول لتبديل
 * محرك النظام بلا إذن خاص وبلا adb (ع٤٥).
 */
object TtsSettingsAutomation {

    private const val TAG = "TtsSettingsAutomation"
    private const val TIMEOUT_MS = 10_000L

    private val automationMutex = Mutex()
    private val contentChangeSignal =
        AtomicReference<CompletableDeferred<Unit>?>()

    data class AutomationResult(
        val success: Boolean,
        val reason: String? = null
    )

    /**
     * استقبال أحداث النوافذ والمحتوى من خدمة إمكانية الوصول
     * لإيقاظ خطوات الانتظار عند تغيّر شاشة الإعدادات.
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
     * تنفيذ دورة الأتمتة كاملة لاختيار [targetPackage] كمحرك افتراضي.
     */
    suspend fun run(
        context: Context,
        targetPackage: String
    ): AutomationResult {
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
        val originalForegroundPkg =
            AccessibilityBridge.resolveForegroundPackage()

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

        var lastStep = "init"
        var lastActivePkg: String? = null

        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                lastStep = "launch_intent"
                val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                lastStep = "wait_for_settings_window"
                var root: AccessibilityNodeInfo? = null
                val windowWaitStart = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - windowWaitStart < 4000L) {
                    root = service.rootInActiveWindow
                    val pkg = root?.packageName?.toString()
                    lastActivePkg = pkg
                    if (pkg != null && isSettingsPackage(pkg)) {
                        break
                    }
                    if (root != null) {
                        SafeNodeWalker.safeRecycle(root)
                        root = null
                    }
                    delay(150L)
                }

                if (root == null) {
                    return@withTimeoutOrNull AutomationResult(
                        success = false,
                        reason = "لم تُفتح شاشة إعدادات النطق"
                    )
                }

                try {
                    // فحص فوري: الخروج عن الشاشة إلى شاشة حساسة
                    if (isUnintendedSettingsScreen(root)) {
                        service.performGlobalAction(
                            AccessibilityService.GLOBAL_ACTION_BACK
                        )
                        return@withTimeoutOrNull AutomationResult(
                            success = false,
                            reason = "تم الخروج عن شاشة إعدادات النطق"
                        )
                    }

                    lastStep = "locate_target_or_preferred_engine"
                    val targetLabel = resolveTargetLabel(context, targetPackage)
                    var targetNode = findNodeByTexts(
                        root = root,
                        texts = listOf(targetLabel, targetPackage),
                        excludePatterns = listOf(
                            "إعدادات", "settings", "gear"
                        )
                    )

                    if (targetNode == null) {
                        // صف المحركات غير معروض؛ البحث عن المحرك المفضل
                        lastStep = "locate_preferred_engine"
                        val preferredTitles = resolvePreferredEngineTitles(
                            context = context,
                            settingsPkg = root.packageName?.toString().orEmpty()
                        )
                        val prefNode = findNodeByTexts(
                            root = root,
                            texts = preferredTitles
                        )
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
                        delay(500L)
                        SafeNodeWalker.safeRecycle(root)
                        root = service.rootInActiveWindow
                            ?: return@withTimeoutOrNull AutomationResult(
                                success = false,
                                reason = "لم تظهر قائمة المحركات بعد النقر"
                            )

                        lastStep = "search_target_in_list"
                        targetNode = findNodeByTexts(
                            root = root,
                            texts = listOf(targetLabel, targetPackage),
                            excludePatterns = listOf(
                                "إعدادات", "settings", "gear"
                            )
                        )

                        // تمرير القائمة حتى 5 مرات إن لم يظهر المحرك
                        var scrollAttempts = 0
                        while (targetNode == null && scrollAttempts < 5) {
                            scrollAttempts++
                            val scrolled = AccessibilityBridge.scrollForward()
                            if (!scrolled) break
                            delay(300L)
                            SafeNodeWalker.safeRecycle(root)
                            root = service.rootInActiveWindow ?: break
                            targetNode = findNodeByTexts(
                                root = root,
                                texts = listOf(targetLabel, targetPackage),
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

                    // فحص حوار التأكيد التحذيري
                    delay(350L)
                    lastStep = "check_confirmation_dialog"
                    SafeNodeWalker.safeRecycle(root)
                    root = service.rootInActiveWindow
                    if (root != null) {
                        val confirmBtn = findConfirmationButton(root)
                        if (confirmBtn != null) {
                            clickNodeOrAncestor(confirmBtn)
                            SafeNodeWalker.safeRecycle(confirmBtn)
                            delay(300L)
                        }
                    }

                    lastStep = "verify_synth_setting"
                    var verified = false
                    val verifyStart = SystemClock.uptimeMillis()
                    while (SystemClock.uptimeMillis() - verifyStart < 2000L) {
                        val current = Settings.Secure.getString(
                            resolver,
                            SystemTtsEngineController.KEY_TTS_DEFAULT_SYNTH
                        )
                        if (current == targetPackage) {
                            verified = true
                            break
                        }
                        delay(200L)
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

                    // العودة إلى التطبيق السابق (GLOBAL_ACTION_BACK حتى 3 مرات)
                    repeat(3) {
                        service.performGlobalAction(
                            AccessibilityService.GLOBAL_ACTION_BACK
                        )
                        delay(120L)
                    }

                    if (originalForegroundPkg != null &&
                        !isSettingsPackage(originalForegroundPkg)
                    ) {
                        val currentFg =
                            AccessibilityBridge.resolveForegroundPackage()
                        if (currentFg != originalForegroundPkg) {
                            val launchIntent = context.packageManager
                                .getLaunchIntentForPackage(
                                    originalForegroundPkg
                                )
                            if (launchIntent != null) {
                                launchIntent.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                                context.startActivity(launchIntent)
                            }
                        }
                    }

                    AutomationResult(success = true)
                } finally {
                    if (root != null) {
                        SafeNodeWalker.safeRecycle(root)
                    }
                }
            }

            if (result == null) {
                dumpFailureTree(
                    context, service, "timeout (lastStep=$lastStep)",
                    lastActivePkg
                )
                repeat(3) {
                    service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_BACK
                    )
                }
                return AutomationResult(
                    success = false,
                    reason = "انتهت مهلة الأتمتة (10 ثوانٍ) عند خطوة $lastStep"
                )
            }

            if (!result.success) {
                dumpFailureTree(
                    context, service, "${result.reason} ($lastStep)",
                    lastActivePkg
                )
                repeat(3) {
                    service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_BACK
                    )
                }
            }

            return result
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled exception in TtsSettingsAutomation", t)
            dumpFailureTree(
                context, service, "exception: ${t.message} ($lastStep)",
                lastActivePkg
            )
            repeat(3) {
                service.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK
                )
            }
            return AutomationResult(
                success = false,
                reason = "استثناء أثناء الأتمتة: ${t.message}"
            )
        } finally {
            AppPolicy.AutomationSession.stop(sessionToken)
            service.applyServiceInfo()
            A11ySpeechCoordinator.isSuppressed = false
            automationMutex.unlock()
        }
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return AppPolicy.SETTINGS_PACKAGES.contains(lower) ||
            lower.endsWith(".settings")
    }

    private fun isUnintendedSettingsScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        val sensitiveScreenWords = listOf(
            "security", "أمان", "الأمان",
            "password", "كلمات المرور",
            "privacy", "الخصوصية",
            "biometric", "بصمة"
        )
        val text = root.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val desc = root.contentDescription?.toString()
            ?.lowercase(Locale.ROOT).orEmpty()
        val title = "$text $desc"
        return sensitiveScreenWords.any { title.contains(it) }
    }

    private fun resolveTargetLabel(
        context: Context,
        pkg: String
    ): String {
        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: pkg
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

    private fun findNodeByTexts(
        root: AccessibilityNodeInfo,
        texts: List<String>,
        excludePatterns: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val text = curr.text?.toString()?.trim().orEmpty()
            val desc = curr.contentDescription?.toString()?.trim().orEmpty()
            val combined = "$text $desc".lowercase(Locale.ROOT)

            val isExcluded = excludePatterns.any {
                combined.contains(it.lowercase(Locale.ROOT))
            }
            if (!isExcluded && combined.isNotEmpty()) {
                val matched = texts.any { t ->
                    val target = t.trim().lowercase(Locale.ROOT)
                    target.isNotEmpty() && combined.contains(target)
                }
                if (matched) {
                    return curr
                }
            }

            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun findConfirmationButton(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val positiveTexts = listOf("موافق", "ok", "yes", "نعم", "استمرار")

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val id = curr.viewIdResourceName.orEmpty()
            if (id.endsWith("button1") || id.contains("positive")) {
                return curr
            }
            val text = curr.text?.toString()?.trim()?.lowercase(Locale.ROOT)
            if (text != null && positiveTexts.contains(text)) {
                return curr
            }
            for (i in 0 until curr.childCount) {
                val child = curr.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun clickNodeOrAncestor(
        node: AccessibilityNodeInfo
    ): Boolean {
        var curr: AccessibilityNodeInfo? = node
        while (curr != null) {
            if (curr.isClickable) {
                val ok = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(
                    TAG,
                    "Automated click: pkg=${curr.packageName}, " +
                        "text='${curr.text}', id=${curr.viewIdResourceName}, " +
                        "result=$ok"
                )
                if (ok) return true
            }
            val parent = curr.parent
            if (curr !== node) {
                SafeNodeWalker.safeRecycle(curr)
            }
            curr = parent
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(
            TAG,
            "Automated fallback click: pkg=${node.packageName}, " +
                "text='${node.text}', id=${node.viewIdResourceName}, " +
                "result=$ok"
        )
        return ok
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

    private fun dumpFailureTree(
        context: Context,
        service: NateqAccessibilityService?,
        step: String,
        activePkg: String?
    ) {
        val timestamp = System.currentTimeMillis()
        val file = File(
            context.filesDir,
            "engine_switch_tree_$timestamp.txt"
        )
        val sb = StringBuilder()
        sb.appendLine("Failure step: $step")
        sb.appendLine("Active package: $activePkg")
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

        runCatching {
            file.writeText(sb.toString())
            Log.e(TAG, "Dumped failure tree: ${file.absolutePath}")
        }.onFailure {
            Log.e(TAG, "Failed writing failure tree", it)
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
}
