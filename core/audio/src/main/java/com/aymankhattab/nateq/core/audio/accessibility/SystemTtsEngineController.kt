package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines

/**
 * تحكم بمحرك نطق النظام الافتراضي (TTS) عبر إعدادات النظام الآمنة.
 * يتطلب إذن WRITE_SECURE_SETTINGS الممنوح عبر ADB.
 */
object SystemTtsEngineController {

    private const val TAG = "NATEQ_TTS"

    const val KEY_TTS_DEFAULT_SYNTH = "tts_default_synth"
    const val KEY_TTS_DEFAULT_RATE = "tts_default_rate"
    const val KEY_TTS_DEFAULT_PITCH = "tts_default_pitch"
    const val KEY_TTS_DEFAULT_LOCALE = "tts_default_locale"

    data class EngineInfo(
        val packageName: String,
        val label: String
    )

    data class SetEngineResult(
        val success: Boolean,
        val packageName: String,
        val reason: String? = null
    )

    /**
     * هل يملك التطبيق صلاحية WRITE_SECURE_SETTINGS؟
     */
    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * يقرأ اسم حزمة محرك النطق الافتراضي الحالي في النظام.
     */
    fun currentSystemEngine(context: Context): String? {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                KEY_TTS_DEFAULT_SYNTH
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system TTS engine", t)
            null
        }
    }

    /**
     * قائمة بجميع محركات TTS المثبتة في النظام بالترتيب الفعلي للنظام.
     * تشمل Lord TTS نفسه بصفته محركاً معتمداً.
     */
    fun getAllInstalledEngines(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfos: List<ResolveInfo> = try {
            pm.queryIntentServices(
                intent,
                PackageManager.GET_META_DATA or PackageManager.MATCH_ALL
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to query system TTS engines", t)
            emptyList()
        }

        val engines = resolveInfos.mapNotNull { ri ->
            val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm).toString()
            EngineInfo(packageName = pkg, label = label)
        }.distinctBy { it.packageName }

        return if (engines.any { it.packageName == context.packageName }) {
            engines
        } else {
            engines + EngineInfo(
                packageName = context.packageName,
                label = "Lord TTS"
            )
        }
    }

    /**
     * قائمة بمحركات TTS المثبتة الظاهرة في النظام.
     */
    fun getInstalledEngines(context: Context): List<EngineInfo> {
        return getAllInstalledEngines(context)
            .filter { VisibleEngines.isVisible(it.packageName) }
    }

    /**
     * يغيّر محرك النطق الافتراضي للنظام إلى الحزمة المحددة مع الحفاظ على
     * إعدادات السرعة والنبرة واللغة الافتراضية للنظام دون أي تغيير.
     * يجرّب بالترتيب:
     * 1. مسار إذن WRITE_SECURE_SETTINGS.
     * 2. مسار root/su إن توفر (تسريع اختياري).
     * 3. مسار الأتمتة عبر خدمة إمكانية الوصول.
     * 4. مسار احتياطي أخير: فتح شاشة إعدادات النطق للمستخدم.
     */
    suspend fun setSystemEngine(
        context: Context,
        pkg: String
    ): SetEngineResult {
        val resolver = context.contentResolver
        val initialRate = try {
            Settings.Secure.getString(resolver, KEY_TTS_DEFAULT_RATE)
        } catch (_: Throwable) { null }

        val initialPitch = try {
            Settings.Secure.getString(resolver, KEY_TTS_DEFAULT_PITCH)
        } catch (_: Throwable) { null }

        val initialLocale = try {
            Settings.Secure.getString(resolver, KEY_TTS_DEFAULT_LOCALE)
        } catch (_: Throwable) { null }

        // 1. مسار الإذن المباشر
        if (hasPermission(context)) {
            val written = try {
                Settings.Secure.putString(resolver, KEY_TTS_DEFAULT_SYNTH, pkg)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to write $KEY_TTS_DEFAULT_SYNTH", t)
                false
            }
            if (written) {
                val verified = verifySystemEngine(context, pkg)
                if (verified) {
                    restoreDefaults(
                        resolver, initialRate, initialPitch, initialLocale
                    )
                    return SetEngineResult(success = true, packageName = pkg)
                }
            }
        }

        // 2. مسار root/su الاختياري للتسريع
        if (trySetViaSu(pkg)) {
            val verified = verifySystemEngine(context, pkg)
            if (verified) {
                restoreDefaults(
                    resolver, initialRate, initialPitch, initialLocale
                )
                return SetEngineResult(success = true, packageName = pkg)
            }
        }

        // 3. مسار الأتمتة عبر خدمة إمكانية الوصول
        if (AccessibilityBridge.isServiceConnected()) {
            val autoResult = TtsSettingsAutomation.run(context, pkg)
            if (autoResult.success) {
                val verified = verifySystemEngine(context, pkg)
                if (verified) {
                    restoreDefaults(
                        resolver, initialRate, initialPitch, initialLocale
                    )
                    return SetEngineResult(success = true, packageName = pkg)
                }
            }
            Log.w(
                TAG,
                "TtsSettingsAutomation unverified/failed: ${autoResult.reason}"
            )
        } else if (!hasPermission(context)) {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "يتطلب منح إذن الإعدادات الآمنة عبر ADB لتبديل المحرك",
                isInterruptAllowed = true
            )
            NextEngineSwitcher.openGuideActivity(context)
            return SetEngineResult(
                success = false,
                packageName = pkg,
                reason = "إذن WRITE_SECURE_SETTINGS غير ممنوح"
            )
        }

        // 4. مسار احتياطي أخير: فتح شاشة إعدادات النطق يدوياً للمستخدم
        openTtsSettings(context)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "تعذّر التبديل التلقائي، اختر المحرك من هذه الشاشة",
            isInterruptAllowed = true
        )
        return SetEngineResult(
            success = false,
            packageName = pkg,
            reason = "تعذّر التبديل التلقائي، تم فتح شاشة إعدادات النطق"
        )
    }

    private suspend fun verifySystemEngine(
        context: Context,
        pkg: String,
        maxWaitMs: Long = 800L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start <= maxWaitMs) {
            if (currentSystemEngine(context) == pkg) return true
            kotlinx.coroutines.delay(50L)
        }
        return currentSystemEngine(context) == pkg
    }

    private suspend fun trySetViaSu(pkg: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var proc: Process? = null
            try {
                proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "settings put secure $KEY_TTS_DEFAULT_SYNTH $pkg"
                    )
                )
                val completed = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    proc.waitFor()
                }
                if (completed != null && completed == 0) {
                    true
                } else {
                    proc.destroyForcibly()
                    false
                }
            } catch (_: Throwable) {
                try {
                    proc?.destroyForcibly()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(
                        TAG,
                        "استثناء إنهاء عملية su: " +
                            "${t.javaClass.simpleName}: ${t.message}"
                    )
                }
                false
            }
        }

    private fun openTtsSettings(context: Context) {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open TTS settings fallback", t)
        }
    }

    private fun restoreDefaults(
        resolver: android.content.ContentResolver,
        rate: String?,
        pitch: String?,
        locale: String?
    ) {
        try {
            val curRate = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_RATE
            )
            if (rate != null && curRate != rate) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_RATE, rate
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(
                TAG,
                "استثناء استعادة سرعة النطق: " +
                    "${t.javaClass.simpleName}: ${t.message}"
            )
        }

        try {
            val curPitch = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_PITCH
            )
            if (pitch != null && curPitch != pitch) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_PITCH, pitch
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(
                TAG,
                "استثناء استعادة نبرة النطق: " +
                    "${t.javaClass.simpleName}: ${t.message}"
            )
        }

        try {
            val curLocale = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_LOCALE
            )
            if (locale != null && curLocale != locale) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_LOCALE, locale
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(
                TAG,
                "استثناء استعادة لغة النطق: " +
                    "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }
}
