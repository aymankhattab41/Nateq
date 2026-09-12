package com.aymankhattab.nateq.core.audio.announcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.common.SystemTimeProvider
import com.aymankhattab.nateq.core.common.TimeProvider
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * مستقبل إعلانات البطارية والشحن.
 *
 * يسمع ACTION_BATTERY_CHANGED (بث مستمر) فيُعلن عند الوصول إلى مستوى مفعّل
 * وعند اكتمال الشحن 100%، ويسمع ACTION_POWER_CONNECTED / POWER_DISCONNECTED
 * فيُعلن توصيل الشاحن وفصله. جميع النطق خاضع للمفتاح الرئيسي والإعدادات.
 */
class BatteryAnnouncementReceiver(
    private val timeProvider: TimeProvider = SystemTimeProvider
) : BroadcastReceiver() {

    companion object {
        // آخر حالة عولجت من بث البطارية الدائم — يُفلتر بها التكرار في
        // الذاكرة قبل أي عمل لاتزامني أو قراءة من القرص (بند 16.1).
        // مفتاح الفلترة يشمل النسبة والحالة ومصدر التوصيل: إغفالُهما كان
        // يُجمّد شرط اكتمال الشحن بعد أول 100% متصلةٍ (الحالة تتحول من
        // CHARGING إلى FULL والنسبة ثابتة فكان الفلتر النسبيّ يمنع الفحص).
        @Volatile
        private var lastFilterKey: String? = null

        // **بند 5.5:** سقف احتجاز نافذة goAsync حتى تمام النطق — لا أطول
        // من نافذة البث الآمنة (~10 ثوانٍ) فلا ANR إن طال التوليف.
        private const val BROADCAST_HOLD_MS = 10_000L

        /** هل هذا البث يمثل حالةً لم تُعالج بعد؟ يحسب النسبة مثل معالجة
         *  handler نفسها دون أي I/O؛ وبلا بيانات صالحة يُمرَّر البث فتتجاهله
         *  المعالجة أصلاً. */
        @JvmStatic
        fun isNewLevel(intent: Intent): Boolean {
            val level =
                intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale =
                intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return true
            val percent = (level * 100) / scale
            val status =
                intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val plugged =
                intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
            val key = "$percent|$status|$plugged"
            if (key == lastFilterKey) return false
            lastFilterKey = key
            return true
        }

        /** تصفير الفلتر بين دورات الاختبار. */
        @JvmStatic
        internal fun resetLevelFilterForTesting() {
            lastFilterKey = null
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BATTERY_CHANGED &&
            action != Intent.ACTION_POWER_CONNECTED &&
            action != Intent.ACTION_POWER_DISCONNECTED
        ) {
            return
        }
        // فلترة البث الدائم في الذاكرة (بند 16.1): يُعالج بث البطارية فقط عند
        // تغيّر النسبة أو حالة الشحن أو مصدر التوصيل، فلا تُطلق كورووتينات
        // ولا تُفتح SharedPreferences لعشرات البثات المتكررة بنفس الحالة
        // (حرارة/جهد/شحن). أحداث التوصيل والفصل أفعال منفصلة لا تمرّ
        // بالفلتر وتُعالج دائماً.
        if (action == Intent.ACTION_BATTERY_CHANGED &&
            !isNewLevel(intent!!)
        ) {
            return
        }
        // goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope =
            (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            // حارس إنهاء وحيد لدورة البث — ذرّيٌ ليتحمل وصولَ الإنهاء من
            // خيطي البث واكتمال النطق معاً (finishٌ مكررٌ تحذير بلا لزوم).
            val finishedBroadcast = AtomicBoolean(false)
            fun finishOnce() {
                if (finishedBroadcast.compareAndSet(false, true)) {
                    pendingResult.finish()
                }
            }
            var completionListener: (() -> Unit)? = null
            try {
                // **بند 5.5:** finish() الفوري قبل تمام التوليف كان يترك
                // أندرويد 14+ يجمد العملية عبر Process Cgroup Freezer
                // فيُبتر صوت البطارية في منتصف الجملة. إن نطق معالجٌ فعلاً
                // نُبقي النافذة حيّةً حتى يُتمَّ النطق (بسقف ~10 ثوانٍ)،
                // وإلا نُنهي فوراً.
                if (handle(context, intent, action)) {
                    val speaker = AnnouncementSpeaker.getInstance(context)
                    completionListener = { finishOnce() }
                    speaker.addCompletionListener(completionListener!!)
                    delay(BROADCAST_HOLD_MS)
                    finishOnce()
                } else {
                    finishOnce()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    throw t
                }
                android.util.Log.e("NATEQ_TTS", "battery announce failed", t)
            } finally {
                completionListener?.let { listener ->
                    runCatching {
                        AnnouncementSpeaker.getInstance(context)
                            .removeCompletionListener(listener)
                    }
                }
                finishOnce()
            }
        }
    }

    /** يُنطق الإعلان إن لم يُمنع حرج؛ يُرجع true إذا صدر نطقٌ فعلي
     *  (أو مؤثرٌ صامت) يقتضي الإبقاء على نافذة goAsync حتى تمامه.
     *  يُسجَّل هذا المستقبل يدوياً من AnnouncementSchedulerService (لا عبر
     *  Hilt)، فيُفضَّل الحقل المحقون من التطبيق وإلا يُبنى محلياً. */
    private fun handle(
        context: Context,
        intent: Intent,
        action: String?
    ): Boolean {
        val appContext = context.applicationContext
        val settings =
            (appContext as? AnnouncementAppContext)?.settingsRepository
            ?: SettingsRepository(context)
        // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
        if (!settings.isAllAnnouncementsEnabled()) return false

        val voiceId = settings.getBatteryAnnouncementVoiceId()
        // يقبل الصيغ القديمة (nateq-ar-…، ar-local) والصيغ
        // الموحّدة الحالية (ar-EG)
        val isArabic = voiceId?.let {
            it.contains("nateq-ar") ||
                it.startsWith("ar-local", ignoreCase = true) ||
                it.startsWith("ar-EG", ignoreCase = true)
        } == true
        val locale =
            if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag)
            else Locale.forLanguageTag(LanguageCode.EN.tag)

        var spoke = false
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (!settings.isChargingCompleteAnnouncementEnabled()) {
                    return false
                }
                val text = LocaleUtils.stringForSpeech(
                    context, if (isArabic) LanguageCode.AR.tag
                    else LanguageCode.EN.tag,
                    R.string.battery_connected, R.string.battery_connected
                )
                spoke = speak(
                    context, settings, text, locale, voiceId,
                    CueType.BATTERY_CHARGING
                )
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                if (!settings.isChargingDisconnectAnnouncementEnabled()) {
                    return false
                }
                val text = LocaleUtils.stringForSpeech(
                    context, if (isArabic) LanguageCode.AR.tag
                    else LanguageCode.EN.tag,
                    R.string.battery_disconnected, R.string.battery_disconnected
                )
                spoke = speak(
                    context, settings, text, locale, voiceId,
                    CueType.BATTERY_DISCONNECTED
                )
            }

            else -> {
                val enabledLevels = settings.getBatteryAnnouncementLevels()
                    .mapNotNull { it.toIntOrNull() }
                if (!settings.isBatteryAnnouncementEnabled() &&
                    enabledLevels.isEmpty()
                ) {
                    return false
                }

                val level =
                    intent.getIntExtra(
                        android.os.BatteryManager.EXTRA_LEVEL, -1
                    )
                val scale =
                    intent.getIntExtra(
                        android.os.BatteryManager.EXTRA_SCALE, -1
                    )
                if (level < 0 || scale <= 0) return false
                val percentage = (level * 100) / scale
                val status =
                    intent.getIntExtra(
                        android.os.BatteryManager.EXTRA_STATUS, -1
                    )
                val plugged =
                    status == android.os.BatteryManager
                        .BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

                // اكتمال الشحن (100% ومتصل): إعلان مستقل يُنطق
                // مرة واحدة في الجلسة، ولا يُكرَّر إذا كان مستوى 100
                // مفعّلاً أصلاً (يُغطيه إعلان المستوى).
                if (percentage == 100 && plugged &&
                    settings.isChargingCompleteAnnouncementEnabled()
                ) {
                    val allowFullAnnounce = !enabledLevels.contains(100)
                    if (allowFullAnnounce &&
                        !announcedRecently(context, "full")
                    ) {
                        markAnnounced(context, "full")
                        val fullText = LocaleUtils.stringForSpeech(
                            context, if (isArabic) LanguageCode.AR.tag
                            else LanguageCode.EN.tag,
                            R.string.battery_full_unplug,
                            R.string.battery_full_unplug
                        )
                        spoke = speak(
                            context, settings, fullText, locale, voiceId,
                            CueType.BATTERY_FULL
                        )
                    }
                } else {
                    // توضيح متعمد: اكتمالُ الشحن ممكَّنٌ ولم يُعلن (إما أن
                    // المستوى 100 مفعّل أصلاً فيُغطيه إعلان المستوى، أو
                    // أُعلن حديثاً خلال نافذة المنع) — نستمر إلى فحص مستوى
                    // البطارية التالي أدناه، وأي نطقٍ منه يرفع `spoke`.
                }

                if (percentage !in enabledLevels) return spoke
                if (announcedRecently(context, "%$percentage")) return spoke
                markAnnounced(context, "%$percentage")

                val text = buildLevelText(context, percentage, isArabic)
                val levelCue = if (percentage <= 10) {
                    CueType.BATTERY_LOW
                } else {
                    null
                }
                spoke = spoke || speak(
                    context, settings, text, locale, voiceId, levelCue
                )
            }
        }
        return spoke
    }

    private fun buildLevelText(
        context: Context,
        percentage: Int,
        isArabic: Boolean
    ): String {
        val lang = if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag
        val percentWords = if (isArabic)
            NumberSpeech.toArabicWords(percentage)
        else
            NumberSpeech.toEnglishWords(percentage)
        return when (percentage) {
            100 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_full, R.string.battery_full
            )
            50 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_level_50,
                R.string.battery_level_50
            )
            20 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_level_20,
                R.string.battery_level_20
            )
            10 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_level_10,
                R.string.battery_level_10
            )
            in 0..9 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_reached,
                R.string.battery_reached
            ).replace("{percent}", percentWords)
            else -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_at, R.string.battery_at
            ).replace("{percent}", percentWords)
        }
    }

    private fun speak(
        context: Context,
        settings: SettingsRepository,
        text: String,
        locale: Locale,
        voiceId: String?,
        cueType: CueType?
    ): Boolean {
        val speechRate = settings.getBatteryAnnouncementRate()
        val volume = settings.getBatteryAnnouncementVolume()
        val cueVolume = runCatching { settings.getBatteryCueVolume() }
            .getOrDefault(0.8f)
        val mode = runCatching { settings.getBatterySoundCueMode() }
            .getOrDefault(0)
        if (mode == 2 && cueType != null) {
            // «مؤثر فقط»: لا نطق، نغمة فقط (بدون تركيز — طويلة قصيرة
            // ضمن مسار الإتاحة).
            AudioCuePlayer.getInstance(context).play(
                AudioCue(type = cueType, volume = cueVolume)
            ) {}
            return false
        }
        val speaker = AnnouncementSpeaker.getInstance(context)
        // إعادة ضبط صوت البطارية قبل كل نطق (بند [1]): صوتُ الإعلان كان
        // يعلق على صوت فئةٍ سابقة (متصل/إشعار/رسالة) فيُقرأ نص البطارية
        // بالصوت الخطأ — نفس نمط المتصل/الرسائل.
        speaker.resetVoice(voiceId)
        val cue = if (mode == 0 && cueType != null) {
            AudioCue(type = cueType, volume = cueVolume)
        } else {
            null
        }
        speaker.speak(
            text, locale, speechRate, 1.0f, volume,
            engineOverride = settings.getEngineForCategory(
                SettingsRepository.DEVICE_HEALTH_BATTERY
            ),
            cue = cue
        )
        return true
    }

    /** منع تكرار نفس الإعلان خلال 5 دقائق (دورة شحن كاملة يمر الزمن كافياً).
     *  تُرجع true إذا نطق هذا المفتاح فعلاً خلال 5 دقائق مضت. التوقيت بالجدار
     *  الزمني (System.currentTimeMillis) لا بعداد الإقلاع: بعد إعادة تشغيل
     *  الهاتف يبدأ elapsedRealtime من الصفر فتصير الفروق مع الختوم القديمة
     *  سالبة ويتجمد إعلان المستوى حتى تنقضي المدة القديمة كاملة. */
    internal fun announcedRecently(
        context: Context,
        key: String,
        now: Long = timeProvider.currentTimeMillis()
    ): Boolean {
        val prefs =
            context.getSharedPreferences(
                "nateq_battery_state", Context.MODE_PRIVATE
            )
        val last = prefs.getLong("battery_last_announced_$key", -1L)
        return now - last < 5 * 60 * 1000L
    }

    internal fun markAnnounced(context: Context, key: String) {
        context.getSharedPreferences(
            "nateq_battery_state", Context.MODE_PRIVATE
        )
            .edit().putLong(
                "battery_last_announced_$key",
                timeProvider.currentTimeMillis()
            )
            .apply()
    }
}