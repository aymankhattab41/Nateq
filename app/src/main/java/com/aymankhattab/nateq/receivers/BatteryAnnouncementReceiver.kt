package com.aymankhattab.nateq.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.AnnouncementSpeaker
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

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BATTERY_CHANGED &&
            action != Intent.ACTION_POWER_CONNECTED &&
            action != Intent.ACTION_POWER_DISCONNECTED
        ) {
            return
        }
        // goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope = (context.applicationContext as com.aymankhattab.nateq.NateqApplication).appScope
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
        val settings = SettingsRepository(context)
        // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
        if (!settings.isAllAnnouncementsEnabled()) return

        val voiceId = settings.getBatteryAnnouncementVoiceId()
        val isArabic = voiceId?.contains("nateq-ar") == true
        val locale = if (isArabic) Locale("ar") else Locale("en")

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (!settings.isChargingCompleteAnnouncementEnabled()) return
                val text = if (isArabic) "تم توصيل الشاحن" else "Charger connected"
                speak(context, settings, text, locale)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                if (!settings.isChargingDisconnectAnnouncementEnabled()) return
                val text = if (isArabic) "تم فصل الشاحن" else "Charger disconnected"
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
                    if (allowFullAnnounce && notAnnouncedRecently(context, "full")) {
                        markAnnounced(context, "full")
                        val fullText = if (isArabic)
                            "البطارية ممتلئة، يمكنك فصل الشاحن"
                        else
                            "Battery full, you can unplug the charger"
                        speak(context, settings, fullText, locale)
                    }
                }

                if (percentage !in enabledLevels) return
                if (notAnnouncedRecently(context, "%$percentage")) return
                markAnnounced(context, "%$percentage")

                val text = buildLevelText(percentage, isArabic)
                speak(context, settings, text, locale)
            }
        }
    }

    private fun buildLevelText(percentage: Int, isArabic: Boolean): String = when {
        percentage == 100 -> if (isArabic) "البطارية ممتلئة" else "Battery full"
        percentage == 50 -> if (isArabic) "البطارية عند الخمسين بالمئة" else "Battery fifty percent"
        percentage == 20 -> if (isArabic) "البطارية عند العشرين بالمئة، يُنصح بالشحن" else "Battery low, twenty percent, please charge"
        percentage == 10 -> if (isArabic) "البطارية عند عشرة بالمئة، الشحن ضروري جداً" else "Battery critical, ten percent, charge immediately"
        percentage < 10 -> if (isArabic)
            "البطارية وصلت ${NumberSpeech.toArabicWords(percentage)} بالمئة"
        else
            "Battery reached ${NumberSpeech.toEnglishWords(percentage)} percent"
        else -> if (isArabic)
            "البطارية ${NumberSpeech.toArabicWords(percentage)} بالمئة"
        else
            "Battery ${NumberSpeech.toEnglishWords(percentage)} percent"
    }

    private fun speak(context: Context, settings: SettingsRepository, text: String, locale: Locale) {
        val speechRate = settings.getBatteryAnnouncementRate()
        val volume = settings.getBatteryAnnouncementVolume()
        AnnouncementSpeaker.getInstance(context).speak(text, locale, speechRate, 1.0f, volume)
    }

    /** منع تكرار نفس الإعلان خلال 5 دقائق (دورة شحن كاملة يمر الزمن كافياً). */
    private fun notAnnouncedRecently(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
        val last = prefs.getLong("battery_last_announced_$key", -1L)
        val bootTime = android.os.SystemClock.elapsedRealtime()
        return bootTime - last >= 5 * 60 * 1000L
    }

    private fun markAnnounced(context: Context, key: String) {
        context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
            .edit().putLong("battery_last_announced_$key", android.os.SystemClock.elapsedRealtime())
            .apply()
    }
}