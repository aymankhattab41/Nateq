package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aymankhattab.nateq.engine.ConvertPreferencesCodec
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs

/**
 * الوسيط الوحيد للقراءة/الكتابة في الإعدادات.
 *
 * يستخدم SharedPreferences عادي في ملف مشترك بين العمليات.
 * LORD يعمل في `:tts` process منفصل، والعمليات في نفس التطبيق (نفس UID)
 * يمكنها قراءة/كتابة ملفات بعضها البعض.
 *
 * في أول تشغيل: يرحّل الإعدادات من الملف المشفر القديم (nateq_secure_settings)
 * إلى الملف الجديد إذا كان الملف الجديد فارغاً.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "NATEQ_TTS"
        private const val NEW_PREFS = "nateq_settings"
        private const val OLD_PREFS = "nateq_secure_settings"
        private const val FALLBACK_PREFS = "nateq_fallback_settings"
        private const val KEY_MIGRATED = "_migrated_to_plain"

        const val VOICE_CATEGORY_TIME = "time"
        const val VOICE_CATEGORY_NUMBERS = "numbers"
        const val VOICE_CATEGORY_NOTIFICATIONS = "notifications"
        const val VOICE_CATEGORY_DEFAULT = "default"

        /** تطبيقات الإشعارات الافتراضية قبل أي اختيار صريح. */
        const val NOTIF_READ_ALL = "all_apps"

        /** عناصر قسم «صحة الجهاز» القابلة للنطق عند الطلب. */
        const val DEVICE_HEALTH_BATTERY = "battery"
        const val DEVICE_HEALTH_CHARGING = "charging"
        const val DEVICE_HEALTH_STORAGE = "storage"
        const val DEVICE_HEALTH_MEMORY = "memory"

        /** العناصر الافتراضية المختارة في قسم صحة الجهاز (كلها). */
        val DEFAULT_DEVICE_HEALTH_ITEMS = setOf(
            DEVICE_HEALTH_BATTERY,
            DEVICE_HEALTH_CHARGING,
            DEVICE_HEALTH_STORAGE,
            DEVICE_HEALTH_MEMORY
        )

        /** أقصى عدد يُقبل من أسماء المتصلين المخصصة (حماية من استيراد فائض). */
        private const val MAX_CALLER_ENTRIES = 2000

        /** التطبيقات الافتراضية التي تُقرأ إشعاراتها قبل أي اختيار صريح. */
        val DEFAULT_NOTIFICATION_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.facebook.orca",
            "com.instagram.android"
        )

        const val KEY_QUIET_MIGRATED = "_quiet_days_migrated"

        /** مفتاح خريطة تفضيلات التحويل لكل لغة (JSON عبر ConvertPreferencesCodec). */
        private const val KEY_CONVERT_PREFS_JSON = "convert_language_prefs"

        /** وسم ترحيل سلوتات اللغة 1/2 القديمة إلى الخريطة الديناميكية (مرة واحدة). */
        private const val KEY_CONVERT_SLOTS_MIGRATED = "_convert_slots_migrated"
    }

    @Volatile
    private var prefs: SharedPreferences =
        context.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE).also {
            migrateIfNeeded(it)
        }

    // أسماء المتصلين = بيانات شخصية (PII) تُخزَّن في ملف مشفَّر منفصل؛
    // عند تعذر التشفير (Keystore معطوب…) تُحتفظ في الذاكرة لهذه الجلسة فقط
    // ولا تُكتب في تفضيلات نصية عادية أبداً (منع تسريب PII).
    @Volatile
    private var callerSecurePrefs: SharedPreferences? = null

    private val memoryCallerNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun reload() {
        // MODE_MULTI_PROCESS مهملة ومسببة تضارب بيانات — نعيد القراءة من القرص بـ MODE_PRIVATE
        // ولضمان استقبال آخر قيمة مكتوبة من العملية الأخرى، نُغلق ونُعيد فتح كائن SharedPreferences
        prefs = context.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * يوحّد معرّفات الأصوات القديمة (nateq-ar*, nateq-en*, ar-local, en-local)
     * مع الصيغة الحالية (ar-EG/en-US) حتى تبقى القيم المخزنة قبل إعادة
     * التسمية تعمل وتعرض بشكل صحيح في شاشات الإعدادات.
     */
    private fun normalizeVoiceId(id: String?): String? {
        if (id == null) return null
        return when {
            id.contains("nateq-ar", ignoreCase = true) ||
                id.equals("ar-local", ignoreCase = true) -> "ar-EG"
            id.contains("nateq-en", ignoreCase = true) ||
                id.equals("en-local", ignoreCase = true) -> "en-US"
            else -> id
        }
    }

    /**
     * يرحّل الإعدادات من الملف المشفر القديم إلى الملف الجديد
     * في أول مرة فقط. لا يضع علامة المُرحَّل إلا إذا نجح فعلياً.
     */
    private fun migrateIfNeeded(newPrefs: SharedPreferences) {
        if (newPrefs.getBoolean(KEY_MIGRATED, false)) return

        val oldPrefs = try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context, OLD_PREFS, masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            try {
                context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            } catch (_: Throwable) { null }
        }

        if (oldPrefs == null || oldPrefs.all.isEmpty()) {
            newPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        val editor = newPrefs.edit()
        var copied = 0
        @Suppress("UNCHECKED_CAST")
        for ((key, value) in oldPrefs.all) {
            if (key == KEY_MIGRATED) continue
            when (value) {
                is Int -> { editor.putInt(key, value); copied++ }
                is Float -> { editor.putFloat(key, value); copied++ }
                is Boolean -> { editor.putBoolean(key, value); copied++ }
                is String -> { editor.putString(key, value); copied++ }
                is Set<*> -> { editor.putStringSet(key, value as Set<String>); copied++ }
            }
        }
        editor.putBoolean(KEY_MIGRATED, true)
        // `commit()` متزامن لضمان كتابة البيانات قبل حذف الملف القديم (بلا سباق)
        if (editor.commit()) {
            context.deleteSharedPreferences(OLD_PREFS)
        }
        Log.w(TAG, "تم ترحيل $copied إعداد من الملف القديم إلى الملف الجديد")
    }

    /** لغة التطبيق المختارة يدوياً: "ar"/"en"/null (null = تتبع لغة النظام) */
    fun getAppLanguage(): String? = prefs.getString("app_language", null)
    fun setAppLanguage(language: String?) =
        prefs.edit().putString("app_language", language).apply()

    /** لغة نطق الإعلانات (الساعة/الأرقام): "ar"/"en"/"" ("" = اتبع لغة التطبيق) */
    fun getAnnouncementSpeechLanguage(): String? = prefs.getString("announcement_speech_language", null)
    fun setAnnouncementSpeechLanguage(language: String?) =
        prefs.edit().putString("announcement_speech_language", language).apply()

    /** طريقة نطق الأرقام: 1=مفردة، 2=زوجي، 3=ثلاثي، ... 8=ثماني */
    fun getNumberReadingMode(): Int = prefs.getInt("number_reading_mode", 1)
    fun setNumberReadingMode(mode: Int) =
        prefs.edit().putInt("number_reading_mode", mode).apply()

    /** الصوت المفضّل لكل لغة (languageTag -> voiceId) */
    fun getPreferredVoiceId(languageTag: String): String? = normalizeVoiceId(prefs.getString("preferred_voice_$languageTag", null))
    fun setPreferredVoiceId(languageTag: String, voiceId: String) =
        prefs.edit().putString("preferred_voice_$languageTag", voiceId).apply()

    /** سرعة النطق لكل لغة (languageTag -> speechRate) */
    fun getSpeechRate(languageTag: String): Float = prefs.getFloat("speech_rate_$languageTag", 1.0f)
    fun setSpeechRate(languageTag: String, rate: Float) =
        prefs.edit().putFloat("speech_rate_$languageTag", rate).apply()

    /** نبرة الصوت لكل لغة (languageTag -> pitch) */
    fun getPitch(languageTag: String): Float = prefs.getFloat("pitch_$languageTag", 1.0f)
    fun setPitch(languageTag: String, pitch: Float) =
        prefs.edit().putFloat("pitch_$languageTag", pitch).apply()

    /** مستوى الصوت لكل لغة (languageTag -> volume) */
    fun getVolume(languageTag: String): Float = prefs.getFloat("volume_$languageTag", 1.0f)
    fun setVolume(languageTag: String, volume: Float) =
        prefs.edit().putFloat("volume_$languageTag", volume).apply()

    /** حزمة محرك TTS الذي اختاره المستخدم في شاشة الإعدادات */
    fun getSelectedEnginePackage(): String? = prefs.getString("selected_engine_package", null)
    fun setSelectedEnginePackage(pkg: String?) =
        prefs.edit().putString("selected_engine_package", pkg).apply()

    /** مسح كل إعدادات التطبيق وإعادتها إلى القيم الافتراضية، بما فيها
     *  أسماء المتصلين المخصصة (PII) المخزنة في الملف المشفر وملفات الحالة. */
    fun resetAllToDefault() {
        prefs.edit().clear().apply()
        memoryCallerNames.clear()
        // مسح ملف أسماء المتصلين المشفر. إن تعذّر الوصول إليه (Keystore معطوب)
        // نحذف الملف نفسه مباشرةً (الحذف لا يحتاج المفتاح) حتى لا يبقى PII
        // غير قابل للمسح على القرص.
        val caller = getCallerPrefs()
        if (caller != null) {
            caller.edit().clear().apply()
        } else {
            runCatching { context.deleteSharedPreferences("nateq_secure_caller_names") }
        }
        // مسح ملفات الحالة والقاموس والـ fallback القديم.
        runCatching { context.deleteSharedPreferences(OLD_PREFS) }
        runCatching { context.deleteSharedPreferences(FALLBACK_PREFS) }
        runCatching { context.deleteSharedPreferences("nateq_pronunciation_dict") }
        runCatching { context.deleteSharedPreferences("nateq_battery_state") }
    }

    // ============ المفتاح الرئيسي ووضع توفير الطاقة ============

    /**
     * المفتاح الرئيسي لإعلانات التطبيق: تعطيله يوقف كل الإعلانات التلقائية
     * دفعة واحدة (الوقت، البطارية، المتصل، الرسائل، الإشعارات) — وتبقى
     * تفعيلاتها الفرعية محفوظة للعودة إليها.
     */
    fun isAllAnnouncementsEnabled(): Boolean = prefs.getBoolean("all_announcements_enabled", true)
    fun setAllAnnouncementsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("all_announcements_enabled", enabled).apply()

    // ============ خصوصية قفل الشاشة ============

    /**
     * عندما يكون مقفلاً (الشاشة قفلت)، تُحجب تفاصيل الرسائل والإشعارات
     * (المحتوى/العنوان/اسم المتصل) عن النطق فلا يُسمع كود تحقق (OTP) أو
     * رسالة خاصة بصوتٍ عالٍ في مكان عام — ويُكتفى بالمصدر أو المضمون العام.
     */
    fun isLockScreenPrivacyEnabled(): Boolean = prefs.getBoolean("lock_screen_privacy_enabled", true)
    fun setLockScreenPrivacyEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("lock_screen_privacy_enabled", enabled).apply()

    /** هل شاشة الجهاز مقفلة فعلاً (قفل أمان)؟ يعود false عند عدم وجود قفل. */
    fun isDeviceScreenLocked(): Boolean {
        val km = context.getSystemService(android.app.KeyguardManager::class.java) ?: return false
        return km.isDeviceLocked
    }

    /** وضع توفير الطاقة: يُخفَّف إعلان الوقت عند انخفاض البطارية عن العتبة. */
    fun isPowerSaverModeEnabled(): Boolean = prefs.getBoolean("power_saver_mode_enabled", false)
    fun setPowerSaverModeEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("power_saver_mode_enabled", enabled).apply()

    /** العتبة (نسبة مئوية) التي يعمل الاحدها وضع توفير الطاقة لإعلان الوقت. */
    fun getPowerSaverBatteryThreshold(): Int = prefs.getInt("power_saver_battery_threshold", 20)
    fun setPowerSaverBatteryThreshold(threshold: Int) =
        prefs.edit().putInt("power_saver_battery_threshold", threshold.coerceIn(0, 100)).apply()

    /** إعلان اكتمال الشحن (وصول 100% والمتصالة). */
    fun isChargingCompleteAnnouncementEnabled(): Boolean =
        prefs.getBoolean("charging_complete_announcement_enabled", true)
    fun setChargingCompleteAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("charging_complete_announcement_enabled", enabled).apply()

    /** إعلان فصل الشاحن. */
    fun isChargingDisconnectAnnouncementEnabled(): Boolean =
        prefs.getBoolean("charging_disconnect_announcement_enabled", true)
    fun setChargingDisconnectAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("charging_disconnect_announcement_enabled", enabled).apply()

    // ============ أسماء المتصلين المخصصة ============

    private fun getCallerPrefs(): SharedPreferences? {
        val cached = callerSecurePrefs
        if (cached != null) return cached
        synchronized(this) {
            callerSecurePrefs?.let { return it }
            return try {
                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    context, "nateq_secure_caller_names", masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { callerSecurePrefs = it }
            } catch (_: Throwable) {
                // Keystore معطوب/مفتاح ضائع: لا نقرأ ولا نكتب الأسماء (PII)
                // هنا أبداً. نحذف الملف المشفر نفسه (الحذف لا يحتاج المفتاح)
                // حتى لا يبقى PRІ ميت على القرص، ونُشغّل تحذيراً لمرة واحدة
                // عبر علامة في الذاكرة يقرأها المتصلون عند الحاجة.
                callerSecurePrefs = null
                runCatching { context.deleteSharedPreferences("nateq_secure_caller_names") }
                null
            }
        }
    }

    /** أسماء متصلين مخصصة: خريطة رقم هاتف (بدون ترميز البلد) -> الاسم المعلَن. */
    fun getCustomCallerNames(): Map<String, String> {
        val raw = getCallerPrefs()?.getString("caller_names", null)
            ?: memoryCallerNames.takeIf { it.isNotEmpty() }?.let { m ->
                m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
            } ?: return emptyMap()
        return raw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx > 0 && idx < line.length - 1) {
                    line.substring(0, idx) to line.substring(idx + 1)
                } else null
            }.toMap()
    }

    fun setCustomCallerNames(names: Map<String, String>) {
        // تعقيم أثناء الاستيراد (من النسخ الاحتياطي أو واجهة الإدخال): نتصفّى
        // المفاتيح لتكون أرقاماً فقط (مع رمز + اختياري)، وحد أقصى لطول الرقم
        // والاسم، وحد أقصى لعدد الإدخالات، حتى لا يدخل ملف JSON خبيث/فاسد
        // من SAF كمية مهولة بلا حدود إلى المخزن المشفر.
        val cleaned = names
            .filterKeys { it.matches(Regex("^[+]?[0-9\\s()\\-]{3,32}$")) }
            .filterValues { it.isNotBlank() && it.trim().length <= 100 }
            .entries
            .take(MAX_CALLER_ENTRIES)
            .associate { (k, v) -> k.trim() to v.trim() }
        val raw = cleaned.entries.mapNotNull { e ->
            if (e.key.isBlank() || e.value.isBlank()) null
            else "${e.key.trim()}\t${e.value.trim()}"
        }.joinToString("\n")
        val secure = getCallerPrefs()
        if (secure != null) {
            secure.edit().putString("caller_names", raw).apply()
        } else {
            // عند فشل التخزين المشفّر (Keystore معطوب) لا نكتب أسماء المتصلين
            // (PII) في تفضيلات نصية عادية أبداً — تُحفظ في الذاكرة لهذه الجلسة.
            memoryCallerNames.clear()
            cleaned.forEach { (k, v) -> memoryCallerNames[k] = v }
        }
    }

    // ============ قوالب الإعلانات ============

    /** قالب إعلان المتصل: يُستبدل {name} باسم المتصل. فارغ = الافتراضي. */
    fun getCallerAnnouncementTemplate(): String =
        prefs.getString("caller_announcement_template", "") ?: ""
    fun setCallerAnnouncementTemplate(template: String?) =
        prefs.edit().putString("caller_announcement_template", template).apply()

    /** قالب قراءة الرسائل: يُستبدل {name} و{message} باسم المرسل ومحتوى الرسالة. */
    fun getSmsAnnouncementTemplate(): String =
        prefs.getString("sms_announcement_template", "") ?: ""
    fun setSmsAnnouncementTemplate(template: String?) =
        prefs.edit().putString("sms_announcement_template", template).apply()

    // ============ إعدادات إعلان الوقت ============

    /** الصوت المفضّل لكل فئة (category -> voiceId) */
    fun getPreferredVoiceIdForCategory(category: String): String? = normalizeVoiceId(prefs.getString("preferred_voice_$category", null))
    fun setPreferredVoiceIdForCategory(category: String, voiceId: String) =
        prefs.edit().putString("preferred_voice_$category", voiceId).apply()

    /** سرعة النطق لكل فئة */
    fun getSpeechRateForCategory(category: String): Float = prefs.getFloat("speech_rate_$category", 1.0f)
    fun setSpeechRateForCategory(category: String, rate: Float) =
        prefs.edit().putFloat("speech_rate_$category", rate).apply()

    /** نبرة الصوت لكل فئة */
    fun getPitchForCategory(category: String): Float = prefs.getFloat("pitch_$category", 1.0f)
    fun setPitchForCategory(category: String, pitch: Float) =
        prefs.edit().putFloat("pitch_$category", pitch).apply()

    /** مستوى الصوت لكل فئة */
    fun getVolumeForCategory(category: String): Float = prefs.getFloat("volume_$category", 1.0f)
    fun setVolumeForCategory(category: String, volume: Float) =
        prefs.edit().putFloat("volume_$category", volume).apply()

    /** تفعيل/إيقاف إعلان الوقت */
    fun isTimeAnnouncementEnabled(): Boolean = prefs.getBoolean("time_announcement_enabled", true)
    fun setTimeAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_announcement_enabled", enabled).apply()

    /** فاصل إعلان الوقت (بالدقائق): 15, 30, 45, 60 */
    fun getTimeAnnouncementInterval(): Int = prefs.getInt("time_announcement_interval", 30)
    fun setTimeAnnouncementInterval(interval: Int) =
        prefs.edit().putInt("time_announcement_interval", interval).apply()

    // ============ ساعات الهدوء لكل يوم ============

    private fun quietDayKey(day: Int, isStart: Boolean): String {
        val d = day.coerceIn(1, 7)
        return "time_quiet_day${d}_${if (isStart) "start" else "end"}"
    }

    /** ترحيل فترة الهدوء القديمة الواحدة إلى كل الأيام (مرة واحدة فقط). */
    private fun migrateQuietScheduleIfNeeded() {
        if (prefs.getBoolean(KEY_QUIET_MIGRATED, false)) return
        val hasLegacyStart = prefs.contains("time_announcement_quiet_start")
        val hasLegacyEnd = prefs.contains("time_announcement_quiet_end")
        if (!hasLegacyStart && !hasLegacyEnd) {
            prefs.edit().putBoolean(KEY_QUIET_MIGRATED, true).apply()
            return
        }
        val start = prefs.getInt("time_announcement_quiet_start", 23).coerceIn(0, 23)
        val end = prefs.getInt("time_announcement_quiet_end", 7).coerceIn(0, 23)
        val edit = prefs.edit()
        for (day in 1..7) {
            edit.putInt(quietDayKey(day, true), start)
            edit.putInt(quietDayKey(day, false), end)
        }
        edit.remove("time_announcement_quiet_start")
            .remove("time_announcement_quiet_end")
            .putBoolean(KEY_QUIET_MIGRATED, true)
            .apply()
    }

    /** بداية فترة الهدوء ليوم محدد (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت). */
    fun getQuietStartForDay(day: Int): Int {
        migrateQuietScheduleIfNeeded()
        return prefs.getInt(quietDayKey(day, true), 23)
    }

    /** نهاية فترة الهدوء ليوم محدد (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت). */
    fun getQuietEndForDay(day: Int): Int {
        migrateQuietScheduleIfNeeded()
        return prefs.getInt(quietDayKey(day, false), 7)
    }

    fun setQuietStartForDay(day: Int, hour: Int) {
        prefs.edit().putInt(quietDayKey(day, true), hour.coerceIn(0, 23)).apply()
    }

    fun setQuietEndForDay(day: Int, hour: Int) {
        prefs.edit().putInt(quietDayKey(day, false), hour.coerceIn(0, 23)).apply()
    }

    /** صيغة إعلان الوقت: "arabic_natural" أو "digital" */
    fun getTimeAnnouncementFormat(): String = prefs.getString("time_announcement_format", "arabic_natural") ?: "arabic_natural"
    fun setTimeAnnouncementFormat(format: String) =
        prefs.edit().putString("time_announcement_format", format).apply()

    /** عرض الوقت بنظام 24 ساعة في الصيغة الرقمية (بدل 12 ساعة الافتراضية) */
    fun isTime24Hour(): Boolean = prefs.getBoolean("time_display_24h", false)
    fun setTime24Hour(enabled: Boolean) =
        prefs.edit().putBoolean("time_display_24h", enabled).apply()

    /** نطق التواريخ بالتقويم الهجري (أم القرى) بدل الميلادي */
    fun isHijriDateEnabled(): Boolean = prefs.getBoolean("hijri_date", false)
    fun setHijriDateEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("hijri_date", enabled).apply()

    // ============ إعدادات عامة ============

    /** السرعة العامة الافتراضية */
    fun getDefaultSpeechRate(): Float = prefs.getFloat("default_speech_rate", 1.0f)
    fun setDefaultSpeechRate(rate: Float) =
        prefs.edit().putFloat("default_speech_rate", rate).apply()

    /** النبرة العامة الافتراضية */
    fun getDefaultPitch(): Float = prefs.getFloat("default_pitch", 1.0f)
    fun setDefaultPitch(pitch: Float) =
        prefs.edit().putFloat("default_pitch", pitch).apply()

    /** مستوى الصوت العام الافتراضي */
    fun getDefaultVolume(): Float = prefs.getFloat("default_volume", 1.0f)
    fun setDefaultVolume(volume: Float) =
        prefs.edit().putFloat("default_volume", volume).apply()

    /** تفعيل أداة الساعة على الشاشة الرئيسية */
    fun isClockWidgetEnabled(): Boolean = prefs.getBoolean("clock_widget_enabled", false)
    fun setClockWidgetEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("clock_widget_enabled", enabled).apply()

    // ============ إعدادات إعلان مستوى البطارية ============

    /** العناصر المختارة في قسم «صحة الجهاز» للنطق عند الطلب. */
    fun getDeviceHealthItems(): Set<String> =
        prefs.getStringSet("device_health_items", null)
            ?.filter { it in validDeviceHealthItems() }
            ?.toSet() ?: DEFAULT_DEVICE_HEALTH_ITEMS

    fun setDeviceHealthItems(items: Set<String>) =
        prefs.edit().putStringSet(
            "device_health_items", items.filter { it in validDeviceHealthItems() }.toMutableSet()
        ).apply()

    private fun validDeviceHealthItems(): Set<String> = setOf(
        DEVICE_HEALTH_BATTERY,
        DEVICE_HEALTH_CHARGING,
        DEVICE_HEALTH_STORAGE,
        DEVICE_HEALTH_MEMORY
    )

    /** تفعيل/إيقاف إعلان مستوى البطارية */
    fun isBatteryAnnouncementEnabled(): Boolean = prefs.getBoolean("battery_announcement_enabled", false)
    fun setBatteryAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("battery_announcement_enabled", enabled).apply()

    /**
     * مستويات البطارية المفعّلة (كنسب مئوية مضاعفات 5) التي يُعلن عنها.
     * مثلاً {"20","15"} تُنطق عند وصول البطارية إلى 20% ثم 15%.
     */
    fun getBatteryAnnouncementLevels(): Set<String> =
        prefs.getStringSet("battery_announcement_levels", mutableSetOf("20", "15"))
            ?.toSet() ?: setOf("20", "15")
    fun setBatteryAnnouncementLevels(levels: Set<String>) =
        prefs.edit().putStringSet("battery_announcement_levels", levels.toMutableSet()).apply()

    /** صوت إعلان البطارية (معرّف صوت موحّد) */
    fun getBatteryAnnouncementVoiceId(): String? = normalizeVoiceId(prefs.getString("battery_announcement_voice", null))
    fun setBatteryAnnouncementVoiceId(voiceId: String?) =
        prefs.edit().putString("battery_announcement_voice", voiceId).apply()

    /** سرعة نطق إعلان البطارية */
    fun getBatteryAnnouncementRate(): Float = prefs.getFloat("battery_announcement_rate", 1.0f)
    fun setBatteryAnnouncementRate(rate: Float) =
        prefs.edit().putFloat("battery_announcement_rate", rate).apply()

    /** مستوى صوت إعلان البطارية */
    fun getBatteryAnnouncementVolume(): Float = prefs.getFloat("battery_announcement_volume", 1.0f)
    fun setBatteryAnnouncementVolume(volume: Float) =
        prefs.edit().putFloat("battery_announcement_volume", volume).apply()

    // ============ إعدادات إعلان اسم المتصل ============

    /** تفعيل/إيقاف إعلان اسم المتصل */
    fun isCallerAnnouncementEnabled(): Boolean = prefs.getBoolean("caller_announcement_enabled", false)
    fun setCallerAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("caller_announcement_enabled", enabled).apply()

    /** عدد مرات تكرار اسم المتصل */
    fun getCallerAnnouncementRepeat(): Int = prefs.getInt("caller_announcement_repeat", 1)
    fun setCallerAnnouncementRepeat(repeat: Int) =
        prefs.edit().putInt("caller_announcement_repeat", repeat).apply()

    /** صوت إعلان المتصل بلغة عربية (معرّف صوت موحّد) */
    fun getCallerAnnouncementArabicVoiceId(): String? = normalizeVoiceId(prefs.getString("caller_announcement_voice_ar", null))
    fun setCallerAnnouncementArabicVoiceId(voiceId: String?) =
        prefs.edit().putString("caller_announcement_voice_ar", voiceId).apply()

    /** صوت إعلان المتصل بلغة إنجليزية (معرّف صوت موحّد) */
    fun getCallerAnnouncementEnglishVoiceId(): String? = normalizeVoiceId(prefs.getString("caller_announcement_voice_en", null))
    fun setCallerAnnouncementEnglishVoiceId(voiceId: String?) =
        prefs.edit().putString("caller_announcement_voice_en", voiceId).apply()

    /** سرعة نطق إعلان المتصل */
    fun getCallerAnnouncementRate(): Float = prefs.getFloat("caller_announcement_rate", 1.0f)
    fun setCallerAnnouncementRate(rate: Float) =
        prefs.edit().putFloat("caller_announcement_rate", rate).apply()

    /** مستوى صوت إعلان المتصل */
    fun getCallerAnnouncementVolume(): Float = prefs.getFloat("caller_announcement_volume", 1.0f)
    fun setCallerAnnouncementVolume(volume: Float) =
        prefs.edit().putFloat("caller_announcement_volume", volume).apply()

    // ============ إعدادات قراءة الرسائل الواردة ============

    /**
     * وضع قراءة الرسائل الواردة:
     * - "off": معطل (لا يُقرأ شيء)
     * - "full": مفعل (يُقرأ اسم المرسل ومحتوى الرسالة)
     * - "source": قراءة مصدر الرسالة فقط (اسم المرسل دون المحتوى)
     */
    fun getSmsReadingMode(): String = prefs.getString("sms_reading_mode", "off") ?: "off"
    fun setSmsReadingMode(mode: String) =
        prefs.edit().putString("sms_reading_mode", mode).apply()

    /** صوت قراءة الرسائل (معرّف صوت موحّد) */
    fun getSmsReadingVoiceId(): String? = normalizeVoiceId(prefs.getString("sms_reading_voice", null))
    fun setSmsReadingVoiceId(voiceId: String?) =
        prefs.edit().putString("sms_reading_voice", voiceId).apply()

    /** سرعة نطق قراءة الرسائل */
    fun getSmsReadingRate(): Float = prefs.getFloat("sms_reading_rate", 1.0f)
    fun setSmsReadingRate(rate: Float) =
        prefs.edit().putFloat("sms_reading_rate", rate).apply()

    /** مستوى صوت قراءة الرسائل */
    fun getSmsReadingVolume(): Float = prefs.getFloat("sms_reading_volume", 1.0f)
    fun setSmsReadingVolume(volume: Float) =
        prefs.edit().putFloat("sms_reading_volume", volume).apply()

    // ===== التحويل التلقائي بين اللغات =====

    /** تفعيل التحويل التلقائي بين اللغتين الأولى والثانية */
    fun isAutoConvertEnabled(): Boolean = prefs.getBoolean("auto_convert_enabled", false)
    fun setAutoConvertEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("auto_convert_enabled", enabled).apply()

    /** إظهار التوضيح الاختياري «بعض اللغات قد لا تظهر…» (نص فقط، غير افتراضي). */
    fun isLanguageInstallHintEnabled(): Boolean = prefs.getBoolean("show_language_install_hint", false)
    fun setLanguageInstallHintEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("show_language_install_hint", enabled).apply()

    // ---- الخريطة الديناميكية للتحويل التلقائي (languageTag -> تفضيلات) ----
    // استبدلنا نظام سلوتات «اللغة 1/اللغة 2» الثابت (ar/en فقط) بتخزين عام
    // محفوظ كخريطة JSON في SharedPreferences عبر GsonTypes+ConvertPreferencesCodec،
    // ليُدعم عدد غير محدود من اللغات. قراءة NateqTtsService.resolveConvertTarget
    // تتم مباشرةً من هذه الخريطة، ويُرحَّل أي إعداد قديم من السلوتات تلقائياً
    // عند أول وصول (ensureConvertSlotsMigrated) دون حذفها نفسها.

    /**
     * تفضيلات التحويل للغة معينة (محرك/صوت/سرعة/نبرة/صوت). تُطبَّع علامة اللغة
     * إلى كود ISO-2 قبل البحث. اللغة بلا أي إعداد → مدخل افتراضي (لا تحويل).
     */
    fun getEnginePreferenceForLanguage(languageTag: String): LanguageSpeechPrefs {
        ensureConvertSlotsMigrated()
        return readConvertPrefs()[
            ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        ] ?: LanguageSpeechPrefs()
    }

    /**
     * يعين تفضيل محرك/صوت/أشرطة للغة معينة في الخريطة الديناميكية.
     * مدخل بلا أي تعديلات (كل القيم الافتراضية) يُحذف من الخريطة (لا تحويل).
     */
    fun setEnginePreferenceForLanguage(
        languageTag: String,
        engine: String?,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        volume: Float
    ) {
        ensureConvertSlotsMigrated()
        val map = readConvertPrefs().toMutableMap()
        val key = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        val entry = ConvertPreferencesCodec.entryForSave(engine, voiceName, rate, pitch, volume)
        if (entry.hasAdjustment) map[key] = entry else map.remove(key)
        writeConvertPrefs(map)
    }

    /** كل تفضيلات التحويل الحالية (لغة -> مدخل) للعرض في قائمة الإعدادات. */
    fun allConvertLanguagePreferences(): Map<String, LanguageSpeechPrefs> {
        ensureConvertSlotsMigrated()
        return readConvertPrefs()
    }

    private fun readConvertPrefs(): Map<String, LanguageSpeechPrefs> {
        val json = prefs.getString(KEY_CONVERT_PREFS_JSON, null)
        return if (json.isNullOrBlank()) emptyMap() else ConvertPreferencesCodec.fromJson(json)
    }

    private fun writeConvertPrefs(map: Map<String, LanguageSpeechPrefs>) {
        prefs.edit().putString(KEY_CONVERT_PREFS_JSON, ConvertPreferencesCodec.toJson(map)).apply()
    }

    /**
     * ترحيل تلقائي لمرة واحدة من سلوتات «اللغة الأولى/اللغة الثانية» القديمة
     * إلى الخريطة الديناميكية، فلا يفقد من خزّن إعداداته قبل التحديث شيئاً.
     * لا تحذف السلوتات نفسها (تبقى قابلة للقراءة للتوافقية العكسية) — يُمهَّر
     * وسم الترحيل فوراً كحارس حتى لا يتكرر العمل إذا فشل لاحقاً.
     */
    private fun ensureConvertSlotsMigrated() {
        if (prefs.getBoolean(KEY_CONVERT_SLOTS_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_CONVERT_SLOTS_MIGRATED, true).apply()

        val map = readConvertPrefs().toMutableMap()
        // السلوت الأول كان بحقّ اللغة العربية افتراضياً، والثاني الإنجليزية.
        ConvertPreferencesCodec.mergeLegacySlot(
            map, getConvertLanguageTag1(),
            ConvertPreferencesCodec.entryForSave(
                getConvertEngine1(), normalizeVoiceId(getConvertVoice1()),
                getConvertRate1(), getConvertPitch1(), getConvertVolume1()
            ),
            "ar"
        )
        ConvertPreferencesCodec.mergeLegacySlot(
            map, getConvertLanguageTag2(),
            ConvertPreferencesCodec.entryForSave(
                getConvertEngine2(), normalizeVoiceId(getConvertVoice2()),
                getConvertRate2(), getConvertPitch2(), getConvertVolume2()
            ),
            "en"
        )
        if (map != readConvertPrefs()) writeConvertPrefs(map)
        Log.w(TAG, "تم ترحيل سلوتات التحويل القديمة إلى الخريطة الديناميكية ($map)")
    }

    // --- السلوتات القديمة (تُبقى للتوافقية العكسية؛ تُرحَّل تلقائياً أعلاه) ---

    /** محرك اللغة الأولى (حزمة محرك TTS) */
    fun getConvertEngine1(): String? = prefs.getString("convert_lang1_engine", null)
    fun setConvertEngine1(pkg: String?) =
        prefs.edit().putString("convert_lang1_engine", pkg).apply()

    /** علامة لغة (languageTag) للغة الأولى */
    fun getConvertLanguageTag1(): String? = prefs.getString("convert_lang1_lang", null)
    fun setConvertLanguageTag1(langTag: String?) =
        prefs.edit().putString("convert_lang1_lang", langTag).apply()

    /** معرف الصوت المختار للغة الأولى */
    fun getConvertVoice1(): String? = prefs.getString("convert_lang1_voice", null)
    fun setConvertVoice1(voiceId: String?) =
        prefs.edit().putString("convert_lang1_voice", voiceId).apply()

    /** مستوى صوت اللغة الأولى */
    fun getConvertVolume1(): Float = prefs.getFloat("convert_lang1_volume", 1.0f)
    fun setConvertVolume1(volume: Float) =
        prefs.edit().putFloat("convert_lang1_volume", volume).apply()

    /** نبرة اللغة الأولى */
    fun getConvertPitch1(): Float = prefs.getFloat("convert_lang1_pitch", 1.0f)
    fun setConvertPitch1(pitch: Float) =
        prefs.edit().putFloat("convert_lang1_pitch", pitch).apply()

    /** سرعة اللغة الأولى */
    fun getConvertRate1(): Float = prefs.getFloat("convert_lang1_rate", 1.0f)
    fun setConvertRate1(rate: Float) =
        prefs.edit().putFloat("convert_lang1_rate", rate).apply()

    /** محرك اللغة الثانية (حزمة محرك TTS) */
    fun getConvertEngine2(): String? = prefs.getString("convert_lang2_engine", null)
    fun setConvertEngine2(pkg: String?) =
        prefs.edit().putString("convert_lang2_engine", pkg).apply()

    /** علامة لغة (languageTag) للغة الثانية */
    fun getConvertLanguageTag2(): String? = prefs.getString("convert_lang2_lang", null)
    fun setConvertLanguageTag2(langTag: String?) =
        prefs.edit().putString("convert_lang2_lang", langTag).apply()

    /** معرف الصوت المختار للغة الثانية */
    fun getConvertVoice2(): String? = prefs.getString("convert_lang2_voice", null)
    fun setConvertVoice2(voiceId: String?) =
        prefs.edit().putString("convert_lang2_voice", voiceId).apply()

    /** مستوى صوت اللغة الثانية */
    fun getConvertVolume2(): Float = prefs.getFloat("convert_lang2_volume", 1.0f)
    fun setConvertVolume2(volume: Float) =
        prefs.edit().putFloat("convert_lang2_volume", volume).apply()

    /** نبرة اللغة الثانية */
    fun getConvertPitch2(): Float = prefs.getFloat("convert_lang2_pitch", 1.0f)
    fun setConvertPitch2(pitch: Float) =
        prefs.edit().putFloat("convert_lang2_pitch", pitch).apply()

    /** سرعة اللغة الثانية */
    fun getConvertRate2(): Float = prefs.getFloat("convert_lang2_rate", 1.0f)
    fun setConvertRate2(rate: Float) =
        prefs.edit().putFloat("convert_lang2_rate", rate).apply()

    // ═══════════════════════════════════════════════════════
    // قراءة الإشعارات (واتساب، تلجرام، إلخ)
    // ═══════════════════════════════════════════════════════

    /** هل قراءة الإشعارات مفعّلة؟ */
    fun isNotificationReadingEnabled(): Boolean =
        prefs.getBoolean("notification_reading_enabled", false)

    fun setNotificationReadingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("notification_reading_enabled", enabled).apply()

    // ============ اختيار تطبيقات قراءة الإشعارات ============

    /** الحزمة "كل التطبيقات" تعني قراءة كل الإشعارات، وإلا حزم مختارة.
     *  عند غياب أي اختيار صريح تُستخدم القائمة الافتراضية المحدودة. */
    fun getNotificationAppsSelection(): Set<String> =
        prefs.getStringSet("notification_apps_selection", null)
            ?.toSet() ?: DEFAULT_NOTIFICATION_APPS

    fun setNotificationAppsSelection(pkgs: Set<String>) =
        prefs.edit().putStringSet("notification_apps_selection", pkgs.toMutableSet()).apply()

    /** هل يُقرأ إشعار من هذه الحزمة حسب الاختيار الحالي؟ */
    fun shouldReadNotificationApp(pkg: String): Boolean {
        val selection = getNotificationAppsSelection()
        return NOTIF_READ_ALL in selection || pkg in selection
    }

    // ============ النسخ الاحتياطي / الاستعادة ============

    /** كل الإعدادات القابلة للنسخ (لا يُصدَّر أي وسم ترحيل داخلي). */
    fun exportSettings(): Map<String, Any> =
        prefs.all.entries
            .filter { !it.key.startsWith("_") }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()

    /** تعقّل قيمة عددية حسب المفتاح حفاظاً على سلامة النطاقات. */
    private fun sanitizeInt(key: String, value: Int): Int = when {
        key.startsWith("time_quiet_day") && key.endsWith("_start") -> value.coerceIn(0, 23)
        key.startsWith("time_quiet_day") && key.endsWith("_end") -> value.coerceIn(0, 23)
        key == "time_announcement_interval" ->
            if (value in intArrayOf(15, 30, 45, 60)) value else 30
        key == "number_reading_mode" -> value.coerceIn(1, 8)
        key == "caller_announcement_repeat" -> value.coerceIn(1, 5)
        key == "power_saver_battery_threshold" -> value.coerceIn(0, 100)
        else -> value
    }

    /** تعقّل قيمة عشرية حسب المفتاح (السرعة/النبرة تعبان، المستوى نسبة). */
    private fun sanitizeFloat(key: String, value: Float): Float = when {
        key.contains("_rate") || key.contains("_pitch") -> value.coerceIn(0f, 2f)
        key.contains("_volume") || key == "default_volume" -> value.coerceIn(0f, 1f)
        else -> value
    }

    /** تعقّل مجموعة سلاسل: مستويات البطارية تبقى مضاعفات 5 في المدى 5..100. */
    private fun sanitizeStringSet(key: String, value: Set<String>): Set<String> = when (key) {
        "battery_announcement_levels" -> value.filter {
            it.toIntOrNull()?.let { n -> n in 5..100 && n % 5 == 0 } == true
        }.toSet()
        "device_health_items" -> value.filter { it in validDeviceHealthItems() }.toSet()
        else -> value
    }

    /**
     * استعادة الإعدادات من خريطة صادرة عن exportSettings، مع تعقّل النطاقات.
     * تجمَّع العمليات أولاً (بلا لمس القرص) ثم تُطبق دفعة واحدة — فلا يبقى
     * الملف نصف مُمسوح إن فشل التجميع.
     */
    fun importSettings(map: Map<String, Any>): Boolean {
        return try {
            class Op(val apply: (SharedPreferences.Editor) -> SharedPreferences.Editor)
            val ops = mutableListOf<Op>()
            var meaningful = 0
            for ((key, value) in map) {
                if (key.startsWith("_")) continue
                when (value) {
                    is Int -> {
                        val v = sanitizeInt(key, value)
                        ops.add(Op { it.putInt(key, v) }); meaningful++
                    }
                    is Long -> {
                        val v = sanitizeInt(key, value.toInt())
                        ops.add(Op { it.putInt(key, v) }); meaningful++
                    }
                    is Float -> {
                        val v = sanitizeFloat(key, value)
                        ops.add(Op { it.putFloat(key, v) }); meaningful++
                    }
                    is Boolean -> { ops.add(Op { it.putBoolean(key, value) }); meaningful++ }
                    is String -> { ops.add(Op { it.putString(key, value) }); meaningful++ }
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val v = sanitizeStringSet(key, value as Set<String>)
                        ops.add(Op { it.putStringSet(key, v) }); meaningful++
                    }
                    else -> {}
                }
            }
            if (meaningful == 0) return false
            val editor = prefs.edit().clear()
            for (op in ops) editor.let(op.apply)
            editor.commit()
        } catch (t: Throwable) {
            Log.e(TAG, "import settings failed", t)
            false
        }
    }
}
