package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يبدّل محرك TTS الافتراضي على مستوى النظام بالكامل.
 * يعتمد على [SystemTtsEngineController] مع إذن WRITE_SECURE_SETTINGS.
 * لا يُغيّر أي إعدادات داخلية لـ Lord TTS.
 */
object NextEngineSwitcher {

    private const val TAG = "NATEQ_TTS"
    const val LORD_TTS_PACKAGE = "com.aymankhattab.nateq.default"

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
        return systemEngines.map {
            EnginePicker.InstalledEngine(it.packageName, it.label)
        }
    }

    /**
     * قراءة حزمة المحرك النشط من الإعدادات الداخلية للتوافق.
     */
    internal fun currentEnginePackage(
        settings: SettingsRepository
    ): String? {
        val defaultCategory = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )
        if (!defaultCategory.isNullOrBlank()) return defaultCategory
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
     * يبدّل محرك النطق الافتراضي للنظام إلى المحرك التالي.
     */
    fun switchToNext(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val engines = availableEngines(appContext)
        if (engines.isEmpty()) {
            announce(appContext, "لا يوجد محرك نطق مثبت")
            return null
        }

        val current = SystemTtsEngineController.currentSystemEngine(appContext)
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
     * يبدّل محرك النطق الافتراضي للنظام إلى المحرك السابق.
     */
    fun switchToPrevious(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val engines = availableEngines(appContext)
        if (engines.isEmpty()) {
            announce(appContext, "لا يوجد محرك نطق مثبت")
            return null
        }

        val current = SystemTtsEngineController.currentSystemEngine(appContext)
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
            Log.w(TAG, "Failed to speak announcement: $text", t)
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
