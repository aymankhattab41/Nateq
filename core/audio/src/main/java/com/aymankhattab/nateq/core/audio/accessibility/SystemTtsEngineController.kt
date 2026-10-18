package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log

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
    fun getInstalledEngines(context: Context): List<EngineInfo> {
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
     * يغيّر محرك النطق الافتراضي للنظام إلى الحزمة المحددة مع الحفاظ على
     * إعدادات السرعة والنبرة واللغة الافتراضية للنظام دون أي تغيير.
     */
    fun setSystemEngine(context: Context, pkg: String): SetEngineResult {
        if (!hasPermission(context)) {
            return SetEngineResult(
                success = false,
                packageName = pkg,
                reason = "WRITE_SECURE_SETTINGS permission not granted"
            )
        }

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

        val written = try {
            Settings.Secure.putString(resolver, KEY_TTS_DEFAULT_SYNTH, pkg)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write $KEY_TTS_DEFAULT_SYNTH", t)
            return SetEngineResult(
                success = false,
                packageName = pkg,
                reason = t.message ?: "SecurityException"
            )
        }

        if (!written) {
            return SetEngineResult(
                success = false,
                packageName = pkg,
                reason = "Settings.Secure.putString returned false"
            )
        }

        val verified = try {
            Settings.Secure.getString(resolver, KEY_TTS_DEFAULT_SYNTH)
        } catch (_: Throwable) { null }

        if (verified != pkg) {
            return SetEngineResult(
                success = false,
                packageName = pkg,
                reason = "Verification failed: expected $pkg but was $verified"
            )
        }

        // استعادة القيم السابقة إن تغيّرت
        try {
            val curRate = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_RATE
            )
            if (initialRate != null && curRate != initialRate) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_RATE, initialRate
                )
            }
        } catch (_: Throwable) {}

        try {
            val curPitch = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_PITCH
            )
            if (initialPitch != null && curPitch != initialPitch) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_PITCH, initialPitch
                )
            }
        } catch (_: Throwable) {}

        try {
            val curLocale = Settings.Secure.getString(
                resolver, KEY_TTS_DEFAULT_LOCALE
            )
            if (initialLocale != null && curLocale != initialLocale) {
                Settings.Secure.putString(
                    resolver, KEY_TTS_DEFAULT_LOCALE, initialLocale
                )
            }
        } catch (_: Throwable) {}

        return SetEngineResult(success = true, packageName = pkg)
    }
}
