package com.aymankhattab.nateq.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.Toast
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale
import kotlin.math.roundToInt
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * ضابط قسم «صحة الجهاز»: نطق حالة الجهاز عند الطلب — مستوى البطارية، حالة
 * الشحن، مساحة التخزين، والذاكرة. تعمل كمحة لحظية دون إعلانات تلقائية:
 * المستخدم يختار العناصر المطلوبة فيضغط «نطق حالة الجهاز الآن» فيُقرأ
 * الوضع الفعلي للحاسوب بلسان صوت النطق المختار.
 */
internal class DeviceHealthController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var cbBattery:
        com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var cbCharging:
        com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var cbStorage:
        com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var cbMemory:
        com.google.android.material.checkbox.MaterialCheckBox

    /** كلفة إعادة الأرقام وحيدة إلى حروفها (على النطق). */
    private fun words(n: Int, isEnglish: Boolean): String =
        if (isEnglish) NumberSpeech.toEnglishWords(n)
        else NumberSpeech.toArabicWords(n, isFeminine = false)

    fun setup(view: View) {
        cbBattery = view.findViewById(R.id.cb_device_health_battery)
        cbCharging = view.findViewById(R.id.cb_device_health_charging)
        cbStorage = view.findViewById(R.id.cb_device_health_storage)
        cbMemory = view.findViewById(R.id.cb_device_health_memory)

        val section = runCatching { settings.getDeviceHealthItems() }
            .getOrDefault(SettingsRepository.DEFAULT_DEVICE_HEALTH_ITEMS)
        cbBattery.isChecked =
            SettingsRepository.DEVICE_HEALTH_BATTERY in section
        cbCharging.isChecked =
            SettingsRepository.DEVICE_HEALTH_CHARGING in section
        cbStorage.isChecked =
            SettingsRepository.DEVICE_HEALTH_STORAGE in section
        cbMemory.isChecked =
            SettingsRepository.DEVICE_HEALTH_MEMORY in section

        val listener =
            android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
                saveSelection()
            }
        cbBattery.setOnCheckedChangeListener(listener)
        cbCharging.setOnCheckedChangeListener(listener)
        cbStorage.setOnCheckedChangeListener(listener)
        cbMemory.setOnCheckedChangeListener(listener)

        view
            .findViewById<View>(R.id.btn_speak_device_health)
            .setOnClickListener {
                speakDeviceHealth()
            }
        view
            .findViewById<View>(R.id.btn_stop_device_health)
            .setOnClickListener {
                fragment.stopPreviewSpeech()
            }
    }

    /** حفظ اختيار العناصر في الإعدادات وتحديث خط حالة القسم. */
    private fun saveSelection() {
        val items = mutableSetOf<String>()
        if (cbBattery.isChecked) {
            items.add(SettingsRepository.DEVICE_HEALTH_BATTERY)
        }
        if (cbCharging.isChecked) {
            items.add(SettingsRepository.DEVICE_HEALTH_CHARGING)
        }
        if (cbStorage.isChecked) {
            items.add(SettingsRepository.DEVICE_HEALTH_STORAGE)
        }
        if (cbMemory.isChecked) {
            items.add(SettingsRepository.DEVICE_HEALTH_MEMORY)
        }
        runCatching { settings.setDeviceHealthItems(items) }
        onStatusChanged()
    }

    /** نطق حالة الجهاز بالعناصر المختارة وبلسان صوت النطق المحدد. */
    private fun speakDeviceHealth() {
        val selected = mutableListOf<String>()
        if (cbBattery.isChecked) {
            selected.add(SettingsRepository.DEVICE_HEALTH_BATTERY)
        }
        if (cbCharging.isChecked) {
            selected.add(SettingsRepository.DEVICE_HEALTH_CHARGING)
        }
        if (cbStorage.isChecked) {
            selected.add(SettingsRepository.DEVICE_HEALTH_STORAGE)
        }
        if (cbMemory.isChecked) {
            selected.add(SettingsRepository.DEVICE_HEALTH_MEMORY)
        }
        if (selected.isEmpty()) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.device_health_none_selected,
                Toast.LENGTH_SHORT
            ).show()
            fragment.view?.announceCompat(
                fragment.getString(R.string.device_health_none_selected)
            )
            return
        }

        val context = fragment.requireContext()
        val forced =
            runCatching { settings.getAnnouncementSpeechLanguage() }
                .getOrNull()
        val appLang = runCatching { settings.getAppLanguage() }
            .getOrNull()
            ?: Locale.getDefault().language
        val isEnglish = if (forced != null) LanguageCode.isEnglish(forced)
            else LanguageCode.isEnglish(appLang)
        val langTag =
            if (isEnglish) LanguageCode.EN.tag else LanguageCode.AR.tag

        val parts = mutableListOf<String>()
        if (SettingsRepository.DEVICE_HEALTH_BATTERY in selected) {
            parts.add(buildBatteryPart(context, langTag, isEnglish))
        }
        if (SettingsRepository.DEVICE_HEALTH_CHARGING in selected) {
            parts.add(buildChargingPart(context, langTag))
        }
        if (SettingsRepository.DEVICE_HEALTH_STORAGE in selected) {
            parts.add(buildStoragePart(context, langTag, isEnglish))
        }
        if (SettingsRepository.DEVICE_HEALTH_MEMORY in selected) {
            parts.add(buildMemoryPart(context, langTag, isEnglish))
        }

        val separator = if (isEnglish) ", " else "، "
        val text = parts.joinToString(separator)
        // لا نضيف announceCompat هنا حتى لا يتداخل صوت قارئ الشاشة مع نطق
        // الحالة نفسه (تكرار مزدوج) — النطق هو نفسه التغذية الراجعة المسموعة.
        fragment.speakWithVoice(langTag, text)
    }

    /** قراءة مستوى البطارية ومصداقيته من البث اللاصق (بلا أذونات). */
    private fun batteryInfo(context: Context): Pair<Int, Boolean> {
        val battery = readStickyBattery(context)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            -1
        }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    /** جلب البث اللاصق للبطارية مع تجاهل علم RECEIVER_NOT_EXPORTED
     *  على أندرويد 14+. */
    private fun readStickyBattery(context: Context): Intent? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        return if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            context.registerReceiver(
                null, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            // تعطيل عمد لإصدارات ما قبل 14
            context.registerReceiver(null, filter)
        }
    }

    /** مساحة التخزين الداخلية الرئيسية: (المتاح، الإجمالي) بالغيغابايت. */
    private fun storageGb(): Pair<Int, Int> {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val totalGb = (stat.totalBytes.toDouble() / GIB_CUBE)
            .roundToInt().coerceAtLeast(0)
        val freeGb = (stat.availableBytes.toDouble() / GIB_CUBE)
            .roundToInt().coerceAtLeast(0)
        return freeGb to totalGb
    }

    /** الذاكرة: (المتاحة، الإجمالية) بالغيغابايت. */
    private fun memoryGb(): Pair<Int, Int> {
        val am = fragment.requireContext()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = (info.totalMem.toDouble() / GIB_CUBE)
            .roundToInt().coerceAtLeast(0)
        val availGb = (info.availMem.toDouble() / GIB_CUBE)
            .roundToInt().coerceAtLeast(0)
        return availGb to totalGb
    }

    private fun buildBatteryPart(
        context: Context,
        langTag: String,
        isEnglish: Boolean
    ): String {
        val (percent, _) = batteryInfo(context)
        if (percent <= 0) return ""
        val percentWords = words(percent, isEnglish)
        return LocaleUtils.stringForSpeech(
            context,
            langTag,
            R.string.device_health_battery_speech,
            R.string.device_health_battery_speech
        ).replace("{percent}", percentWords)
    }

    private fun buildChargingPart(context: Context, langTag: String): String {
        val (_, charging) = batteryInfo(context)
        val res = if (charging) R.string.battery_connected
            else R.string.battery_disconnected
        return LocaleUtils.stringForSpeech(
            context, langTag, res, res
        )
    }

    private fun buildStoragePart(
        context: Context,
        langTag: String,
        isEnglish: Boolean
    ): String {
        val (free, total) = storageGb()
        if (total <= 0) return ""
        val freeWords = words(free, isEnglish)
        val totalWords = words(total, isEnglish)
        return LocaleUtils.stringForSpeech(
            context,
            langTag,
            R.string.device_health_storage_speech,
            R.string.device_health_storage_speech
        ).replace("{free}", freeWords).replace("{total}", totalWords)
    }

    private fun buildMemoryPart(
        context: Context,
        langTag: String,
        isEnglish: Boolean
    ): String {
        val (avail, total) = memoryGb()
        if (total <= 0) return ""
        val availWords = words(avail, isEnglish)
        val totalWords = words(total, isEnglish)
        return LocaleUtils.stringForSpeech(
            context,
            langTag,
            R.string.device_health_memory_speech,
            R.string.device_health_memory_speech
        ).replace("{free}", availWords).replace("{total}", totalWords)
    }

    private companion object {
        const val GIB_CUBE = 1024.0 * 1024.0 * 1024.0
    }
}
