package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.common.VisibleLanguages
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
    CallerNamesStore,
    UpdatePrefs,
    AccessibilityPrefs {

    companion object {
        internal const val TAG = "NATEQ_TTS"
        internal const val NEW_PREFS = "nateq_settings"
        private const val OLD_PREFS = "nateq_secure_settings"
        private const val FALLBACK_PREFS = "nateq_fallback_settings"
        private const val KEY_MIGRATED = "_migrated_to_plain"

        /** **بند V2:** تفعيلُ الصدى (PresetReverb) — مستقلٌّ عن
         *  `audio_expansion_level` وافتراضياً false. */
        internal const val KEY_AUDIO_REVERB_ENABLED = "audio_reverb_enabled"

        const val KEY_ANDROID17_BANKING_HARDENING =
            "android17_banking_hardening"

        /** **بند V3:** فئاتُ الإعلانات التي عُطّل فيها مؤثر الصوت. */
        internal const val KEY_AUDIO_EFFECTS_DISABLED_CATEGORIES =
            "audio_effects_disabled_categories"

        /** فئاتُ الإعلانات التي يقبلها [KNOWN_EFFECT_CATEGORIES] — مطابقة
         *  لـ`AnnouncementSpeaker.EVENT_CATEGORIES` عمداً، فكلُّ فئةٍ
         *  إعلانية قابلةٌ للتعطيل ما عدا قراءةِ النص الطويل. */
        internal val KNOWN_EFFECT_CATEGORIES = setOf(
            VOICE_CATEGORY_TIME,
            VOICE_CATEGORY_BATTERY,
            ANNOUNCE_CATEGORY_CALLER,
            ANNOUNCE_CATEGORY_CALLER_AR,
            ANNOUNCE_CATEGORY_CALLER_EN,
            ANNOUNCE_CATEGORY_SMS,
            VOICE_CATEGORY_NOTIFICATIONS,
            VOICE_CATEGORY_LORD_SYSTEM
        )

        /** قالب إعلان المتصل المحذوف: كان يُخزَّن قبل إزالته، واسمه
         *  محفوظٌ هنا ليُطمس من مخازن التثبيتات السابقة
         *  ([dropObsoleteCallerTemplateKey]). */
        private const val KEY_CALLER_TEMPLATE_OBSOLETE =
            "caller_announcement_template"

        /** منفّذ إعادة تحميل الإعدادات خارج الواجهة (بند الأداء): تحليل ملف
         *  الإعدادات وتبديل اللقطة عند إشعار العملية الأخرى عمل ثقيل كان
         *  يجري على خيط الواجهة (ContentObserver) — يُنفَّذ هنا تسلسلياً،
         *  وخيطه وصّي فلا يحجب ضغط العملية. [reload] نفسه يبقى تزامنياً
         *  لمستدعيه المباشرين (الخدمة) ولا يُحوَّل إلا مسار المراقب. */
        private val reloadExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "nateq-prefs-reload").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }

        const val VOICE_CATEGORY_TIME = "time"
        const val VOICE_CATEGORY_NUMBERS = "numbers"
        const val VOICE_CATEGORY_NOTIFICATIONS = "notifications"
        const val VOICE_CATEGORY_DEFAULT = "default"
        const val VOICE_CATEGORY_EMOJI = "emoji"
        const val VOICE_CATEGORY_DIALOGUE = "dialogue"
        const val VOICE_CATEGORY_NARRATION = "narration"
        const val VOICE_CATEGORY_BATTERY = "battery"
        const val VOICE_CATEGORY_LORD_SYSTEM = "lord_system"
        const val ENGINE_LORD_TTS = "lord_tts"

        /** حدّا الفاصل الزمني بين تكرارات إعلان المتصل (بالثواني): ثلاث
         *  قيم فقط 3/4/5 — وهما حدُّ المخزن فيساوي حدَّ الواجهة. */
        const val CALLER_INTERVAL_MIN = 3
        const val CALLER_INTERVAL_MAX = 5

        /** حدّا عدد تكرارات إعلان المتصل: من مرة واحدة إلى عشر — وهما
         *  حدُّ المخزن فيساوي حدَّ الواجهة [REPEAT_MAX] في
         *  CallerAnnouncementController، ويوافقُه consumeRepeat في
         *  CallerAnnouncementReceiver. */
        const val CALLER_REPEAT_MIN = 1
        const val CALLER_REPEAT_MAX = 10

        /** مدى نسبة خفض الرنين أثناء إعلان المتصل (بالمئة): من 10 إلى 90
         *  — لا صفرَ (كتمٌ تامٌ مزعج) ولا 100 (لا خفضٌ أصلاً فيُكذَب
         *  المفتاح). الافتراضي 40. */
        const val CALLER_RING_DUCK_MIN = 10
        const val CALLER_RING_DUCK_MAX = 90
        const val CALLER_RING_DUCK_DEFAULT = 40

        const val KEY_PENDING_BOOSTED_STREAM = "pending_boosted_stream"
        const val KEY_PENDING_BOOSTED_VOLUME = "pending_boosted_volume"
        const val KEY_EVENT_PCM_GAIN_DB = "event_pcm_gain_db"
        const val DEFAULT_EVENT_PCM_GAIN_DB = 6.0f
        const val MAX_EVENT_PCM_GAIN_DB = 6.0f

        private const val KEY_BATTERY_VOICE_MIGRATED =
            "_battery_voice_migrated"

        /** فئة إعلان المتصل (محرك/صوت مستقل للإعلان عن المكالمات)
         *  — أُبقيها للمتوافقية مع مفاتيح قديمة مخزنة. */
        const val ANNOUNCE_CATEGORY_CALLER = "caller"

        /** محرّك نطق الأسماء العربية في إعلان المتصل. */
        const val ANNOUNCE_CATEGORY_CALLER_AR = "caller_ar"

        /** محرّك نطق الأسماء الإنجليزية في إعلان المتصل. */
        const val ANNOUNCE_CATEGORY_CALLER_EN = "caller_en"

        /** فئة إعلان الرسائل النصية (محرك/نبرة مستقلان إن ضُبطا). */
        const val ANNOUNCE_CATEGORY_SMS = "sms"

        /**
         * فئات الصوت النشطة (بدون فئة "الافتراضي" المحذوفة). الثابت
         * [VOICE_CATEGORY_DEFAULT] باقٍ للترحيل/التخزين القديم فقط.
         */
        val ALL_VOICE_CATEGORIES = listOf(
            VOICE_CATEGORY_TIME,
            VOICE_CATEGORY_NUMBERS,
            VOICE_CATEGORY_NOTIFICATIONS,
            VOICE_CATEGORY_DIALOGUE,
            VOICE_CATEGORY_NARRATION,
            VOICE_CATEGORY_BATTERY,
            VOICE_CATEGORY_LORD_SYSTEM,
            ANNOUNCE_CATEGORY_CALLER,
            ANNOUNCE_CATEGORY_CALLER_AR,
            ANNOUNCE_CATEGORY_CALLER_EN,
            ANNOUNCE_CATEGORY_SMS
        )

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
        internal val SAFE_DEFAULT_FALSE_KEYS = setOf(
            "shake_to_stop_enabled",
            "proximity_silence_enabled",
            "long_text_speedup_enabled",
            "proactive_protected_watch_enabled"
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
        const val KEY_QUIET_BITMASK_MIGRATED = "_quiet_days_bitmask_migrated"
        const val QUIET_HOUR_FULL_MASK = 0x00FFFFFF
        const val QUIET_HOUR_EMPTY_MASK = 0

        /** مفتاح خريطة تفضيلات التحويل لكل لغة
         *  (JSON عبر ConvertPreferencesCodec). */
        private const val KEY_CONVERT_PREFS_JSON = "convert_language_prefs"

        /** وسم ترحيل سلوتات اللغة 1/2 القديمة إلى الخريطة
         *  الديناميكية (مرة واحدة). */
        private const val KEY_CONVERT_SLOTS_MIGRATED = "_convert_slots_migrated"

        /** وسم نقل أصوات الفئات من المفتاح القديم `preferred_voice_<cat>`
         *  إلى مفتاح (المحرك، الفئة) مرة واحدة. */
        private const val KEY_PREFERRED_VOICE_ENGINE_MIGRATED =
            "_preferred_voice_engine_migrated"

        /** وسم ترحيل صوت القراءة الافتراضي القديم إلى اختيار صوت اللغة. */
        private const val KEY_DEFAULT_VOICE_MIGRATED =
            "_default_voice_migrated"

        /** وسوم الترحيل الداخلية الثلاثة: تُستثنى من النسخ الاحتياطي/الاستيراد
         *  (تبدأ بـ «_») لكن لا يجوز أن يمسحها clear() في importSettings وإلا
         *  يتحرك الترحيل من جديد فوق نسخةٍ مكتملة.
         *  تُحفظ وتُعاد عند الاستيراد. */
        internal val MIGRATION_KEYS = listOf(
            KEY_MIGRATED,
            KEY_QUIET_MIGRATED,
            KEY_QUIET_BITMASK_MIGRATED,
            KEY_CONVERT_SLOTS_MIGRATED,
            KEY_BATTERY_VOICE_MIGRATED,
            KEY_PREFERRED_VOICE_ENGINE_MIGRATED,
            KEY_DEFAULT_VOICE_MIGRATED
        )

        const val KEY_KEYBOARD_MODE = "keyboard_mode"
        const val KEY_KEYBOARD_ACTIVATION = "keyboard_activation"
        const val KEY_KEYBOARD_ANNOUNCE_AFTER_LIFT =
            "keyboard_announce_after_lift"
        const val KEY_KEYBOARD_HAPTIC = "keyboard_haptic"
        const val KEY_KEYBOARD_SOUND = "keyboard_sound"
        const val KEY_KEYBOARD_PHONETIC = "keyboard_phonetic"
        const val KEY_KEYBOARD_ALWAYS_SHOW_NUMBER_ROW =
            "keyboard_always_show_number_row"
        const val KEY_KEYBOARD_SIZE_LEVEL = "keyboard_size_level"

        const val KEYBOARD_MODE_BLIND = "blind"
        const val KEYBOARD_MODE_LOW_VISION = "low_vision"
        const val KEYBOARD_ACTIVATION_LIFT = "lift"
        const val KEYBOARD_ACTIVATION_DOUBLE_TAP = "double_tap"

        const val KEY_PHONE_MODE = "phone_mode"
        const val KEY_PHONE_ACTIVATION = "phone_activation"
        const val KEY_DIALPAD_SIZE_LEVEL = "dialpad_size_level"
        const val KEY_DIALPAD_LAYOUT_STYLE = "dialpad_layout_style"
        const val DIALPAD_STYLE_GRID_WIDE = "GRID_WIDE"
        const val DIALPAD_STYLE_GRID_5X2 = "GRID_5X2"
        const val DIALPAD_STYLE_SINGLE_ROW = "SINGLE_ROW"
        const val KEY_PHONE_ANNOUNCE_AFTER_LIFT =
            "phone_announce_after_lift"
        const val KEY_PHONE_HAPTIC = "phone_haptic"
        const val KEY_PHONE_SOUND = "phone_sound"
        const val KEY_PHONE_RECORD_ENABLED = "phone_record_enabled"
        const val KEY_PHONE_RECORD_MODE = "phone_record_mode"
        const val KEY_PHONE_SHOW_RECORD_BUTTON =
            "phone_show_record_button"
        const val KEY_PHONE_RECORD_BOOST_VOLUME =
            "phone_record_boost_volume"
        const val KEY_PHONE_AUTO_SPEAKER_PROXIMITY =
            "phone_auto_speaker_proximity"
        const val KEY_PHONE_HARDWARE_KEYS_ANSWER =
            "phone_hardware_keys_answer"
        const val KEY_PHONE_LAUNCHER_ICON =
            "phone_launcher_icon_enabled"
        const val KEY_SPEED_DIAL_LAUNCHER_ICON =
            "speed_dial_launcher_icon_enabled"
        const val KEY_APP_PROFILES = "app_profiles"
        const val KEY_CONTACT_VOICES = "contact_voices"
        const val KEY_READ_LATER = "read_later_queue"
        const val KEY_READ_LATER_LONG_TEXT_POLICY =
            "read_later_long_text_policy"
        const val READ_LATER_POLICY_SAVE_FULL = "save_full"
        const val READ_LATER_POLICY_TRUNCATE = "truncate"
        internal const val MAX_READ_LATER_ITEMS = 50
        internal const val MAX_READ_LATER_TEXT_CHARS = 20_000
        const val BOOT_MIRROR_PREFS = "nateq_boot_mirror"

        private val BOOT_MIRROR_EXCLUDED_KEYS = setOf(
            KEY_CONTACT_VOICES,
            "contact_voice_overrides"
        )

        private val BOOT_MIRROR_EXCLUDED_PREFIXES = listOf(
            "contact_", "caller_", "phone_", "call_record_"
        )

        private val BOOT_MIRROR_ALLOWED_EXACT_KEYS = setOf(
            "app_language", "number_reading_language", "secondary_language",
            "primary_engine", "preferred_engine", "default_engine",
            "speech_rate", "speech_pitch", "speech_volume",
            "boot_speech_enabled", "speak_on_boot"
        )

        private val BOOT_MIRROR_ALLOWED_PREFIXES = listOf(
            "emergency_",
            "preferred_voice_",
            "primary_voice_",
            "speech_rate_",
            "speech_pitch_",
            "speech_volume_",
            "rate_",
            "pitch_",
            "volume_",
            "engine_voice_",
            "language_for_",
            "filter_"
        )

        internal fun isAllowedInBootMirror(key: String): Boolean {
            if (key in BOOT_MIRROR_EXCLUDED_KEYS) return false
            if (BOOT_MIRROR_EXCLUDED_PREFIXES.any { key.startsWith(it) }) {
                return false
            }
            if (key in BOOT_MIRROR_ALLOWED_EXACT_KEYS) return true
            return BOOT_MIRROR_ALLOWED_PREFIXES.any { key.startsWith(it) }
        }
        const val KEY_EMERGENCY_ENGINE = "emergency_engine"
        const val KEY_EMERGENCY_VOICE = "emergency_voice"
        const val KEY_EMERGENCY_RATE = "emergency_rate"
        const val KEY_EMERGENCY_PITCH = "emergency_pitch"
        const val KEY_EMERGENCY_VOLUME = "emergency_volume"
        const val KEY_EMERGENCY_SAVED = "emergency_saved"
        const val KEY_FILTER_LANGUAGES_ENABLED = "filter_languages_enabled"
        const val KEY_FILTER_LANGUAGES_SET = "filter_languages_set"
        const val KEY_FILTER_ENGINES_ENABLED = "filter_engines_enabled"
        const val KEY_FILTER_ENGINES_SET = "filter_engines_set"
        const val KEY_DICTIONARY_STANDALONE_MAX_LENGTH =
            "dictionary_standalone_max_length"
        const val KEY_DICTIONARY_CASE_INSENSITIVE =
            "dictionary_case_insensitive"
        const val KEY_TIME_SUFFIX_M_MODE = "time_suffix_m_mode"
        const val TIME_SUFFIX_M_CONTEXT = "context"
        const val TIME_SUFFIX_M_LETTER = "letter"
        const val TIME_SUFFIX_M_EVENING = "evening"
        const val KEY_PHONE_RECORD_AUTO_SPEAKER =
            "phone_record_auto_speaker"
        const val KEY_READ_MORE_ALLOWED_PACKAGES =
            "read_more_allowed_packages"
        const val KEY_PROTECTED_APPS = "protected_apps"
        const val KEY_PROTECTED_SUSPENDED_PKG = "protected_suspended_pkg"
        const val KEY_PROTECTED_SUSPENDED_TIME = "protected_suspended_time"
        const val KEY_PROTECTED_APP_AUTO_RESTORE_TIMEOUT_MINUTES =
            "protected_app_auto_restore_timeout_minutes"

        /**
         * التطبيقات المحمية الافتراضية — تعمل من أول تشغيل بلا إعداد:
         * خدمتنا في إمكانية الوصول هي ما يحبط التطبيقات المصرفية
         * (قياسُ الجهاز: إنستاباي يتحطّم مع وجود الخدمة ويعمل بلاها)،
         * فتُعطَّل تلقائياً أمام هذه الحزم ثم تعود فور الخروج.
         * الاسم [label] هو اسم التطبيق الحقيقي كما يظهر في المشغّل،
         * لأنه هو ما تصله نقرة الأيقونة وتُطابَق عليه.
         * تُحذف هذه القائمة إذا حفظ المستخدم قائمته ولو كانت فارغة.
         */
        private val DEFAULT_PROTECTED_APPS = listOf(
            ProtectedApp(
                packageName = "com.egyptianbanks.instapay",
                label = "InstaPay",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )
        const val KEY_EMOJI_PRONUNCIATION_LANGUAGE =
            "emoji_pronunciation_language"
        const val KEY_LANGUAGE_DIAGNOSTICS_ENABLED =
            "language_diagnostics_enabled"
        const val KEY_DAY_PERIOD_EXPLICIT_NUN =
            "day_period_explicit_nun"
        const val KEY_DAY_PERIOD_STYLE = "day_period_style"
        const val DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA = "explicit_nun_comma"
        const val DAY_PERIOD_STYLE_PREPOSITIONAL = "prepositional"
        const val DAY_PERIOD_STYLE_AS_IS = "as_is"

        const val KEY_INCOMING_CALL_UI_MODE = "incoming_call_ui_mode"
        const val INCOMING_CALL_UI_AUTO = "auto"
        const val INCOMING_CALL_UI_FULLSCREEN = "fullscreen"
        const val INCOMING_CALL_UI_NOTIFICATION = "notification"

        const val PHONE_MODE_BLIND = "blind"
        const val PHONE_MODE_LOW_VISION = "low_vision"
        const val PHONE_ACTIVATION_LIFT = "lift"
        const val PHONE_ACTIVATION_DOUBLE_TAP = "double_tap"
        const val PHONE_RECORD_MODE_MANUAL = "manual"
        const val PHONE_RECORD_MODE_AUTO_ALL = "auto_all"
        const val PHONE_RECORD_MODE_AUTO_UNSAVED = "auto_unsaved"

        @Volatile
        private var instance: SettingsRepository? = null

        @androidx.annotation.VisibleForTesting
        val activeInstancesCount =
            java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * المصنّع الموحّد الوحيد لمثيل SettingsRepository
         * — مفرد على مستوى العملية ولا يحمل نشاطاً (Activity).
         */
        fun create(context: Context): SettingsRepository {
            val app = context.applicationContext ?: context
            val current = instance
            if (current != null && current.appContext === app) {
                return current
            }
            return synchronized(SettingsRepository::class.java) {
                val existing = instance
                if (existing != null && existing.appContext === app) {
                    existing
                } else {
                    SettingsRepository(app).also {
                        instance = it
                    }
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(SettingsRepository::class.java) {
                SnapshotPrefs.awaitPendingWritesForTesting(2000)
                instance?.unregister()
                instance = null
                activeInstancesCount.set(0)
            }
        }
    }

    private val prefsLock = Any()
    private val localWriteSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val flushedSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var bootMirrorHandler: Handler? = null
    private val bootMirrorRunnable = Runnable {
        updateBootMirrorIfNeeded()
        VisibleLanguages.update(
            isLanguageFilterEnabled(),
            getLanguageFilterSet()
        )
        VisibleEngines.update(
            isEngineFilterEnabled(),
            getEngineFilterSet()
        )
    }

    /** سياق التطبيق العام لضمان عدم احتجاز سياق النشاط في المراقبين. */
    internal val appContext: Context = context.applicationContext

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
    internal val rawPrefs: SharedPreferences = openSharedPrefs().also {
        migrateIfNeeded(it)
        migrateBatteryVoicePrefsIfNeeded(it)
        migratePreferredVoiceEngineKeysIfNeeded(it)
        migrateDefaultVoiceToLanguageIfNeeded(it)
        dropObsoleteCallerTemplateKey(it)
        migrateAppProfilesAndContactVoicesIfNeeded(it)
        migrateLanguageFilterRemoval(it)
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
    internal val prefs: SnapshotPrefs = SnapshotPrefs(
        delegate = rawPrefs,
        context = appContext,
        onChanged = ::notifySettingsChanged,
        stampProvider = ::prefsFileStamp
    )

    private val isReloadScheduled =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /** مراقب التغييرات بين العمليتين: يستيقظ عند إشعار [SettingsChangeProvider]
     *  لتحديث اللقطة محلياً بتجميع المهام دون تراكم مهام نوم. */
    private val settingsObserver = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            if (isReloadScheduled.compareAndSet(false, true)) {
                reloadExecutor.execute {
                    try {
                        do {
                            isReloadScheduled.set(false)
                            reload(force = true)
                        } while (isReloadScheduled.get())
                    } finally {
                        isReloadScheduled.set(false)
                    }
                }
            }
        }
    }

    private val isObserverRegistered =
        java.util.concurrent.atomic.AtomicBoolean(false)
    private val isReceiverRegistered =
        java.util.concurrent.atomic.AtomicBoolean(false)
    private var userUnlockedReceiver: android.content.BroadcastReceiver? = null

    init {
        synchronized(SettingsRepository::class.java) {
            instance = this
            activeInstancesCount.incrementAndGet()
        }
        if (isObserverRegistered.compareAndSet(false, true)) {
            runCatching {
                appContext.contentResolver.registerContentObserver(
                    SettingsChangeProvider.uri(), false, settingsObserver
                )
            }
        }
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.N
        ) {
            if (isReceiverRegistered.compareAndSet(false, true)) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: android.content.Intent?
                    ) {
                        if (intent?.action ==
                            android.content.Intent.ACTION_USER_UNLOCKED
                        ) {
                            onUserUnlocked()
                        }
                    }
                }
                userUnlockedReceiver = receiver
                try {
                    androidx.core.content.ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        android.content.IntentFilter(
                            android.content.Intent.ACTION_USER_UNLOCKED
                        ),
                        androidx.core.content.ContextCompat
                            .RECEIVER_NOT_EXPORTED
                    )
                } catch (_: Exception) {
                    isReceiverRegistered.set(false)
                    userUnlockedReceiver = null
                }
            }
        }
        updateBootMirrorIfNeeded()
        VisibleLanguages.update(
            isLanguageFilterEnabled(),
            getLanguageFilterSet()
        )
        VisibleEngines.update(
            isEngineFilterEnabled(),
            getEngineFilterSet()
        )
    }

    /**
     * يلغي تسجيل مراقب التغييرات ومستقبل فتح القفل بملكية وإغلاق واضحين.
     */
    fun unregister() {
        SnapshotPrefs.awaitPendingWritesForTesting(2000)
        if (isObserverRegistered.compareAndSet(true, false)) {
            runCatching {
                appContext.contentResolver.unregisterContentObserver(
                    settingsObserver
                )
            }
        }
        if (isReceiverRegistered.compareAndSet(true, false)) {
            userUnlockedReceiver?.let { receiver ->
                runCatching {
                    appContext.unregisterReceiver(receiver)
                }
            }
            userUnlockedReceiver = null
        }
        synchronized(SettingsRepository::class.java) {
            if (instance === this) {
                instance = null
            }
            activeInstancesCount.decrementAndGet()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun isObserverRegisteredForTesting(): Boolean =
        isObserverRegistered.get()

    @androidx.annotation.VisibleForTesting
    fun isReceiverRegisteredForTesting(): Boolean =
        isReceiverRegistered.get()

    private fun isUserUnlocked(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.N
        ) {
            val um = appContext.getSystemService(
                android.os.UserManager::class.java
            )
            return um?.isUserUnlocked ?: true
        }
        return true
    }

    /**
     * يفتح تفضيلات [NEW_PREFS] أو المرآة المحمية قبل فتح القفل (الأمر 6).
     */
    private fun openSharedPrefs(): SharedPreferences {
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.N &&
            !isUserUnlocked()
        ) {
            val deviceContext = appContext.createDeviceProtectedStorageContext()
            return deviceContext.getSharedPreferences(
                BOOT_MIRROR_PREFS, Context.MODE_PRIVATE
            )
        }
        return appContext.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
    }

    fun scheduleBootMirrorUpdate() {
        val handler = bootMirrorHandler ?: Handler(
            Looper.getMainLooper()
        ).also { bootMirrorHandler = it }
        handler.removeCallbacks(bootMirrorRunnable)
        handler.postDelayed(bootMirrorRunnable, 500L)
    }

    internal fun updateBootMirrorIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.N
        ) return
        if (!isUserUnlocked()) return
        runCatching {
            val deviceContext = appContext.createDeviceProtectedStorageContext()
            val mirror = deviceContext.getSharedPreferences(
                BOOT_MIRROR_PREFS, Context.MODE_PRIVATE
            )
            val editor = mirror.edit()
            val currentMirrorKeys = mirror.all.keys.toSet()
            val allowedInMain = mutableSetOf<String>()

            for ((key, value) in prefs.all) {
                if (isAllowedInBootMirror(key)) {
                    allowedInMain.add(key)
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Long -> editor.putLong(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                    }
                }
            }

            // ترحيل: حذف المفاتيح المستبعدة والمفاتيح المحذوفة من المرآة
            for (oldKey in currentMirrorKeys) {
                if (!allowedInMain.contains(oldKey)) {
                    editor.remove(oldKey)
                }
            }
            editor.apply()
        }
    }

    private fun onUserUnlocked() {
        val normal = appContext.getSharedPreferences(
            NEW_PREFS, Context.MODE_PRIVATE
        )
        prefs.updateDelegate(normal)
        updateBootMirrorIfNeeded()
        notifySettingsChanged()
    }

    // أسماء المتصلين = بيانات شخصية (PII) تُخزَّن في ملف مشفَّر منفصل؛
    // عند تعذر التشفير (Keystore معطوب…) تُحتفظ في الذاكرة لهذه الجلسة فقط
    // ولا تُكتب في تفضيلات نصية عادية أبداً (منع تسريب PII).
    @Volatile
    private var callerSecurePrefs: SharedPreferences? = null

    private val memoryCallerNames =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    fun reload(force: Boolean = false) {
        if (localWriteSeq.get() != flushedSeq.get()) return
        if (!force && !prefsFileChanged()) return

        val stampAtEntry = prefsFileStamp()
        val diskFresh = runCatching {
            prefsBridge.parse(NEW_PREFS)
        }.getOrNull()

        val fresh = if (diskFresh != null && diskFresh.isNotEmpty()) {
            diskFresh
        } else {
            val fromAuth = SettingsAuthority.getSnapshot(appContext).second
            if (fromAuth.isNotEmpty()) fromAuth else diskFresh ?: return
        }

        if (fresh.isEmpty() && prefs.all.isNotEmpty()) {
            return
        }
        if (prefs.all.minus(KEY_MIGRATED) == fresh.minus(KEY_MIGRATED)) {
            refreshPrefsStamp()
            return
        }
        if (prefsFileStamp() != stampAtEntry) return
        if (localWriteSeq.get() != flushedSeq.get()) return

        prefs.replaceSnapshot(fresh)
        refreshPrefsStamp()
        runCatching {
            VisibleLanguages.update(
                isLanguageFilterEnabled(),
                getLanguageFilterSet()
            )
            VisibleEngines.update(
                isEngineFilterEnabled(),
                getEngineFilterSet()
            )
        }
    }

    /** ينتظر اكتمال أي إعادة تحميل معلّقة على [reloadExecutor] (أداة
     *  تحديدٍ في الاختبارات بعد إشعار العملية الأخرى؛ لا تُستخدم في
     *  الإنتاج — مسار المراقب غير تزامني عمداً الآن). */
    internal fun awaitReloadForTesting() {
        val latch = java.util.concurrent.CountDownLatch(1)
        reloadExecutor.execute { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** يُبلغ بطاقةَ الـ ContentObserver المعلنة في Manifest الوحدة بعد كل
     *  كتابةٍ ناجحة عبر [SnapshotPrefs]، فيستيقظ مراقبُ العملية الأخرى
     *  (ContentObserver) ويسترجع اللقطة فوراً بدل انتظار دورة
     *  reload التالية. */
    private fun notifySettingsChanged() {
        scheduleBootMirrorUpdate()
        runCatching {
            VisibleLanguages.update(
                isLanguageFilterEnabled(),
                getLanguageFilterSet()
            )
            VisibleEngines.update(
                isEngineFilterEnabled(),
                getEngineFilterSet()
            )
        }
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
    internal fun normalizeVoiceId(id: String?): String? =
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

    /**
     * ترحيل إعدادات صوت/سرعة/نبرة/مستوى البطارية القديمة إلى بنية الفئات
     * الموحّدة (preferred_voice_battery, speech_rate_battery, ...) مرة
     * واحدة بعد التحديث، مع إزالة المفاتيح المنفصلة القديمة.
     */
    internal fun migrateBatteryVoicePrefsIfNeeded(
        targetPrefs: SharedPreferences
    ) {
        if (targetPrefs.getBoolean(KEY_BATTERY_VOICE_MIGRATED, false)) return
        val edit = targetPrefs.edit()

        if (targetPrefs.contains("battery_announcement_voice")) {
            val v = targetPrefs.getString(
                "battery_announcement_voice", null
            )
            if (!v.isNullOrBlank() &&
                !targetPrefs.contains("preferred_voice_$VOICE_CATEGORY_BATTERY")
            ) {
                edit.putString(
                    "preferred_voice_$VOICE_CATEGORY_BATTERY", v
                )
            }
            edit.remove("battery_announcement_voice")
        }

        if (targetPrefs.contains("battery_announcement_language")) {
            val l = targetPrefs.getString(
                "battery_announcement_language", null
            )
            if (!l.isNullOrBlank() &&
                !targetPrefs.contains("language_for_$VOICE_CATEGORY_BATTERY")
            ) {
                edit.putString(
                    "language_for_$VOICE_CATEGORY_BATTERY", l
                )
            }
            edit.remove("battery_announcement_language")
        }

        if (targetPrefs.contains("battery_announcement_rate")) {
            val r = targetPrefs.getFloat("battery_announcement_rate", 1.0f)
            if (!targetPrefs.contains("speech_rate_$VOICE_CATEGORY_BATTERY")) {
                edit.putFloat("speech_rate_$VOICE_CATEGORY_BATTERY", r)
            }
            edit.remove("battery_announcement_rate")
        }

        if (targetPrefs.contains("battery_announcement_pitch")) {
            val p = targetPrefs.getFloat("battery_announcement_pitch", 1.0f)
            if (!targetPrefs.contains("pitch_$VOICE_CATEGORY_BATTERY")) {
                edit.putFloat("pitch_$VOICE_CATEGORY_BATTERY", p)
            }
            edit.remove("battery_announcement_pitch")
        }

        if (targetPrefs.contains("battery_announcement_volume")) {
            val vol = targetPrefs.getFloat(
                "battery_announcement_volume", 1.0f
            )
            if (!targetPrefs.contains("volume_$VOICE_CATEGORY_BATTERY")) {
                edit.putFloat("volume_$VOICE_CATEGORY_BATTERY", vol)
            }
            edit.remove("battery_announcement_volume")
        }

        edit.putBoolean(KEY_BATTERY_VOICE_MIGRATED, true).apply()
    }

    /**
     * ترحيل آمن لصوت القراءة الافتراضي القديم («فئة الافتراضي» المحذوفة)
     * إلى اختيار «صوت القراءة لكل لغة» بلغة مستنتَجة، دون افتراض العربية.
     * يهاجر مفاتيح الصوت/المحرك/الفئة الافتراضية القديمة
     * (`engine_for_default`/`preferred_voice_default`/`primary_voice_default`
     * وما يقابلها) فقط إن لم يوجد اختيار لغة محفوظ للغة المستهدفة.
     * العملية idempotent ولا تمسح الأصل قبل نجاح كتابة البديل.
     */
    internal fun migrateDefaultVoiceToLanguageIfNeeded(
        targetPrefs: SharedPreferences
    ) {
        if (targetPrefs.getBoolean(KEY_DEFAULT_VOICE_MIGRATED, false)) {
            return
        }

        // توحيد الأشرطة العامة: إن غاب المفتاح العام وُجدت قيمة قديمة
        // (فئة/مفتاح تراثي) تُنسخ إلى default_* دون مسح الأصل.
        val generalEditor = targetPrefs.edit()
        copyLegacyGeneralIfMissing(
            targetPrefs, generalEditor,
            "default_speech_rate", "speech_rate_default"
        )
        copyLegacyGeneralIfMissing(
            targetPrefs, generalEditor,
            "default_pitch", "pitch_default"
        )
        copyLegacyGeneralIfMissing(
            targetPrefs, generalEditor,
            "default_volume", "volume_default"
        )

        val legacyVoice = firstNonBlankLegacyDefaultVoice(targetPrefs)
        val legacyEngineRaw = targetPrefs.getString(
            "engine_for_$VOICE_CATEGORY_DEFAULT", null
        )?.takeIf { it.isNotBlank() }
        val inferredLang = inferLanguageFromLegacyDefault(
            legacyVoice, legacyEngineRaw
        )

        var migratedVoice = false
        if (!inferredLang.isNullOrBlank() &&
            !hasSavedLanguageChoiceForMigration(targetPrefs, inferredLang)
        ) {
            migratedVoice = migrateLegacyDefaultVoiceToLanguage(
                targetPrefs, inferredLang, legacyVoice, legacyEngineRaw
            )
        }

        // الوسم يجعل الترحيل idempotent؛ الأصل يُترك كما هو.
        generalEditor.putBoolean(KEY_DEFAULT_VOICE_MIGRATED, true).apply()
        if (migratedVoice) {
            Log.i(
                TAG,
                "Migrated legacy default voice to language '$inferredLang'"
            )
        }
    }

    private fun copyLegacyGeneralIfMissing(
        targetPrefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        generalKey: String,
        legacyKey: String
    ) {
        if (targetPrefs.contains(generalKey)) return
        if (!targetPrefs.contains(legacyKey)) return
        // المفاتيح قديمة عامة/لفئة افتراضية بنفس الاسم؛ نحافظ على القيمة.
        if (legacyKey.endsWith("_volume") ||
            generalKey.endsWith("_volume")
        ) {
            val v = targetPrefs.getFloat(legacyKey, 1.0f)
            editor.putFloat(generalKey, v.coerceIn(0f, 1f))
        } else {
            val v = targetPrefs.getFloat(legacyKey, 1.0f)
            editor.putFloat(generalKey, v.coerceIn(0f, 2f))
        }
    }

    private fun firstNonBlankLegacyDefaultVoice(
        targetPrefs: SharedPreferences
    ): String? {
        val keys = linkedSetOf(
            "preferred_voice_$VOICE_CATEGORY_DEFAULT",
            "primary_voice_$VOICE_CATEGORY_DEFAULT",
            "primary_voice_default",
            "preferred_voice_default"
        )
        val engine = targetPrefs.getString(
            "engine_for_$VOICE_CATEGORY_DEFAULT", null
        )
        if (!engine.isNullOrBlank()) {
            keys.add("preferred_voice_${engine}_$VOICE_CATEGORY_DEFAULT")
            keys.add("preferred_voice_${engine}_default")
        }
        // أي مفتاح محركي قديم لفئة الافتراضي
        for ((k, v) in targetPrefs.all) {
            if (k.startsWith("preferred_voice_") &&
                k.endsWith("_$VOICE_CATEGORY_DEFAULT") &&
                v is String && v.isNotBlank()
            ) {
                keys.add(k)
            }
        }
        return keys.asSequence()
            .mapNotNull { targetPrefs.getString(it, null) }
            .mapNotNull { normalizeVoiceId(it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun inferLanguageFromLegacyDefault(
        voiceId: String?,
        engine: String?
    ): String? {
        val combined = listOfNotNull(voiceId, engine)
            .joinToString(" ").lowercase()
        if (combined.isBlank()) return null
        val arHints = listOf(
            "ar-", "ar_", "ara", "arab", "laila", "leila", "maged",
            "majed", "tarik", "tariq", "mariam", "maryam", "mehdi",
            "salma", "nizar", "youssef", "naayf", "naif", "hoda",
            "zeina", "sara", "saudi", "egypt", "gulf"
        )
        val enHints = listOf(
            "en-", "en_", "eng", "english", "samantha", "ava", "tom",
            "serena", "daniel", "oliver", "karen", "alex",
            "victoria", "allison", "joanna", "matthew", "joey",
            "ivy", "justin", "kendra", "kimberly", "salli"
        )
        val ar = arHints.any { combined.contains(it) }
        val en = enHints.any { combined.contains(it) }
        return when {
            ar && !en -> "ar"
            en && !ar -> "en"
            else -> null
        }
    }

    private fun hasSavedLanguageChoiceForMigration(
        targetPrefs: SharedPreferences,
        languageTag: String
    ): Boolean {
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        // اختيار محفوظ في تفضيلات التحويل/اللغة (JSON)
        val json = targetPrefs.getString(KEY_CONVERT_PREFS_JSON, null)
        if (!json.isNullOrBlank()) {
            val map = ConvertPreferencesCodec.fromJson(json)
            val entry = map[norm] ?: map[languageTag]
            if (entry != null &&
                (!entry.voiceName.isNullOrBlank() ||
                    !entry.engine.isNullOrBlank())
            ) {
                return true
            }
        }
        // مفاتيح لغة مباشرة
        for (k in listOf(
            "preferred_voice_$norm",
            "preferred_voice_$languageTag",
            "primary_voice_$norm",
            "primary_voice_$languageTag"
        )) {
            if (!targetPrefs.getString(k, null).isNullOrBlank()) return true
        }
        // أي مفتاح محركي مرتبط بهذه اللغة
        val suffixNorm = "_$norm"
        val suffixTag = "_$languageTag"
        for ((k, v) in targetPrefs.all) {
            if (!k.startsWith("preferred_voice_")) continue
            if (k == "preferred_voice_$norm" ||
                k == "preferred_voice_$languageTag"
            ) {
                continue
            }
            if ((k.endsWith(suffixNorm) || k.endsWith(suffixTag)) &&
                v is String && v.isNotBlank()
            ) {
                return true
            }
        }
        return false
    }

    private fun migrateLegacyDefaultVoiceToLanguage(
        targetPrefs: SharedPreferences,
        languageTag: String,
        legacyVoice: String?,
        legacyEngineRaw: String?
    ): Boolean {
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        val engine = resolveLegacyDefaultEngineForStorage(legacyEngineRaw)
        val voice = normalizeVoiceId(legacyVoice)
        if (voice.isNullOrBlank() && legacyEngineRaw.isNullOrBlank()) {
            return false
        }

        val editor = targetPrefs.edit()
        var wrote = false

        // كتابة اختيار لغة في JSON تفضيلات التحويل/اللغة إن لم يوجد
        val json = targetPrefs.getString(KEY_CONVERT_PREFS_JSON, null)
        val map = if (json.isNullOrBlank()) {
            mutableMapOf()
        } else {
            ConvertPreferencesCodec.fromJson(json).toMutableMap()
        }
        val existing = map[norm] ?: map[languageTag]
        if (existing == null ||
            (existing.voiceName.isNullOrBlank() &&
                existing.engine.isNullOrBlank())
        ) {
            val entry = ConvertPreferencesCodec.entryForSave(
                engine = if (engine == ENGINE_LORD_TTS) null else engine,
                voiceName = voice,
                rate = existing?.rate ?: 1.0f,
                pitch = existing?.pitch ?: 1.0f,
                volume = existing?.volume ?: 1.0f
            )
            if (entry.hasAdjustment || !voice.isNullOrBlank()) {
                map[norm] = entry
                editor.putString(
                    KEY_CONVERT_PREFS_JSON,
                    ConvertPreferencesCodec.toJson(map)
                )
                wrote = true
            }
        }

        if (!voice.isNullOrBlank()) {
            // ربط الصوت بمحرك اللغة الفعلي (أو LORD_TTS)
            val engineKey = "preferred_voice_${engine}_$norm"
            if (targetPrefs.getString(engineKey, null).isNullOrBlank()) {
                editor.putString(engineKey, voice)
                wrote = true
            }
            if (targetPrefs.getString("preferred_voice_$norm", null)
                    .isNullOrBlank()
            ) {
                editor.putString("preferred_voice_$norm", voice)
                wrote = true
            }
            if (engine == ENGINE_LORD_TTS &&
                targetPrefs.getString("primary_voice_$norm", null)
                    .isNullOrBlank()
            ) {
                editor.putString("primary_voice_$norm", voice)
                wrote = true
            }
        }

        if (wrote) {
            editor.apply()
        }
        return wrote
    }

    private fun resolveLegacyDefaultEngineForStorage(
        legacyEngineRaw: String?
    ): String {
        val candidate = legacyEngineRaw?.takeIf {
            it.isNotBlank() &&
                it != VOICE_CATEGORY_DEFAULT &&
                it != "default"
        }
        return when {
            candidate.isNullOrBlank() -> ENGINE_LORD_TTS
            candidate == ENGINE_LORD_TTS -> ENGINE_LORD_TTS
            VisibleEngines.isVisible(candidate) -> candidate
            else -> ENGINE_LORD_TTS
        }
    }

    /**
     * نقل القيم القديمة المخزّنة تحت `preferred_voice_<category>` إلى
     * مفتاح (المحرك، الفئة) الذي يقرأه [getPreferredVoiceForEngine]،
     * إن كان محرك الفئة معروفاً. المرآة القديمة تُبقى للقراءة القديمة.
     */
    internal fun migratePreferredVoiceEngineKeysIfNeeded(
        targetPrefs: SharedPreferences
    ) {
        if (targetPrefs.getBoolean(
                KEY_PREFERRED_VOICE_ENGINE_MIGRATED, false
            )
        ) {
            return
        }
        val editor = targetPrefs.edit()
        for (category in ALL_VOICE_CATEGORIES +
            listOf(VOICE_CATEGORY_EMOJI, VOICE_CATEGORY_DEFAULT)
        ) {
            val engine = targetPrefs.getString("engine_for_$category", null)
            if (engine.isNullOrBlank() ||
                !VisibleEngines.isVisible(engine)
            ) {
                continue
            }
            val legacy = targetPrefs.getString(
                "preferred_voice_$category", null
            )
            if (legacy.isNullOrBlank()) continue
            val norm = ConvertPreferencesCodec.normalizeLanguageTag(category)
            val key = "preferred_voice_${engine}_$norm"
            if (targetPrefs.getString(key, null).isNullOrBlank()) {
                editor.putString(key, legacy)
            }
        }
        editor.putBoolean(KEY_PREFERRED_VOICE_ENGINE_MIGRATED, true).apply()
    }

    /**
     * يحذف مفتاح قالب إعلان المتصل القديم.
     *
     * **لماذا الحذف لازم:** القالب لم يعد له واجهة ولا دالّة، لكن المفتاح
     * يبقى في مخزّن مُثبَّتٍ سابقاً — و[exportSettings] ينسخ كل مفتاحٍ بلا
     * بادئة سفلية، فيُصدَّر كإعدادٍ «ghost» إلى الملفات والنسخ الاحتياطية
     * ويبقى حيّاً بعد [resetAllToDefault]؟ لا: الطمس يمسح كل شيء — لكن
     * التصدير يسبق المسح فيبدأ الملف المستوردُ به من جديد. الحذف يُنهي
     * وجوده نهائياً فلا يعود يظهر في أي نسخة.
     */
    internal fun dropObsoleteCallerTemplateKey(targetPrefs: SharedPreferences) {
        if (!targetPrefs.contains(KEY_CALLER_TEMPLATE_OBSOLETE)) return
        targetPrefs.edit().remove(KEY_CALLER_TEMPLATE_OBSOLETE).apply()
    }

    /**
     * ترحيل ملفات التطبيقات وأصوات جهات الاتصال من ملفي SharedPreferences
     * القديمين إلى مخزن الإعدادات الموحّد المشترك بين العمليات.
     */
    internal fun migrateAppProfilesAndContactVoicesIfNeeded(
        targetPrefs: SharedPreferences = prefs
    ) {
        var changed = false
        val edit = targetPrefs.edit()

        if (!targetPrefs.contains(KEY_APP_PROFILES)) {
            val oldAppPrefs = appContext.getSharedPreferences(
                "nateq_app_profiles", Context.MODE_PRIVATE
            )
            val oldRaw = oldAppPrefs.getString("app_profiles", null)
            if (!oldRaw.isNullOrBlank()) {
                edit.putString(KEY_APP_PROFILES, oldRaw)
                changed = true
            }
            runCatching {
                oldAppPrefs.edit().clear().commit()
                appContext.deleteSharedPreferences("nateq_app_profiles")
            }
        }

        if (!targetPrefs.contains(KEY_CONTACT_VOICES)) {
            val oldContactPrefs = appContext.getSharedPreferences(
                "nateq_contact_voices", Context.MODE_PRIVATE
            )
            val oldRaw = oldContactPrefs.getString("contact_voices", null)
            if (!oldRaw.isNullOrBlank()) {
                edit.putString(KEY_CONTACT_VOICES, oldRaw)
                changed = true
            }
            runCatching {
                oldContactPrefs.edit().clear().commit()
                appContext.deleteSharedPreferences("nateq_contact_voices")
            }
        }

        if (changed) {
            edit.commit()
            notifySettingsChanged()
        }
    }

    private fun migrateLanguageFilterRemoval(p: SharedPreferences) {
        if (p.contains(KEY_FILTER_LANGUAGES_ENABLED) ||
            p.contains(KEY_FILTER_LANGUAGES_SET)
        ) {
            p.edit()
                .remove(KEY_FILTER_LANGUAGES_ENABLED)
                .remove(KEY_FILTER_LANGUAGES_SET)
                .apply()
        }
    }

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
    override fun getSecondaryLanguage(): String {
        val raw = prefs.getString("secondary_language", LanguageCode.EN.tag)
            ?: LanguageCode.EN.tag
        if (raw == LanguageCode.AR.tag || raw.isBlank()) {
            return LanguageCode.EN.tag
        }
        if (!VisibleLanguages.isVisible(raw)) {
            val visible = VisibleLanguages.visibleSet()
            return when {
                VisibleLanguages.isVisible(LanguageCode.EN.tag) ->
                    LanguageCode.EN.tag
                visible.isNotEmpty() -> visible.first()
                else -> LanguageCode.EN.tag
            }
        }
        return raw
    }
    override fun setSecondaryLanguage(language: String) =
        prefs.edit().putString("secondary_language", language).apply()

    override fun isLanguageFilterEnabled(): Boolean {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        return p?.getBoolean(KEY_FILTER_LANGUAGES_ENABLED, false) ?: false
    }

    override fun setLanguageFilterEnabled(enabled: Boolean) {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        p?.edit()?.putBoolean(KEY_FILTER_LANGUAGES_ENABLED, enabled)?.commit()
        VisibleLanguages.update(enabled, getLanguageFilterSet())
        notifySettingsChanged()
    }

    override fun getLanguageFilterSet(): Set<String> {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        return p?.getStringSet(KEY_FILTER_LANGUAGES_SET, null).orEmpty()
    }

    override fun setLanguageFilterSet(languages: Set<String>) {
        val safe = languages.map { VisibleLanguages.normalize(it) }
            .filter { it.isNotBlank() }.toSet()
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        p?.edit()?.putStringSet(KEY_FILTER_LANGUAGES_SET, safe)?.commit()
        VisibleLanguages.update(isLanguageFilterEnabled(), safe)
        notifySettingsChanged()
    }

    override fun isEngineFilterEnabled(): Boolean {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        return p?.getBoolean(KEY_FILTER_ENGINES_ENABLED, false) ?: false
    }

    override fun setEngineFilterEnabled(enabled: Boolean) {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        p?.edit()?.putBoolean(KEY_FILTER_ENGINES_ENABLED, enabled)?.commit()
        VisibleEngines.update(enabled, getEngineFilterSet())
        notifySettingsChanged()
    }

    override fun getEngineFilterSet(): Set<String> {
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        return p?.getStringSet(KEY_FILTER_ENGINES_SET, null).orEmpty()
    }

    override fun setEngineFilterSet(engines: Set<String>) {
        val safe = engines.map { it.trim() }
            .filter { it.isNotBlank() }.toSet()
        val p = runCatching { prefs }.getOrNull() ?: rawPrefs
        p?.edit()?.putStringSet(KEY_FILTER_ENGINES_SET, safe)?.commit()
        VisibleEngines.update(isEngineFilterEnabled(), safe)
        notifySettingsChanged()
    }

    /** طريقة نطق الأرقام: 1=مفردة، 2=زوجي، 3=ثلاثي، ... 8=ثماني */
    override fun getNumberReadingMode(): Int =
        prefs.getInt("number_reading_mode", 1)
    override fun setNumberReadingMode(mode: Int) =
        prefs.edit().putInt("number_reading_mode", mode.coerceIn(1, 8)).apply()

    /** لغة نطق الأرقام: "ar" أو "en" أو "auto" (تلقائي مع سياق الجملة). */
    override fun getNumberReadingLanguage(): String {
        val lang = prefs.getString("number_reading_language", "ar") ?: "ar"
        return if (lang == "en" || lang == "auto") lang else "ar"
    }
    override fun setNumberReadingLanguage(lang: String) {
        val safe = if (lang == "en" || lang == "auto") lang else "ar"
        prefs.edit().putString("number_reading_language", safe).apply()
    }

    /** الصوت المفضّل لكل لغة (languageTag -> voiceId) */
    override fun getPreferredVoiceId(languageTag: String): String? {
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        // تفضيل صوت القراءة المحفوظ للغة (بما فيه تفضيلات التحويل/اللغة)
        val fromConvert = getVoiceForLanguage(languageTag)
            ?: getVoiceForLanguage(norm)
        val engine = resolveLanguageEngineForRead(languageTag)
        val byEngine = getPreferredVoiceForEngine(engine, norm)
            ?: getPreferredVoiceForEngine(engine, languageTag)
        val direct = byEngine
            ?: prefs.getString("preferred_voice_$languageTag", null)
            ?: prefs.getString("preferred_voice_$norm", null)
            ?: prefs.getString("primary_voice_$languageTag", null)
            ?: prefs.getString("primary_voice_$norm", null)
            ?: fromConvert
        return normalizeVoiceId(direct)
    }
    override fun setPreferredVoiceId(languageTag: String, voiceId: String) {
        val key = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        val engine = resolveLanguageEngineForRead(languageTag)
        val safeId = normalizeVoiceId(voiceId) ?: voiceId
        val editor = prefs.edit()
            .putString("preferred_voice_$key", safeId)
            .putString("preferred_voice_${engine}_$key", safeId)
        if (engine == ENGINE_LORD_TTS) {
            editor.putString("primary_voice_$key", safeId)
        }
        editor.apply()
    }
    override fun clearPreferredVoiceId(languageTag: String) {
        val key = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        val engine = resolveLanguageEngineForRead(languageTag)
        val editor = prefs.edit()
            .remove("preferred_voice_$key")
            .remove("primary_voice_$key")
            .remove("preferred_voice_${engine}_$key")
            .remove("preferred_voice_${ENGINE_LORD_TTS}_$key")
        editor.apply()
    }
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

    fun getSmsReadingPitchOrDefault(
        languageTag: String
    ): Float = getCategoryPitchOrFallback(
        "sms_reading_pitch", languageTag
    )

    fun setSmsReadingPitch(pitch: Float) =
        setCategoryPitch("sms_reading_pitch", pitch)

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

    /** مستوىُ صوت نطق الساعة **أثناء المكالمات** وحده (شريطٌ مستقلّ
     *  بقرار المدير، افتراضُه 100% ولا يتبع مستوى فئة الساعة). يُستبدل
     *  به مستوىُ فئة الساعة في [TimeAnnouncementManager] حصراً حين يكون
     *  المشهدُ مكالمةً نشطة. */
    fun getTimeDuringCallsVolume(): Float =
        prefs.getFloat("time_during_calls_volume", 1.0f)

    fun setTimeDuringCallsVolume(volume: Float) =
        prefs.edit()
            .putFloat("time_during_calls_volume", volume.coerceIn(0f, 1f))
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
        val editor = prefs.edit().clear()
        for (key in MIGRATION_KEYS) {
            editor.putBoolean(key, true)
        }
        editor.apply()
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
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.N
            ) {
                appContext.createDeviceProtectedStorageContext()
                    .deleteSharedPreferences(BOOT_MIRROR_PREFS)
            }
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

    override fun getDictionaryStandaloneMaxLength(): Int =
        prefs.getInt(KEY_DICTIONARY_STANDALONE_MAX_LENGTH, 1).coerceIn(0, 3)

    override fun setDictionaryStandaloneMaxLength(len: Int) {
        val safe = len.coerceIn(0, 3)
        prefs.edit().putInt(KEY_DICTIONARY_STANDALONE_MAX_LENGTH, safe).commit()
    }

    override fun isDictionaryCaseInsensitive(): Boolean =
        prefs.getBoolean(KEY_DICTIONARY_CASE_INSENSITIVE, true)

    fun setDictionaryCaseInsensitive(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DICTIONARY_CASE_INSENSITIVE, enabled)
            .commit()
    }

    override fun getTimeSuffixMMode(): String =
        prefs.getString(KEY_TIME_SUFFIX_M_MODE, TIME_SUFFIX_M_CONTEXT)
            ?: TIME_SUFFIX_M_CONTEXT

    fun setTimeSuffixMMode(mode: String) {
        val safe = if (mode in setOf(
                TIME_SUFFIX_M_CONTEXT,
                TIME_SUFFIX_M_LETTER,
                TIME_SUFFIX_M_EVENING
            )
        ) mode else TIME_SUFFIX_M_CONTEXT
        prefs.edit().putString(KEY_TIME_SUFFIX_M_MODE, safe).commit()
    }

    fun isPhoneRecordAutoSpeakerEnabled(): Boolean =
        prefs.getBoolean(KEY_PHONE_RECORD_AUTO_SPEAKER, false)

    fun setPhoneRecordAutoSpeakerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_RECORD_AUTO_SPEAKER, enabled).commit()
    }

    override fun getEmojiPronunciationLanguage(): String {
        val raw = prefs.getString(KEY_EMOJI_PRONUNCIATION_LANGUAGE, "AUTO")
            ?: "AUTO"
        return when (raw.uppercase()) {
            "AR" -> "AR"
            "EN" -> "EN"
            else -> "AUTO"
        }
    }

    override fun setEmojiPronunciationLanguage(lang: String) {
        val safe = when (lang.uppercase()) {
            "AR" -> "AR"
            "EN" -> "EN"
            else -> "AUTO"
        }
        prefs.edit().putString(KEY_EMOJI_PRONUNCIATION_LANGUAGE, safe).apply()
    }

    override fun isLanguageDiagnosticsEnabled(): Boolean =
        prefs.getBoolean(KEY_LANGUAGE_DIAGNOSTICS_ENABLED, false)

    override fun setLanguageDiagnosticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(
            KEY_LANGUAGE_DIAGNOSTICS_ENABLED, enabled
        ).apply()
    }

    override fun isDayPeriodExplicitNunEnabled(): Boolean =
        prefs.getBoolean(KEY_DAY_PERIOD_EXPLICIT_NUN, true)

    override fun setDayPeriodExplicitNunEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(
            KEY_DAY_PERIOD_EXPLICIT_NUN, enabled
        ).apply()
    }

    override fun getDayPeriodStyle(): String =
        prefs.getString(
            KEY_DAY_PERIOD_STYLE,
            DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA
        ) ?: DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA

    override fun setDayPeriodStyle(style: String) {
        prefs.edit().putString(KEY_DAY_PERIOD_STYLE, style).apply()
    }

    override fun getIncomingCallUiMode(): String =
        prefs.getString(
            KEY_INCOMING_CALL_UI_MODE,
            INCOMING_CALL_UI_AUTO
        ) ?: INCOMING_CALL_UI_AUTO

    override fun setIncomingCallUiMode(mode: String) {
        prefs.edit().putString(KEY_INCOMING_CALL_UI_MODE, mode).apply()
    }

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

    // ============ GitHub PAT للتحديث من مستودع خاص ============

    /**
     * مفتاح الـ PAT المشفر (GitHub Personal Access Token) — يُخزّن مشفراً
     * عبر EncryptedSharedPreferences (Android Keystore) لأن صلاحيته حساسة.
     * يستخدمه [UpdateChecker] لإضافة `Authorization: Bearer <token>`
     * عند طلب GitHub Releases API للمستودع الخاص.
     */
    override fun getGitHubUpdatePat(): String? =
        getEncryptedPrefs()?.getString("github_update_pat", null)

    override fun setGitHubUpdatePat(token: String?) {
        val encrypted = getOrCreateEncryptedPrefs()
        if (encrypted != null) {
            if (token.isNullOrBlank()) {
                encrypted.edit().remove("github_update_pat").apply()
            } else {
                encrypted.edit()
                    .putString("github_update_pat", token.trim())
                    .apply()
            }
        }
    }

    override fun getActiveUpdateDownloadId(): Long =
        prefs.getLong("active_update_download_id", -1L)

    override fun getActiveUpdateApkUrl(): String? =
        prefs.getString("active_update_apk_url", null)

    override fun getActiveUpdateExpectedSha256(): String? =
        prefs.getString("active_update_expected_sha256", null)

    override fun setActiveUpdateDownload(
        downloadId: Long,
        apkUrl: String,
        expectedSha256: String?
    ) {
        prefs.edit()
            .putLong("active_update_download_id", downloadId)
            .putString("active_update_apk_url", apkUrl)
            .putString("active_update_expected_sha256", expectedSha256)
            .apply()
    }

    override fun clearActiveUpdateDownload() {
        prefs.edit()
            .remove("active_update_download_id")
            .remove("active_update_apk_url")
            .remove("active_update_expected_sha256")
            .apply()
    }

    /** يفتح أو ينشئ التخزين المشفر للـ PAT. */
    @Volatile
    private var patEncryptedPrefs: SharedPreferences? = null

    private fun getEncryptedPrefs(): SharedPreferences? {
        val cached = patEncryptedPrefs
        if (cached != null) return cached
        synchronized(this) {
            patEncryptedPrefs?.let { return it }
            return openOrCreateEncryptedPrefs()
        }
    }

    private fun getOrCreateEncryptedPrefs(): SharedPreferences? {
        val cached = patEncryptedPrefs
        if (cached != null) return cached
        synchronized(this) {
            patEncryptedPrefs?.let { return it }
            return openOrCreateEncryptedPrefs()
        }
    }

    private fun openOrCreateEncryptedPrefs(): SharedPreferences? {
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
                        appContext, "nateq_pat_store", masterKey,
                        androidx.security.crypto.EncryptedSharedPreferences
                            .PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences
                            .PrefValueEncryptionScheme.AES256_GCM
                    ).also { patEncryptedPrefs = it }
            } catch (e: Throwable) {
                if (attempt == 1) {
                    Log.w(TAG, "تعذر فتح التخزين المشفر للـ PAT", e)
                    return null
                }
            }
        }
        return null
    }

    // ==== جملتا إعلان المتصل (قبل / بعد) وقالب الرسائل (مفوّض) ====
    private val announcementTemplateDelegate by lazy {
        SettingsAnnouncementTemplateDelegate(this)
    }

    /** تفعيل/تعطيل البادئة النصية قبل اسم المتصل. */
    override fun isCallerPrefixEnabled(): Boolean =
        announcementTemplateDelegate.isCallerPrefixEnabled()

    override fun setCallerPrefixEnabled(enabled: Boolean) =
        announcementTemplateDelegate.setCallerPrefixEnabled(enabled)

    override fun getCallerPrefixText(): String =
        announcementTemplateDelegate.getCallerPrefixText()

    override fun setCallerPrefixText(text: String?) =
        announcementTemplateDelegate.setCallerPrefixText(text)

    override fun isCallerSuffixEnabled(): Boolean =
        announcementTemplateDelegate.isCallerSuffixEnabled()

    override fun setCallerSuffixEnabled(enabled: Boolean) =
        announcementTemplateDelegate.setCallerSuffixEnabled(enabled)

    override fun getCallerSuffixText(): String =
        announcementTemplateDelegate.getCallerSuffixText()

    override fun setCallerSuffixText(text: String?) =
        announcementTemplateDelegate.setCallerSuffixText(text)

    /** قالب إعلان الرسائل: يدعم {name} و{message}. */
    override fun getSmsAnnouncementTemplate(): String =
        announcementTemplateDelegate.getSmsAnnouncementTemplate()

    override fun setSmsAnnouncementTemplate(template: String?) =
        announcementTemplateDelegate.setSmsAnnouncementTemplate(template)

    // ============ إعدادات إعلان الوقت ============

    /**
     * محرك اللغة الفعلي للقراءة/الحفظ (أو LORD_TTS) دون السقوط لمحرك DEFAULT.
     */
    private fun resolveLanguageEngineForRead(languageTag: String): String {
        val engine = getEngineForLanguage(languageTag)
            ?.takeIf {
                it.isNotBlank() &&
                    it != VOICE_CATEGORY_DEFAULT &&
                    it != "default"
            }
        return if (engine.isNullOrBlank()) ENGINE_LORD_TTS else engine
    }

    /**
     * الصوت المفضّل لكل فئة (category -> voiceId). يُقرأ مفتاح
     * (المحرك، الفئة) أولاً ثم المرآة القديمة بترتيب واحد ثابت.
     */
    override fun getPreferredVoiceIdForCategory(category: String): String? {
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(category)
        val engine = getEngineForCategory(category)
        val byEngine = if (engine.isNullOrBlank()) {
            null
        } else {
            prefs.getString("preferred_voice_${engine}_$norm", null)
        }
        val mirrored = prefs.getString("preferred_voice_$category", null)
        val raw = if (!byEngine.isNullOrBlank()) byEngine else mirrored
        return normalizeVoiceId(raw)?.takeIf { it.isNotBlank() }
    }

    override fun setPreferredVoiceIdForCategory(
        category: String,
        voiceId: String
    ) {
        val engine = getEngineForCategory(category)
        if (voiceId.isBlank()) {
            val editor = prefs.edit().remove("preferred_voice_$category")
            if (!engine.isNullOrBlank()) {
                val norm = ConvertPreferencesCodec
                    .normalizeLanguageTag(category)
                editor.remove("preferred_voice_${engine}_$norm")
            }
            editor.apply()
            return
        }
        if (!engine.isNullOrBlank() &&
            !VoiceIdContract.isVoiceBelongingToEngine(engine, voiceId)
        ) {
            // لا مسح خام ولا كتابة خاطئة: القيمة المحفوظة تبقى كما هي.
            Log.w(
                TAG,
                "الصوت $voiceId لا يتبع المحرك $engine للفئة $category — " +
                    "الامتناع عن الكتابة الخاطئة بلا تعديل"
            )
            return
        }
        val isLegacyDefault = category == VOICE_CATEGORY_DEFAULT
        if (!engine.isNullOrBlank() &&
            engine != VOICE_CATEGORY_DEFAULT &&
            engine != "default"
        ) {
            setPreferredVoiceForEngine(engine, category, voiceId)
        } else if (isLegacyDefault) {
            // مسار ترحيل/تخزين قديم فقط — بلا تأثير خفي على فئات نشطة.
            setPreferredVoiceForEngine(
                ENGINE_LORD_TTS, category, voiceId
            )
        }
        // مرآة للفئات القديمة؛ مفتاح المحرك/الفئة هو الأساس.
        prefs.edit().putString(
            "preferred_voice_$category", voiceId
        ).apply()
    }

    /** يُحدِّث اسم الصوت المحفوظ في كل الفئات التي تستخدم الاسم القديم */
    fun updateSavedVoiceNameIfNeeded(oldName: String, newName: String) {
        if (oldName == newName) return
        val categories = ALL_VOICE_CATEGORIES +
            listOf(VOICE_CATEGORY_EMOJI, VOICE_CATEGORY_DEFAULT)
        for (cat in categories) {
            val saved = getPreferredVoiceIdForCategory(cat)
            if (saved == oldName) {
                setPreferredVoiceIdForCategory(cat, newName)
            }
        }
        // الأصوات المربوطة باللغة وتفضيلات التحويل/اللغة
        val langs = linkedSetOf(
            "ar", "en", "ar-SA", "ar-EG", "ar-AE"
        )
        runCatching { allConvertLanguagePreferences().keys }
            .getOrDefault(emptySet()).forEach { langs.add(it) }
        for (lang in langs) {
            val saved = getPreferredVoiceId(lang)
            if (saved == oldName) {
                setPreferredVoiceId(lang, newName)
            }
            val pref = runCatching {
                getEnginePreferenceForLanguage(lang)
            }.getOrNull()
            if (pref?.voiceName == oldName) {
                runCatching {
                    setEnginePreferenceForLanguage(
                        lang,
                        pref.engine,
                        newName,
                        pref.rate,
                        pref.pitch,
                        pref.volume
                    )
                }
            }
        }
        Log.i(
            TAG,
            "Updated voice name across prefs: $oldName → $newName"
        )
    }
    /** لغة الصوت المختارة لكل فئة (category -> رمز ISO) — تقيّد قائمة
     *  الأصوات وتُحسم لغة النطق الفعلية لها. */
    fun getLanguageForCategory(category: String): String? =
        prefs.getString("language_for_$category", null)
    fun setLanguageForCategory(category: String, language: String?) =
        prefs.edit().putString("language_for_$category", language).apply()

    /** محرك النطق الخاص بفئةٍ معيّنة (متصل/بطارية/وقت…)، null = تلقائي
     *  (يتبع محرك اللغة ثم المحرك المختار العام). يُخزَّن تحت
     *  `engine_for_<category>` ليستقل كل إعلانٍ بمحركه. */
    override fun getEngineForCategory(category: String): String? {
        val engine = prefs.getString("engine_for_$category", null)
        if (!engine.isNullOrBlank() && !VisibleEngines.isVisible(engine)) {
            return null
        }
        return engine
    }
    override fun setEngineForCategory(category: String, engine: String?) {
        val normalized = engine
            ?.takeIf {
                it.isNotBlank() &&
                    it != VOICE_CATEGORY_DEFAULT &&
                    it != "default"
            }
        val storedOld = prefs.getString("engine_for_$category", null)
        val oldEngine = storedOld?.takeIf { VisibleEngines.isVisible(it) }
        val changed = !storedOld.isNullOrBlank() &&
            storedOld != normalized
        val editor = prefs.edit()
        if (normalized.isNullOrBlank()) {
            editor.remove("engine_for_$category")
        } else {
            editor.putString("engine_for_$category", normalized)
        }
        if (changed) {
            // مسح الصوت المحفوظ للفئة تلقائياً عند تغيير المحرك
            val currentVoice = getPreferredVoiceIdForCategory(category)
            if (!currentVoice.isNullOrBlank() &&
                !VoiceIdContract.isVoiceBelongingToEngine(
                    normalized, currentVoice
                )
            ) {
                editor.remove("preferred_voice_$category")
                if (!storedOld.isNullOrBlank()) {
                    val oldNorm = ConvertPreferencesCodec
                        .normalizeLanguageTag(category)
                    editor.remove(
                        "preferred_voice_${storedOld}_$oldNorm"
                    )
                }
            }
            // محاولة استعادة صوت محفوظ للمحرك الجديد
            if (!normalized.isNullOrBlank()) {
                val savedVoice = getPreferredVoiceForEngine(
                    normalized, category
                )
                if (!savedVoice.isNullOrBlank()) {
                    editor.putString(
                        "preferred_voice_$category", savedVoice
                    )
                }
            }
        }
        editor.apply()
    }
    /** سرعة النطق لكل فئة */
    override fun getSpeechRateForCategory(category: String): Float =
        if (category == VOICE_CATEGORY_DEFAULT) {
            // مسار ترحيل/تخزين قديم فقط: تتبع الأشرطة العامة.
            getDefaultSpeechRate()
        } else if (hasExplicitSpeechRateForCategory(category)) {
            prefs.getFloat(
                "speech_rate_$category", getDefaultSpeechRate()
            )
        } else {
            // الفئات بلا تجاوز صريح تتبع العام.
            getDefaultSpeechRate()
        }

    override fun setSpeechRateForCategory(category: String, rate: Float) {
        val clamped = rate.coerceIn(0f, 2f)
        prefs.edit().putFloat("speech_rate_$category", clamped).apply()
    }

    /** نبرة الصوت لكل فئة */
    override fun getPitchForCategory(category: String): Float =
        if (category == VOICE_CATEGORY_DEFAULT) {
            getDefaultPitch()
        } else if (hasExplicitPitchForCategory(category)) {
            prefs.getFloat("pitch_$category", getDefaultPitch())
        } else {
            getDefaultPitch()
        }

    override fun setPitchForCategory(category: String, pitch: Float) {
        val clamped = pitch.coerceIn(0f, 2f)
        prefs.edit().putFloat("pitch_$category", clamped).apply()
    }

    /** مستوى الصوت لكل فئة */
    override fun getVolumeForCategory(category: String): Float =
        if (category == VOICE_CATEGORY_DEFAULT) {
            getDefaultVolume()
        } else if (hasExplicitVolumeForCategory(category)) {
            prefs.getFloat("volume_$category", getDefaultVolume())
        } else {
            getDefaultVolume()
        }

    override fun setVolumeForCategory(category: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs.edit().putFloat("volume_$category", clamped).apply()
    }

    override fun hasExplicitSpeechRateForCategory(category: String): Boolean =
        prefs.contains("speech_rate_$category")

    override fun hasExplicitPitchForCategory(category: String): Boolean =
        prefs.contains("pitch_$category")

    override fun hasExplicitVolumeForCategory(category: String): Boolean =
        prefs.contains("volume_$category")

    override fun getEmergencyEngine(): String? =
        prefs.getString(KEY_EMERGENCY_ENGINE, null)

    override fun setEmergencyEngine(engine: String?) {
        if (engine.isNullOrBlank()) {
            prefs.edit().remove(KEY_EMERGENCY_ENGINE).apply()
        } else {
            prefs.edit().putString(KEY_EMERGENCY_ENGINE, engine).apply()
        }
    }

    override fun getEmergencyVoice(): String? =
        prefs.getString(KEY_EMERGENCY_VOICE, null)

    override fun setEmergencyVoice(voice: String?) {
        if (voice.isNullOrBlank()) {
            prefs.edit().remove(KEY_EMERGENCY_VOICE).apply()
        } else {
            prefs.edit().putString(KEY_EMERGENCY_VOICE, voice).apply()
        }
    }

    override fun getEmergencyRate(): Float =
        prefs.getFloat(KEY_EMERGENCY_RATE, 1.0f).coerceIn(0.5f, 3.0f)

    override fun setEmergencyRate(rate: Float) {
        prefs.edit()
            .putFloat(KEY_EMERGENCY_RATE, rate.coerceIn(0.5f, 3.0f))
            .apply()
    }

    override fun getEmergencyPitch(): Float =
        prefs.getFloat(KEY_EMERGENCY_PITCH, 1.0f).coerceIn(0.5f, 2.0f)

    override fun setEmergencyPitch(pitch: Float) {
        prefs.edit()
            .putFloat(KEY_EMERGENCY_PITCH, pitch.coerceIn(0.5f, 2.0f))
            .apply()
    }

    override fun getEmergencyVolume(): Float =
        prefs.getFloat(KEY_EMERGENCY_VOLUME, 1.0f).coerceIn(0.0f, 1.0f)

    override fun setEmergencyVolume(volume: Float) {
        prefs.edit()
            .putFloat(KEY_EMERGENCY_VOLUME, volume.coerceIn(0.0f, 1.0f))
            .apply()
    }

    override fun isEmergencySaved(): Boolean =
        prefs.getBoolean(KEY_EMERGENCY_SAVED, false)

    override fun setEmergencySaved(saved: Boolean) {
        prefs.edit().putBoolean(KEY_EMERGENCY_SAVED, saved).apply()
    }

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

    private fun quietMaskKey(day: Int): String {
        val d = day.coerceIn(1, 7)
        return "time_quiet_day${d}_mask"
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

    /**
     * ترحيل إعدادات الهدوء القديمة إلى قناع 24 خانة اختيار (بتات 1..24).
     * يمنع تسرب منتصف الليل (هدوء الجمعة لا يُسكت صباح السبت).
     */
    fun migrateQuietHoursToBitmaskIfNeeded() {
        if (prefs.getBoolean(KEY_QUIET_BITMASK_MIGRATED, false)) return
        migrateQuietScheduleIfNeeded()
        val edit = prefs.edit()
        for (day in 1..7) {
            val enabled = prefs.getBoolean(
                "time_quiet_day${day}_enabled",
                false
            )
            if (!enabled) {
                edit.putInt(quietMaskKey(day), QUIET_HOUR_EMPTY_MASK)
            } else {
                val start = prefs.getInt(quietDayKey(day, true), 23)
                    .coerceIn(0, 23)
                val end = prefs.getInt(quietDayKey(day, false), 7)
                    .coerceIn(0, 23)
                var mask = 0
                if (start == 0 && end == 0) {
                    mask = 0
                } else if (start <= end) {
                    val s = if (start == 0) 24 else start
                    val e = if (end == 0) 24 else end
                    if (s <= e) {
                        for (h in s..e) {
                            mask = mask or (1 shl (h - 1))
                        }
                    }
                } else {
                    val s = if (start == 0) 24 else start
                    for (h in s..24) {
                        mask = mask or (1 shl (h - 1))
                    }
                    val e = if (end == 0) 24 else end
                    for (h in 1..e) {
                        mask = mask or (1 shl (h - 1))
                    }
                }
                edit.putInt(quietMaskKey(day), mask and QUIET_HOUR_FULL_MASK)
            }
        }
        edit.putBoolean(KEY_QUIET_BITMASK_MIGRATED, true).apply()
    }

    override fun getQuietHoursBitmaskForDay(day: Int): Int {
        migrateQuietHoursToBitmaskIfNeeded()
        val d = day.coerceIn(1, 7)
        return prefs.getInt(quietMaskKey(d), QUIET_HOUR_EMPTY_MASK)
    }

    override fun setQuietHoursBitmaskForDay(day: Int, mask: Int) {
        migrateQuietHoursToBitmaskIfNeeded()
        val d = day.coerceIn(1, 7)
        val validMask = mask and QUIET_HOUR_FULL_MASK
        prefs.edit().putInt(quietMaskKey(d), validMask).apply()
        setDayQuietEnabled(d, validMask != 0)
    }

    override fun isHourBitSet(day: Int, hour1To24: Int): Boolean {
        if (hour1To24 !in 1..24) return false
        val mask = getQuietHoursBitmaskForDay(day)
        return (mask and (1 shl (hour1To24 - 1))) != 0
    }

    override fun setHourBit(day: Int, hour1To24: Int, isSilent: Boolean) {
        if (hour1To24 !in 1..24) return
        val current = getQuietHoursBitmaskForDay(day)
        val bit = 1 shl (hour1To24 - 1)
        val updated = if (isSilent) current or bit else current and bit.inv()
        setQuietHoursBitmaskForDay(day, updated)
    }

    override fun isHourQuiet(dayOfWeek: Int, calendarHour: Int): Boolean {
        val d = dayOfWeek.coerceIn(1, 7)
        return if (calendarHour == 0) {
            val yesterday = if (d == 1) 7 else d - 1
            if (!isDayQuietEnabled(yesterday)) false
            else isHourBitSet(yesterday, 24)
        } else if (calendarHour == 24) {
            if (!isDayQuietEnabled(d)) false
            else isHourBitSet(d, 24)
        } else {
            if (!isDayQuietEnabled(d)) false
            else isHourBitSet(d, calendarHour)
        }
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

    private fun syncBitmaskFromStartEnd(day: Int) {
        val d = day.coerceIn(1, 7)
        val start = prefs.getInt(quietDayKey(d, true), 23).coerceIn(0, 23)
        val end = prefs.getInt(quietDayKey(d, false), 7).coerceIn(0, 23)
        var mask = 0
        if (start == 0 && end == 0) {
            mask = 0
        } else if (start <= end) {
            val s = if (start == 0) 24 else start
            val e = if (end == 0) 24 else end
            if (s <= e) {
                for (h in s..e) {
                    mask = mask or (1 shl (h - 1))
                }
            }
        } else {
            val s = if (start == 0) 24 else start
            for (h in s..24) {
                mask = mask or (1 shl (h - 1))
            }
            val e = if (end == 0) 24 else end
            for (h in 1..e) {
                mask = mask or (1 shl (h - 1))
            }
        }
        val validMask = mask and QUIET_HOUR_FULL_MASK
        prefs.edit()
            .putInt(quietMaskKey(d), validMask)
            .putBoolean(KEY_QUIET_BITMASK_MIGRATED, true)
            .apply()
    }

    override fun setQuietStartForDay(day: Int, hour: Int) {
        val d = day.coerceIn(1, 7)
        prefs.edit()
            .putInt(quietDayKey(d, true), hour.coerceIn(0, 23))
            .apply()
        syncBitmaskFromStartEnd(d)
    }

    override fun setQuietEndForDay(day: Int, hour: Int) {
        val d = day.coerceIn(1, 7)
        prefs.edit()
            .putInt(quietDayKey(d, false), hour.coerceIn(0, 23))
            .apply()
        syncBitmaskFromStartEnd(d)
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
        if (enabled && getQuietHoursBitmaskForDay(d) == 0) {
            syncBitmaskFromStartEnd(d)
        }
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

    // ============ قراءة النصوص ومعالجة/تحويل النص (مفوّض) ============
    private val textProcessingDelegate by lazy {
        SettingsTextProcessingDelegate(this)
    }

    /** مستوى نطق علامات الترقيم (0 بدون، 1 بعضها، 2 كلها). */
    override fun getPunctuationLevel(): Int =
        textProcessingDelegate.getPunctuationLevel()

    override fun setPunctuationLevel(level: Int) =
        textProcessingDelegate.setPunctuationLevel(level)

    /** الحفاظ على التشكيل عند تمرير النص للمحرّك. */
    override fun isTashkeelPreserved(): Boolean =
        textProcessingDelegate.isTashkeelPreserved()

    override fun setTashkeelPreserved(enabled: Boolean) =
        textProcessingDelegate.setTashkeelPreserved(enabled)

    override fun getSingleLetterMode(): String =
        textProcessingDelegate.getSingleLetterMode()

    override fun setSingleLetterMode(mode: String) =
        textProcessingDelegate.setSingleLetterMode(mode)

    override fun isNumberVoiceSpelledWordsEnabled(): Boolean =
        textProcessingDelegate.isNumberVoiceSpelledWordsEnabled()

    override fun setNumberVoiceSpelledWordsEnabled(enabled: Boolean) =
        textProcessingDelegate.setNumberVoiceSpelledWordsEnabled(enabled)

    override fun isTimeConversionEnabled(): Boolean =
        textProcessingDelegate.isTimeConversionEnabled()

    override fun setTimeConversionEnabled(enabled: Boolean) =
        textProcessingDelegate.setTimeConversionEnabled(enabled)

    override fun isCurrencyConversionEnabled(): Boolean =
        textProcessingDelegate.isCurrencyConversionEnabled()

    override fun setCurrencyConversionEnabled(enabled: Boolean) =
        textProcessingDelegate.setCurrencyConversionEnabled(enabled)

    override fun isUnitConversionEnabled(): Boolean =
        textProcessingDelegate.isUnitConversionEnabled()

    override fun setUnitConversionEnabled(enabled: Boolean) =
        textProcessingDelegate.setUnitConversionEnabled(enabled)

    override fun isSymbolConversionEnabled(): Boolean =
        textProcessingDelegate.isSymbolConversionEnabled()

    override fun setSymbolConversionEnabled(enabled: Boolean) =
        textProcessingDelegate.setSymbolConversionEnabled(enabled)

    override fun isPhoneConversionEnabled(): Boolean =
        textProcessingDelegate.isPhoneConversionEnabled()

    override fun setPhoneConversionEnabled(enabled: Boolean) =
        textProcessingDelegate.setPhoneConversionEnabled(enabled)

    override fun isArabicNormalizationEnabled(): Boolean =
        textProcessingDelegate.isArabicNormalizationEnabled()

    override fun setArabicNormalizationEnabled(enabled: Boolean) =
        textProcessingDelegate.setArabicNormalizationEnabled(enabled)

    /** اختصار الروابط الطويلة (ميزة وصولية اختيارية). */
    override fun isUrlShorteningEnabled(): Boolean =
        textProcessingDelegate.isUrlShorteningEnabled()

    /** اتباع سرعة القارئ عند مستوى صوت 100. */
    override fun isFollowReaderRateEnabled(): Boolean =
        textProcessingDelegate.isFollowReaderRateEnabled()

    override fun setFollowReaderRateEnabled(enabled: Boolean) =
        textProcessingDelegate.setFollowReaderRateEnabled(enabled)

    /** تسريع النص الطويل تلقائياً (اختياري). */
    override fun isLongTextSpeedupEnabled(): Boolean =
        textProcessingDelegate.isLongTextSpeedupEnabled()

    override fun setLongTextSpeedupEnabled(enabled: Boolean) =
        textProcessingDelegate.setLongTextSpeedupEnabled(enabled)

    /** معالجة مصرية/تشكيل تلقائي خفيف للنص العربي (افتراضي مقفول). */
    override fun isEgyptianSpeechProcessingEnabled(): Boolean =
        textProcessingDelegate.isEgyptianSpeechProcessingEnabled()

    override fun setEgyptianSpeechProcessingEnabled(enabled: Boolean) =
        textProcessingDelegate.setEgyptianSpeechProcessingEnabled(enabled)

    /** سرعة ديناميكية حسب طول الجملة/نوع المحتوى (افتراضي مقفول). */
    override fun isDynamicSpeedEnabled(): Boolean =
        textProcessingDelegate.isDynamicSpeedEnabled()

    override fun setDynamicSpeedEnabled(enabled: Boolean) =
        textProcessingDelegate.setDynamicSpeedEnabled(enabled)

    /** قارئ متعدد الأصوات للحوار/السرد (افتراضي مقفول). */
    override fun isMultiVoiceReaderEnabled(): Boolean =
        textProcessingDelegate.isMultiVoiceReaderEnabled()

    override fun setMultiVoiceReaderEnabled(enabled: Boolean) =
        textProcessingDelegate.setMultiVoiceReaderEnabled(enabled)

    // ============ الإسكات الفوري: الهز والتقارب (مفوّض) ============
    private val instantSilenceDelegate by lazy {
        SettingsInstantSilenceDelegate(this)
    }

    /** إيقاف النطق فوراً عند هز الجهاز. معطّل افتراضياً. */
    override fun isShakeToStopEnabled(): Boolean =
        instantSilenceDelegate.isShakeToStopEnabled()

    override fun setShakeToStopEnabled(enabled: Boolean) =
        instantSilenceDelegate.setShakeToStopEnabled(enabled)

    /** تقريب الهاتف (يد/جيب) يوقف النطق فوراً. معطّل افتراضياً. */
    override fun isProximitySilenceEnabled(): Boolean =
        instantSilenceDelegate.isProximitySilenceEnabled()

    override fun setProximitySilenceEnabled(enabled: Boolean) =
        instantSilenceDelegate.setProximitySilenceEnabled(enabled)

    /**
     * المراقبة الاستباقية للتطبيقات المحمية (UsageStats). معطّلة
     * افتراضياً: تستهلك طاقةً إضافيةً دون حاجة واضحة لمعظم المستخدمين.
     */
    override fun isProactiveProtectedWatchEnabled(): Boolean =
        instantSilenceDelegate.isProactiveProtectedWatchEnabled()

    override fun setProactiveProtectedWatchEnabled(enabled: Boolean) =
        instantSilenceDelegate.setProactiveProtectedWatchEnabled(enabled)

    // ============ الإعدادات العامة (مفوّض) ============
    private val generalDelegate by lazy {
        SettingsGeneralDelegate(this)
    }

    /** السرعة الافتراضية للإعلانات */
    override fun getDefaultSpeechRate(): Float =
        generalDelegate.getDefaultSpeechRate()

    override fun setDefaultSpeechRate(rate: Float) {
        generalDelegate.setDefaultSpeechRate(rate)
    }

    /** النبرة الافتراضية للإعلانات */
    override fun getDefaultPitch(): Float =
        generalDelegate.getDefaultPitch()

    override fun setDefaultPitch(pitch: Float) {
        generalDelegate.setDefaultPitch(pitch)
    }

    /** مستوى الصوت الافتراضي للإعلانات */
    override fun getDefaultVolume(): Float =
        generalDelegate.getDefaultVolume()

    override fun setDefaultVolume(volume: Float) {
        generalDelegate.setDefaultVolume(volume)
    }

    /** مستوى توسيع الصوت */
    override fun getAudioExpansionLevel(): Int =
        generalDelegate.getAudioExpansionLevel()

    override fun setAudioExpansionLevel(level: Int) {
        generalDelegate.setAudioExpansionLevel(level)
    }

    override fun isAudioReverbEnabled(): Boolean =
        generalDelegate.isAudioReverbEnabled()

    override fun setAudioReverbEnabled(enabled: Boolean) {
        generalDelegate.setAudioReverbEnabled(enabled)
    }

    override fun getAudioEffectsDisabledCategories(): Set<String> =
        generalDelegate.getAudioEffectsDisabledCategories()

    override fun setAudioEffectsDisabledCategories(
        categories: Set<String>
    ) {
        generalDelegate.setAudioEffectsDisabledCategories(categories)
    }

    override fun isSpeechBoostEnabled(): Boolean =
        generalDelegate.isSpeechBoostEnabled()

    override fun setSpeechBoostEnabled(enabled: Boolean) {
        generalDelegate.setSpeechBoostEnabled(enabled)
    }

    override fun getSpeechBoostValue(): Float =
        generalDelegate.getSpeechBoostValue()

    override fun setSpeechBoostValue(value: Float) {
        generalDelegate.setSpeechBoostValue(value)
    }

    override fun isVolumeBoostEnabled(): Boolean =
        generalDelegate.isVolumeBoostEnabled()

    override fun setVolumeBoostEnabled(enabled: Boolean) {
        generalDelegate.setVolumeBoostEnabled(enabled)
    }

    override fun getVolumeBoostValue(): Float =
        generalDelegate.getVolumeBoostValue()

    override fun setVolumeBoostValue(value: Float) {
        generalDelegate.setVolumeBoostValue(value)
    }

    // ============ صحة الجهاز والبطارية ومؤثرات الرنات (مفوّض) ============
    private val deviceHealthAudioDelegate by lazy {
        SettingsDeviceHealthAudioDelegate(this)
    }

    override fun getDeviceHealthItems(): Set<String> =
        deviceHealthAudioDelegate.getDeviceHealthItems()

    override fun setDeviceHealthItems(items: Set<String>) {
        deviceHealthAudioDelegate.setDeviceHealthItems(items)
    }

    internal fun validDeviceHealthItems(): Set<String> =
        deviceHealthAudioDelegate.validDeviceHealthItems()

    override fun isBatteryAnnouncementEnabled(): Boolean =
        deviceHealthAudioDelegate.isBatteryAnnouncementEnabled()

    override fun setBatteryAnnouncementEnabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setBatteryAnnouncementEnabled(enabled)
    }

    override fun getBatteryAnnouncementLevels(): Set<String> =
        deviceHealthAudioDelegate.getBatteryAnnouncementLevels()

    override fun setBatteryAnnouncementLevels(levels: Set<String>) {
        deviceHealthAudioDelegate.setBatteryAnnouncementLevels(levels)
    }

    override fun getBatteryAnnouncementVoiceId(): String? =
        deviceHealthAudioDelegate.getBatteryAnnouncementVoiceId()

    override fun setBatteryAnnouncementVoiceId(voiceId: String?) {
        deviceHealthAudioDelegate.setBatteryAnnouncementVoiceId(voiceId)
    }

    fun getBatteryAnnouncementLanguage(): String? =
        deviceHealthAudioDelegate.getBatteryAnnouncementLanguage()

    fun setBatteryAnnouncementLanguage(language: String?) {
        deviceHealthAudioDelegate.setBatteryAnnouncementLanguage(language)
    }

    override fun getBatteryAnnouncementRate(): Float =
        deviceHealthAudioDelegate.getBatteryAnnouncementRate()

    override fun setBatteryAnnouncementRate(rate: Float) {
        deviceHealthAudioDelegate.setBatteryAnnouncementRate(rate)
    }

    override fun getBatteryAnnouncementVolume(): Float =
        deviceHealthAudioDelegate.getBatteryAnnouncementVolume()

    override fun setBatteryAnnouncementVolume(volume: Float) {
        deviceHealthAudioDelegate.setBatteryAnnouncementVolume(volume)
    }

    internal fun validTimeChimeSounds(): Set<String> =
        deviceHealthAudioDelegate.validTimeChimeSounds()

    override fun isTimeChimeEnabled(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeEnabled()

    override fun setTimeChimeEnabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeChimeEnabled(enabled)
    }

    override fun isTimeChimeAt0Enabled(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeAt0Enabled()

    override fun setTimeChimeAt0Enabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeChimeAt0Enabled(enabled)
    }

    override fun isTimeChimeAt15Enabled(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeAt15Enabled()

    override fun setTimeChimeAt15Enabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeChimeAt15Enabled(enabled)
    }

    override fun isTimeChimeAt30Enabled(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeAt30Enabled()

    override fun setTimeChimeAt30Enabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeChimeAt30Enabled(enabled)
    }

    override fun isTimeChimeAt45Enabled(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeAt45Enabled()

    override fun setTimeChimeAt45Enabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeChimeAt45Enabled(enabled)
    }

    override fun getTimeChimeSound(): String =
        deviceHealthAudioDelegate.getTimeChimeSound()

    override fun setTimeChimeSound(sound: String) {
        deviceHealthAudioDelegate.setTimeChimeSound(sound)
    }

    override fun getTimeChimeVolume(): Float =
        deviceHealthAudioDelegate.getTimeChimeVolume()

    override fun setTimeChimeVolume(volume: Float) {
        deviceHealthAudioDelegate.setTimeChimeVolume(volume)
    }

    override fun isTimeChimeVolumeFollowsAnnouncement(): Boolean =
        deviceHealthAudioDelegate.isTimeChimeVolumeFollowsAnnouncement()

    override fun setTimeChimeVolumeFollowsAnnouncement(
        follows: Boolean
    ) {
        deviceHealthAudioDelegate
            .setTimeChimeVolumeFollowsAnnouncement(follows)
    }

    override fun getEffectiveTimeChimeVolume(): Float =
        deviceHealthAudioDelegate.getEffectiveTimeChimeVolume()

    override fun getCustomChimeUri(): String =
        deviceHealthAudioDelegate.getCustomChimeUri()

    override fun setCustomChimeUri(uri: String) {
        deviceHealthAudioDelegate.setCustomChimeUri(uri)
    }

    override fun getCustomChimeUri15(): String =
        deviceHealthAudioDelegate.getCustomChimeUri15()

    override fun setCustomChimeUri15(uri: String) {
        deviceHealthAudioDelegate.setCustomChimeUri15(uri)
    }

    override fun getCustomChimeUri30(): String =
        deviceHealthAudioDelegate.getCustomChimeUri30()

    override fun setCustomChimeUri30(uri: String) {
        deviceHealthAudioDelegate.setCustomChimeUri30(uri)
    }

    override fun getCustomChimeUri45(): String =
        deviceHealthAudioDelegate.getCustomChimeUri45()

    override fun setCustomChimeUri45(uri: String) {
        deviceHealthAudioDelegate.setCustomChimeUri45(uri)
    }

    override fun isTimeAlarmMaxPrecisionEnabled(): Boolean =
        deviceHealthAudioDelegate.isTimeAlarmMaxPrecisionEnabled()

    override fun setTimeAlarmMaxPrecisionEnabled(enabled: Boolean) {
        deviceHealthAudioDelegate.setTimeAlarmMaxPrecisionEnabled(enabled)
    }

    override fun getBatteryCueVolume(): Float =
        deviceHealthAudioDelegate.getBatteryCueVolume()

    override fun setBatteryCueVolume(volume: Float) {
        deviceHealthAudioDelegate.setBatteryCueVolume(volume)
    }

    override fun getBatterySoundCueMode(): Int =
        deviceHealthAudioDelegate.getBatterySoundCueMode()

    override fun setBatterySoundCueMode(mode: Int) {
        deviceHealthAudioDelegate.setBatterySoundCueMode(mode)
    }

    // ============ إعدادات إعلان اسم المتصل (مفوّض) ============
    private val callerAnnouncementDelegate by lazy {
        SettingsCallerAnnouncementDelegate(this)
    }

    override fun isCallerAnnouncementEnabled(): Boolean =
        callerAnnouncementDelegate.isCallerAnnouncementEnabled()

    override fun setCallerAnnouncementEnabled(enabled: Boolean) {
        callerAnnouncementDelegate.setCallerAnnouncementEnabled(enabled)
    }

    override fun isCallerLockScreenPrivacyEnabled(): Boolean =
        callerAnnouncementDelegate.isCallerLockScreenPrivacyEnabled()

    override fun setCallerLockScreenPrivacyEnabled(enabled: Boolean) {
        callerAnnouncementDelegate
            .setCallerLockScreenPrivacyEnabled(enabled)
    }

    override fun isCallerAnnouncementDuringCallEnabled(): Boolean =
        callerAnnouncementDelegate
            .isCallerAnnouncementDuringCallEnabled()

    override fun setCallerAnnouncementDuringCallEnabled(
        enabled: Boolean
    ) {
        callerAnnouncementDelegate
            .setCallerAnnouncementDuringCallEnabled(enabled)
    }

    override fun isCallerRingDuckingEnabled(): Boolean =
        callerAnnouncementDelegate.isCallerRingDuckingEnabled()

    override fun setCallerRingDuckingEnabled(enabled: Boolean) {
        callerAnnouncementDelegate.setCallerRingDuckingEnabled(enabled)
    }

    override fun getCallerRingDuckPercent(): Int =
        callerAnnouncementDelegate.getCallerRingDuckPercent()

    override fun setCallerRingDuckPercent(percent: Int) {
        callerAnnouncementDelegate.setCallerRingDuckPercent(percent)
    }

    override fun getCallerAnnouncementRepeat(): Int =
        callerAnnouncementDelegate.getCallerAnnouncementRepeat()

    override fun setCallerAnnouncementRepeat(repeat: Int) {
        callerAnnouncementDelegate.setCallerAnnouncementRepeat(repeat)
    }

    override fun getCallWaitingRepeat(): Int =
        callerAnnouncementDelegate.getCallWaitingRepeat()

    override fun setCallWaitingRepeat(repeat: Int) {
        callerAnnouncementDelegate.setCallWaitingRepeat(repeat)
    }

    override fun getCallerAnnouncementIntervalSeconds(): Int =
        callerAnnouncementDelegate
            .getCallerAnnouncementIntervalSeconds()

    override fun setCallerAnnouncementIntervalSeconds(seconds: Int) {
        callerAnnouncementDelegate
            .setCallerAnnouncementIntervalSeconds(seconds)
    }

    override fun getCallerAnnouncementArabicVoiceId(): String? =
        callerAnnouncementDelegate.getCallerAnnouncementArabicVoiceId()

    override fun setCallerAnnouncementArabicVoiceId(voiceId: String?) {
        callerAnnouncementDelegate
            .setCallerAnnouncementArabicVoiceId(voiceId)
    }

    override fun getCallerAnnouncementEnglishVoiceId(): String? =
        callerAnnouncementDelegate.getCallerAnnouncementEnglishVoiceId()

    override fun setCallerAnnouncementEnglishVoiceId(voiceId: String?) {
        callerAnnouncementDelegate
            .setCallerAnnouncementEnglishVoiceId(voiceId)
    }

    override fun getCallerAnnouncementRate(): Float =
        callerAnnouncementDelegate.getCallerAnnouncementRate()

    override fun setCallerAnnouncementRate(rate: Float) {
        callerAnnouncementDelegate.setCallerAnnouncementRate(rate)
    }

    override fun getCallerAnnouncementVolume(): Float =
        callerAnnouncementDelegate.getCallerAnnouncementVolume()

    override fun setCallerAnnouncementVolume(volume: Float) {
        callerAnnouncementDelegate.setCallerAnnouncementVolume(volume)
    }

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
        val p = readConvertPrefs()[
            ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        ] ?: LanguageSpeechPrefs()
        return if (!p.engine.isNullOrBlank() &&
            !VisibleEngines.isVisible(p.engine)
        ) {
            p.copy(engine = null, voiceName = null)
        } else {
            p
        }
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
        synchronized(prefsLock) {
            val seq = localWriteSeq.incrementAndGet()
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

            val editor = prefs.edit()
            val json = ConvertPreferencesCodec.toJson(map)
            editor.putString(KEY_CONVERT_PREFS_JSON, json)
            val writtenKeys = mutableListOf(KEY_CONVERT_PREFS_JSON)

            if (!engine.isNullOrBlank() && !voiceName.isNullOrBlank()) {
                val prefKey = "preferred_voice_${engine}_$key"
                editor.putString(prefKey, voiceName)
                writtenKeys.add(prefKey)

                val langKey = "preferred_voice_$key"
                editor.putString(langKey, voiceName)
                writtenKeys.add(langKey)
            } else if (engine.isNullOrBlank() && !voiceName.isNullOrBlank()) {
                val prefKey = "preferred_voice_${ENGINE_LORD_TTS}_$key"
                editor.putString(prefKey, voiceName)
                writtenKeys.add(prefKey)

                val langKey = "preferred_voice_$key"
                editor.putString(langKey, voiceName)
                writtenKeys.add(langKey)

                val primaryKey = "primary_voice_$key"
                editor.putString(primaryKey, voiceName)
                writtenKeys.add(primaryKey)
            }

            val ok = editor.commit()
            flushedSeq.set(seq)

            val readBack = getEnginePreferenceForLanguage(languageTag)
            Log.i(
                TAG,
                "[SettingsRepo] setLangPref tag=$languageTag, ok=$ok, " +
                    "keys=$writtenKeys, readBack=$readBack"
            )
        }
    }

    /**
     * محرك النطق الصريح للغة معيّنة (سارٍ في النطق العام
     * بلا ربط بحالة التحويل التلقائي)، null إن لم يُحدَّد.
     */
    override fun getEngineForLanguage(languageTag: String): String? {
        val direct = getEnginePreferenceForLanguage(languageTag).engine
        if (!direct.isNullOrBlank()) return direct
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        if (norm != languageTag) {
            return getEnginePreferenceForLanguage(norm).engine
        }
        return null
    }

    /**
     * صوت المحرك الصريح (داخل محرك اللغة) للغة معيّنة،
     * null إن لم يُحدَّد.
     */
    override fun getVoiceForLanguage(languageTag: String): String? {
        val direct = getEnginePreferenceForLanguage(languageTag).voiceName
        if (!direct.isNullOrBlank()) return direct
        val norm = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        if (norm != languageTag) {
            return getEnginePreferenceForLanguage(norm).voiceName
        }
        return null
    }

    /**
     * كل تفضيلات التحويل الحالية (لغة -> مدخل)
     * للعرض في قائمة الإعدادات.
     */
    override fun allConvertLanguagePreferences():
        Map<String, LanguageSpeechPrefs> {
        ensureConvertSlotsMigrated()
        return readConvertPrefs()
    }

    override fun getPreferredVoiceForEngine(
        engine: String,
        languageTag: String
    ): String? {
        val key = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        val fromKey = prefs.getString("preferred_voice_${engine}_$key", null)
        if (!fromKey.isNullOrBlank()) return normalizeVoiceId(fromKey)
        val direct = prefs.getString(
            "preferred_voice_${engine}_$languageTag",
            null
        )
        return normalizeVoiceId(direct)
    }

    override fun setPreferredVoiceForEngine(
        engine: String,
        languageTag: String,
        voiceName: String?
    ) {
        val key = ConvertPreferencesCodec.normalizeLanguageTag(languageTag)
        if (voiceName.isNullOrBlank()) {
            prefs.edit().remove("preferred_voice_${engine}_$key").apply()
        } else {
            prefs.edit()
                .putString("preferred_voice_${engine}_$key", voiceName)
                .apply()
        }
    }

    private fun readConvertPrefs(): Map<String, LanguageSpeechPrefs> {
        val json = prefs.getString(KEY_CONVERT_PREFS_JSON, null)
        return if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            ConvertPreferencesCodec.fromJson(json)
        }
    }

    /**
     * يعين محركاً موحّداً لجميع اللغات المحددة مع استعادة صوت
     * المحرك المفضل لكل لغة إن وُجد، أو تركه للمحرك ليختار
     * صوته المناسب دون إبقاء صوت قديم.
     */
    fun setEngineForAllLanguages(
        languages: Collection<String>,
        engine: String?
    ) {
        synchronized(prefsLock) {
            val seq = localWriteSeq.incrementAndGet()
            ensureConvertSlotsMigrated()
            val map = readConvertPrefs().toMutableMap()
            val editor = prefs.edit()
            val isVoc = engine == "vocalizer_embedded"
            val isLord = engine.isNullOrBlank()
            val writtenKeys = mutableListOf<String>()

            for (lang in languages) {
                val key = ConvertPreferencesCodec.normalizeLanguageTag(lang)
                val current = map[key] ?: LanguageSpeechPrefs()
                var engineVoice = if (!isLord) {
                    val curLord = prefs.getString("preferred_voice_$key", null)
                    if (!curLord.isNullOrBlank()) {
                        editor.putString("primary_voice_$key", curLord)
                        writtenKeys.add("primary_voice_$key")
                    }
                    getPreferredVoiceForEngine(engine!!, key)
                } else {
                    getPreferredVoiceForEngine(ENGINE_LORD_TTS, key)
                        ?: prefs.getString("primary_voice_$key", null)
                        ?: prefs.getString("preferred_voice_$key", null)
                }
                if (engineVoice.isNullOrBlank() && isVoc) {
                    engineVoice = if (key.equals("en", ignoreCase = true)) {
                        "Samantha"
                    } else {
                        "Laila"
                    }
                }
                val entry = ConvertPreferencesCodec.entryForSave(
                    engine = engine,
                    voiceName = if (isLord) null else engineVoice,
                    rate = current.rate,
                    pitch = current.pitch,
                    volume = current.volume
                )
                if (entry.hasAdjustment) map[key] = entry else map.remove(key)

                if (isLord) {
                    val restoreVoice = engineVoice
                        ?: VoiceIdContract.createId(key)
                    editor.putString("preferred_voice_$key", restoreVoice)
                    writtenKeys.add("preferred_voice_$key")
                } else if (!engineVoice.isNullOrBlank()) {
                    editor.putString("preferred_voice_$key", engineVoice)
                    writtenKeys.add("preferred_voice_$key")
                }
            }
            val json = ConvertPreferencesCodec.toJson(map)
            editor.putString(KEY_CONVERT_PREFS_JSON, json)
            writtenKeys.add(KEY_CONVERT_PREFS_JSON)

            val ok = editor.commit()
            flushedSeq.set(seq)

            Log.i(
                TAG,
                "[SettingsRepo] setEngineForAllLanguages engine=$engine, " +
                    "ok=$ok, keysCount=${writtenKeys.size}"
            )
        }
    }

    private fun writeConvertPrefs(map: Map<String, LanguageSpeechPrefs>) {
        prefs.edit()
            .putString(
                KEY_CONVERT_PREFS_JSON,
                ConvertPreferencesCodec.toJson(map)
            )
            .commit()
    }

    /**
     * ترحيل تلقائي لمرة واحدة من سلوتات «اللغة الأولى/اللغة الثانية» القديمة
     * إلى الخريطة الديناميكية، فلا يفقد من خزّن إعداداته قبل التحديث شيئاً.
     * لا تحذف السلوتات نفسها (تبقى قابلة للقراءة للتوافقية العكسية) — يُمهَّر
     * وسم الترحيل فوراً كحارس حتى لا يتكرر العمل إذا فشل لاحقاً.
     */
    private fun ensureConvertSlotsMigrated() {
        if (prefs.getBoolean(KEY_CONVERT_SLOTS_MIGRATED, false)) return

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
        val editor = prefs.edit().putBoolean(KEY_CONVERT_SLOTS_MIGRATED, true)
        if (map != readConvertPrefs()) {
            editor.putString(
                KEY_CONVERT_PREFS_JSON,
                ConvertPreferencesCodec.toJson(map)
            )
        }
        editor.apply()
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

    /** وضع قراءة الإشعارات: "full"=مفعّل، "off"=معطّل، "source"=مصدر فقط. */
    override fun getNotificationReadingMode(): String =
        prefs.getString("notification_reading_mode", "off") ?: "off"

    /** يعيّن وضع قراءة الإشعارات: "full" | "off" | "source". */
    override fun setNotificationReadingMode(mode: String) =
        prefs.edit().putString("notification_reading_mode", mode).apply()

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

    // ============ النسخ الاحتياطي / الاستعادة (مفوّض) ============
    private val backupRestoreDelegate by lazy {
        SettingsBackupRestoreDelegate(this)
    }

    /** يقرأ كل الإعدادات القابلة للنسخ (لا يضمِّن وسوم ترحيل داخلية). */
    fun exportSettings(): Map<String, Any> =
        backupRestoreDelegate.exportSettings()

    /** يستورد الإعدادات مع تثبيت القيم؛ يُرجع true عند تطبيق أي مفتاح. */
    fun importSettings(map: Map<String, Any>): Boolean =
        backupRestoreDelegate.importSettings(map)


    // ============ محرك TTS الاحتياطي للإعلانات (مفوّض) ============
    private val announcementFallbackDelegate by lazy {
        SettingsAnnouncementFallbackDelegate(this)
    }

    /** مفتاح محرك TTS الاحتياطي للإعلانات (اختياري، مخفى أدوياً). */
    override fun getAnnouncementFallbackEngine(): String? =
        announcementFallbackDelegate.getAnnouncementFallbackEngine()

    /** حفظ محرك TTS الاحتياطي للإعلانات (null = حذف). */
    override fun setAnnouncementFallbackEngine(enginePackage: String?) =
        announcementFallbackDelegate.setAnnouncementFallbackEngine(
            enginePackage
        )

    // ============ إعدادات خدمة إمكانية الوصول وخدمات الشاشة ============

    override fun isAccessibilityFeatureEnabled(featureKey: String): Boolean {
        if (featureKey == "a11y_read_more_expand") {
            return prefs.getBoolean(
                "a11y_read_more_expand",
                prefs.getBoolean("a11y_read_more", false)
            )
        }
        return prefs.getBoolean(featureKey, false)
    }

    override fun setAccessibilityFeatureEnabled(
        featureKey: String,
        enabled: Boolean
    ) {
        val editor = prefs.edit().putBoolean(featureKey, enabled)
        if (featureKey == "a11y_read_more_expand") {
            editor.putBoolean("a11y_read_more", enabled)
        }
        editor.apply()
    }

    /** حفظ الأوصاف التلقائية للعناصر غير المسماة (البند 4). */
    override fun isAutoSaveUnlabeledDescriptionsEnabled(): Boolean =
        prefs.getBoolean("auto_save_unlabeled_descriptions", false)

    override fun setAutoSaveUnlabeledDescriptionsEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("auto_save_unlabeled_descriptions", enabled)
            .apply()
    }

    fun getReadMoreAllowedPackages(): Set<String> {
        val defaultSet = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "com.facebook.orca",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.twitter.android",
            "com.google.android.gm",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.chrome"
        )
        val saved = prefs.getStringSet(KEY_READ_MORE_ALLOWED_PACKAGES, null)
        return saved ?: defaultSet
    }

    fun setReadMoreAllowedPackages(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_READ_MORE_ALLOWED_PACKAGES, packages)
            .apply()
    }

    fun getProtectedApps(): List<ProtectedApp> {
        val raw = prefs.getString(KEY_PROTECTED_APPS, null)
            ?: return DEFAULT_PROTECTED_APPS
        return decodeProtectedApps(raw)
    }

    fun setProtectedApps(apps: List<ProtectedApp>) {
        prefs.edit()
            .putString(KEY_PROTECTED_APPS, encodeProtectedApps(apps))
            .apply()
    }

    fun addProtectedApp(app: ProtectedApp) {
        val list = getProtectedApps()
            .filter { it.packageName != app.packageName }
            .toMutableList()
        list.add(app)
        setProtectedApps(list)
    }

    fun removeProtectedApp(packageName: String) {
        val list = getProtectedApps().filter { it.packageName != packageName }
        setProtectedApps(list)
    }

    fun getProtectedApp(packageName: String): ProtectedApp? {
        return getProtectedApps().firstOrNull { it.packageName == packageName }
    }

    fun isProtectedApp(packageName: String): Boolean {
        return getProtectedApp(packageName) != null
    }

    fun getSuspendedProtectedApp(): String? {
        return prefs.getString(KEY_PROTECTED_SUSPENDED_PKG, null)
    }

    fun setSuspendedProtectedApp(
        pkg: String?,
        timestamp: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString(KEY_PROTECTED_SUSPENDED_PKG, pkg)
            .putLong(
                KEY_PROTECTED_SUSPENDED_TIME,
                if (pkg != null) timestamp else 0L
            )
            .apply()
    }

    fun getSuspendedProtectedAppTimestamp(): Long {
        return prefs.getLong(KEY_PROTECTED_SUSPENDED_TIME, 0L)
    }

    fun getProtectedAppAutoRestoreTimeoutMinutes(): Int {
        val v = prefs.getInt(
            KEY_PROTECTED_APP_AUTO_RESTORE_TIMEOUT_MINUTES, 15
        )
        return v.coerceIn(5, 60)
    }

    fun setProtectedAppAutoRestoreTimeoutMinutes(minutes: Int) {
        val sanitized = minutes.coerceIn(5, 60)
        prefs.edit().putInt(
            KEY_PROTECTED_APP_AUTO_RESTORE_TIMEOUT_MINUTES, sanitized
        ).apply()
    }

    fun isAndroid17BankingHardeningEnabled(): Boolean =
        prefs.getBoolean(KEY_ANDROID17_BANKING_HARDENING, false)

    fun setAndroid17BankingHardeningEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ANDROID17_BANKING_HARDENING, enabled)
            .apply()
    }

    fun getEngineSwitchRecipe(
        model: String = android.os.Build.MODEL,
        sdk: Int = android.os.Build.VERSION.SDK_INT
    ): String? {
        val safeModel = model.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return prefs.getString(
            "engine_switch_recipe_${safeModel}_$sdk", null
        )
    }

    fun setEngineSwitchRecipe(
        model: String = android.os.Build.MODEL,
        sdk: Int = android.os.Build.VERSION.SDK_INT,
        recipe: String?
    ) {
        val safeModel = model.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val key = "engine_switch_recipe_${safeModel}_$sdk"
        if (recipe == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, recipe).apply()
        }
    }

    override fun isA11yPaused(): Boolean =
        prefs.getBoolean("a11y_service_paused", false)

    override fun setA11yPaused(paused: Boolean) =
        prefs.edit().putBoolean("a11y_service_paused", paused).apply()

    override fun getA11yPausedUntil(): Long =
        prefs.getLong("a11y_service_paused_until_epoch", 0L)

    override fun setA11yPausedUntil(untilEpoch: Long) =
        prefs.edit().putLong("a11y_service_paused_until_epoch", untilEpoch)
            .apply()

    override fun getA11yPauseLevel(): Int =
        prefs.getInt("a11y_service_pause_level", 1)

    override fun setA11yPauseLevel(level: Int) =
        prefs.edit().putInt("a11y_service_pause_level", level.coerceIn(1, 2))
            .apply()

    fun setA11yPausedState(paused: Boolean, untilEpoch: Long, level: Int) {
        prefs.edit()
            .putBoolean("a11y_service_paused", paused)
            .putLong("a11y_service_paused_until_epoch", untilEpoch)
            .putInt("a11y_service_pause_level", level.coerceIn(1, 2))
            .apply()
    }

    override fun getA11yAllowedPackages(): Set<String>? =
        prefs.getStringSet("a11y_allowed_packages", null)?.toSet()

    override fun setA11yAllowedPackages(packages: Set<String>?) {
        if (packages == null) {
            prefs.edit().remove("a11y_allowed_packages").apply()
        } else {
            prefs.edit()
                .putStringSet("a11y_allowed_packages", packages.toMutableSet())
                .apply()
        }
    }

    override fun isA11yAllowAllApps(): Boolean =
        prefs.getBoolean("a11y_allow_all_apps", false)

    override fun setA11yAllowAllApps(allowAll: Boolean) =
        prefs.edit().putBoolean("a11y_allow_all_apps", allowAll).apply()

    override fun isA11ySmartPauseCallEnabled(): Boolean =
        isAccessibilityFeatureEnabled("a11y_smart_pause_call")

    override fun setA11ySmartPauseCallEnabled(enabled: Boolean) =
        setAccessibilityFeatureEnabled("a11y_smart_pause_call", enabled)

    override fun isA11ySmartPauseGameEnabled(): Boolean =
        isAccessibilityFeatureEnabled("a11y_smart_pause_game")

    override fun setA11ySmartPauseGameEnabled(enabled: Boolean) =
        setAccessibilityFeatureEnabled("a11y_smart_pause_game", enabled)

    override fun getA11yShortcutBindings(): String? =
        prefs.getString("a11y_shortcut_bindings", null)

    override fun setA11yShortcutBindings(encoded: String?) {
        if (encoded == null) {
            prefs.edit().remove("a11y_shortcut_bindings").apply()
        } else {
            prefs.edit().putString("a11y_shortcut_bindings", encoded).apply()
        }
    }

    // ع٤١-ب8: تبديل مفاتيح الردّ/الرفض أثناء الرنين
    // (افتراضياً: صوت+ ردّ).
    fun isA11yCallKeysSwapped(): Boolean =
        prefs.getBoolean("a11y_call_keys_swapped", false)

    fun setA11yCallKeysSwapped(swapped: Boolean) =
        prefs.edit().putBoolean("a11y_call_keys_swapped", swapped).apply()

    private fun sanitizeLoudnessCategory(category: String?): String =
        when {
            category.isNullOrBlank() -> VOICE_CATEGORY_DEFAULT
            else -> category
        }
    override fun getCategoryLoudnessConfig(
        category: String?
    ): CategoryLoudnessConfig {
        val cat = sanitizeLoudnessCategory(category)
        val default = CategoryLoudnessConfig.defaultForCategory(cat)
        val modeStr = prefs.getString("loudness_stream_mode_$cat", null)
        val mode = if (modeStr != null) {
            StreamMode.fromId(modeStr)
        } else {
            default.streamMode
        }
        val forceMax = prefs.getBoolean(
            "loudness_force_max_$cat",
            default.forceMax
        )
        val bypassSilent = prefs.getBoolean(
            "loudness_bypass_silent_$cat",
            default.bypassSilent
        )
        val cap = prefs.getInt(
            "loudness_headset_cap_$cat",
            default.headsetCapPercent
        ).coerceIn(0, 100)
        return CategoryLoudnessConfig(
            streamMode = mode,
            forceMax = forceMax,
            bypassSilent = bypassSilent,
            headsetCapPercent = cap
        )
    }

    override fun setCategoryLoudnessConfig(
        category: String,
        config: CategoryLoudnessConfig
    ) {
        val cat = sanitizeLoudnessCategory(category)
        prefs.edit()
            .putString("loudness_stream_mode_$cat", config.streamMode.id)
            .putBoolean("loudness_force_max_$cat", config.forceMax)
            .putBoolean("loudness_bypass_silent_$cat", config.bypassSilent)
            .putInt(
                "loudness_headset_cap_$cat",
                config.headsetCapPercent.coerceIn(0, 100)
            )
            .apply()
    }

    override fun isLoudnessForceMaxEnabled(category: String?): Boolean {
        val cat = sanitizeLoudnessCategory(category)
        val default = CategoryLoudnessConfig.defaultForCategory(cat)
        return prefs.getBoolean("loudness_force_max_$cat", default.forceMax)
    }

    override fun setLoudnessForceMaxEnabled(
        category: String,
        enabled: Boolean
    ) {
        val cat = sanitizeLoudnessCategory(category)
        prefs.edit().putBoolean("loudness_force_max_$cat", enabled).apply()
    }

    override fun isLoudnessBypassSilentEnabled(category: String?): Boolean {
        val cat = sanitizeLoudnessCategory(category)
        val default = CategoryLoudnessConfig.defaultForCategory(cat)
        return prefs.getBoolean(
            "loudness_bypass_silent_$cat",
            default.bypassSilent
        )
    }

    override fun setLoudnessBypassSilentEnabled(
        category: String,
        enabled: Boolean
    ) {
        val cat = sanitizeLoudnessCategory(category)
        prefs.edit()
            .putBoolean("loudness_bypass_silent_$cat", enabled)
            .apply()
    }

    override fun getLoudnessStreamMode(category: String?): StreamMode {
        val cat = sanitizeLoudnessCategory(category)
        val default = CategoryLoudnessConfig.defaultForCategory(cat)
        val modeStr = prefs.getString("loudness_stream_mode_$cat", null)
        return if (modeStr != null) {
            StreamMode.fromId(modeStr)
        } else {
            default.streamMode
        }
    }

    override fun setLoudnessStreamMode(category: String, mode: StreamMode) {
        val cat = sanitizeLoudnessCategory(category)
        prefs.edit()
            .putString("loudness_stream_mode_$cat", mode.id)
            .apply()
    }

    override fun getLoudnessHeadsetCapPercent(category: String?): Int {
        val cat = sanitizeLoudnessCategory(category)
        val default = CategoryLoudnessConfig.defaultForCategory(cat)
        return prefs.getInt(
            "loudness_headset_cap_$cat",
            default.headsetCapPercent
        ).coerceIn(0, 100)
    }

    override fun setLoudnessHeadsetCapPercent(
        category: String,
        percent: Int
    ) {
        val cat = sanitizeLoudnessCategory(category)
        prefs.edit()
            .putInt("loudness_headset_cap_$cat", percent.coerceIn(0, 100))
            .apply()
    }

    override fun savePendingBoost(stream: Int, originalVolume: Int) {
        prefs.edit()
            .putInt(KEY_PENDING_BOOSTED_STREAM, stream)
            .putInt(KEY_PENDING_BOOSTED_VOLUME, originalVolume)
            .commit()
    }

    override fun clearPendingBoost() {
        prefs.edit()
            .remove(KEY_PENDING_BOOSTED_STREAM)
            .remove(KEY_PENDING_BOOSTED_VOLUME)
            .commit()
    }

    override fun getPendingBoostStream(): Int =
        prefs.getInt(KEY_PENDING_BOOSTED_STREAM, -1)

    override fun getPendingBoostVolume(): Int =
        prefs.getInt(KEY_PENDING_BOOSTED_VOLUME, -1)

    override fun getEventPcmGainDb(): Float =
        prefs.getFloat(KEY_EVENT_PCM_GAIN_DB, DEFAULT_EVENT_PCM_GAIN_DB)
            .coerceIn(0.0f, MAX_EVENT_PCM_GAIN_DB)

    override fun setEventPcmGainDb(gainDb: Float) {
        prefs.edit()
            .putFloat(
                KEY_EVENT_PCM_GAIN_DB,
                gainDb.coerceIn(0.0f, MAX_EVENT_PCM_GAIN_DB)
            )
            .apply()
    }

    private val phoneInputDelegate by lazy {
        SettingsPhoneInputDelegate(this)
    }

    private val readLaterDelegate by lazy {
        SettingsReadLaterDelegate(this)
    }

    fun getKeyboardMode(): String =
        phoneInputDelegate.getKeyboardMode()

    fun setKeyboardMode(mode: String) {
        phoneInputDelegate.setKeyboardMode(mode)
    }

    fun getKeyboardActivation(): String =
        phoneInputDelegate.getKeyboardActivation()

    fun setKeyboardActivation(activation: String) {
        phoneInputDelegate.setKeyboardActivation(activation)
    }

    fun isKeyboardAnnounceAfterLiftEnabled(): Boolean =
        phoneInputDelegate.isKeyboardAnnounceAfterLiftEnabled()

    fun isKeyboardAnnounceAfterLift(): Boolean =
        phoneInputDelegate.isKeyboardAnnounceAfterLift()

    fun setKeyboardAnnounceAfterLiftEnabled(enabled: Boolean) {
        phoneInputDelegate.setKeyboardAnnounceAfterLiftEnabled(enabled)
    }

    fun setKeyboardAnnounceAfterLift(enabled: Boolean) {
        phoneInputDelegate.setKeyboardAnnounceAfterLift(enabled)
    }

    fun isKeyboardHapticEnabled(): Boolean =
        phoneInputDelegate.isKeyboardHapticEnabled()

    fun setKeyboardHapticEnabled(enabled: Boolean) {
        phoneInputDelegate.setKeyboardHapticEnabled(enabled)
    }

    fun isKeyboardSoundEnabled(): Boolean =
        phoneInputDelegate.isKeyboardSoundEnabled()

    fun setKeyboardSoundEnabled(enabled: Boolean) {
        phoneInputDelegate.setKeyboardSoundEnabled(enabled)
    }

    fun isKeyboardPhoneticEnabled(): Boolean =
        phoneInputDelegate.isKeyboardPhoneticEnabled()

    fun setKeyboardPhoneticEnabled(enabled: Boolean) {
        phoneInputDelegate.setKeyboardPhoneticEnabled(enabled)
    }

    fun isKeyboardAlwaysShowNumberRow(): Boolean =
        phoneInputDelegate.isKeyboardAlwaysShowNumberRow()

    fun setKeyboardAlwaysShowNumberRow(enabled: Boolean) {
        phoneInputDelegate.setKeyboardAlwaysShowNumberRow(enabled)
    }

    fun getPhoneMode(): String =
        phoneInputDelegate.getPhoneMode()

    fun setPhoneMode(mode: String) {
        phoneInputDelegate.setPhoneMode(mode)
    }

    fun getPhoneActivation(): String =
        phoneInputDelegate.getPhoneActivation()

    fun setPhoneActivation(activation: String) {
        phoneInputDelegate.setPhoneActivation(activation)
    }

    fun isPhoneAnnounceAfterLiftEnabled(): Boolean =
        phoneInputDelegate.isPhoneAnnounceAfterLiftEnabled()

    fun setPhoneAnnounceAfterLiftEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneAnnounceAfterLiftEnabled(enabled)
    }

    fun isPhoneHapticEnabled(): Boolean =
        phoneInputDelegate.isPhoneHapticEnabled()

    fun setPhoneHapticEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneHapticEnabled(enabled)
    }

    fun isPhoneSoundEnabled(): Boolean =
        phoneInputDelegate.isPhoneSoundEnabled()

    fun setPhoneSoundEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneSoundEnabled(enabled)
    }

    fun isPhoneRecordEnabled(): Boolean =
        phoneInputDelegate.isPhoneRecordEnabled()

    fun setPhoneRecordEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneRecordEnabled(enabled)
    }

    fun getPhoneRecordMode(): String =
        phoneInputDelegate.getPhoneRecordMode()

    fun setPhoneRecordMode(mode: String) {
        phoneInputDelegate.setPhoneRecordMode(mode)
    }

    fun isPhoneShowRecordButton(): Boolean =
        phoneInputDelegate.isPhoneShowRecordButton()

    fun setPhoneShowRecordButton(show: Boolean) {
        phoneInputDelegate.setPhoneShowRecordButton(show)
    }

    fun isPhoneRecordBoostVolumeEnabled(): Boolean =
        phoneInputDelegate.isPhoneRecordBoostVolumeEnabled()

    fun setPhoneRecordBoostVolumeEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneRecordBoostVolumeEnabled(enabled)
    }

    fun isPhoneAutoSpeakerProximityEnabled(): Boolean =
        phoneInputDelegate.isPhoneAutoSpeakerProximityEnabled()

    fun setPhoneAutoSpeakerProximityEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneAutoSpeakerProximityEnabled(enabled)
    }

    fun isPhoneHardwareKeysAnswerEnabled(): Boolean =
        phoneInputDelegate.isPhoneHardwareKeysAnswerEnabled()

    fun setPhoneHardwareKeysAnswerEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneHardwareKeysAnswerEnabled(enabled)
    }

    fun isPhoneLauncherIconEnabled(): Boolean =
        phoneInputDelegate.isPhoneLauncherIconEnabled()

    fun setPhoneLauncherIconEnabled(enabled: Boolean) {
        phoneInputDelegate.setPhoneLauncherIconEnabled(enabled)
    }

    fun isSpeedDialLauncherIconEnabled(): Boolean =
        phoneInputDelegate.isSpeedDialLauncherIconEnabled()

    fun setSpeedDialLauncherIconEnabled(enabled: Boolean) {
        phoneInputDelegate.setSpeedDialLauncherIconEnabled(enabled)
    }

    fun getKeyboardSizeLevel(): Int =
        phoneInputDelegate.getKeyboardSizeLevel()

    fun setKeyboardSizeLevel(level: Int) {
        phoneInputDelegate.setKeyboardSizeLevel(level)
    }

    fun getDialpadSizeLevel(): Int =
        phoneInputDelegate.getDialpadSizeLevel()

    fun setDialpadSizeLevel(level: Int) {
        phoneInputDelegate.setDialpadSizeLevel(level)
    }

    fun getDialpadLayoutStyle(): String =
        phoneInputDelegate.getDialpadLayoutStyle()

    fun setDialpadLayoutStyle(style: String) {
        phoneInputDelegate.setDialpadLayoutStyle(style)
    }

    fun getAppProfilesRaw(): String? =
        phoneInputDelegate.getAppProfilesRaw()

    fun setAppProfilesRaw(raw: String): Boolean =
        phoneInputDelegate.setAppProfilesRaw(raw)

    fun getContactVoicesRaw(): String? =
        phoneInputDelegate.getContactVoicesRaw()

    fun setContactVoicesRaw(raw: String): Boolean =
        phoneInputDelegate.setContactVoicesRaw(raw)

    // ============ اقرأ لاحقاً ============

    enum class ReadLaterSaveResult {
        EMPTY,
        DUPLICATE,
        SAVED,
        SAVED_TRUNCATED,
        FAILED
    }

    data class ReadLaterItem(
        val id: Long,
        val text: String,
        val timestampMs: Long
    )

    fun getReadLaterLongTextPolicy(): String =
        prefs.getString(
            KEY_READ_LATER_LONG_TEXT_POLICY,
            READ_LATER_POLICY_SAVE_FULL
        ) ?: READ_LATER_POLICY_SAVE_FULL

    fun setReadLaterLongTextPolicy(policy: String) {
        prefs.edit().putString(KEY_READ_LATER_LONG_TEXT_POLICY, policy).apply()
    }

    fun getReadLaterItems(): List<ReadLaterItem> =
        readLaterDelegate.getReadLaterItems()

    fun addReadLaterText(text: String): Boolean =
        readLaterDelegate.addReadLaterText(text)

    fun saveReadLaterText(text: String): ReadLaterSaveResult =
        readLaterDelegate.saveReadLaterText(text)

    fun removeReadLaterItem(id: Long): Boolean =
        readLaterDelegate.removeReadLaterItem(id)

    fun clearReadLater(): Boolean =
        readLaterDelegate.clearReadLater()

}

/**
 * بيانات التطبيق المحمي (استثناءات وسائط وحالات التشغيل).
 */data class ProtectedApp(
    val packageName: String,
    val label: String,
    val mode: String = MODE_FULL_DISABLE
) {
    companion object {
        const val MODE_FULL_DISABLE = "full_disable"
        const val MODE_PARTIAL_SUSPEND = "partial_suspend"
    }
}
