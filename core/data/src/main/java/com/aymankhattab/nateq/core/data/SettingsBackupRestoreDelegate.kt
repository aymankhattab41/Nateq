package com.aymankhattab.nateq.core.data

import android.content.SharedPreferences
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.common.VisibleLanguages
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.util.LanguageCode

/**
 * تفويض النسخ الاحتياطي/الاستعادة والتثبيت (sanitize) — مُستخرج من
 * [SettingsRepository] ضمن تفكيك (14) دون تغيير سلوك.
 */
internal class SettingsBackupRestoreDelegate(
    private val repo: SettingsRepository
) {

    /** كل الإعدادات القابلة للنسخ (لا يُصدَّر أي وسم ترحيل داخلي). */
    fun exportSettings(): Map<String, Any> =
        repo.prefs.all.entries
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
            if (value in PunctuationLevels.MIN..PunctuationLevels.MAX) {
                value
            } else {
                PunctuationLevels.SOME
            }
        key == "audio_expansion_level" ->
            value.coerceIn(
                AudioExpansionLevels.MIN,
                AudioExpansionLevels.MAX
            )
        key == "caller_announcement_repeat" ||
        key == "call_waiting_repeat" ->
            value.coerceIn(
                SettingsRepository.CALLER_REPEAT_MIN,
                SettingsRepository.CALLER_REPEAT_MAX
            )
        key == "caller_ring_duck_percent" ->
            value.coerceIn(
                SettingsRepository.CALLER_RING_DUCK_MIN,
                SettingsRepository.CALLER_RING_DUCK_MAX
            )
        key == "caller_announcement_interval_seconds" ->
            value.coerceIn(
                SettingsRepository.CALLER_INTERVAL_MIN,
                SettingsRepository.CALLER_INTERVAL_MAX
            )
        key == "power_saver_battery_threshold" -> value.coerceIn(0, 100)
        key == "battery_sound_cue_mode" -> value.coerceIn(0, 2)
        key == "a11y_service_pause_level" -> value.coerceIn(1, 2)
        key == SettingsRepository.KEY_KEYBOARD_SIZE_LEVEL ->
            value.coerceIn(0, 2)
        key == SettingsRepository.KEY_DIALPAD_SIZE_LEVEL ->
            value.coerceIn(0, 2)
        key == SettingsRepository.KEY_DICTIONARY_STANDALONE_MAX_LENGTH ->
            value.coerceIn(0, 3)
        key.startsWith("loudness_headset_cap_") -> value.coerceIn(0, 100)
        else -> value
    }

    /** تعقّل قيمة نصية (رنة الساعة تقبل الأسماء الثلاثة فقط، واللغة
     *  الاحتياطية اللغات المدعومة فقط). */
    private fun sanitizeString(
        key: String,
        value: String
    ): String = when (key) {
        "single_letter_mode" -> when (value) {
            "as_written", "dictionary" -> value
            else -> "name"
        }
        "time_chime_sound" -> value.takeIf {
            it in repo.validTimeChimeSounds()
        } ?: "classic_bell"
        "secondary_language" -> value.takeIf {
            it in SettingsRepository.VALID_SECONDARY_LANGUAGES
        } ?: LanguageCode.EN.tag
        "number_reading_language" ->
            if (value == "en" || value == "auto") value else "ar"
        SettingsRepository.KEY_EMOJI_PRONUNCIATION_LANGUAGE ->
            when (value.uppercase()) {
                "AR" -> "AR"
                "EN" -> "EN"
                else -> "AUTO"
            }
        // جملتا «قبل» و«بعد»: تُقصّان إلى الفراغ عند كل مدخل (كتابةً
        // أو استيراداً) فمسافاتٌ فقط ليست جملةً تُنطق.
        "caller_prefix_text" -> value.trim()
        "caller_suffix_text" -> value.trim()
        SettingsRepository.KEY_PHONE_MODE -> value.takeIf {
            it in setOf(
                SettingsRepository.PHONE_MODE_BLIND,
                SettingsRepository.PHONE_MODE_LOW_VISION
            )
        } ?: SettingsRepository.PHONE_MODE_BLIND
        SettingsRepository.KEY_PHONE_ACTIVATION -> value.takeIf {
            it in setOf(
                SettingsRepository.PHONE_ACTIVATION_LIFT,
                SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP
            )
        } ?: SettingsRepository.PHONE_ACTIVATION_LIFT
        SettingsRepository.KEY_PHONE_RECORD_MODE -> value.takeIf {
            it in setOf(
                SettingsRepository.PHONE_RECORD_MODE_MANUAL,
                SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL,
                SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED
            )
        } ?: SettingsRepository.PHONE_RECORD_MODE_MANUAL
        SettingsRepository.KEY_DIALPAD_LAYOUT_STYLE -> value.takeIf {
            it in setOf(
                SettingsRepository.DIALPAD_STYLE_GRID_WIDE,
                SettingsRepository.DIALPAD_STYLE_GRID_5X2,
                SettingsRepository.DIALPAD_STYLE_SINGLE_ROW
            )
        } ?: SettingsRepository.DIALPAD_STYLE_GRID_WIDE
        SettingsRepository.KEY_DAY_PERIOD_STYLE -> when (value) {
            SettingsRepository.DAY_PERIOD_STYLE_PREPOSITIONAL,
            SettingsRepository.DAY_PERIOD_STYLE_AS_IS -> value
            else -> SettingsRepository.DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA
        }
        SettingsRepository.KEY_INCOMING_CALL_UI_MODE -> when (value) {
            SettingsRepository.INCOMING_CALL_UI_FULLSCREEN,
            SettingsRepository.INCOMING_CALL_UI_NOTIFICATION -> value
            else -> SettingsRepository.INCOMING_CALL_UI_AUTO
        }
        else -> if (key.startsWith("loudness_stream_mode_")) {
            StreamMode.fromId(value).id
        } else {
            value
        }
    }

    /** تعقّل قيمة عشرية حسب المفتاح (السرعة/النبرة تعبان، المستوى نسبة). */
    private fun sanitizeFloat(key: String, value: Float): Float {
        if (!value.isFinite()) return 1.0f
        return when {
            // مضاعفا السرعة والصوت نطاقهما آمنٌ خاص (1.0..2.5) — لا يقعان تحت
            // فرعَي _rate/_volume (اسماهما لا يحويان المقطعين) فبدون هذا
            // الفرع تمرّ قيمتهما الجامحة من sanitizeFloat بلا قصّ أصلاً.
            key == "speech_boost_value" ||
                key == "volume_boost_value" -> value.coerceIn(1f, 2.5f)
            key == SettingsRepository.KEY_EMERGENCY_RATE ->
                value.coerceIn(0.5f, 3.0f)
            key == SettingsRepository.KEY_EMERGENCY_PITCH ->
                value.coerceIn(0.5f, 2.0f)
            key == SettingsRepository.KEY_EMERGENCY_VOLUME ->
                value.coerceIn(0.0f, 1.0f)
            // مستويا صوت الرنة ونغمة البطارية لا يهبطان تحت 0.1 — يُفحصان قبل
            // الفرع العام لـ _volume وإلا ابتلعه الفرعُ العامُ بمجرد احتوائهما
            // على المقطع (0..1) فسقط السقف الأدنى.
            key == "time_chime_volume" ||
                key == "battery_cue_volume" -> value.coerceIn(0.1f, 1f)
            key == SettingsRepository.KEY_EVENT_PCM_GAIN_DB ->
                value.coerceIn(0f, SettingsRepository.MAX_EVENT_PCM_GAIN_DB)
            key.contains("_volume") ||
                key == "default_volume" -> value.coerceIn(0f, 1f)
            key.contains("_rate") ||
                key.contains("_pitch") -> value.coerceIn(0f, 2f)
            else -> value
        }
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
            it in repo.validDeviceHealthItems()
        }.toSet()
        SettingsRepository.KEY_AUDIO_EFFECTS_DISABLED_CATEGORIES ->
            value.filter {
                it in SettingsRepository.KNOWN_EFFECT_CATEGORIES
            }.toSet()
        SettingsRepository.KEY_FILTER_LANGUAGES_SET -> value.map {
            VisibleLanguages.normalize(it)
        }.filter { it.isNotBlank() }.toSet()
        SettingsRepository.KEY_FILTER_ENGINES_SET -> value.map { it.trim() }
            .filter { it.isNotBlank() }.toSet()
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
                        val safe = if (
                            key in SettingsRepository.SAFE_DEFAULT_FALSE_KEYS
                        ) {
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
                            key.startsWith("rms_calibration_") ||
                            key == "default_volume" ||
                            key == "time_chime_volume" ||
                            key == "battery_cue_volume" ||
                            key == "speech_boost_value" ||
                            key == "volume_boost_value"
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
                            key.startsWith("rms_calibration_") ||
                            key == "default_volume" ||
                            key == "time_chime_volume" ||
                            key == "battery_cue_volume" ||
                            key == "speech_boost_value" ||
                            key == "volume_boost_value"
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
            val migrationFlags =
                SettingsRepository.MIGRATION_KEYS.mapNotNull { key ->
                    if (repo.rawPrefs.contains(key)) {
                        key to repo.rawPrefs.getBoolean(key, false)
                    } else {
                        null
                    }
                }.toMap()
            val editor = repo.prefs.edit().clear()
            for (op in ops) editor.let(op.apply)
            for ((key, flag) in migrationFlags) {
                editor.putBoolean(key, flag)
            }
            editor.commit()
        } catch (t: Throwable) {
            Log.e(SettingsRepository.TAG, "import settings failed", t)
            false
        }
    }
}
