package com.aymankhattab.nateq.core.audio.announcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * مستقبل إعلانات البطارية والشحن.
 *
 * يسمع ACTION_BATTERY_CHANGED (بث مستمر) فيُعلن عند الوصول إلى مستوى مفعّل
 * وعند اكتمال الشحن 100%، ويسمع ACTION_POWER_CONNECTED / POWER_DISCONNECTED
 * فيُعلن توصيل الشاحن وفصله. جميع النطق خاضع للمفتاح الرئيسي والإعدادات.
 */
class BatteryAnnouncementReceiver : BroadcastReceiver() {

    companion object {
        // آخر نسبة عولجت من بث البطارية الدائم — يُفلتر بها التكرار في
        // الذاكرة قبل أي عمل لاتزامني أو قراءة من القرص (بند 16.1).
        @Volatile
        private var lastLevelPercent = -1

        /** هل هذا البث يمثل نسبة لم تُعالج بعد؟ يحسب النسبة مثل معالجة
         *  handler نفسها دون أي I/O؛ وبلا بيانات صالحة يُمرَّر البث فتتجاهله
         *  المعالجة أصلاً. */
        @JvmStatic
        fun isNewLevel(intent: Intent): Boolean {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return true
            val percent = (level * 100) / scale
            if (percent == lastLevelPercent) return false
            lastLevelPercent = percent
            return true
        }

        /** تصفير الفلتر بين دورات الاختبار. */
        @JvmStatic
        internal fun resetLevelFilterForTesting() {
            lastLevelPercent = -1
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
        // تغيّر النسبة، فلا تُطلق كورووتينات ولا تُفتح SharedPreferences لعشرات
        // البثات المتكررة بنفس المستوى (حرارة/جهد/شحن). أحداث التوصيل والفصل
        // أفعال منفصلة لا تمرّ بالفلتر وتُعالج دائماً.
        if (action == Intent.ACTION_BATTERY_CHANGED && !isNewLevel(intent!!)) return
        // goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope = (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            try {
                handle(context, intent, action)
            } catch (t: Throwable) {
                android.util.Log.e("NATEQ_TTS", "battery announce failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent, action: String?) {
        // يُسجَّل هذا المستقبل يدوياً من AnnouncementSchedulerService (لا عبر
        // Hilt)، فيُفضَّل الحقل المحقون من التطبيق وإلا يُبنى محلياً.
        val appContext = context.applicationContext
        val settings = (appContext as? AnnouncementAppContext)?.settingsRepository
            ?: SettingsRepository(context)
        // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
        if (!settings.isAllAnnouncementsEnabled()) return

        val voiceId = settings.getBatteryAnnouncementVoiceId()
        // يقبل الصيغ القديمة (nateq-ar-…، ar-local) والصيغ الموحّدة الحالية (ar-EG)
        val isArabic = voiceId?.let {
            it.contains("nateq-ar") || it.startsWith("ar-local", ignoreCase = true) ||
                it.startsWith("ar-EG", ignoreCase = true)
        } == true
        val locale = if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag) else Locale.forLanguageTag(LanguageCode.EN.tag)

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (!settings.isChargingCompleteAnnouncementEnabled()) return
                val text = LocaleUtils.stringForSpeech(
                    context, if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag,
                    R.string.battery_connected, R.string.battery_connected
                )
                speak(context, settings, text, locale)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                if (!settings.isChargingDisconnectAnnouncementEnabled()) return
                val text = LocaleUtils.stringForSpeech(
                    context, if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag,
                    R.string.battery_disconnected, R.string.battery_disconnected
                )
                speak(context, settings, text, locale)
            }

            else -> {
                val enabledLevels = settings.getBatteryAnnouncementLevels()
                    .mapNotNull { it.toIntOrNull() }
                if (!settings.isBatteryAnnouncementEnabled() && enabledLevels.isEmpty()) return

                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return
                val percentage = (level * 100) / scale
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val plugged = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

                // اكتمال الشحن (100% ومتصل): إعلان مستقل يُنطق مرة واحدة في الجلسة،
                // ولا يُكرَّر إذا كان مستوى 100 مفعلاً أصلاً (يُغطيه إعلان المستوى).
                if (percentage == 100 && plugged && settings.isChargingCompleteAnnouncementEnabled()) {
                    val allowFullAnnounce = !enabledLevels.contains(100)
                    if (allowFullAnnounce && !announcedRecently(context, "full")) {
                        markAnnounced(context, "full")
                        val fullText = LocaleUtils.stringForSpeech(
                            context, if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag,
                            R.string.battery_full_unplug, R.string.battery_full_unplug
                        )
                        speak(context, settings, fullText, locale)
                    }
                }

                if (percentage !in enabledLevels) return
                if (announcedRecently(context, "%$percentage")) return
                markAnnounced(context, "%$percentage")

                val text = buildLevelText(context, percentage, isArabic)
                speak(context, settings, text, locale)
            }
        }
    }

    private fun buildLevelText(context: Context, percentage: Int, isArabic: Boolean): String {
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
                context, lang, R.string.battery_level_50, R.string.battery_level_50
            )
            20 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_level_20, R.string.battery_level_20
            )
            10 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_level_10, R.string.battery_level_10
            )
            in 0..9 -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_reached, R.string.battery_reached
            ).replace("{percent}", percentWords)
            else -> LocaleUtils.stringForSpeech(
                context, lang, R.string.battery_at, R.string.battery_at
            ).replace("{percent}", percentWords)
        }
    }

    private fun speak(context: Context, settings: SettingsRepository, text: String, locale: Locale) {
        val speechRate = settings.getBatteryAnnouncementRate()
        val volume = settings.getBatteryAnnouncementVolume()
        AnnouncementSpeaker.getInstance(context).speak(text, locale, speechRate, 1.0f, volume)
    }

    /** منع تكرار نفس الإعلان خلال 5 دقائق (دورة شحن كاملة يمر الزمن كافياً).
     *  تُرجع true إذا نطق هذا المفتاح فعلاً خلال 5 دقائق مضت. التوقيت بالجدار
     *  الزمني (System.currentTimeMillis) لا بعداد الإقلاع: بعد إعادة تشغيل
     *  الهاتف يبدأ elapsedRealtime من الصفر فتصير الفروق مع الختوم القديمة
     *  سالبة ويتجمد إعلان المستوى حتى تنقضي المدة القديمة كاملة. */
    internal fun announcedRecently(context: Context, key: String, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
        val last = prefs.getLong("battery_last_announced_$key", -1L)
        return now - last < 5 * 60 * 1000L
    }

    internal fun markAnnounced(context: Context, key: String) {
        context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
            .edit().putLong("battery_last_announced_$key", System.currentTimeMillis())
            .apply()
    }
}