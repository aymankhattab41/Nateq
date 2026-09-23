package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.ConvertPreferencesCodec
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.VoiceIdContract

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
class SettingsRepository(context: Context) :
    SynthesisConfig,
    VoicePrefsProvider,
    LanguagePrefs,
    SynthesisPrefs,
    CategoryVoicePrefs,
    ReadingPrefs,
    AnnouncementPrefs,
    DevicePrefs,
    ConvertPrefs,
    CallerNamesStore {

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
        const val VOICE_CATEGORY_EMOJI = "emoji"

        /** فئة إعلان المتصل (محرك/صوت مستقل للإعلان عن المكالمات). */
        const val ANNOUNCE_CATEGORY_CALLER = "caller"

        /** فئة إعلان الرسائل النصية (محرك/نبرة مستقلان إن ضُبطا). */
        const val ANNOUNCE_CATEGORY_SMS = "sms"

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

        /** اللغات الصالحة للغة النطق الاحتياطية غير المتحسَّم (بند اللغة
         *  الثانية) — تطابق اللغات الأربع المكتشفة في
         *  LatinLanguageDetector (core:audio)؛ تُثبَّت "en" عند استيراد
         *  قيمة خارجة عنها حتى لا تُطلب اللغةُ الاحتياطيةُ بلسانٍ غير
         *  مدعوم. */
        val VALID_SECONDARY_LANGUAGES = setOf(
            "en", "fr", "de", "es"
        )

        /** أقصى عدد يُقبل من أسماء المتصلين المخصصة (حماية من استيراد فائض). */
        private const val MAX_CALLER_ENTRIES = 2000

        /** قالب رقم هاتف مقبول في أسماء المتصلين (رمز + اختياري ثم 3..32
         *  من الأرقام/المسافات/الأقواس/الشرطات) — نمط واحد مشترك معرّف
         *  مرة واحدة لا يُبنى لكل مفتاح عند كل استيراد. */
        private val CALLER_PHONE_REGEX =
            Regex("^[+]?[0-9\\s()\\-]{3,32}$")

        /** مفاتيح ميزات حساسة (استشعارات) تُصفَّر دائماً عند الاستيراد:
         *  الهز/التقارب يفعّلان مستشعرات فعلية تُثبَّت false عند الترميم
         *  حتى لا تستهلك مستشعراً بلا علم المستخدم
         *  (المحور 6 — «إعداد آمن عند الاستيراد»). */
        private val SAFE_DEFAULT_FALSE_KEYS = setOf(
            "shake_to_stop_enabled",
            "proximity_silence_enabled"
        )

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

        /** مفتاح خريطة تفضيلات التحويل لكل لغة
         *  (JSON عبر ConvertPreferencesCodec). */
        private const val KEY_CONVERT_PREFS_JSON = "convert_language_prefs"

        /** وسم ترحيل سلوتات اللغة 1/2 القديمة إلى الخريطة
         *  الديناميكية (مرة واحدة). */
        private const val KEY_CONVERT_SLOTS_MIGRATED = "_convert_slots_migrated"

        /** وسوم الترحيل الداخلية الثلاثة: تُستثنى من النسخ الاحتياطي/الاستيراد
         *  (تبدأ بـ «_») لكن لا يجوز أن يمسحها clear() في importSettings وإلا
         *  يتحرك الترحيل من جديد فوق نسخةٍ مكتملة.
         *  تُحفظ وتُعاد عند الاستيراد. */
        private val MIGRATION_KEYS = listOf(
            KEY_MIGRATED,
            KEY_QUIET_MIGRATED,
            KEY_CONVERT_SLOTS_MIGRATED
        )

        /**
         * المصنّع الموحّد الوحيد لمثيل SettingsRepository — يُستعمل في كل
         * المواقع التي لا يتوفر فيها [AnnouncementAppContext] المعرّف من :app
         * (البند 4 — توحيد الحقن بنقطة بناء واحدة بدل بناءات متفرقة).
         */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext)
    }

    /** سياق التطبيق العام لضمان عدم احتجاز سياق النشاط في المراقبين. */
    private val appContext: Context = context.applicationContext

    /** جسر القراءة عبر العمليتين لملف الإعدادات المشترك. */
    private val prefsBridge = MultiProcessPrefsBridge(appContext)

    /** توقيت آخر تعديل لملف الإعدادات — حارس كشف الكتابة من العملية الأخرى. */
    @Volatile
    private var prefsLastModified: Long = prefsFileLastModified()

    /** حجم آخر قراءةٍ لملف الإعدادات بالبايت — حارسٌ مكمّل للتوقيت: زمن
     *  المغيّر (دقة ثانيةٍ على بعض الأنظمة) وحده يفوّت كتابتين متتاليتين
     *  في نفس الثانية، فيلتقطها الطول حتى مع تطابق الزمن (بند السباقات). */
    @Volatile
    private var prefsFileLength: Long = prefsFileLength()

    /** المخزن الحقيقي على القرص (مصدر اللقطة): تُقرأ منه وسوم الترحيل عبر
     *  [rawPrefs] لا من [prefs] لأن الرصدَ الخارجي قد يكتب المخزن مباشرةً
     *  (اختبار/مكوّن قديم) فيبقى قرصه محدّثاً ولقطةُ الغلاف غيرَ ملتقطة. */
    private val rawPrefs: SharedPreferences = openSharedPrefs().also {
        migrateIfNeeded(it)
    }

    /**
     * غلاف [SharedPreferences] القابل لاستبدال اللقطة — تُقرأ منه كل قيم
     * الإعدادات، وتُكتب عبره إلى المخزن الحقيقي ثم يبلّغ
     *  [notifySettingsChanged]
     * ليستيقظ مراقبُ العملية الأخرى عبر [SettingsChangeProvider] فوراً.
     *
     * في أول تشغيل يمرّ المخزن الحقيقي أولاً بترحيل الملف المشفر القديم
     * ([migrateIfNeeded])، ثم تُبني اللقطة من مضمونه فيحسب النطق والمستخدم
     * الجديد ما ترحّل مباشرةً بلا دورة إضافية.
     */
    private val prefs: SnapshotPrefs = SnapshotPrefs(
        delegate = rawPrefs,
        onChanged = ::notifySettingsChanged
    )

    /** مراقب التغييرات بين العمليتين: يستيقظ عند إشعار [SettingsChangeProvider]
     *  (كتابةٌ في العملية الأخرى) فيستدعي [reload] لتبديل اللقطة محلياً. */
    private val settingsObserver = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            reload()
        }
    }

    init {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                SettingsChangeProvider.uri(), false, settingsObserver
            )
        }
    }

    /**
     * يلغي تسجيل مراقب التغييرات عند الحاجة (إغلاق يدوي أو في بيئة الاختبار).
     */
    fun unregister() {
        runCatching {
            appContext.contentResolver.unregisterContentObserver(
                settingsObserver
            )
        }
    }

    /**
     * يفتح تفضيلات [NEW_PREFS] العادية.
     *
     * التطبيق يعمل في عمليتين (main و :tts) وSharedPreferences يحتفظ بكاش
     * في الذاكرة لكل عملية، فلا يوجد «إعادة فتح» داخل نفس العملية. لذلك
     * تُعالَج حداثة بيانات العملية الأخرى صراحةً في [reload] بقراءة ملف
     * القرص الفعلي من جديد — بلا أي اعتماد على MODE_MULTI_PROCESS المكروه
     * (API 23+) الذي يجعل الإطار يعيد قراءة المخزن داخلياً فيتعذّر اختباره.
     */
    private fun openSharedPrefs(): SharedPreferences =
        appContext.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)

    // أسماء المتصلين = بيانات شخصية (PII) تُخزَّن في ملف مشفَّر منفصل؛
    // عند تعذر التشفير (Keystore معطوب…) تُحتفظ في الذاكرة لهذه الجلسة فقط
    // ولا تُكتب في تفضيلات نصية عادية أبداً (منع تسريب PII).
    @Volatile
    private var callerSecurePrefs: SharedPreferences? = null

    private val memoryCallerNames =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    fun reload() {
        // التطبيق يعمل في عمليتين (main و :tts) وSharedPreferences يحتفظ
        // بكاش في الذاكرة لكل عملية ولا يعيد قراءة القرص تلقائياً. لتخفيف
        // عبء I/O لا نعيد القراءة إلا عندما يتغيّر توقيت الملف الفعلي (أي
        // كتابة من العملية الأخرى) بدل قراءة قرص دائمة مع كل نطق.
        if (!prefsFileChanged()) return
        // **بند 5.1:** نثبّت زمن الدخول (التوقيت والطول معاً — زمنٌ واحد
        // قد يتطابق لكتابةٍ ثانيةٍ في نفس الثانية) لنكتشف لاحقاً أيَّ كتابةٍ
        // متزامنةٍ من العملية الأخرى حدثت أثناء قراءتنا للملف — إن تغيّرت
        // العلامة قبل التطبيق نتخلى عن هذه الجولة كي لا نكتب أقدم فوق أحدث.
        val stampAtEntry = prefsFileStamp()
        // قراءة صريحة لملف القرص بتنسيق SharedPreferences التوثيقي ثم تبديل
        // اللقطة المقروءة منها القيم — بلا أي كتابةٍ إلى الملف. لذلك لم يعد
        // `reload` في حد ذاته يغيّر القرص فيطرد إشعاراً متكرراً بين العمليتين
        // (بند 6: التحديث من العملية الأخرى يُلتقط بالقراءة لا بإعادة الكتابة).
        val fresh = runCatching {
            prefsBridge.parse(NEW_PREFS)
        }.getOrNull()
            ?: return
        // بند 5.2: عند تطابق القراءة الجديدة مع الحالة الحالية
        // (قراءةُ عمليةٍ أخرى لملفٍ لم يتبدّل مضمونه) لا نُحدّث شيئاً —
        // المفتاح الداخلي KEY_MIGRATED يُستثنى من الطرفين معاً حتى لا
        // يفشل التطابقُ لاختلافه الضمني (كان كل reloadٍ يعيد flash/apply).
        if (prefs.all.minus(KEY_MIGRATED) ==
            fresh.minus(KEY_MIGRATED)
        ) {
            refreshPrefsStamp()
            return
        }
        // **بند 5.1:** إعادة الفحص قبل التطبيق — إن تغيّر الملف أثناء
        // القراءة (كتابة متزامنة من العملية الأخرى) نتخلى عن التحديث
        // هذه الجولة وتُعالَج الأحدثُ في نداء reload() تالٍ.
        if (prefsFileStamp() != stampAtEntry) return
        // تطبيق اللقطة في الذاكرة (مفتاح الترحيل محفوظ من القراءة الجديدة —
        // الملف يُكتب عليه دائماً عند الترحيل فيبقى موجوداً في fresh).
        prefs.replaceSnapshot(fresh)
        refreshPrefsStamp()
    }

    /** يُبلغ بطاقةَ الـ ContentObserver المعلنة في Manifest الوحدة بعد كل
     *  كتابةٍ ناجحة عبر [SnapshotPrefs]، فيستيقظ مراقبُ العملية الأخرى
     *  (ContentObserver) ويسترجع اللقطة فوراً بدل انتظار دورة
     *  reload التالية. */
    private fun notifySettingsChanged() {
        runCatching {
            appContext.contentResolver.notifyChange(
                SettingsChangeProvider.uri(), null
            )
        }
    }

    /** مسار ملف الإعدادات المشترك بين العمليات. */
    private fun prefsFile(): java.io.File = prefsBridge.fileFor(NEW_PREFS)

    private fun prefsFileLastModified(): Long =
        prefsBridge.lastModified(NEW_PREFS)

    private fun prefsFileLength(): Long =
        prefsBridge.length(NEW_PREFS)

    /** لقطةٌ مزدوجة لحالة الملف (توقيت + طول): تُستعمل علاّمةً ثابتةً عند
     *  دخول [reload] — فالتوقيت وحده قد يتطابق لكتابةٍ ثانيةٍ في نفس
     *  الثانية، والطول يفرّقها. */
    private fun prefsFileStamp(): Pair<Long, Long> =
        prefsFileLastModified() to prefsFileLength()

    private fun prefsFileChanged(): Boolean =
        prefsBridge.isChanged(
            NEW_PREFS, prefsLastModified, prefsFileLength
        )

    private fun refreshPrefsStamp() {
        prefsLastModified = prefsFileLastModified()
        prefsFileLength = prefsFileLength()
    }

    /**
     * يوحّد معرّفات الأصوات القديمة (nateq-ar*, nateq-en*, ar-local, en-local
     * وكذلك البديل الخاطئ الأحدث nateq-<lang>-local والصيغة القديمة
     * "<lang>-local") مع الصيغة الحالية (ar-EG/en-US/<lang>) حتى تبقى القيم
     * المخزنة قبل إعادة التسمية تعمل وتعرض بشكل صحيح في شاشات الإعدادات.
     * القاعدة كلها في [VoiceIdContract].
     */
    private fun normalizeVoiceId(id: String?): String? =
        VoiceIdContract.normalize(id)

    /**
     * يرحّل الإعدادات من الملف المشفر القديم إلى الملف الجديد في أول مرة
     * فقط. لا يضع علامة المُرحَّل إلا إذا نجح فعلياً (أو لم يوجد ملف قديم
     * أصلاً) — عطل Keystore العابر لا يُعلن الهجرة ولا يُهمل بيانات المستخدم.
     */
    private fun migrateIfNeeded(newPrefs: SharedPreferences) {
        if (newPrefs.getBoolean(KEY_MIGRATED, false)) return

        // هل يوجد الملف المشفر الحقيقي على القرص؟ هو معيار «هل هناك ما
        // يُرحَّل» — لا نتيجة الفتح التي قد تفشل عابراً (Keystore مباشرة بعد
        // الإقلاع أو قفل الشاشة) فيبدو المخزن «فارغاً» فتُعلَن الهجرة خطأً
        // وتُهمل إعدادات المستخدم بلا رجعة.
        val secureFileExists =
            prefsBridge.fileFor(OLD_PREFS).exists()

        var openedEncrypted = false
        val oldPrefs = try {
            val masterKey = androidx.security.crypto.MasterKey
                .Builder(appContext)
                .setKeyScheme(
                    androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM
                )
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                appContext, OLD_PREFS, masterKey,
                androidx.security.crypto.EncryptedSharedPreferences
                    .PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences
                    .PrefValueEncryptionScheme.AES256_GCM
            ).also { openedEncrypted = true }
        } catch (_: Throwable) {
            // عطل Keystore عابر محتمل: نُجرّب البديل النصي القديم إن حُرِّر
            // في عطلٍ سابق؛ وإن غاب يُترك الترحيل معلّقاً (بلا وسم).
            try {
                appContext.getSharedPreferences(
                    FALLBACK_PREFS,
                    Context.MODE_PRIVATE
                )
            } catch (_: Throwable) { null }
        }

        if (oldPrefs == null || oldPrefs.all.isEmpty()) {
            // لا توجد بيانات مقروءة للترحيل. إن بقي ملف مشفر على القرص ولم
            // نستطع قراءته فلا نُعلن الهجرة — يُعاد الفتح عند إنشاء مخزن لاحق
            // بعد زوال العطل العابر؛ وإن لم يوجد الملف (تثبيت نظيف) أو فُتح
            // المشفر فارغاً فعلاً فلا شيء يُرحّل، فيُؤشَّر مرة واحدة للأبد.
            if (!openedEncrypted && secureFileExists) return
            newPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        val editor = newPrefs.edit()
        var copied = 0
        @Suppress("UNCHECKED_CAST")
        for ((key, value) in oldPrefs.all) {
            if (key == KEY_MIGRATED) continue
            when (value) {
                // كانت قيم Long تُسقط صامتة (لا فرع لها):
                // مخزن SharedPreferences يدعم النوع
                // الطويل ويُخزّنه وسم <long>، فتُفقَد
                // مع كل ترحيل.
                is Int -> { editor.putInt(key, value); copied++ }
                is Long -> { editor.putLong(key, value); copied++ }
                is Float -> { editor.putFloat(key, value); copied++ }
                is Boolean -> { editor.putBoolean(key, value); copied++ }
                is String -> { editor.putString(key, value); copied++ }
                is Set<*> -> {
                    editor.putStringSet(key, value as Set<String>)
                    copied++
                }
            }
        }
        editor.putBoolean(KEY_MIGRATED, true)
        // `commit()` متزامن: يضمن كتابة البيانات على القرص قبل حذف الملف
        // القديم حتى لا يسبق الحذفُ الحفظَ (بلا سباق).
        if (editor.commit()) {
            // لا نُحذف الملف المشفر إلا عند النسخ منه مباشرةً؛ أما عند النسخ
            // من البديل النصي (المشفر غير مقروء عابراً) فيبقى الملف على القرص
            // بلا حذف — نفس سياسة أسماء المتصلين: لا حذف عند الشك.
            if (openedEncrypted) {
                appContext.deleteSharedPreferences(OLD_PREFS)
            }
        }
        Log.w(TAG, "تم ترحيل $copied إعداد من الملف القديم إلى الملف الجديد")
    }

    /** هل اكتمل وسم الهجرة من المخزن القديم؟ — رصدٌ للاختبار في نفس الوحدة. */
    @androidx.annotation.VisibleForTesting
    internal fun isMigrationCompleted(): Boolean =
        prefs.getBoolean(KEY_MIGRATED, false)

    /** لغة التطبيق المختارة يدوياً: "ar"/"en"/null (null = تتبع لغة النظام) */
    override fun getAppLanguage(): String? =
        prefs.getString("app_language", null)
    override fun setAppLanguage(language: String?) =
        prefs.edit().putString("app_language", language).apply()

    /** لغة نطق الإعلانات (الساعة/الأرقام): "ar"/"en"/""
     *  ("" = اتبع لغة التطبيق) */
    override fun getAnnouncementSpeechLanguage(): String? =
        prefs.getString("announcement_speech_language", null)
    override fun setAnnouncementSpeechLanguage(language: String?) =
        prefs.edit().putString("announcement_speech_language", language).apply()

    /** لغة النطق الاحتياطية للنص اللاتيني القصير غير المتحسَّم (بند اللغة
     *  الثانية): تُمرَّر إلى [com.aymankhattab.nateq.core.audio.engine
     *  .LanguageSegmenter] فتُنطق بها الكلمة الأجنبية بدل الإنجليزية
     *  الافتراضية. الافتراضي "en". */
    override fun getSecondaryLanguage(): String =
        prefs.getString("secondary_language", LanguageCode.EN.tag)
            ?: LanguageCode.EN.tag
    override fun setSecondaryLanguage(language: String) =
        prefs.edit().putString("secondary_language", language).apply()

    /** طريقة نطق الأرقام: 1=مفردة، 2=زوجي، 3=ثلاثي، ... 8=ثماني */
    override fun getNumberReadingMode(): Int =
        prefs.getInt("number_reading_mode", 1)
    override fun setNumberReadingMode(mode: Int) =
        prefs.edit().putInt("number_reading_mode", mode.coerceIn(1, 8)).apply()

    /** لغة نطق الأرقام: "ar" أو "en" فقط (لا تلقائي). */
    override fun getNumberReadingLanguage(): String {
        val lang = prefs.getString("number_reading_language", "ar") ?: "ar"
        return if (lang == "en") "en" else "ar"
    }
    override fun setNumberReadingLanguage(lang: String) {
        val safe = if (lang == "en") "en" else "ar"
        prefs.edit().putString("number_reading_language", safe).apply()
    }

    /** الصوت المفضّل لكل لغة (languageTag -> voiceId) */
    override fun getPreferredVoiceId(languageTag: String): String? =
        normalizeVoiceId(
            prefs.getString("preferred_voice_$languageTag", null)
        )
    override fun setPreferredVoiceId(languageTag: String, voiceId: String) =
        prefs.edit().putString("preferred_voice_$languageTag", voiceId).apply()

    /** سرعة النطق لكل لغة (languageTag -> speechRate) */
    override fun getSpeechRate(languageTag: String): Float =
        prefs.getFloat("speech_rate_$languageTag", 1.0f)
    override fun setSpeechRate(languageTag: String, rate: Float) =
        prefs.edit()
            .putFloat("speech_rate_$languageTag", rate.coerceIn(0f, 2f))
            .apply()

    /** نبرة الصوت لكل لغة (languageTag -> pitch) */
    override fun getPitch(languageTag: String): Float =
        prefs.getFloat("pitch_$languageTag", 1.0f)
    override fun setPitch(languageTag: String, pitch: Float) =
        prefs.edit()
            .putFloat("pitch_$languageTag", pitch.coerceIn(0f, 2f))
            .apply()

    /** مستوى الصوت لكل لغة (languageTag -> volume) */
    override fun getVolume(languageTag: String): Float =
        prefs.getFloat("volume_$languageTag", 1.0f)
    override fun setVolume(languageTag: String, volume: Float) =
        prefs.edit()
            .putFloat("volume_$languageTag", volume.coerceIn(0f, 1f))
            .apply()

    // ============ نبرة الإعلانات المستقلة (بند 2.2) ============
    // شريط نبرة لكل فئة إعلان (متصل/بطارية/رسائل): إن لم يُضبط للمستخدم
    // قيمتها الخاصة تُرجع الدالة نبرةَ نطق اللغة بديلاً — فيظل إعلانُ فئةٍ
    // بلا شريطٍ مخصّصٍ يتبع نبرةَ اللغة المختارة (السلوك القديم نفسه).
    private fun getCategoryPitchOrFallback(
        key: String, languageTag: String
    ): Float =
        if (prefs.contains(key)) {
            prefs.getFloat(key, 1.0f).coerceIn(0f, 2f)
        } else {
            getPitch(languageTag)
        }

    private fun setCategoryPitch(key: String, pitch: Float) {
        prefs.edit()
            .putFloat(key, pitch.coerceIn(0f, 2f))
            .apply()
    }

    fun getCallerAnnouncementPitchOrDefault(
        languageTag: String
    ): Float = getCategoryPitchOrFallback(
        "caller_announcement_pitch", languageTag
    )

    fun setCallerAnnouncementPitch(pitch: Float) =
        setCategoryPitch("caller_announcement_pitch", pitch)

    fun getBatteryAnnouncementPitchOrDefault(
        languageTag: String
    ): Float = getCategoryPitchOrFallback(
        "battery_announcement_pitch", languageTag
    )

    fun setBatteryAnnouncementPitch(pitch: Float) =
        setCategoryPitch("battery_announcement_pitch", pitch)

    fun getSmsReadingPitchOrDefault(
        languageTag: String
    ): Float = getCategoryPitchOrFallback(
        "sms_reading_pitch", languageTag
    )

    fun setSmsReadingPitch(pitch: Float) =
        setCategoryPitch("sms_reading_pitch", pitch)

    // ============ مسار الصوت للإعلانات (بند 1.4) ============
    // إجبار النطق على مسار الموسيقى (USAGE_MEDIA) حتى دون قارئ شاشة:
    // مسار USAGE_ASSISTANCE_ACCESSIBILITY يُكتم صوتُه أو يُخفى على بعض
    // أجهزة OEM حين لا يكون قارئ الشاشة متفاعلاً — يُقرأ في
    // AnnouncementSpeaker.speechAudioAttributes.
    fun isAnnouncementMediaStreamAlways(): Boolean =
        prefs.getBoolean("announcement_media_stream_always", false)

    fun setAnnouncementMediaStreamAlways(enabled: Boolean) =
        prefs.edit()
            .putBoolean("announcement_media_stream_always", enabled)
            .apply()

    // ============ خفض صوت الوسائط أثناء النطق (بند الصوتيات) ============
    // يُطلب التركيز بنوع MAY_DUCK افتراضياً (تخفض التطبيقات الأخرى
    // وسائطها مؤقتاً ثم تعود). عند التعطيل يُطلب GAIN_TRANSIENT فتتوقف
    // الوسائط مؤقتاً بلا أي خفضٍ لمستوى الصوت (شكوى «انخفاض صوت
    // الوسائط») — يُقرأ في AnnouncementSpeaker.requestAudioFocus.
    fun isDuckMediaDuringAnnouncements(): Boolean =
        prefs.getBoolean("duck_media_during_announcements", true)

    fun setDuckMediaDuringAnnouncements(enabled: Boolean) =
        prefs.edit()
            .putBoolean("duck_media_during_announcements", enabled)
            .apply()

    // ============ نطق الساعة في السيناريوهات الصوتية ============
    // مفاتيح إيقاف/تفعيل الإعلان التلقائي للوقت أثناء: المكالمة
    // الهاتفية، تشغيل الوسائط، ووضع الصامت — تُقرأ في
    // TimeAnnouncementManager. أثناء المكالمة الافتراضي false محافظةً
    // على السلوك الحالي (المكالمة تحجز التركيز فيُسقط الإعلان)،
    // وبقية السيناريوهات true (يُنطق كما اليوم).
    fun isAnnounceTimeDuringCalls(): Boolean =
        prefs.getBoolean("announce_time_during_calls", false)

    fun setAnnounceTimeDuringCalls(enabled: Boolean) =
        prefs.edit().putBoolean("announce_time_during_calls", enabled)
            .apply()

    fun isAnnounceTimeDuringMedia(): Boolean =
        prefs.getBoolean("announce_time_during_media", true)

    fun setAnnounceTimeDuringMedia(enabled: Boolean) =
        prefs.edit().putBoolean("announce_time_during_media", enabled)
            .apply()

    fun isAnnounceTimeDuringSilent(): Boolean =
        prefs.getBoolean("announce_time_during_silent", true)

    fun setAnnounceTimeDuringSilent(enabled: Boolean) =
        prefs.edit().putBoolean("announce_time_during_silent", enabled)
            .apply()

    /** يُرجع مفتاح التفضيل الفعلي للغة: بالوسم الكامل (ar-EG) إن وُجد، ثم
     *  بكود اللغة وحده (ar) إن وُجد — تراجعٌ تدريجي لتعميم تفضيل الأهل على
     *  كل لهجاتها. null إن لم يُحفظ أي تفضيل لها. */
    private fun languagePrefKey(prefix: String, languageTag: String): String? {
        val tagKey = "$prefix$languageTag"
        if (prefs.contains(tagKey)) return tagKey
        val lang = java.util.Locale.forLanguageTag(languageTag).language
        if (lang.isNotBlank() && lang != languageTag) {
            val baseKey = "$prefix$lang"
            if (prefs.contains(baseKey)) return baseKey
        }
        return null
    }

    /** سرعة نطق مخزّنة صراحةً للغة (وسم كامل أو كود اللغة) — null إن لم
     *  يُعيّن المستخدم قيمةً لها. «1.0x الصريح» يُحترم ولا يُخلط مع الغياب. */
    override fun getSpeechRateOrNull(languageTag: String): Float? =
        languagePrefKey("speech_rate_", languageTag)
            ?.let { prefs.getFloat(it, 1.0f) }

    /** نبرة مخزّنة صراحةً للغة (وسم كامل أو كود اللغة) — null إن لم تُعيّن. */
    override fun getPitchOrNull(languageTag: String): Float? =
        languagePrefKey("pitch_", languageTag)?.let { prefs.getFloat(it, 1.0f) }

    /** مستوى صوت مخزّن صراحةً للغة (وسم كامل أو كود اللغة) —
     *  null إن لم يُعيّن. */
    override fun getVolumeOrNull(languageTag: String): Float? =
        languagePrefKey("volume_", languageTag)
            ?.let { prefs.getFloat(it, 1.0f) }

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
            runCatching {
                appContext.deleteSharedPreferences("nateq_secure_caller_names")
            }
        }
        // مسح ملفات الحالة والقاموس والـ fallback القديم.
        runCatching { appContext.deleteSharedPreferences(OLD_PREFS) }
        runCatching { appContext.deleteSharedPreferences(FALLBACK_PREFS) }
        runCatching {
            appContext.deleteSharedPreferences("nateq_pronunciation_dict")
        }
        runCatching {
            appContext.deleteSharedPreferences("nateq_battery_state")
        }
    }

    // ============ المفتاح الرئيسي ووضع توفير الطاقة ============

    /**
     * المفتاح الرئيسي لإعلانات التطبيق: تعطيله يوقف كل الإعلانات التلقائية
     * دفعة واحدة (الوقت، البطارية، المتصل، الرسائل، الإشعارات) — وتبقى
     * تفعيلاتها الفرعية محفوظة للعودة إليها.
     */
    override fun isAllAnnouncementsEnabled(): Boolean =
        prefs.getBoolean("all_announcements_enabled", true)
    override fun setAllAnnouncementsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("all_announcements_enabled", enabled).apply()

    // ============ نطق الإيموجي ورموز المشاعر ============

    /**
     * هل تُنطق أسماء الإيموجي ورموز المشاعر في النصوص (بدل حذفها)؟
     * مفعّل افتراضياً: يُنطق «وجه مبتسم» بدل صمت الإيموجي.
     */
    override fun isEmojiPronunciationEnabled(): Boolean =
        prefs.getBoolean("emoji_pronunciation_enabled", true)
    override fun setEmojiPronunciationEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("emoji_pronunciation_enabled", enabled).apply()

    // ============ خصوصية قفل الشاشة ============

    /**
     * عندما يكون مقفلاً (الشاشة قفلت)، تُحجب تفاصيل الرسائل والإشعارات
     * (المحتوى/العنوان/اسم المتصل) عن النطق فلا يُسمع كود تحقق (OTP) أو
     * رسالة خاصة بصوتٍ عالٍ في مكان عام — ويُكتفى بالمصدر أو المضمون العام.
     */
    override fun isLockScreenPrivacyEnabled(): Boolean =
        prefs.getBoolean("lock_screen_privacy_enabled", true)
    override fun setLockScreenPrivacyEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("lock_screen_privacy_enabled", enabled).apply()

    /** هل شاشة الجهاز مقفلة فعلاً أو مطفأة (حالة خصوصية)؟
     *  يعود false عند عدم وجود قفل. */
    @Suppress("DEPRECATION")
    override fun isDeviceScreenLocked(): Boolean {
        val km = appContext.getSystemService(
            android.app.KeyguardManager::class.java
        )
        val power = appContext.getSystemService(
            android.os.PowerManager::class.java
        )
        // الشاشة مطفأة: حالة خصوصية أعلى حتى مع قفل غير آمن (Swipe) — نمنع
        // نطق الحساسيات (OTP) في الجيب أو على الطاولة.
        if (power != null && !power.isInteractive) return true
        // isKeyguardLocked يشمل الأقفال غير الآمنة (Swipe) التي تُهملها
        // isDeviceLocked (فهي تُعرَّف في النظام بأنها secure && locked).
        return try {
            km?.isKeyguardLocked ?: false
        } catch (t: Throwable) {
            // أنظمة تُقيّد قراءة حالة القفل دون الإذن الأمني:
            // نعود للقفل الآمن فقط.
            km?.isDeviceLocked ?: false
        }
    }

    /** وضع توفير الطاقة: يُخفَّف إعلان الوقت عند انخفاض البطارية عن العتبة. */
    override fun isPowerSaverModeEnabled(): Boolean =
        prefs.getBoolean("power_saver_mode_enabled", false)
    override fun setPowerSaverModeEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("power_saver_mode_enabled", enabled).apply()

    /** العتبة (نسبة مئوية) التي يعمل الاحدها وضع توفير الطاقة لإعلان الوقت. */
    override fun getPowerSaverBatteryThreshold(): Int =
        prefs.getInt("power_saver_battery_threshold", 20)
    override fun setPowerSaverBatteryThreshold(threshold: Int) =
        prefs.edit()
            .putInt(
                "power_saver_battery_threshold",
                threshold.coerceIn(0, 100)
            )
            .apply()

    /** إعلان اكتمال الشحن (وصول 100% والمتصالة). */
    override fun isChargingCompleteAnnouncementEnabled(): Boolean =
        prefs.getBoolean("charging_complete_announcement_enabled", true)
    override fun setChargingCompleteAnnouncementEnabled(enabled: Boolean) =
        prefs.edit()
            .putBoolean(
                "charging_complete_announcement_enabled",
                enabled
            )
            .apply()

    /** إعلان فصل الشاحن. */
    override fun isChargingDisconnectAnnouncementEnabled(): Boolean =
        prefs.getBoolean("charging_disconnect_announcement_enabled", true)
    override fun setChargingDisconnectAnnouncementEnabled(enabled: Boolean) =
        prefs.edit()
            .putBoolean(
                "charging_disconnect_announcement_enabled",
                enabled
            )
            .apply()

    // ============ أسماء المتصلين المخصصة ============

    private fun getCallerPrefs(): SharedPreferences? {
        val cached = callerSecurePrefs
        if (cached != null) return cached
        synchronized(this) {
            callerSecurePrefs?.let { return it }
            return openSecureCallerPrefs()
        }
    }

    /**
     * يفتح التخزين المشفر لأسماء المتصلين (nateq_secure_caller_names).
     *
     * عند أي استثناء من Keystore:
     * - لا يُحذف الملف إطلاقاً. يبقى مشفراً على القرص، فقد يكون الفشل عابراً
     *   (TemporaryNotAvailableException مباشرة بعد الإقلاع أو قفل الشاشة) أو
     *   دائماً (فقدان المفتاح) — وفي الحالتين الحذف يمحو أسماء المستخدم
     *   بلا رجعة بينما إبقاء الملف لا يسبّب أي تسريب (البيانات تبقى مشفرة).
     * - نُجرب فتحاً واحداً ثانياً بعد مهلة قصيرة لاجتياز السباقات العابرة.
     * - callerSecurePrefs يبقى null: فيُعاد فتح الملف تلقائياً عند كل نداء
     *   لاحق، وحتى بعد إعادة تشغيل العملية، فتنجح العملية متى زال العطل.
     * - الكتابة أثناء التعطل تُحفظ في الذاكرة لهذه الجلسة فقط (بلا أي
     *   تفضيلات نصية مكشوفة — منع تسريب PII).
     */
    private fun openSecureCallerPrefs(): SharedPreferences? {
        repeat(2) { attempt ->
            try {
                val masterKey = androidx.security.crypto.MasterKey
                    .Builder(appContext)
                    .setKeyScheme(
                        androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM
                    )
                    .build()
                return androidx.security.crypto.EncryptedSharedPreferences
                    .create(
                        appContext, "nateq_secure_caller_names", masterKey,
                        androidx.security.crypto.EncryptedSharedPreferences
                            .PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences
                            .PrefValueEncryptionScheme.AES256_GCM
                    ).also { callerSecurePrefs = it }
            } catch (e: Throwable) {
                if (attempt == 0) {
                    // عطل عابر محتمل: محاولة ثانية فورية. كانت تُحقن هنا
                    // مهلة Thread.sleep(150L): تُجمّد الخيط المستدعي (وقد
                    // يكون الخيط الرئيسي عند فتح شاشة أسماء المتصلين فتهبط
                    // إطاراتُ الواجهة) والخروجُ بالذاكرة يحدث أسرع وأبسط —
                    // الفشلُ العابر يُجتَاز بإعادة الفتح نفسها لا بالنوم.
                } else {
                    Log.w(
                        TAG,
                        "تعذر فتح التخزين المشفر لأسماء المتصلين — " +
                            "تُحفظ الأسماء في الذاكرة لهذه الجلسة فقط " +
                            "ويُبقى الملف بلا حذف",
                        e
                    )
                    return null
                }
            }
        }
        return null
    }

    /** أسماء متصلين مخصصة: خريطة رقم هاتف (بدون ترميز البلد)
     *  -> الاسم المعلَن. */
    override fun getCustomCallerNames(): Map<String, String> {
        val secure = getCallerPrefs()
        if (secure == null) {
            // عند تعطل التخزين المشفر نعيد ما في الذاكرة كما هو — كان
            // يُسلسل ثم يُحلل سطرياً (بتبويب/سطر) فيفسد أي اسمٍ يحوي
            // تبويباً أو سطراً جديداً؛ الخريطة المرجعية تُنسخ مباشرة.
            return memoryCallerNames.toMap()
        }
        val raw = secure.getString("caller_names", null)
            ?: return emptyMap()
        // الصيغة الحالية: خريطة JSON عبر مظلة NateqJson (البند 3). الصيغة
        // السطرية القديمة «key\tvalue» تُقرأ احتياطاً للتوافقية مع بيانات
        // الأجهزة المخزّنة قبل هذا الترحيل.
        NateqJson.parseStringMap(raw)?.let { return it }
        return raw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx > 0 && idx < line.length - 1) {
                    line.substring(0, idx) to line.substring(idx + 1)
                } else null
            }.toMap()
    }

    override fun setCustomCallerNames(names: Map<String, String>) {
        // تعقيم أثناء الاستيراد (من النسخ الاحتياطي أو واجهة الإدخال): نتصفّى
        // المفاتيح لتكون أرقاماً فقط (مع رمز + اختياري)، وحد أقصى لطول الرقم
        // والاسم، وحد أقصى لعدد الإدخالات، حتى لا يدخل ملف JSON خبيث/فاسد
        // من SAF كمية مهولة بلا حدود إلى المخزن المشفر.
        val cleaned = names
            // النمط يبقى ثابتاً بين العناصر — كان يُنشأ Regex داخل
            // المرشّح لكل مفتاح في كل استيراد (هدر إنشاء/تجميع متكرر).
            .filterKeys { it.matches(CALLER_PHONE_REGEX) }
            .filterValues { it.isNotBlank() && it.trim().length <= 100 }
            .entries
            .take(MAX_CALLER_ENTRIES)
            .associate { (k, v) -> k.trim() to v.trim() }
        val secure = getCallerPrefs()
        if (secure != null) {
            secure.edit()
                .putString("caller_names", NateqJson.toJson(cleaned))
                .apply()
        } else {
            // عند فشل التخزين المشفّر (Keystore معطوب) لا نكتب أسماء المتصلين
            // (PII) في تفضيلات نصية عادية أبداً — تُحفظ في الذاكرة لهذه الجلسة.
            memoryCallerNames.clear()
            cleaned.forEach { (k, v) -> memoryCallerNames[k] = v }
        }
    }

    // ============ قوالب الإعلانات ============

    /** قالب إعلان المتصل: يُستبدل {name} باسم المتصل. فارغ = الافتراضي. */
    override fun getCallerAnnouncementTemplate(): String =
        prefs.getString("caller_announcement_template", "") ?: ""
    override fun setCallerAnnouncementTemplate(template: String?) =
        prefs.edit().putString("caller_announcement_template", template).apply()

    /** قالب قراءة الرسائل: يُستبدل {name} و{message} باسم المرسل
     *  ومحتوى الرسالة. */
    override fun getSmsAnnouncementTemplate(): String =
        prefs.getString("sms_announcement_template", "") ?: ""
    override fun setSmsAnnouncementTemplate(template: String?) =
        prefs.edit().putString("sms_announcement_template", template).apply()

    // ============ إعدادات إعلان الوقت ============

    /** الصوت المفضّل لكل فئة (category -> voiceId) */
    override fun getPreferredVoiceIdForCategory(category: String): String? =
        normalizeVoiceId(
            prefs.getString("preferred_voice_$category", null)
        )
    override fun setPreferredVoiceIdForCategory(
        category: String,
        voiceId: String
    ) =
        prefs.edit().putString("preferred_voice_$category", voiceId).apply()

    /** لغة الصوت المختارة لكل فئة (category -> رمز ISO) — تقيّد قائمة
     *  الأصوات وتُحسم لغة النطق الفعلية لها. */
    fun getLanguageForCategory(category: String): String? =
        prefs.getString("language_for_$category", null)
    fun setLanguageForCategory(category: String, language: String?) =
        prefs.edit().putString("language_for_$category", language).apply()

    /** محرك النطق الخاص بفئةٍ معيّنة (متصل/بطارية/وقت…)، null = تلقائي
     *  (يتبع محرك اللغة ثم المحرك المختار العام). يُخزَّن تحت
     *  `engine_for_<category>` ليستقل كل إعلانٍ بمحركه. */
    override fun getEngineForCategory(category: String): String? =
        prefs.getString("engine_for_$category", null)
    override fun setEngineForCategory(category: String, engine: String?) =
        prefs.edit().putString("engine_for_$category", engine).apply()

    /** سرعة النطق لكل فئة */
    override fun getSpeechRateForCategory(category: String): Float =
        prefs.getFloat("speech_rate_$category", 1.0f)
    override fun setSpeechRateForCategory(category: String, rate: Float) =
        prefs.edit()
            .putFloat("speech_rate_$category", rate.coerceIn(0f, 2f))
            .apply()

    /** نبرة الصوت لكل فئة */
    override fun getPitchForCategory(category: String): Float =
        prefs.getFloat("pitch_$category", 1.0f)
    override fun setPitchForCategory(category: String, pitch: Float) =
        prefs.edit().putFloat("pitch_$category", pitch.coerceIn(0f, 2f)).apply()

    /** مستوى الصوت لكل فئة */
    override fun getVolumeForCategory(category: String): Float =
        prefs.getFloat("volume_$category", 1.0f)
    override fun setVolumeForCategory(category: String, volume: Float) =
        prefs.edit()
            .putFloat("volume_$category", volume.coerceIn(0f, 1f))
            .apply()

    /** معايرة RMS الدائمة لكل محرك (بند الأوامر د.3.3): كسب التطبيع
     *  المستقر تحت `rms_calibration_<engine>` — تُصدَّر/تُستورَد تلقائياً
     *  (لا تبدأ بشرطة سفلية)، وتُمسح مع «استعادة الافتراضيات». القارئ
     *  يرفض غير المحدود؛ التحجيم لحدود المحرك شأنُ المزوّد المالك لها. */
    override fun getEngineRmsCalibration(enginePackage: String): Float? {
        val stored = prefs.getFloat(
            rmsCalibrationKey(enginePackage), Float.NaN
        )
        return stored.takeIf { it.isFinite() }
    }
    override fun saveEngineRmsCalibration(
        enginePackage: String,
        gain: Float
    ) {
        if (!gain.isFinite()) return
        prefs.edit()
            .putFloat(rmsCalibrationKey(enginePackage), gain)
            .apply()
    }
    override fun clearEngineRmsCalibration(enginePackage: String) {
        prefs.edit().remove(rmsCalibrationKey(enginePackage)).apply()
    }

    /** مفتاح المعايرة — فارغٌ/بلانك يُثبَّت على مفتاحٍ ثابتٍ لا يصطاد
     *  غيرَه حتى لا تُكتب معايرةُ محركٍ مجهولٍ فوق أخرى. */
    private fun rmsCalibrationKey(enginePackage: String): String {
        val engine = enginePackage.trim().ifEmpty { "unknown" }
        return "rms_calibration_$engine"
    }

    /** كسب معادِل الصوت المخصص لمحركٍ معيّن (بالديسيبل). */
    override fun getEngineEqualizerGains(
        enginePackage: String
    ): FloatArray? {
        val raw = prefs.getString(engineEqKey(enginePackage), null)
            ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (parts.isEmpty()) return null
        return parts.map { it.coerceIn(-12f, 12f) }.toFloatArray()
    }

    /** حفظ كسب معادِل الصوت لمحرك. */
    override fun saveEngineEqualizerGains(
        enginePackage: String,
        gains: FloatArray
    ) {
        val formatted = gains.joinToString(",") {
            it.coerceIn(-12f, 12f).toString()
        }
        prefs.edit()
            .putString(engineEqKey(enginePackage), formatted)
            .apply()
    }

    /** مسح كسب معادِل الصوت لمحرك. */
    override fun clearEngineEqualizerGains(enginePackage: String) {
        prefs.edit().remove(engineEqKey(enginePackage)).apply()
    }

    private fun engineEqKey(enginePackage: String): String {
        val engine = enginePackage.trim().ifEmpty { "unknown" }
        return "engine_eq_$engine"
    }

    /** آخر فشل تراجع مُسجَّل (للشاشة التشخيصية — بند د.6.2)؛ null إن لم
     *  يُسجَّل أي فشل بعد. المفتاح يُكتب من SystemVoiceProvider عند حدوث
     *  تراجع فعلي (يحمل المحرك/الصوت/الوقت) فتعرضه الشاشة التشخيصية
     *  للمستخدم لإرساله في تقارير الأعطال. */
    fun getLastFallbackFailureInfo(): String? =
        prefs.getString("last_fallback_failure_info", null)

    /** يُخزّن نص آخر فشل تراجع للعرض لاحقاً في الشاشة التشخيصية. */
    fun setLastFallbackFailureInfo(info: String) =
        prefs.edit().putString(
            "last_fallback_failure_info", info.take(512)
        ).apply()

    /** تفعيل/إيقاف إعلان الوقت */
    override fun isTimeAnnouncementEnabled(): Boolean =
        prefs.getBoolean("time_announcement_enabled", true)
    override fun setTimeAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_announcement_enabled", enabled).apply()

    /** فاصل إعلان الوقت (بالدقائق): 5–60 بخطوة 5 */
    override fun getTimeAnnouncementInterval(): Int =
        prefs.getInt("time_announcement_interval", 30)
    override fun setTimeAnnouncementInterval(interval: Int) =
        prefs.edit()
            .putInt("time_announcement_interval", interval.coerceIn(5, 60))
            .apply()

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
        val start = prefs.getInt(
            "time_announcement_quiet_start",
            23
        ).coerceIn(0, 23)
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
    override fun getQuietStartForDay(day: Int): Int {
        migrateQuietScheduleIfNeeded()
        return prefs.getInt(quietDayKey(day, true), 23)
    }

    /** نهاية فترة الهدوء ليوم محدد (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت). */
    override fun getQuietEndForDay(day: Int): Int {
        migrateQuietScheduleIfNeeded()
        return prefs.getInt(quietDayKey(day, false), 7)
    }

    override fun setQuietStartForDay(day: Int, hour: Int) {
        prefs.edit()
            .putInt(quietDayKey(day, true), hour.coerceIn(0, 23))
            .apply()
    }

    override fun setQuietEndForDay(day: Int, hour: Int) {
        prefs.edit()
            .putInt(quietDayKey(day, false), hour.coerceIn(0, 23))
            .apply()
    }

    /** هل ساعات الهدوء مفعّلة ليوم محدد؟ الافتراضي مفعّل لكل الأيام
     *  حفاظاً على السلوك السابق قبل إدخال مفاتيح اليوم. */
    override fun isDayQuietEnabled(day: Int): Boolean {
        val d = day.coerceIn(1, 7)
        return prefs.getBoolean("time_quiet_day${d}_enabled", false)
    }

    override fun setDayQuietEnabled(day: Int, enabled: Boolean) {
        val d = day.coerceIn(1, 7)
        prefs.edit()
            .putBoolean("time_quiet_day${d}_enabled", enabled)
            .apply()
    }

    /** صيغة إعلان الوقت: "arabic_natural" أو "digital" */
    override fun getTimeAnnouncementFormat(): String =
        prefs.getString("time_announcement_format", "arabic_natural")
            ?: "arabic_natural"
    override fun setTimeAnnouncementFormat(format: String) =
        prefs.edit().putString("time_announcement_format", format).apply()

    /** عرض الوقت بنظام 24 ساعة في الصيغة الرقمية (بدل 12 ساعة الافتراضية) */
    override fun isTime24Hour(): Boolean =
        prefs.getBoolean("time_display_24h", false)
    override fun setTime24Hour(enabled: Boolean) =
        prefs.edit().putBoolean("time_display_24h", enabled).apply()

    // ============ قراءة النصوص: الترقيم والتهجئة الذكية ============

    /** مستوى نطق علامات الترقيم والرموز (0 لا شيء، 1 البعض، 2 الكل).
     *  الافتراضي «البعض» حفاظاً على السلوك القائم للنطق بالرموز الشائعة. */
    override fun getPunctuationLevel(): Int =
        prefs.getInt("punctuation_level", PunctuationLevels.SOME)
    override fun setPunctuationLevel(level: Int) =
        prefs.edit()
            .putInt(
                "punctuation_level",
                level.coerceIn(PunctuationLevels.MIN, PunctuationLevels.MAX)
            )
            .apply()


    /** الحفاظ على تشكيل النصوص العربية المُرسلة للمحرك (بند 1.7):
     *  تُعاد الكلمات الأصلية غير المتحوّلة بتشكيلها الأصلي بدل إرسالها
     *  مجرّدةً — تفيد المحركات العربية التي تنطق التشكيل بوضوح. معطّل
     *  افتراضياً حفاظاً على السلوك القائم (المحركات التي لا تفهم التشكيل
     *  قد تُعلِق على الحركات إن سُلّمت). */
    override fun isTashkeelPreserved(): Boolean =
        prefs.getBoolean("tashkeel_preserved", false)
    override fun setTashkeelPreserved(enabled: Boolean) =
        prefs.edit().putBoolean("tashkeel_preserved", enabled).apply()

    /** اتباع سرعة قارئ الشاشة (النسبة المئوية 100 = طبيعي) في نطق نصه:
     *  عند التفعيل (افتراضياً) تُهمل أشرطة سرعة LORD فتكون سرعة النطق
     *  سرعة القارئ الفعلية بلا مضاعفة. */
    override fun isFollowReaderRateEnabled(): Boolean =
        prefs.getBoolean("follow_reader_rate_enabled", true)
    override fun setFollowReaderRateEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("follow_reader_rate_enabled", enabled).apply()

    // ============ الإسكات الفوري: الهز والتقارب ============

    /** هز الجهاز أثناء النطق يوقفه فوراً. معطّل افتراضياً. */
    override fun isShakeToStopEnabled(): Boolean =
        prefs.getBoolean("shake_to_stop_enabled", false)
    override fun setShakeToStopEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("shake_to_stop_enabled", enabled).apply()

    /** تغطية الجهاز (يد/جيب) أثناء النطق توقفه فوراً. معطّل افتراضياً. */
    override fun isProximitySilenceEnabled(): Boolean =
        prefs.getBoolean("proximity_silence_enabled", false)
    override fun setProximitySilenceEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("proximity_silence_enabled", enabled).apply()

    // ============ إعدادات عامة ============

    /** السرعة العامة الافتراضية */
    override fun getDefaultSpeechRate(): Float =
        prefs.getFloat("default_speech_rate", 1.0f)
    override fun setDefaultSpeechRate(rate: Float) =
        prefs.edit()
            .putFloat("default_speech_rate", rate.coerceIn(0f, 2f))
            .apply()

    /** النبرة العامة الافتراضية */
    override fun getDefaultPitch(): Float =
        prefs.getFloat("default_pitch", 1.0f)
    override fun setDefaultPitch(pitch: Float) =
        prefs.edit().putFloat("default_pitch", pitch.coerceIn(0f, 2f)).apply()

    /** مستوى الصوت العام الافتراضي */
    override fun getDefaultVolume(): Float =
        prefs.getFloat("default_volume", 1.0f)
    override fun setDefaultVolume(volume: Float) =
        prefs.edit().putFloat("default_volume", volume.coerceIn(0f, 1f)).apply()

    /** مستوى اتساع الصوت (0 إيقاف، 1 خفيف، 2 متوسط) */
    override fun getAudioExpansionLevel(): Int =
        prefs.getInt("audio_expansion_level", AudioExpansionLevels.DEFAULT)

    override fun setAudioExpansionLevel(level: Int) {
        val safe = level.coerceIn(
            AudioExpansionLevels.MIN,
            AudioExpansionLevels.MAX
        )
        prefs.edit().putInt("audio_expansion_level", safe).apply()
    }


    // ============ إعدادات إعلان مستوى البطارية ============

    /** العناصر المختارة في قسم «صحة الجهاز» للنطق عند الطلب. */
    override fun getDeviceHealthItems(): Set<String> =
        prefs.getStringSet("device_health_items", null)
            ?.filter { it in validDeviceHealthItems() }
            ?.toSet() ?: DEFAULT_DEVICE_HEALTH_ITEMS

    override fun setDeviceHealthItems(items: Set<String>) =
        prefs.edit().putStringSet(
            "device_health_items",
            items.filter { it in validDeviceHealthItems() }.toMutableSet()
        ).apply()

    private fun validDeviceHealthItems(): Set<String> = setOf(
        DEVICE_HEALTH_BATTERY,
        DEVICE_HEALTH_CHARGING,
        DEVICE_HEALTH_STORAGE,
        DEVICE_HEALTH_MEMORY
    )

    /** تفعيل/إيقاف إعلان مستوى البطارية */
    override fun isBatteryAnnouncementEnabled(): Boolean =
        prefs.getBoolean("battery_announcement_enabled", false)
    override fun setBatteryAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("battery_announcement_enabled", enabled).apply()

    /**
     * مستويات البطارية المفعّلة (كنسب مئوية مضاعفات 5) التي يُعلن عنها.
     * مثلاً {"20","15"} تُنطق عند وصول البطارية إلى 20% ثم 15%.
     */
    override fun getBatteryAnnouncementLevels(): Set<String> =
        prefs.getStringSet(
            "battery_announcement_levels",
            mutableSetOf("20", "15")
        )
            ?.toSet() ?: setOf("20", "15")
    override fun setBatteryAnnouncementLevels(levels: Set<String>) =
        prefs.edit()
            .putStringSet(
                "battery_announcement_levels",
                levels.toMutableSet()
            )
            .apply()

    /** صوت إعلان البطارية (معرّف صوت موحّد) */
    override fun getBatteryAnnouncementVoiceId(): String? =
        normalizeVoiceId(
            prefs.getString("battery_announcement_voice", null)
        )
    override fun setBatteryAnnouncementVoiceId(voiceId: String?) =
        prefs.edit().putString("battery_announcement_voice", voiceId).apply()

    /** لغة الصوت المختارة لإعلان البطارية (رمز ISO) — تقيّد قائمة
     *  الأصوات وتُحسم لغة النطق الفعلية للإعلان إن لم تُحدَّد من
     *  الصوت نفسه. */
    fun getBatteryAnnouncementLanguage(): String? =
        prefs.getString("battery_announcement_language", null)
    fun setBatteryAnnouncementLanguage(language: String?) =
        prefs.edit()
            .putString("battery_announcement_language", language)
            .apply()

    /** سرعة نطق إعلان البطارية */
    override fun getBatteryAnnouncementRate(): Float =
        prefs.getFloat("battery_announcement_rate", 1.0f)
    override fun setBatteryAnnouncementRate(rate: Float) =
        prefs.edit()
            .putFloat("battery_announcement_rate", rate.coerceIn(0f, 2f))
            .apply()

    /** مستوى صوت إعلان البطارية */
    override fun getBatteryAnnouncementVolume(): Float =
        prefs.getFloat("battery_announcement_volume", 1.0f)
    override fun setBatteryAnnouncementVolume(volume: Float) =
        prefs.edit()
            .putFloat("battery_announcement_volume", volume.coerceIn(0f, 1f))
            .apply()

    // ============ مؤثرات الصوت (رنة الساعة + نغمات البطارية) ============

    /** نغمات الرنة المتاحة لرنة رأس الساعة. */
    private fun validTimeChimeSounds(): Set<String> = setOf(
        "classic_bell", "digital_chime", "soft_ding"
    )

    /** تفعيل/إيقاف رنة رأس الساعة (تسبق نطق الوقت عند الدقيقة صفر). */
    override fun isTimeChimeEnabled(): Boolean =
        prefs.getBoolean("time_chime_enabled", true)
    override fun setTimeChimeEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_chime_enabled", enabled).apply()

    /** تفعيل رنة رأس الساعة (:00). */
    override fun isTimeChimeAt0Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_0", true)
    override fun setTimeChimeAt0Enabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_chime_at_0", enabled).apply()

    /** تفعيل رنة الربع (:15). */
    override fun isTimeChimeAt15Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_15", false)
    override fun setTimeChimeAt15Enabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_chime_at_15", enabled).apply()

    /** تفعيل رنة النصف (:30). */
    override fun isTimeChimeAt30Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_30", false)
    override fun setTimeChimeAt30Enabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_chime_at_30", enabled).apply()

    /** تفعيل رنة الـ 45 دقيقة (:45). */
    override fun isTimeChimeAt45Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_45", false)
    override fun setTimeChimeAt45Enabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_chime_at_45", enabled).apply()

    /** اسم الرنة المختارة: classic_bell | digital_chime | soft_ding. */
    override fun getTimeChimeSound(): String {
        val value = prefs.getString("time_chime_sound", "classic_bell")
            ?: "classic_bell"
        return value.takeIf { it in validTimeChimeSounds() }
            ?: "classic_bell"
    }
    override fun setTimeChimeSound(sound: String) =
        prefs.edit()
            .putString(
                "time_chime_sound",
                sound.takeIf { it in validTimeChimeSounds() }
                    ?: "classic_bell"
            )
            .apply()

    /** مستوى صوت رنة الساعة 0.1..1.0. */
    override fun getTimeChimeVolume(): Float =
        prefs.getFloat("time_chime_volume", 0.5f)
            .coerceIn(0.1f, 1f)
    override fun setTimeChimeVolume(volume: Float) =
        prefs.edit().putFloat(
            "time_chime_volume", volume.coerceIn(0.1f, 1f)
        ).apply()

    /** مسار URI لملف رنة الساعة المخصص. */
    override fun getCustomChimeUri(): String =
        prefs.getString("custom_chime_uri", "").orEmpty()

    override fun setCustomChimeUri(uri: String) =
        prefs.edit().putString("custom_chime_uri", uri).apply()

    override fun isTimeAlarmMaxPrecisionEnabled(): Boolean =
        prefs.getBoolean("time_alarm_max_precision", false)
    override fun setTimeAlarmMaxPrecisionEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("time_alarm_max_precision", enabled).apply()

    /** مستوى صوت نغمة البطارية 0.1..1.0 (مستقل عن صوت النطق — بند 3-3). */
    override fun getBatteryCueVolume(): Float =
        prefs.getFloat("battery_cue_volume", 0.8f)
            .coerceIn(0.1f, 1f)
    override fun setBatteryCueVolume(volume: Float) =
        prefs.edit()
            .putFloat("battery_cue_volume", volume.coerceIn(0.1f, 1f))
            .apply()

    /**
     * وضع مؤثر البطارية: 0=نطق ومؤثر، 1=نطق فقط، 2=مؤثر فقط.
     */
    override fun getBatterySoundCueMode(): Int =
        prefs.getInt("battery_sound_cue_mode", 0).coerceIn(0, 2)
    override fun setBatterySoundCueMode(mode: Int) =
        prefs.edit()
            .putInt("battery_sound_cue_mode", mode.coerceIn(0, 2))
            .apply()

    // ============ إعدادات إعلان اسم المتصل ============

    /** تفعيل/إيقاف إعلان اسم المتصل */
    override fun isCallerAnnouncementEnabled(): Boolean =
        prefs.getBoolean("caller_announcement_enabled", false)
    override fun setCallerAnnouncementEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("caller_announcement_enabled", enabled).apply()

    /** عدد مرات تكرار اسم المتصل */
    override fun getCallerAnnouncementRepeat(): Int =
        prefs.getInt("caller_announcement_repeat", 1)
    override fun setCallerAnnouncementRepeat(repeat: Int) =
        prefs.edit()
            .putInt("caller_announcement_repeat", repeat.coerceIn(1, 5))
            .apply()

    /** الفاصل الزمني (بالثواني) بين كل مرة نطق لاسم المتصل — 1..10 ثوانٍ */
    override fun getCallerAnnouncementIntervalSeconds(): Int =
        prefs.getInt("caller_announcement_interval_seconds", 3)
    override fun setCallerAnnouncementIntervalSeconds(seconds: Int) =
        prefs.edit()
            .putInt(
                "caller_announcement_interval_seconds",
                seconds.coerceIn(1, 10)
            )
            .apply()

    /** صوت إعلان المتصل بلغة عربية (معرّف صوت موحّد) */
    override fun getCallerAnnouncementArabicVoiceId(): String? =
        normalizeVoiceId(
            prefs.getString("caller_announcement_voice_ar", null)
        )
    override fun setCallerAnnouncementArabicVoiceId(voiceId: String?) =
        prefs.edit().putString("caller_announcement_voice_ar", voiceId).apply()

    /** صوت إعلان المتصل بلغة إنجليزية (معرّف صوت موحّد) */
    override fun getCallerAnnouncementEnglishVoiceId(): String? =
        normalizeVoiceId(
            prefs.getString("caller_announcement_voice_en", null)
        )
    override fun setCallerAnnouncementEnglishVoiceId(voiceId: String?) =
        prefs.edit().putString("caller_announcement_voice_en", voiceId).apply()

    /** سرعة نطق إعلان المتصل */
    override fun getCallerAnnouncementRate(): Float =
        prefs.getFloat("caller_announcement_rate", 1.0f)
    override fun setCallerAnnouncementRate(rate: Float) =
        prefs.edit()
            .putFloat("caller_announcement_rate", rate.coerceIn(0f, 2f))
            .apply()

    /** مستوى صوت إعلان المتصل */
    override fun getCallerAnnouncementVolume(): Float =
        prefs.getFloat("caller_announcement_volume", 1.0f)
    override fun setCallerAnnouncementVolume(volume: Float) =
        prefs.edit()
            .putFloat("caller_announcement_volume", volume.coerceIn(0f, 1f))
            .apply()

    // ============ إعدادات قراءة الرسائل الواردة ============

    /**
     * وضع قراءة الرسائل الواردة:
     * - "off": معطّل (لا يُقرأ شيء)
     * - "full": مفعّل (يُقرأ اسم المرسل ومحتوى الرسالة)
     * - "source": قراءة مصدر الرسالة فقط (اسم المرسل دون المحتوى)
     */
    override fun getSmsReadingMode(): String =
        prefs.getString("sms_reading_mode", "off") ?: "off"
    override fun setSmsReadingMode(mode: String) =
        prefs.edit().putString("sms_reading_mode", mode).apply()

    /** صوت قراءة الرسائل (معرّف صوت موحّد) */
    override fun getSmsReadingVoiceId(): String? =
        normalizeVoiceId(
            prefs.getString("sms_reading_voice", null)
        )
    override fun setSmsReadingVoiceId(voiceId: String?) =
        prefs.edit().putString("sms_reading_voice", voiceId).apply()

    /** لغة الصوت المختارة لقراءة الرسائل (رمز ISO) — تقيّد قائمة
     *  الأصوات وتُحسم لغة نطق صياغة الإعلان. */
    fun getSmsReadingLanguage(): String? =
        prefs.getString("sms_reading_language", null)
    fun setSmsReadingLanguage(language: String?) =
        prefs.edit().putString("sms_reading_language", language).apply()

    /** سرعة نطق قراءة الرسائل */
    override fun getSmsReadingRate(): Float =
        prefs.getFloat("sms_reading_rate", 1.0f)
    override fun setSmsReadingRate(rate: Float) =
        prefs.edit().putFloat("sms_reading_rate", rate.coerceIn(0f, 2f)).apply()

    /** مستوى صوت قراءة الرسائل */
    override fun getSmsReadingVolume(): Float =
        prefs.getFloat("sms_reading_volume", 1.0f)
    override fun setSmsReadingVolume(volume: Float) =
        prefs.edit()
            .putFloat("sms_reading_volume", volume.coerceIn(0f, 1f))
            .apply()

    // ===== التحويل التلقائي بين اللغات =====

    /** تفعيل التحويل التلقائي بين اللغتين الأولى والثانية */
    override fun isAutoConvertEnabled(): Boolean =
        prefs.getBoolean("auto_convert_enabled", false)
    override fun setAutoConvertEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("auto_convert_enabled", enabled).apply()

    /** إظهار التوضيح الاختياري «بعض اللغات قد لا تظهر…»
     *  (نص فقط، غير افتراضي). */
    override fun isLanguageInstallHintEnabled(): Boolean =
        prefs.getBoolean("show_language_install_hint", false)
    override fun setLanguageInstallHintEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("show_language_install_hint", enabled).apply()

    /** هل اكتمل «معالج الإعداد الأولي» القابل للتخطي؟ يُعرض مرة واحدة عند
     *  أول تشغيل (اختيار لغة الواجهة ومحرك النطق) ثم يُعلَّم منجزاً
     *  عند التخطي أو الحفظ فلا يُزعج في التشغيلات التالية. */
    override fun isFirstRunSetupCompleted(): Boolean =
        prefs.getBoolean("first_run_setup_completed", false)
    override fun setFirstRunSetupCompleted(completed: Boolean) =
        prefs.edit().putBoolean("first_run_setup_completed", completed).apply()

    // ---- الخريطة الديناميكية للتحويل التلقائي (languageTag -> تفضيلات) ----
    // استبدلنا نظام سلوتات «اللغة 1/اللغة 2» الثابت (ar/en فقط) بتخزين عام
    // محفوظ كخريطة JSON في SharedPreferences عبر ConvertPreferencesCodec
    // (مظلة NateqJson)، ليُدعم عدد غير محدود من اللغات. قراءة
    // NateqTtsService.resolveConvertTarget
    // تتم مباشرةً من هذه الخريطة، ويُرحَّل أي إعداد قديم من السلوتات تلقائياً
    // عند أول وصول (ensureConvertSlotsMigrated) دون حذفها نفسها.

    /**
     * تفضيلات التحويل للغة معينة (محرك/صوت/سرعة/نبرة/صوت). تُطبَّع علامة اللغة
     * إلى كود ISO-2 قبل البحث. اللغة بلا أي إعداد → مدخل افتراضي (لا تحويل).
     */
    override fun getEnginePreferenceForLanguage(
        languageTag: String
    ): LanguageSpeechPrefs {
        ensureConvertSlotsMigrated()
        return readConvertPrefs()[
            ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        ] ?: LanguageSpeechPrefs()
    }

    /**
     * يعين تفضيل محرك/صوت/أشرطة للغة معينة في الخريطة الديناميكية.
     * مدخل بلا أي تعديلات (كل القيم الافتراضية) يُحذف من الخريطة (لا تحويل).
     */
    override fun setEnginePreferenceForLanguage(
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
        val entry = ConvertPreferencesCodec.entryForSave(
            engine,
            voiceName,
            rate,
            pitch,
            volume
        )
        if (entry.hasAdjustment) map[key] = entry else map.remove(key)
        writeConvertPrefs(map)
    }

    /** محرك النطق الصريح للغة معيّنة (سارٍ في النطق العام بلا ربط
     *  بحالة التحويل التلقائي)، null إن لم يُحدَّد محركٌ للغة. */
    override fun getEngineForLanguage(languageTag: String): String? =
        getEnginePreferenceForLanguage(languageTag).engine

    /** صوت المحرك الصريح (داخل محرك اللغة) للغة معيّنة، null إن لم يُحدَّد. */
    override fun getVoiceForLanguage(languageTag: String): String? =
        getEnginePreferenceForLanguage(languageTag).voiceName

    /** كل تفضيلات التحويل الحالية (لغة -> مدخل) للعرض في قائمة الإعدادات. */
    override fun allConvertLanguagePreferences():
        Map<String, LanguageSpeechPrefs> {
        ensureConvertSlotsMigrated()
        return readConvertPrefs()
    }

    private fun readConvertPrefs(): Map<String, LanguageSpeechPrefs> {
        val json = prefs.getString(KEY_CONVERT_PREFS_JSON, null)
        return if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            ConvertPreferencesCodec.fromJson(json)
        }
    }

    private fun writeConvertPrefs(map: Map<String, LanguageSpeechPrefs>) {
        prefs.edit()
            .putString(
                KEY_CONVERT_PREFS_JSON,
                ConvertPreferencesCodec.toJson(map)
            )
            .apply()
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
            LanguageCode.AR.tag
        )
        ConvertPreferencesCodec.mergeLegacySlot(
            map, getConvertLanguageTag2(),
            ConvertPreferencesCodec.entryForSave(
                getConvertEngine2(), normalizeVoiceId(getConvertVoice2()),
                getConvertRate2(), getConvertPitch2(), getConvertVolume2()
            ),
            LanguageCode.EN.tag
        )
        if (map != readConvertPrefs()) writeConvertPrefs(map)
        Log.w(
            TAG,
            "تم ترحيل سلوتات التحويل القديمة إلى الخريطة الديناميكية " +
                "(${map.size} عنصر)"
        )
    }

    // --- السلوتات القديمة (تُبقى للتوافقية العكسية؛
    // تُرحَّل تلقائياً أعلاه) ---

    /** محرك اللغة الأولى (حزمة محرك TTS) */
    override fun getConvertEngine1(): String? =
        prefs.getString("convert_lang1_engine", null)
    override fun setConvertEngine1(pkg: String?) =
        prefs.edit().putString("convert_lang1_engine", pkg).apply()

    /** علامة لغة (languageTag) للغة الأولى */
    override fun getConvertLanguageTag1(): String? =
        prefs.getString("convert_lang1_lang", null)
    override fun setConvertLanguageTag1(langTag: String?) =
        prefs.edit().putString("convert_lang1_lang", langTag).apply()

    /** معرف الصوت المختار للغة الأولى */
    override fun getConvertVoice1(): String? =
        prefs.getString("convert_lang1_voice", null)
    override fun setConvertVoice1(voiceId: String?) =
        prefs.edit().putString("convert_lang1_voice", voiceId).apply()

    /** مستوى صوت اللغة الأولى */
    override fun getConvertVolume1(): Float =
        prefs.getFloat("convert_lang1_volume", 1.0f)
    override fun setConvertVolume1(volume: Float) =
        prefs.edit()
            .putFloat("convert_lang1_volume", volume.coerceIn(0f, 1f))
            .apply()

    /** نبرة اللغة الأولى */
    override fun getConvertPitch1(): Float =
        prefs.getFloat("convert_lang1_pitch", 1.0f)
    override fun setConvertPitch1(pitch: Float) =
        prefs.edit()
            .putFloat("convert_lang1_pitch", pitch.coerceIn(0f, 2f))
            .apply()

    /** سرعة اللغة الأولى */
    override fun getConvertRate1(): Float =
        prefs.getFloat("convert_lang1_rate", 1.0f)
    override fun setConvertRate1(rate: Float) =
        prefs.edit()
            .putFloat("convert_lang1_rate", rate.coerceIn(0f, 2f))
            .apply()

    /** محرك اللغة الثانية (حزمة محرك TTS) */
    override fun getConvertEngine2(): String? =
        prefs.getString("convert_lang2_engine", null)
    override fun setConvertEngine2(pkg: String?) =
        prefs.edit().putString("convert_lang2_engine", pkg).apply()

    /** علامة لغة (languageTag) للغة الثانية */
    override fun getConvertLanguageTag2(): String? =
        prefs.getString("convert_lang2_lang", null)
    override fun setConvertLanguageTag2(langTag: String?) =
        prefs.edit().putString("convert_lang2_lang", langTag).apply()

    /** معرف الصوت المختار للغة الثانية */
    override fun getConvertVoice2(): String? =
        prefs.getString("convert_lang2_voice", null)
    override fun setConvertVoice2(voiceId: String?) =
        prefs.edit().putString("convert_lang2_voice", voiceId).apply()

    /** مستوى صوت اللغة الثانية */
    override fun getConvertVolume2(): Float =
        prefs.getFloat("convert_lang2_volume", 1.0f)
    override fun setConvertVolume2(volume: Float) =
        prefs.edit()
            .putFloat("convert_lang2_volume", volume.coerceIn(0f, 1f))
            .apply()

    /** نبرة اللغة الثانية */
    override fun getConvertPitch2(): Float =
        prefs.getFloat("convert_lang2_pitch", 1.0f)
    override fun setConvertPitch2(pitch: Float) =
        prefs.edit()
            .putFloat("convert_lang2_pitch", pitch.coerceIn(0f, 2f))
            .apply()

    /** سرعة اللغة الثانية */
    override fun getConvertRate2(): Float =
        prefs.getFloat("convert_lang2_rate", 1.0f)
    override fun setConvertRate2(rate: Float) =
        prefs.edit()
            .putFloat("convert_lang2_rate", rate.coerceIn(0f, 2f))
            .apply()

    // ═══════════════════════════════════════════════════════
    // قراءة الإشعارات (واتساب، تلجرام، إلخ)
    // ═══════════════════════════════════════════════════════

    /** هل قراءة الإشعارات مفعّلة؟ */
    override fun isNotificationReadingEnabled(): Boolean =
        prefs.getBoolean("notification_reading_enabled", false)

    override fun setNotificationReadingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("notification_reading_enabled", enabled).apply()

    // ============ اختيار تطبيقات قراءة الإشعارات ============

    /** الحزمة "كل التطبيقات" تعني قراءة كل الإشعارات، وإلا حزم مختارة.
     *  عند غياب أي اختيار صريح تُستخدم القائمة الافتراضية المحدودة. */
    override fun getNotificationAppsSelection(): Set<String> =
        prefs.getStringSet("notification_apps_selection", null)
            ?.toSet() ?: DEFAULT_NOTIFICATION_APPS

    override fun setNotificationAppsSelection(pkgs: Set<String>) =
        prefs.edit()
            .putStringSet(
                "notification_apps_selection",
                pkgs.toMutableSet()
            )
            .apply()

    /** هل يُقرأ إشعار من هذه الحزمة حسب الاختيار الحالي؟ */
    override fun shouldReadNotificationApp(pkg: String): Boolean {
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
        key.startsWith("time_quiet_day") &&
            key.endsWith("_start") -> value.coerceIn(0, 23)
        key.startsWith("time_quiet_day") &&
            key.endsWith("_end") -> value.coerceIn(0, 23)
        key == "time_announcement_interval" ->
            if (value in 5..60 && value % 5 == 0) value else 30
        key == "number_reading_mode" -> value.coerceIn(1, 8)
        key == "punctuation_level" ->
            value.coerceIn(PunctuationLevels.MIN, PunctuationLevels.MAX)
        key == "audio_expansion_level" ->
            value.coerceIn(
                AudioExpansionLevels.MIN,
                AudioExpansionLevels.MAX
            )
        key == "caller_announcement_repeat" -> value.coerceIn(1, 5)
        key == "caller_announcement_interval_seconds" -> value.coerceIn(1, 10)
        key == "power_saver_battery_threshold" -> value.coerceIn(0, 100)
        key == "battery_sound_cue_mode" -> value.coerceIn(0, 2)
        else -> value
    }

    /** تعقّل قيمة نصية (رنة الساعة تقبل الأسماء الثلاثة فقط، واللغة
     *  الاحتياطية اللغات المدعومة فقط). */
    private fun sanitizeString(
        key: String,
        value: String
    ): String = when (key) {
        "time_chime_sound" -> value.takeIf {
            it in validTimeChimeSounds()
        } ?: "classic_bell"
        "secondary_language" -> value.takeIf {
            it in VALID_SECONDARY_LANGUAGES
        } ?: LanguageCode.EN.tag
        "number_reading_language" -> if (value == "en") "en" else "ar"
        else -> value
    }

    /** تعقّل قيمة عشرية حسب المفتاح (السرعة/النبرة تعبان، المستوى نسبة). */
    private fun sanitizeFloat(key: String, value: Float): Float = when {
        // مستويا صوت الرنة ونغمة البطارية لا يهبطان تحت 0.1 — يُفحصان قبل
        // الفرع العام لـ _volume وإلا ابتلعه الفرعُ العامُ بمجرد احتوائهما
        // على المقطع (0..1) فسقط السقف الأدنى.
        key == "time_chime_volume" ||
            key == "battery_cue_volume" -> value.coerceIn(0.1f, 1f)
        key.contains("_volume") ||
            key == "default_volume" -> value.coerceIn(0f, 1f)
        key.contains("_rate") ||
            key.contains("_pitch") -> value.coerceIn(0f, 2f)
        else -> value
    }

    /** تعقّل مجموعة سلاسل: مستويات البطارية تبقى مضاعفات 5 في المدى 5..100. */
    private fun sanitizeStringSet(
        key: String,
        value: Set<String>
    ): Set<String> = when (key) {
        "battery_announcement_levels" -> value.filter {
            it.toIntOrNull()?.let { n -> n in 5..100 && n % 5 == 0 } == true
        }.toSet()
        "device_health_items" -> value.filter {
            it in validDeviceHealthItems()
        }.toSet()
        else -> value
    }

    /**
     * استعادة الإعدادات من خريطة صادرة عن exportSettings، مع تعقّل النطاقات.
     * تجمَّع العمليات أولاً (بلا لمس القرص) ثم تُطبق دفعة واحدة — فلا يبقى
     * الملف نصف مُمسوح إن فشل التجميع.
     */
    fun importSettings(map: Map<String, Any>): Boolean {
        return try {
            class Op(
                val apply: (SharedPreferences.Editor) ->
                    SharedPreferences.Editor
            )
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
                        // بند 5.1: القيم الطولية تُستعاد كما هي (putLong) —
                        // كانت تتحول sanitizeInt/putInt فتقتطع خارج مدى Int
                        // أو تُرمى في النسخ الاحتياطي عند تصدير JSON.
                        ops.add(Op { it.putLong(key, value) }); meaningful++
                    }
                    is Float -> {
                        val v = sanitizeFloat(key, value)
                        ops.add(Op { it.putFloat(key, v) }); meaningful++
                    }
                    is Boolean -> {
                        if (key == "hijri_date" ||
                            key == "hijri_date_enabled") continue
                        // المفاتيح الحساسة تُثبَّت false على الاستيراد مهما
                        // وردت في النسخة (انظر SAFE_DEFAULT_FALSE_KEYS).
                        val safe = if (key in SAFE_DEFAULT_FALSE_KEYS) {
                            false
                        } else {
                            value
                        }
                        ops.add(Op { it.putBoolean(key, safe) })
                        meaningful++
                    }
                    is String -> {
                        val v = sanitizeString(key, value)
                        ops.add(Op { it.putString(key, v) })
                        meaningful++
                    }
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val v = sanitizeStringSet(key, value as Set<String>)
                        ops.add(Op { it.putStringSet(key, v) }); meaningful++
                    }
                    is Double -> {
                        val isFloatKey = key.contains("_volume") ||
                            key.contains("_rate") ||
                            key.contains("_pitch") ||
                            key == "default_volume" ||
                            key == "time_chime_volume" ||
                            key == "battery_cue_volume"
                        if (isFloatKey) {
                            val v = sanitizeFloat(key, value.toFloat())
                            ops.add(Op { it.putFloat(key, v) })
                            meaningful++
                        } else {
                            val v = sanitizeInt(key, value.toInt())
                            ops.add(Op { it.putInt(key, v) })
                            meaningful++
                        }
                    }
                    is Number -> {
                        val isFloatKey = key.contains("_volume") ||
                            key.contains("_rate") ||
                            key.contains("_pitch") ||
                            key == "default_volume" ||
                            key == "time_chime_volume" ||
                            key == "battery_cue_volume"
                        if (isFloatKey) {
                            val v = sanitizeFloat(key, value.toFloat())
                            ops.add(Op { it.putFloat(key, v) })
                            meaningful++
                        } else {
                            val v = sanitizeInt(key, value.toInt())
                            ops.add(Op { it.putInt(key, v) })
                            meaningful++
                        }
                    }
                    else -> {}
                }
            }
            if (meaningful == 0) return false
            // كانت وسوم الترحيل الداخلية (التي تبدأ بـ «_» وتُستثنى من
            // الاستيراد) تُمسح مع clear() فيتحرّك الترحيل الثلاثي من جديد
            // على النسخة المستوردة وربما أعاد تحويل قيمٍ نُقّيت بعد ترحيلها.
            // نحفظها ونعيدها بعد المسح — الإعدادات وحيدةٌ فعلياً بلا إعادة
            // ترحيل، والوسوم نفسها لا تُصدَّر عند النسخ الاحتياطي.
            val migrationFlags = MIGRATION_KEYS.mapNotNull { key ->
                if (rawPrefs.contains(key)) {
                    key to rawPrefs.getBoolean(key, false)
                } else null
            }.toMap()
            val editor = prefs.edit().clear()
            for (op in ops) editor.let(op.apply)
            for ((key, flag) in migrationFlags) {
                editor.putBoolean(key, flag)
            }
            editor.commit()
        } catch (t: Throwable) {
            Log.e(TAG, "import settings failed", t)
            false
        }
    }
}
