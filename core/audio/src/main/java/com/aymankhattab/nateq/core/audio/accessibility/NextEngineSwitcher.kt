package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
     * يختار المحرك التالي في الترتيب الدائري.
     */
    internal fun nextEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? {
        if (engines.isEmpty()) return null
        val effective = currentPackage ?: LORD_TTS_PACKAGE
        val currentIndex = engines.indexOfFirst {
            it.packageName == effective ||
                (effective == LORD_TTS_PACKAGE &&
                    (it.packageName == LORD_TTS_PACKAGE ||
                        it.packageName == "com.aymankhattab.nateq")) ||
                (effective == "com.aymankhattab.nateq" &&
                    (it.packageName == LORD_TTS_PACKAGE ||
                        it.packageName == "com.aymankhattab.nateq"))
        }
        val nextIndex = if (currentIndex < 0) {
            0
        } else {
            (currentIndex + 1).mod(engines.size)
        }
        return engines[nextIndex]
    }

    /**
     * يختار المحرك السابق في الترتيب الدائري.
     */
    internal fun previousEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? {
        if (engines.isEmpty()) return null
        val effective = currentPackage ?: LORD_TTS_PACKAGE
        val currentIndex = engines.indexOfFirst {
            it.packageName == effective ||
                (effective == LORD_TTS_PACKAGE &&
                    (it.packageName == LORD_TTS_PACKAGE ||
                        it.packageName == "com.aymankhattab.nateq")) ||
                (effective == "com.aymankhattab.nateq" &&
                    (it.packageName == LORD_TTS_PACKAGE ||
                        it.packageName == "com.aymankhattab.nateq"))
        }
        val prevIndex = if (currentIndex <= 0) {
            engines.size - 1
        } else {
            currentIndex - 1
        }
        return engines[prevIndex]
    }

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

    fun switchToNextBlocking(context: Context): SwitchResult? =
        kotlinx.coroutines.runBlocking { switchToNext(context) }

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

    fun switchToPreviousBlocking(context: Context): SwitchResult? =
        kotlinx.coroutines.runBlocking { switchToPrevious(context) }

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

        activeSwitchJob?.cancel()
        activeSwitchJob = switchScope.launch {
            delay(120L)
            val steps = accumulatedSteps.getAndSet(0)
            if (steps == 0) return@launch

            val engines = availableEngines(appContext)
            if (engines.isEmpty()) {
                announce(appContext, "لا يوجد محرك نطق مثبت")
                onComplete?.invoke(null)
                return@launch
            }

            val current =
                SystemTtsEngineController.currentSystemEngine(appContext)
                    ?: appContext.packageName

            val curIndex = engines.indexOfFirst {
                it.packageName == current ||
                    (current == LORD_TTS_PACKAGE &&
                        (it.packageName == LORD_TTS_PACKAGE ||
                            it.packageName == "com.aymankhattab.nateq")) ||
                    (current == "com.aymankhattab.nateq" &&
                        (it.packageName == LORD_TTS_PACKAGE ||
                            it.packageName == "com.aymankhattab.nateq"))
            }.let { if (it < 0) 0 else it }

            val targetIndex = (curIndex + steps).mod(engines.size)
            val target = engines[targetIndex]

            val result = SystemTtsEngineController.setSystemEngine(
                appContext,
                target.packageName
            )
            val switchRes = if (result.success) {
                announce(appContext, "محرك الهاتف الآن: ${target.label}")
                SwitchResult(target.packageName, target.label, engines.size)
            } else {
                val reason = result.reason ?: "خطأ غير معروف"
                announce(appContext, "فشل تبديل المحرك: $reason")
                null
            }
            onComplete?.invoke(switchRes)
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
            .firstOrNull { it.packageName == currentPkg }
            ?.label
            ?: if (currentPkg == appContext.packageName) {
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

    private fun openGuideActivity(context: Context) {
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
