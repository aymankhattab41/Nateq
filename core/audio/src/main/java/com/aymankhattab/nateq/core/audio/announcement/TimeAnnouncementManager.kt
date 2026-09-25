package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.common.SystemTimeProvider
import com.aymankhattab.nateq.core.common.TimeProvider
import com.aymankhattab.nateq.core.common.readStickyBattery
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.util.LanguageCode
import kotlin.math.max
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * مدير إعلان الوقت - مسؤول عن:
 * 1. تنسيق الوقت باللغة العربية الطبيعية (الربع، النصف، إلا ربع)
 * 2. جدولة إعلان الوقت الدوري عبر [AlarmManager] (مستقبل مستقل لا يموت
 *    مع قتل العملية أو تجمّد Doze) بدل مؤقتات RAM داخل الخدمة (بند 9).
 * 3. فحص ساعات الهدوء
 */
class TimeAnnouncementManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val catalog: VoiceCatalog,
    private val requestHandler: SynthesisRequestHandler,
    private val timeProvider: TimeProvider = SystemTimeProvider
) {

    companion object {
        // وسم الإنجليزية لعناصر النطق الأساسية عند تبعية
        // لغة التطبيق لفئة إنجليزية
        const val ENGLISH_LANGUAGE_TAG = "en"

        // مثيل مشترك واحد عبر العملية يستخدمه مستقبل المنبه والودجت، حتى لا
        // يتضاعف المحرك/المرشح ولا تتعارض حالا نطق متزامنة (كان الودجت يبني
        // مديراً جديداً عند كل نقرة فيتسرب CoroutineScope تدريجياً).
        @Volatile
        private var sharedInstance: TimeAnnouncementManager? = null

        /** الحصول على المدير المشترك الوحيد (يبني أول مرة مع مكوناته).
         *  يُمرَّر [settings] اختيارياً من الكائن المحقون في الخدمة ليُستعمل
         *  نفس المرجع (تجنب كائنات متعددة عبر العملية)؛ وإلا يُبنى محلياً لو
         *  كانت الدعوة من مستقبل المنبه أو الودجت اللذين لا يمرران مرجعاً. */
        @JvmStatic
        fun shared(
            context: Context,
            settings: SettingsRepository? = null
        ): TimeAnnouncementManager {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: run {
                    val appContext = context.applicationContext
                    val sharedSettings = settings
                        ?: SettingsRepository.create(appContext)
                    val providers = listOf(
                        SystemVoiceProvider(appContext, sharedSettings)
                    )
                    val catalog = VoiceCatalog(providers)
                    val handler = SynthesisRequestHandler(
                        catalog, sharedSettings
                    )
                    TimeAnnouncementManager(
                        appContext, sharedSettings, catalog, handler
                    )
                        .also { sharedInstance = it }
                }
            }
        }
    }

    /** لغة التطبيق الفعلية: المختارة يدوياً دوناً عن
     *  الافتراضي، إن لم تُختر فتتبع لغة النظام */
    private fun effectiveAppLanguage(): String {
        val chosen = runCatching { settings.getAppLanguage() }.getOrNull()
        val base = chosen ?: Locale.getDefault().language
        return if (LanguageCode.isArabic(base)) {
            LanguageCode.AR.tag
        } else {
            LanguageCode.EN.tag
        }
    }

    private var isRunning = false

    /** نطاق عمليات النطق اللاتزامنية — يعيش مع عمر المدير */
    private val announceJob = SupervisorJob()
    private val announceScope = CoroutineScope(announceJob + AppDispatchers.io)

    /** آخر عملية نطق معلّقة (عقدة فرعية تُلغى في stop) دون إنهاء نطاق الجذر
     *  حتى تستمر عمليات النطق بعد إعادة تشغيل الخدمة (بند المحور التاسع). */
    private var activeAnnounceJob: Job? = null

    /** بدء جدولة إعلان الوقت عبر منبه النظام. عند إعادة التفعيل أثناء تشغيل
     *  الخدمة (isRunning=true) يُلغى المنبه القديم ويُبنى جديد بتأجيل فوري قصير
     *  فيُنطق الوقت قريباً وليس بعد فاصل كامل (بند [6]).
     *
     *  [announceImmediately] يتحكم في نطق أول تفعيل: true عندما يفعّل المستخدم
     *  الإعلان بنفسه (مفتاح/زر) فيُعلن الوقت حالاً؛ false عند الإقلاع أو إعادة
     *  جدولة الخدمة بعد موتها — فلا يُباغت المستخدم بنطق الوقت لحظة فتح الهاتف
     *  بل يُجدول أول إعلان تلقائي فقط. */
    fun start(announceImmediately: Boolean = true) {
        if (!settings.isTimeAnnouncementEnabled()) return

        val wasRunning = isRunning
        isRunning = true

        // إلغاء أي منبه سابق (نفس PendingIntent يستبدله عند جدولة جديدة) —
        // لا حاجة لإعادة البناء لأن آلية الجدولة واحدة دائماً عبر AlarmManager.
        TimeAlarmReceiver.cancel(context)

        // أثناء ساعات الهدوء لا ننطق ولا نرسل فترات: نضبط منبهاً واحداً عند
        // نهاية الهدوء بالضبط (لا صحوة كل فاصل في Doze تنهك البطارية).
        if (isInQuietHours()) {
            scheduleQuietEndAlarm()
            return
        }

        if (!wasRunning && announceImmediately) {
            // أول تفعيل بطلب المستخدم: ننطق حالاً بدل انتظار بداية الفاصل
            // (مزامنة فورية) — أما المسارات الصامتة (الإقلاع/إعادة الجدولة)
            // فتكتفي بجدولة أول إعلان دون نطق.
            announceCurrentTime()
        }
        // إعادة جدولة الإعلان القادم من نقطة الصفر (وليس من المنبه القديم).
        scheduleNextAlarm()
    }

    /** إيقاف جدولة إعلان الوقت (إلغاء منبه النظام المعلّق) */
    fun stop() {
        isRunning = false
        TimeAlarmReceiver.cancel(context)
        // إلغاء عمليات النطق اللاتزامنية المعلّقة فقط (العقدة الفرعية) دون
        // قتل نطاق الجذر — كان إلغاء announceJob.cancel() يمنع النطق تماماً
        // بعد إعادة تشغيل الخدمة (لا يمكن إعادة استخدام Job مُلغى).
        activeAnnounceJob?.cancel()
        activeAnnounceJob = null
    }

    /**
     * تنفيذ منبه الوقت المستقل ([TimeAlarmReceiver]): يُنطق الوقت الآن ثم
     * يعيد جدولة الفاصل التالي. يُستدعى من مستقبل المنبه مباشرةً دون مرورٍ
     * بالخدمة، فلا يتوقف الإعلان عند قتل النظام للعملية أو تجمّدها في Doze.
     */
    fun onAlarmTick() {
        // إيقاف تشغيل نهائي معطّل (بند [6]): بدون العبارة التالية يستمر
        // مستقبل المنبه بالنطق حتى بعد تعطيل الإعلان من الإعدادات.
        if (!settings.isTimeAnnouncementEnabled()) {
            TimeAlarmReceiver.cancel(context)
            isRunning = false
            return
        }
        // الجدولة تعمل الآن عبر المنبه؛ نعلّم الحالة «تعمل» حتى لا يُعيد
        // start() (مثلاً عند تشغيل الخدمة من داخل النطق عبر startIfNeeded)
        // نطقَ الوقت فورياً مرة ثانية فيتضاعف الإعلان.
        isRunning = true

        // المفتاح الرئيسي يُوقف الإعلان التلقائي الدوري (ويُستثنى طلب
        // «أعلن الآن» الصريح الذي يمر عبر announceNow() خارج هذا المسار).
        if (!settings.isAllAnnouncementsEnabled()) {
            scheduleNextAlarm()
            return
        }

        // فحص ساعات الهدوء
        if (isInQuietHours()) {
            // أثناء الهدوء لا تصحو العملية كل فاصل: منبه واحد موقوت عند لحظة
            // انتهاء الهدوء (يُعلن عند مروره ثم يعود الجدول الطبيعي).
            scheduleQuietEndAlarm()
            return
        }

        // نطق الوقت الحالي ثم جدولة الإعلان القادم
        announceCurrentTime()
        scheduleNextAlarm()
    }

    /** جدولة الإعلان القادم عبر منبه النظام الدقيق. */
    private fun scheduleNextAlarm() {
        TimeAlarmReceiver.scheduleNext(
            context,
            timeProvider.currentTimeMillis() + calculateInitialDelay()
        )
    }

    /**
     * **بند 2.19 (تغيّر إذن المنبهات الدقيقة):** يُستدعى من مستقبلِ البثِّ
     * الديناميكيِّ في [NateqApplication] عندَ تغيُّرِ إذن
     * SCHEDULE_EXACT_ALARM في إعداداتِ النظامِ (سحباً أو منحاً) — يُعادُ
     * بناءُ منبهِ الوقتِ التالي فوراً فيلتقطَ الصيغةَ الدقيقةَ الجديدةَ
     * (setExact ثمَّ المرنةَ) دونَ انتظارِ دورةِ الجدولةِ الطبيعيةِ
     * التاليةِ. صامتٌ بالتصميمِ (لا نطقَ فورياً).
     */
    fun onExactAlarmPermissionChanged() {
        if (!isRunning) return
        runCatching { scheduleNextAlarm() }
    }

    /**
     * أثناء ساعات الهدوء: حساب أقرب لحظة مستقبلية لنهاية فترة الهدوء الحالية
     * (اليوم عند ساعة النهاية، وإلا غداً إن كانت النهاية بالأمس بالنسبة للآن —
     * يغطي الفترات العابرة لمنتصف الليل كـ 23→7) وضبط منبه واحد عندها بدل
     * صحوة كل فاصل دون أي نطق (بند [13.2] — استنزاف بطارية ساعات الهدوء).
     */
    private fun calculateQuietEndMillis(): Long {
        val now = timeProvider.currentTimeMillis()
        val endHour = settings.getQuietEndForDay(
            timeProvider.now().get(Calendar.DAY_OF_WEEK)
        )
        val today = timeProvider.now().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var candidate = today.timeInMillis
        if (candidate <= now) {
            val tomorrow = today.clone() as Calendar
            tomorrow.add(Calendar.DAY_OF_YEAR, 1)
            candidate = tomorrow.timeInMillis
        }
        return candidate
    }

    /** ضبط المنبه الوحيد عند نهاية ساعات الهدوء
     * (يستقبله [TimeAlarmReceiver] نفسه). */
    private fun scheduleQuietEndAlarm() {
        TimeAlarmReceiver.scheduleNext(context, calculateQuietEndMillis())
    }

    /** الدقائق المفعَّلة لرنة الساعة (:00، :15، :30، :45). */
    private fun enabledChimeMinutes(): Set<Int> {
        if (!settings.isTimeChimeEnabled()) return emptySet()
        val set = mutableSetOf<Int>()
        if (settings.isTimeChimeAt0Enabled()) set.add(0)
        if (settings.isTimeChimeAt15Enabled()) set.add(15)
        if (settings.isTimeChimeAt30Enabled()) set.add(30)
        if (settings.isTimeChimeAt45Enabled()) set.add(45)
        return set
    }

    /** حساب التأخير لأول إعلان (للبداية القادمة للفاصل أو الرنة) */
    private fun calculateInitialDelay(): Long {
        val calendar = timeProvider.now()
        val interval = effectiveIntervalMinutes()
        val gridStep = interval.coerceAtLeast(2)
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(Calendar.MINUTE)
        val chimes = enabledChimeMinutes()
        var candidate = minuteOfDay + 1
        while (true) {
            if (candidate % gridStep == 0 || (candidate % 60) in chimes) {
                break
            }
            candidate++
        }
        val nextSlot = candidate

        calendar.add(Calendar.MINUTE, nextSlot - minuteOfDay)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val delay = calendar.timeInMillis - timeProvider.currentTimeMillis()
        return max(delay, 1000L) // على الأقل ثانية واحدة
    }

    /**
     * الفاصل الفعلي مع «وضع توفير الطاقة»: عند تفعيله وانخفاض البطارية تحت
     * العتبة يُضاعف الفاصل ثلاث مرات (يقلل إهلاك البطارية دون تعطيل الخدمة).
     */
    private fun effectiveIntervalMinutes(): Int {
        val base = try {
            settings.getTimeAnnouncementInterval()
        } catch (t: Throwable) {
            30
        }
        val powerSaver = try {
            settings.isPowerSaverModeEnabled()
        } catch (t: Throwable) {
            false
        }
        if (powerSaver && isBatteryBelow(
                try {
                    settings.getPowerSaverBatteryThreshold()
                } catch (t: Throwable) {
                    20
                }
            )
        ) {
            return base * 3
        }
        return base
    }

    /** هل البطارية الحالية تحت العتبة؟
     * (يقرأ آخر حالة من بث البطارية الدائم). */
    private fun isBatteryBelow(threshold: Int): Boolean {
        return try {
            // بند 4.2: بث البطارية اللاصق عبر التسجيل الموحّد في
            // core:common — عتبة أعلامٍ واحدة وسلوك واحد مع شاشة صحة الجهاز.
            val battery = readStickyBattery(context)
            val level = battery?.getIntExtra(
                android.os.BatteryManager.EXTRA_LEVEL, -1
            ) ?: -1
            val scale = battery?.getIntExtra(
                android.os.BatteryManager.EXTRA_SCALE, -1
            ) ?: -1
            if (level < 0 || scale <= 0) {
                false
            } else {
                (level * 100 / scale) < threshold
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** فحص ما إذا كنا في ساعات الهدوء (لكل يوم فترة مستقلة + مفتاح تفعيل) */
    private fun isInQuietHours(): Boolean {
        val calendar = timeProvider.now()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val day = calendar.get(Calendar.DAY_OF_WEEK) // 1=الأحد … 7=السبت
        // اليوم المعطَّل (مفتاح الشاشة) يُستثنى من الهدوء تماماً
        val disabledToday = !settings.isDayQuietEnabled(day)
        if (!disabledToday) {
            val quietStart = settings.getQuietStartForDay(day)
            val quietEnd = settings.getQuietEndForDay(day)
            if (quietStart <= quietEnd) {
                // مثال: 7 إلى 23 (نفس اليوم)
                if (currentHour >= quietStart && currentHour < quietEnd) {
                    return true
                }
            } else {
                // مثال: 23 إلى 7 (يعبر منتصف الليل)
                if (currentHour >= quietStart || currentHour < quietEnd) {
                    return true
                }
            }
        }
        // فترة «أمس» العابرة لمنتصف الليل (مثل جمعة 23:00 → سبت 07:00) تظل
        // سارية في الساعات الأولى من اليوم الجديد؛ بلا هذا الفحص كان يُقرع
        // الإعلانُ في 00:00..07:00 من اليوم المغصوب رغم وجوب الصمت.
        val yesterday = if (day == Calendar.SUNDAY) {
            Calendar.SATURDAY
        } else {
            day - 1
        }
        if (settings.isDayQuietEnabled(yesterday)) {
            val start = settings.getQuietStartForDay(yesterday)
            val end = settings.getQuietEndForDay(yesterday)
            if (start > end && currentHour < end) return true
        }
        return false
    }

    /**
     * نطق الوقت الحالي على المسار التلقائي (منبه الوقت) — يُعلَّق عند
     * السيناريو الصوتي الممنوع بمفاتيح «نطق الساعة أثناء…» (المكالمة/
     * الوسائط/الصامت). أما announceNow (طلب المستخدم الصريح) فيتجاوز
     * التعليق عمداً كتجاوزه لساعات الهدوء.
     */
    private fun announceCurrentTime() {
        val scene = detectAudioScene(context)
        if (shouldSuppressTimeAnnouncement(
                scene,
                allowDuringCalls = runCatching {
                    settings.isAnnounceTimeDuringCalls()
                }.getOrDefault(false),
                allowDuringMedia = runCatching {
                    settings.isAnnounceTimeDuringMedia()
                }.getOrDefault(true),
                allowDuringSilent = runCatching {
                    settings.isAnnounceTimeDuringSilent()
                }.getOrDefault(true)
            )
        ) {
            return
        }
        speakCurrentTime()
    }

    /**
     * نطق الوقت فوراً عند طلب المستخدم (زر "أعلن الآن") — يتجاوز ساعات
     * الهدوء عمداً لأن المستخدم طلب النطق بنفسه.
     */
    fun announceNow() {
        speakCurrentTime()
    }

    /** المنطق المشترك لنطق الوقت بالصوت المفضل للفئة وبإعداداتها. */
    private fun speakCurrentTime() {
        // تُستبدل أي عقدة نطق سابقة (تتراكم النطقات المتداخلة عند تكرار الطلب).
        activeAnnounceJob?.cancel()
        activeAnnounceJob = announceScope.launch {
            try {
                // ترتيب تحديد لغة نطق الساعة: مفتاح النطق EN/AR ثم
                // لغة فئة الساعة المحفوظة ثم لغة الصوت المحفوظ (يشمل
                // أصوات المحركات المكتشفة مثل com.google.android.tts:
                // eng-usa) ثم لغة التطبيق — عبر القارئ الموحّد.
                val forced = runCatching {
                    settings.getAnnouncementSpeechLanguage()
                }.getOrNull()
                val categoryLang = runCatching {
                    settings.getLanguageForCategory(
                        SettingsRepository.VOICE_CATEGORY_TIME
                    )
                }.getOrNull()
                val pref = settings.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )
                val languageTag = AnnouncementLanguageResolver.resolve(
                    forced = forced,
                    categoryLang = categoryLang,
                    savedVoiceId = pref,
                    appLanguage = effectiveAppLanguage()
                )
                val isEnglish = languageTag == ENGLISH_LANGUAGE_TAG
                val locale = Locale.forLanguageTag(languageTag)

                val timeText = formatCurrentTime(isEnglish)
                val speechRate = requestHandler.getSpeechRateForCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )
                val pitch = requestHandler.getPitchForCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )
                val volume = requestHandler.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )

                // محرك النطق يحسمه AnnouncementSpeaker: فئة الساعة الصريح ثم
                // محرك اللغة المضبوط ثم محرك النظام الافتراضي.
                val speaker = AnnouncementSpeaker.getInstance(context)
                // إعادة ضبط صوت فئة الوقت قبل النطق (بند [2]): الصوت كان
                // يعلق على آخر فئةٍ نطقت (متصل/إشعار/رسالة) فيُقرأ الوقت
                // بالصوت الخطأ — نفس نمط المتصل/الرسائل.
                speaker.resetVoice(pref)
                speaker.speak(
                    timeText, locale, speechRate, pitch, volume,
                    engineOverride = settings.getEngineForCategory(
                        SettingsRepository.VOICE_CATEGORY_TIME
                    ),
                    cue = hourlyChimeCue()
                )
            } catch (t: Throwable) {
                android.util.Log.e("NATEQ_TTS", "announce time failed", t)
            }
        }
    }

    /** الحصول على الصوت المخصص لفئة معينة */
    private suspend fun getVoiceForCategory(
        category: String,
        languageTag: String
    ): VoiceDescriptor? {
        val locale = Locale.forLanguageTag(languageTag)
        val voices = catalog.allAvailableVoices(locale)

        val voiceId = settings.getPreferredVoiceIdForCategory(category)
            ?: settings.getPreferredVoiceId(languageTag)
        if (voiceId != null) {
            voices.find { it.id == voiceId }?.let { return it }
        }

        // تراجع للصوت الافتراضي للغة حتى لا يبقى الإعلان صامتاً
        val defaultName = catalog.defaultVoiceNameForLanguage(locale.language)
        defaultName?.let { name ->
            voices.find { it.id == name }?.let { return it }
        }

        return voices.firstOrNull()
    }

    /**
     * رنة الساعة: تُبنى عند أوقات أرباع الساعة المختارة من قِبل المستخدم
     * (رأس الساعة :00، الربع :15، النصف :30، والـ 45 دقيقة :45) بتفعيل الرنة.
     * وتُحمل نغمة مخصصة مستقلة لكل قيمة وقت إن اختارها المستخدم من ملف
     * خارجي (مسارها الخاص لكل رباع ورأس الساعة).
     */
    private fun hourlyChimeCue(): AudioCue? {
        return try {
            if (!settings.isTimeChimeEnabled()) return null
            val minute = timeProvider.now().get(Calendar.MINUTE)
            val shouldPlay = when (minute) {
                0 -> settings.isTimeChimeAt0Enabled()
                15 -> settings.isTimeChimeAt15Enabled()
                30 -> settings.isTimeChimeAt30Enabled()
                45 -> settings.isTimeChimeAt45Enabled()
                else -> false
            }
            if (!shouldPlay) return null
            // لكل قيمة وقت نغمة مخصصة مستقلة: رأس الساعة :00 تحتفظ بمسارها
            // الأصلي (custom_chime_uri) والأرباع :15/:30/:45 بمساراتها الخاصة.
            val customUri = runCatching {
                when (minute) {
                    15 -> settings.getCustomChimeUri15()
                    30 -> settings.getCustomChimeUri30()
                    45 -> settings.getCustomChimeUri45()
                    else -> settings.getCustomChimeUri()
                }.takeIf { it.isNotBlank() }
            }.getOrNull()
            AudioCue(
                type = CueType.TIME_HOURLY,
                soundName = settings.getTimeChimeSound(),
                volume = settings.getTimeChimeVolume(),
                customUri = customUri
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** تنسيق الوقت حسب الصيغة المختارة (طبيعي/رقمي) ولغة الصوت المحددة */
    fun formatCurrentTime(isEnglish: Boolean): String {
        val calendar = timeProvider.now()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val format = runCatching {
            settings.getTimeAnnouncementFormat()
        }.getOrNull()
            ?: "arabic_natural"
        val use24h = runCatching { settings.isTime24Hour() }.getOrDefault(false)
        return if (format == "digital") {
            formatDigitalTime(hour, minute, isEnglish, use24h)
        } else if (isEnglish) {
            formatEnglishNaturalTime(hour, minute)
        } else {
            formatArabicNaturalTime(hour, minute)
        }
    }

    /**
     * لاحقة الفترة الإنجليزية (AM/PM) للصيغة الرقمية بنظام 12 ساعة —
     * بلا لاحقة إطلاقاً بنظام 24 ساعة.
     */
    private fun meridiemSuffix(use24h: Boolean, hour: Int): String =
        if (use24h) {
            ""
        } else if (hour < 12) {
            " AM"
        } else {
            " PM"
        }

    /**
     * الصيغة الرقمية المنطوقة: تُعلن الساعة ثم الدقائق (بصيغة «ساعة ودقائق»
     * بدون الربع/النصف/إلا)، وتبدّل صريحاً مع 12/24 ساعة حسب اختيار المستخدم.
     */
    private fun formatDigitalTime(
        hour: Int,
        minute: Int,
        isEnglish: Boolean,
        use24h: Boolean
    ): String {
        val displayedHour = if (use24h) {
            hour
        } else if (hour == 0) {
            12
        } else if (hour > 12) {
            hour - 12
        } else {
            hour
        }
        // منتصف الليل بالصيغة الرقمية 24h لا يُنطق «صفر» بل «منتصف الليل»
        val midnightPhrase = use24h && hour == 0
        return if (isEnglish) {
            if (midnightPhrase && minute == 0) {
                // منتصف الليل بصلاحية 24 ساعة: «midnight» لا «zero o'clock»
                // (الساعة 0 كانت تُنطق «zero» — إبهام ليس من كلام الساعة).
                "midnight"
            } else if (minute == 0) {
                // رأس الساعة يُنطق بـ«o'clock» مع لاحقة الفترة الصحيحة
                // (AM لساعات 0..11 وPM لـ 12..23) — كان ينقصه التمييز.
                NumberSpeech.toEnglishWords(displayedHour) +
                if (use24h) "" else " o'clock" + meridiemSuffix(use24h, hour)
            } else {
                NumberSpeech.toEnglishWords(displayedHour) +
                " " + NumberSpeech.toEnglishWords(minute) +
                meridiemSuffix(use24h, hour)
            }
        } else {
            // الساعة تُنطق بالصيغة الترتيبية المؤنثة المعرّفة بأل:
            // «الساعة الآن الثانية» لا «اثنتين».
            if (minute == 0) {
                    if (midnightPhrase) {
                        "الساعة الآن منتصف الليل"
                    } else {
                        "الساعة الآن " +
                        NumberSpeech.toOrdinalHourWord(displayedHour)
                    }
                } else {
                    if (midnightPhrase) {
                        "الساعة الآن منتصف الليل و " +
                        arabicMinutePhrase(minute)
                    } else {
                        "الساعة الآن " +
                        NumberSpeech.toOrdinalHourWord(displayedHour) +
                        " و " + arabicMinutePhrase(minute)
                    }
                }
        }
    }

    /** تنسيق الوقت الإنجليزية الطبيعية:
     * "quarter past ten"، "half past ten"، "quarter to eleven".
     *  **بند 3.6:** الدقائق 31–59 تُشير للساعة التالية فتُبدِّل الفترةَ
     *  (11:45 → "quarter to 12 PM" لا AM؛ و23:45 → "quarter to 12 AM").
     *  والفرع الصفري و15/30 يبقيان على فترة الساعة الحالية. */
    private fun formatEnglishNaturalTime(hour: Int, minute: Int): String {
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val nextHour = if (hour12 == 12) 1 else hour12 + 1
        val period = if (hour < 12) "AM" else "PM"
        // فترة الساعة التالية (التي تُشير إليها الدقائق 31–59): تُحسب من
        // الساعة المطلقة — تجاوزُ منتصف النهار/الليل يقلب AM↔PM.
        val nextPeriod = if ((hour + 1) % 24 < 12) "AM" else "PM"

        return when (minute) {
            0 -> "$hour12 o'clock $period"
            15 -> "quarter past $hour12 $period"
            30 -> "half past $hour12 $period"
            45 -> "quarter to $nextHour $nextPeriod"
            in 1..14 -> englishMinutesWord(minute) +
                " past $hour12 $period"
            in 16..29 -> "$minute minutes past $hour12 $period"
            in 31..44 -> "${60 - minute} minutes to $nextHour $nextPeriod"
            // الدقائق 46..59 تُشير للساعة التالية؛ والمفرد محفوظ: 10:59
            // تُنطق «1 minute to 11 AM» لا «1 minutes» (العدد 60-59=1).
            in 46..59 -> englishMinutesWord(60 - minute) +
                " to $nextHour $nextPeriod"
            else -> "$hour12 o'clock $period"
        }
    }

    /** «دقيقة»/«دقيقتان+» بإنجليزية سليمة: 1 → "1 minute"،
     *  غيرها → "n minutes". */
    private fun englishMinutesWord(count: Int): String =
        "$count minute" + if (count == 1) "" else "s"

    /** صيغة «عدد + دقيقة» كاملة مطابقة نحويّاً: «دقيقة واحدة»، «دقيقتان»،
     * «ثلاث دقائق»، «أربع عشرة دقيقة». */
    private fun arabicMinutePhrase(count: Int): String = when (count) {
        1 -> "دقيقة واحدة"
        2 -> "دقيقتان"
        in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
        else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
    }

    /** صيغة سياق «إلا» (المثنى منصوب): «إلا دقيقة واحدة»، «إلا دقيقتين». */
    private fun arabicMinuteOmissionPhrase(count: Int): String = when (count) {
        1 -> "دقيقة واحدة"
        2 -> "دقيقتين"
        in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
        else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
    }

    /** فترة اليوم حسب ساعة 24 — صريحة تُستخدم في الصيغة الطبيعية ونظيرتها
     *  «إلا» (حيث يُحسب الوقت بإشارة إلى الساعة التالية). */
    private fun periodFor(hour24: Int): String = when (hour24) {
        12 -> "ظهراً"
        in 0..11 -> "صباحاً"
        else -> "مساءاً"
    }

    /** تنسيق الوقت بالعربية الطبيعية: "الساعة الآن العاشرة والربع" */
    private fun formatArabicNaturalTime(hour: Int, minute: Int): String {
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        // الساعة تُنطق بالصيغة الترتيبية المؤنثة المعرّفة بأل:
        // «الثانية صباحاً» لا «اثنتين صباحاً».
        val arabicHour = NumberSpeech.toOrdinalHourWord(hour12)
        // صبيحة/مساء للصيغة الطبيعية على نحو ما أعلنه
        // TextProcessor للصيغة الرقمية.
        val period = periodFor(hour)

        return when (minute) {
            0 -> "الساعة الآن $arabicHour $period"
            15 -> "الساعة الآن $arabicHour والربع $period"
            30 -> "الساعة الآن $arabicHour والنصف $period"
            45 -> {
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextArabicHour = NumberSpeech.toOrdinalHourWord(nextHour)
                // **بند 5.3:** في صيغة «إلا» نُشير إلى الساعة التالية، فتُحسب
                // الفترة من الساعة التالية لا الحالية: 11:45 ظهراً وليست
                // صباحاً، و23:45 مساءً كما في منتصف الليل.
                val nextPeriod = periodFor(if (hour == 23) 0 else hour + 1)
                "الساعة الآن $nextArabicHour إلا ربع $nextPeriod"
            }
            in 1..14 -> "الساعة الآن $arabicHour و " +
                "${arabicMinutePhrase(minute)} $period"
            in 16..29 -> "الساعة الآن $arabicHour و " +
                "${arabicMinutePhrase(minute)} $period"
            in 31..44 -> "الساعة الآن $arabicHour و " +
                "${arabicMinutePhrase(minute)} $period"
            in 46..59 -> {
                val remaining = 60 - minute
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextArabicHour = NumberSpeech.toOrdinalHourWord(nextHour)
                val nextPeriod = periodFor(if (hour == 23) 0 else hour + 1)
                "الساعة الآن $nextArabicHour إلا " +
                "${arabicMinuteOmissionPhrase(remaining)} $nextPeriod"
            }
            else -> "الساعة الآن $arabicHour $period"
        }
    }

    /** نطق رقم معين (للأرقام المنفصلة عن الوقت) */
    fun speakNumber(number: Int) {
        announceScope.launch {
            // تُنطق الأرقام بنمط التجميع المُختار
            // (مفردة/زوجي/ثلاثي..) وبلغة النطق المختارة
            val isEnglish = effectiveNumberSpeechIsEnglish()
            val text = formatNumberByMode(number)
            val languageTag = if (isEnglish) {
                ENGLISH_LANGUAGE_TAG
            } else {
                LanguageCode.AR.tag
            }

            val speechRate = requestHandler.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            val pitch = requestHandler.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            val volume = requestHandler.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )

            // إعادة ضبط صوت فئة الأرقام قبل النطق (نفس نمط فئة الوقت بند
            // [2]): الصوت كان يعلق على آخر فئة نطقت فيُقرأ الرقم بصوتها.
            val numPref = settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            val speaker = AnnouncementSpeaker.getInstance(context)
            speaker.resetVoice(numPref)
            speaker.speak(
                text, Locale.forLanguageTag(languageTag),
                speechRate, pitch, volume,
                engineOverride = settings.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            )
        }
    }

    /** تنسيق رقم في مجموعات أرقام حسب طريقة النطق المختارة (1..8).
     *  1=مفردة (رقم رقم)، 2=زوجي (رقمين كرقم واحد)، 3..8=ثلاثي..ثماني. */
    fun formatNumberByMode(number: Int): String {
        return NumberSpeech.formatByMode(
            settings.getNumberReadingMode().coerceIn(1, 8),
            number,
            effectiveNumberSpeechIsEnglish()
        )
    }

    /** لغة نطق الأرقام: مفتاح النطق EN/AR إن حُدِّد، وإلا لغة فئة
     *  «الأرقام» المحفوظة وإلا لغة الصوت المحفوظ (يشمل أصوات المحركات
     *  المكتشفة) وإلا لغة التطبيق الفعلية — عبر القارئ الموحّد. */
    private fun effectiveNumberSpeechIsEnglish(): Boolean {
        val forced = runCatching {
            settings.getAnnouncementSpeechLanguage()
        }.getOrNull()
        val categoryLang = runCatching {
            settings.getLanguageForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrNull()
        val numPref = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        return AnnouncementLanguageResolver.resolve(
            forced = forced,
            categoryLang = categoryLang,
            savedVoiceId = numPref,
            appLanguage = effectiveAppLanguage()
        ) == ENGLISH_LANGUAGE_TAG
    }
}