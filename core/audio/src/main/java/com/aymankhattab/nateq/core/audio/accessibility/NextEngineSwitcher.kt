package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * يبدّل محرك TTS الافتراضي على مستوى النظام بالكامل.
 * يعتمد على [SystemTtsEngineController] مع إذن WRITE_SECURE_SETTINGS.
 * لا يُغيّر أي إعدادات داخلية لـ Lord TTS.
 */
object NextEngineSwitcher {

    private const val TAG = "NATEQ_TTS"
    const val LORD_TTS_PACKAGE = "com.aymankhattab.nateq.default"

    private val switchScope =
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var activeSwitchJob: Job? = null
    private val accumulatedSteps = AtomicInteger(0)
    private val pendingCallbacks =
        Collections.synchronizedList(mutableListOf<(SwitchResult?) -> Unit>())

    private fun drainCallbacks(): List<(SwitchResult?) -> Unit> {
        synchronized(pendingCallbacks) {
            val list = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
            return list
        }
    }

    data class SwitchResult(
        val packageName: String,
        val label: String,
        val engineCount: Int
    )

    /**
     * قائمة المحركات المتاحة في حلقة التبديل من محركات النظام الفعلية.
     */
    fun availableEngines(
        context: Context
    ): List<EnginePicker.InstalledEngine> {
        val systemEngines =
            SystemTtsEngineController.getInstalledEngines(context)
        return systemEngines
            .filter { VisibleEngines.isVisible(it.packageName) }
            .map {
                EnginePicker.InstalledEngine(it.packageName, it.label)
            }
    }

    /**
     * قراءة حزمة المحرك النشط من الإعدادات الداخلية للتوافق.
     */
    internal fun currentEnginePackage(
        settings: SettingsRepository
    ): String? {
        val ar = settings.getEngineForLanguage("ar")
        if (!ar.isNullOrBlank()) return ar
        val en = settings.getEngineForLanguage("en")
        if (!en.isNullOrBlank()) return en
        return settings.allConvertLanguagePreferences().values
            .firstOrNull { !it.engine.isNullOrBlank() }
            ?.engine
    }

    /**
     * مطابقة أسماء الحزم مع اعتبار اسم Lord TTS الافتراضي واسم حزمته مترادفين.
     */
    internal fun isSameEngine(pkg1: String?, pkg2: String?): Boolean {
        if (pkg1 == null || pkg2 == null) return false
        if (pkg1 == pkg2) return true
        val isLord1 = pkg1 == LORD_TTS_PACKAGE ||
            pkg1 == "com.aymankhattab.nateq"
        val isLord2 = pkg2 == LORD_TTS_PACKAGE ||
            pkg2 == "com.aymankhattab.nateq"
        return isLord1 && isLord2
    }

    /**
     * البحث عن فهرس المحرك الحالي في القائمة.
     */
    internal fun findEngineIndex(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): Int {
        val effective = currentPackage ?: LORD_TTS_PACKAGE
        return engines.indexOfFirst { isSameEngine(it.packageName, effective) }
    }

    /**
     * حساب المحرك المستهدف حسب الخطوات
     * (التالي للأول والسابق للأخير عند الغياب).
     */
    internal fun resolveTargetEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?,
        steps: Int
    ): EnginePicker.InstalledEngine? {
        if (engines.isEmpty()) return null
        val curIndex = findEngineIndex(engines, currentPackage)
        val targetIndex = if (curIndex in engines.indices) {
            (curIndex + steps).mod(engines.size)
        } else {
            if (steps >= 0) {
                (steps - 1).mod(engines.size)
            } else {
                (engines.size + steps).mod(engines.size)
            }
        }
        return engines[targetIndex]
    }

    /**
     * يختار المحرك التالي في الترتيب الدائري.
     */
    internal fun nextEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? =
        resolveTargetEngine(engines, currentPackage, 1)

    /**
     * يختار المحرك السابق في الترتيب الدائري.
     */
    internal fun previousEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? =
        resolveTargetEngine(engines, currentPackage, -1)

    /**
     * يبدّل محرك النطق الافتراضي للنظام إلى المحرك التالي بشكل غير متزامن.
     */
    suspend fun switchToNext(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val engines = availableEngines(appContext)
        if (engines.isEmpty()) {
            announce(appContext, "لا يوجد محرك نطق مثبت")
            return null
        }

        val current =
            SystemTtsEngineController.currentSystemEngine(appContext)
                ?: appContext.packageName
        val next = nextEngine(engines, current) ?: return null

        val result = SystemTtsEngineController.setSystemEngine(
            appContext,
            next.packageName
        )
        return if (result.success) {
            announce(appContext, "محرك الهاتف الآن: ${next.label}")
            SwitchResult(next.packageName, next.label, engines.size)
        } else {
            val reason = result.reason ?: "خطأ غير معروف"
            announce(appContext, "فشل تبديل المحرك: $reason")
            null
        }
    }

    /**
     * يبدّل محرك النطق الافتراضي للنظام إلى المحرك السابق بشكل غير متزامن.
     */
    suspend fun switchToPrevious(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val engines = availableEngines(appContext)
        if (engines.isEmpty()) {
            announce(appContext, "لا يوجد محرك نطق مثبت")
            return null
        }

        val current =
            SystemTtsEngineController.currentSystemEngine(appContext)
                ?: appContext.packageName
        val prev = previousEngine(engines, current) ?: return null

        val result = SystemTtsEngineController.setSystemEngine(
            appContext,
            prev.packageName
        )
        return if (result.success) {
            announce(appContext, "محرك الهاتف الآن: ${prev.label}")
            SwitchResult(prev.packageName, prev.label, engines.size)
        } else {
            val reason = result.reason ?: "خطأ غير معروف"
            announce(appContext, "فشل تبديل المحرك: $reason")
            null
        }
    }

    /**
     * تشغيل عملية التبديل بجلسة قابلة للإلغاء وتجميع الضغطات المتلاحقة.
     * تضمن عدم حجز خيط الواجهة أو خدمة إمكانية الوصول.
     */
    fun triggerSwitch(
        context: Context,
        isNext: Boolean,
        onComplete: ((SwitchResult?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val step = if (isNext) 1 else -1
        accumulatedSteps.addAndGet(step)
        if (onComplete != null) {
            pendingCallbacks.add(onComplete)
        }

        activeSwitchJob?.cancel()
        activeSwitchJob = switchScope.launch {
            try {
                delay(120L)
                val steps = accumulatedSteps.getAndSet(0)
                if (steps == 0) return@launch

                val engines = availableEngines(appContext)
                if (engines.isEmpty()) {
                    announce(appContext, "لا يوجد محرك نطق مثبت")
                    drainCallbacks().forEach { it.invoke(null) }
                    return@launch
                }

                val current =
                    SystemTtsEngineController.currentSystemEngine(appContext)
                        ?: appContext.packageName

                val target = resolveTargetEngine(engines, current, steps)
                if (target == null) {
                    drainCallbacks().forEach { it.invoke(null) }
                    return@launch
                }

                val result = withContext(NonCancellable) {
                    SystemTtsEngineController.setSystemEngine(
                        appContext,
                        target.packageName
                    )
                }
                val switchRes = if (result.success) {
                    announce(appContext, "محرك الهاتف الآن: ${target.label}")
                    SwitchResult(
                        target.packageName,
                        target.label,
                        engines.size
                    )
                } else {
                    val reason = result.reason ?: "خطأ غير معروف"
                    announce(appContext, "فشل تبديل المحرك: $reason")
                    null
                }
                drainCallbacks().forEach { it.invoke(switchRes) }
            } finally {
                drainCallbacks().forEach { it.invoke(null) }
            }
        }
    }

    /**
     * المحرك المختار حالياً كنظام وتسمية عرضه في البلاطة.
     */
    fun selectedEngineLabel(context: Context): String? {
        val appContext = context.applicationContext
        val currentPkg =
            SystemTtsEngineController.currentSystemEngine(appContext)
                ?: appContext.packageName
        return availableEngines(appContext)
            .firstOrNull { isSameEngine(it.packageName, currentPkg) }
            ?.label
            ?: if (isSameEngine(currentPkg, LORD_TTS_PACKAGE) ||
                isSameEngine(currentPkg, appContext.packageName)
            ) {
                "Lord TTS"
            } else {
                currentPkg
            }
    }

    private fun announce(context: Context, text: String) {
        try {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = text,
                isInterruptAllowed = true
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Failed to speak announcement (len=${text.length})",
                t
            )
        }
    }

    internal fun openGuideActivity(context: Context) {
        try {
            val guideClass = "com.aymankhattab.nateq.settings." +
                "WriteSecureSettingsGuideActivity"
            val intent = Intent().setClassName(
                context.packageName,
                guideClass
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open WriteSecureSettingsGuideActivity", t)
        }
    }
}
