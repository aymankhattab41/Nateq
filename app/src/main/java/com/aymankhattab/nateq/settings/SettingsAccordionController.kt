package com.aymankhattab.nateq.settings

import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar

/** بطاقة قسم في القائمة الرئيسية: رأس + سهم + حالة + محتوى (يُفتح كشاشة فرعية). */
internal data class AccordionEntry(
    val header: View,
    val arrow: TextView,
    val status: TextView?,
    val content: View
)

/**
 * ضابط الأكورديون والتنقل على مستويين (القائمة الرئيسية وشاشة القسم):
 * بطاقات الأقسام القابلة للفتح، زر العودة، أسطر حالة كل قسم، وقسم
 * «أدوات التطبيق» القابل للطي.
 */
internal class SettingsAccordionController(
    private val fragment: Fragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val engines: List<EngineInfo>,
    private val spinnerEngine: android.widget.Spinner
) {

    private val accordionEntries = mutableListOf<AccordionEntry>()

    // ===== التنقّل بين المستويين: القائمة الرئيسية وشاشة القسم =====
    private var llDetailBack: android.widget.LinearLayout? = null
    private var llMasterSwitch: android.widget.LinearLayout? = null
    private var svSettingsScroll: NestedScrollView? = null
    private var tvSectionTitle: TextView? = null
    private var tvBackToList: MaterialButton? = null
    private var dictHeader: View? = null
    private var dictContent: View? = null
    private var switchHome: SwitchMaterial? = null
    private var detailOpen = false
    private var dictContentPriorVisibility = View.GONE

    /** حالة طي قسم «أدوات التطبيق» (مفتوح افتراضياً). */
    private var toolsSectionOpen = true

    /** تحذير لمرة واحدة في الجلسة إذا كان إذن الإشعارات مرفوضاً (الأزرار لن تظهر). */
    private var notificationsHiddenWarned = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (detailOpen) showHome()
        }
    }

    /** يربط عروض التنقل ويسجّل بطاقات الأقسام ومستمع زر العودة وقسم الأدوات. */
    fun setup(view: View, owner: LifecycleOwner) {
        llDetailBack = view.findViewById(R.id.ll_detail_back)
        llMasterSwitch = view.findViewById(R.id.ll_master_switch)
        svSettingsScroll = view.findViewById(R.id.sv_settings_scroll)
        tvSectionTitle = view.findViewById(R.id.tv_detail_section_title)
        tvBackToList = view.findViewById(R.id.btn_back_to_list)
        dictHeader = view.findViewById(R.id.ll_dict_header)
        dictContent = view.findViewById(R.id.ll_dict_content)
        switchHome = view.findViewById(R.id.switch_all_announcements)
        view.findViewById<View>(R.id.btn_back_to_list).setOnClickListener { showHome() }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(owner, backCallback)
        setupAccordionSections(view)
        setupToolsSection(view)
        showHome()
    }

    // ===== الأكورديون: بطاقات الأقسام والتنقّل لشاشة القسم =====
    private fun accordionEntry(
        root: View,
        headerId: Int,
        arrowId: Int,
        statusId: Int,
        contentId: Int,
        base: String
    ) {
        val header = root.findViewById<View>(headerId) ?: return
        val arrow = root.findViewById<TextView>(arrowId) ?: return
        val status = root.findViewById<TextView>(statusId)
        val content = root.findViewById<View>(contentId) ?: return
        header.tag = base
        accordionEntries.add(AccordionEntry(header, arrow, status, content))
        // في القائمة الرئيسية: فتح شاشة القسم عند الضغط على البطاقة
        header.setOnClickListener { openSection(content) }
        content.visibility = View.GONE
        arrow.text = sectionArrowGlyph()
        refreshCardDesc(content)
    }

    /** سهم بطاقة القسم: يشير لليسار في RTL (اتجاه التقدّم) ولليمين في LTR */
    private fun sectionArrowGlyph(): String {
        val rtl = fragment.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) "‹" else "›"
    }

    /** وصف وصول موحّد لبطاقة القسم: الأساس + الحالة */
    private fun refreshCardDesc(content: View) {
        val e = accordionEntries.firstOrNull { it.content === content } ?: return
        val base = e.header.tag as? String ?: ""
        val statusText = e.status?.text?.toString()?.trim().orEmpty()
        e.header.contentDescription = if (statusText.isNotEmpty()) {
            base + "، " + statusText
        } else {
            base
        }
    }

    private fun setSectionStatus(contentId: Int, text: String) {
        val e = accordionEntries.firstOrNull { it.content.id == contentId } ?: return
        e.status?.text = text
        refreshCardDesc(e.content)
    }

    /** فتح شاشة قسم فرعي: إخفاء كل شيء عدا القسم المطلوب + شريط العودة */
    private fun openSection(content: View) {
        detailOpen = true
        backCallback.isEnabled = true
        llDetailBack?.visibility = View.VISIBLE
        llMasterSwitch?.visibility = View.GONE
        dictContentPriorVisibility = dictContent?.visibility ?: View.GONE
        dictHeader?.visibility = View.GONE
        dictContent?.visibility = View.GONE
        setHomeActionsVisible(false)
        fragment.view?.findViewById<View>(R.id.btn_toggle_language)?.visibility = View.GONE
        setSectionDividersVisible(false)
        svSettingsScroll?.scrollTo(0, 0)
        var sectionName = ""
        for (e in accordionEntries) {
            val target = e.content === content
            if (target) sectionName = e.header.tag as? String ?: ""
            // رأس القسم المفتوح يُخفى أيضاً: tvSectionTitle يعرض اسمه أعلى الشاشة
            e.header.visibility = View.GONE
            e.status?.visibility = if (target) View.VISIBLE else View.GONE
            e.content.visibility = if (target) View.VISIBLE else View.GONE
        }
        tvSectionTitle?.text = sectionName
        // إعلان مسموع لفتح القسم + نقل تركيز الوصول إلى أول عنصر تفاعلي في المحتوى
        val focusTarget = findFirstFocusableView(content)
            ?: tvBackToList
        focusTarget?.let {
            it.announceCompat(fragment.getString(R.string.section_opened, sectionName))
            focusForAccessibility(it)
        }
    }

    /** إيجاد أول عرض قابل للتركيز في الشجرة (أول عنصر تفاعلي لفتح القسم) */
    private fun findFirstFocusableView(root: View): View? {
        var found: View? = null
        forEachView(root) { v ->
            if (found == null && v.isFocusable && v.visibility == View.VISIBLE) {
                found = v
            }
        }
        return found
    }

    /** تحذير لمرة واحدة في الجلسة إذا كان إذن الإشعارات مرفوضاً (الأزرار لن تظهر). */
    fun warnIfNotificationsHidden() {
        if (notificationsHiddenWarned) return
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationsHiddenWarned = true
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(R.string.notification_permission_actions_hidden),
            Toast.LENGTH_LONG
        ).show()
        fragment.view?.announceCompat(
            fragment.getString(R.string.notification_permission_actions_hidden)
        )
    }

    /** العودة إلى القائمة الرئيسية: تُظهر كل البطاقات وتطوي المحتويات */
    fun showHome() {
        val returning = detailOpen
        detailOpen = false
        backCallback.isEnabled = false
        llDetailBack?.visibility = View.GONE
        llMasterSwitch?.visibility = View.VISIBLE
        dictHeader?.visibility = View.VISIBLE
        dictContent?.visibility = dictContentPriorVisibility
        setHomeActionsVisible(true)
        fragment.view?.findViewById<View>(R.id.btn_toggle_language)?.visibility = View.VISIBLE
        setSectionDividersVisible(true)
        svSettingsScroll?.scrollTo(0, 0)
        for (e in accordionEntries) {
            e.header.visibility = View.VISIBLE
            e.status?.visibility = View.VISIBLE
            e.content.visibility = View.GONE
            e.arrow.visibility = View.VISIBLE
            e.arrow.text = sectionArrowGlyph()
        }
        // عند العودة من قسم فقط: نعيد التركيز للمفتاح الرئيسي مع إعلان مسموع
        if (returning) {
            switchHome?.announceCompat(fragment.getString(R.string.back_to_home))
            switchHome?.let { focusForAccessibility(it) }
        }
    }

    /** نقل تركيز الوصول إلى عرض معيّن عبر واجهات عامة */
    private fun focusForAccessibility(target: View) {
        target.post {
            target.requestFocus(View.FOCUS_FORWARD)
            target.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            )
        }
    }

    /** إظهار/إخفاء الفواصل بين بطاقات القائمة (تُخفى داخل شاشات الأقسام) */
    private fun setSectionDividersVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        val root = fragment.view ?: return
        forEachView(root) { if (it.tag == "section_divider") it.visibility = v }
    }

    /** تجوال الشجرة كاملة وتنفيذ إجراء على كل عرض (بديل findViewsWithTag) */
    private fun forEachView(current: View, action: (View) -> Unit) {
        action(current)
        if (current is android.view.ViewGroup) {
            for (i in 0 until current.childCount) {
                current.getChildAt(i).let { forEachView(it, action) }
            }
        }
    }

    /** إظهار/إخفاء أدوات القائمة الرئيسية (الحفظ/الاستعادة/النسخ) */
    private fun setHomeActionsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        fragment.view?.findViewById<View>(R.id.ll_tools_header)?.visibility = v
        // المحتوى يسترجع حالته الأصلية (مفتوح إن كان مفتوحاً قبل الدخول لقسم)
        if (visible && toolsSectionOpen) {
            fragment.view?.findViewById<View>(R.id.ll_tools_content)?.visibility = View.VISIBLE
        } else if (!visible) {
            fragment.view?.findViewById<View>(R.id.ll_tools_content)?.visibility = v
        }
    }

    /** تسجيل الأقسام التسعة كبطاقات في القائمة الرئيسية (تُفتح كل منها شاشة فرعية) */
    private fun setupAccordionSections(view: View) {
        accordionEntries.clear()
        val engine = fragment.getString(R.string.section_voice_selection)
        val cats = fragment.getString(R.string.voice_category_default)
        val time = fragment.getString(R.string.section_time_announcement)
        val num = fragment.getString(R.string.section_number_reading)
        val battery = fragment.getString(R.string.section_battery_announcement)
        val notif = fragment.getString(R.string.section_notification_reading)
        val caller = fragment.getString(R.string.section_caller_announcement)
        val sms = fragment.getString(R.string.section_sms_reading)
        val general = fragment.getString(R.string.section_general_settings)
        accordionEntry(
            view, R.id.ll_engine_header, R.id.tv_engine_arrow,
            R.id.tv_engine_status, R.id.ll_engine_content,
            engine
        )
        accordionEntry(
            view, R.id.ll_categories_header, R.id.tv_categories_arrow,
            R.id.tv_categories_status, R.id.ll_categories_content,
            cats
        )
        accordionEntry(
            view, R.id.ll_time_announcement_header, R.id.tv_time_announcement_arrow,
            R.id.tv_time_announcement_status, R.id.ll_time_announcement_settings,
            time
        )
        accordionEntry(
            view, R.id.ll_number_reading_header, R.id.tv_number_reading_arrow,
            R.id.tv_number_reading_status, R.id.ll_numbers_content,
            num
        )
        accordionEntry(
            view, R.id.ll_battery_announcement_header, R.id.tv_battery_announcement_arrow,
            R.id.tv_battery_announcement_status, R.id.ll_battery_announcement_settings,
            battery
        )
        accordionEntry(
            view, R.id.ll_notification_reading_header, R.id.tv_notification_reading_arrow,
            R.id.tv_notification_reading_status, R.id.ll_notification_reading_settings,
            notif
        )
        accordionEntry(
            view, R.id.ll_caller_announcement_header, R.id.tv_caller_announcement_arrow,
            R.id.tv_caller_announcement_status, R.id.ll_caller_announcement_settings,
            caller
        )
        accordionEntry(
            view, R.id.ll_sms_reading_header, R.id.tv_sms_reading_arrow,
            R.id.tv_sms_reading_status, R.id.ll_sms_reading_settings,
            sms
        )
        accordionEntry(
            view, R.id.ll_general_settings_header, R.id.tv_general_settings_arrow,
            R.id.tv_general_settings_status, R.id.ll_general_settings_content,
            general
        )
    }

    /** تحديث أسطر الحالة لكل قسم (يُستدعى عند التهيئة وبعد كل تغيير أساسي) */
    fun updateSectionStatuses() {
        setSectionStatus(R.id.ll_engine_content, buildEngineStatus())
        setSectionStatus(R.id.ll_categories_content, buildCategoriesStatus())
        setSectionStatus(R.id.ll_time_announcement_settings, buildTimeStatus())
        setSectionStatus(R.id.ll_numbers_content, buildNumberStatus())
        setSectionStatus(R.id.ll_battery_announcement_settings, buildBatteryStatus())
        setSectionStatus(R.id.ll_notification_reading_settings, buildNotificationStatus())
        setSectionStatus(R.id.ll_caller_announcement_settings, buildCallerStatus())
        setSectionStatus(R.id.ll_sms_reading_settings, buildSmsStatus())
        setSectionStatus(R.id.ll_general_settings_content, buildGeneralStatus())
    }

    private fun buildEngineStatus(): String {
        val auto = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        val engine = engines.getOrNull(spinnerEngine.selectedItemPosition)?.label
            ?: fragment.getString(R.string.no_voices_available)
        val autoLabel = if (auto) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        return fragment.getString(R.string.auto_convert_enabled) + ": " +
            autoLabel + "، " + engine
    }

    private fun buildCategoriesStatus(): String {
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(SettingsRepository.VOICE_CATEGORY_DEFAULT)
        }.getOrNull()
        val name = voices.firstOrNull { it.name == saved }?.displayName
            ?: voices.firstOrNull()?.displayName
            ?: fragment.getString(R.string.no_voices_available)
        return fragment.getString(R.string.voice_category_default) + ": " + name
    }

    private fun buildTimeStatus(): String {
        val enabled = runCatching { settings.isTimeAnnouncementEnabled() }
            .getOrDefault(true)
        val interval = runCatching { settings.getTimeAnnouncementInterval() }
            .getOrDefault(30)
        val intervalLabel = fragment.getString(
            when (interval) {
                15 -> R.string.time_interval_15
                30 -> R.string.time_interval_30
                45 -> R.string.time_interval_45
                else -> R.string.time_interval_60
            }
        )
        val quietStart = runCatching { settings.getQuietStartForDay(Calendar.DAY_OF_WEEK) }
            .getOrDefault(23)
        val quietEnd = runCatching { settings.getQuietEndForDay(Calendar.DAY_OF_WEEK) }
            .getOrDefault(7)
        return buildString {
            val on = if (enabled) fragment.getString(R.string.toggle_on)
            else fragment.getString(R.string.toggle_off)
            append(on)
            append("، ").append(intervalLabel)
            append("، ").append(fragment.getString(R.string.time_quiet_schedule_title))
            append(": ").append(quietStart).append("/").append(quietEnd)
        }
    }

    private fun buildNumberStatus(): String {
        val mode = runCatching { settings.getNumberReadingMode() }
            .getOrDefault(1).coerceIn(1, 8)
        val label = fragment.getString(
            when (mode) {
                1 -> R.string.number_mode_single
                2 -> R.string.number_mode_pairs
                3 -> R.string.number_mode_triples
                4 -> R.string.number_mode_quadruples
                5 -> R.string.number_mode_quintuples
                6 -> R.string.number_mode_sextuples
                7 -> R.string.number_mode_septuples
                else -> R.string.number_mode_octuples
            }
        )
        return fragment.getString(R.string.number_reading_mode) + ": " + label
    }

    private fun buildBatteryStatus(): String {
        val enabled = runCatching { settings.isBatteryAnnouncementEnabled() }
            .getOrDefault(false)
        val levels = runCatching { settings.getBatteryAnnouncementLevels() }
            .getOrDefault(emptySet())
        val on = if (enabled) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        val summary = levels.sortedDescending().joinToString("، ") { "$it%" }
        return buildString {
            append(on)
            if (summary.isNotEmpty()) append("، ").append(summary)
        }
    }

    private fun buildNotificationStatus(): String {
        val enabled = runCatching { settings.isNotificationReadingEnabled() }
            .getOrDefault(false)
        val sel = runCatching { settings.getNotificationAppsSelection() }
            .getOrDefault(SettingsRepository.DEFAULT_NOTIFICATION_APPS)
        val on = if (enabled) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        val apps = if (SettingsRepository.NOTIF_READ_ALL in sel) {
            fragment.getString(R.string.notification_apps_all)
        } else {
            sel.size.toString()
        }
        return buildString {
            append(on)
            append("، ").append(fragment.getString(R.string.notification_apps_title))
            append(": ").append(apps)
        }
    }

    private fun buildCallerStatus(): String {
        val enabled = runCatching { settings.isCallerAnnouncementEnabled() }
            .getOrDefault(false)
        val repeat = runCatching { settings.getCallerAnnouncementRepeat() }
            .getOrDefault(1).coerceIn(1, 5)
        val on = if (enabled) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        val label = fragment.getString(
            when (repeat) {
                1 -> R.string.repeat_once
                2 -> R.string.repeat_twice
                3 -> R.string.repeat_3
                4 -> R.string.repeat_4
                else -> R.string.repeat_5
            }
        )
        return on + "، " + label
    }

    private fun buildSmsStatus(): String {
        val mode = runCatching { settings.getSmsReadingMode() }.getOrDefault("off")
        val label = fragment.getString(
            when (mode) {
                "full" -> R.string.sms_mode_full
                "source" -> R.string.sms_mode_source
                else -> R.string.sms_mode_off
            }
        )
        return fragment.getString(R.string.sms_reading_mode) + ": " + label
    }

    private fun buildGeneralStatus(): String {
        val rate = runCatching { settings.getDefaultSpeechRate() }.getOrDefault(1.0f)
        val volume = runCatching { settings.getDefaultVolume() }.getOrDefault(1.0f)
        val rateText = String.format(java.util.Locale.US, "%.1fx", rate)
        val volumeText = (volume * 100).toInt().toString() + "%"
        return fragment.getString(R.string.default_speech_rate_label) + ": " +
            rateText + "، " + volumeText
    }

    // ===== قسم أدوات التطبيق القابل للطي =====
    private fun setupToolsSection(view: View) {
        val header = view.findViewById<View>(R.id.ll_tools_header) ?: return
        val content = view.findViewById<View>(R.id.ll_tools_content) ?: return
        val arrow = view.findViewById<TextView>(R.id.tv_tools_arrow) ?: return
        val base = fragment.getString(R.string.tools_section)
        header.contentDescription = base
        header.setOnClickListener {
            toolsSectionOpen = content.visibility != View.VISIBLE
            content.visibility = if (toolsSectionOpen) View.VISIBLE else View.GONE
            arrow.text = if (toolsSectionOpen) "▼" else sectionArrowGlyph()
            header.announceCompat(
                fragment.getString(
                    if (toolsSectionOpen) R.string.section_opened else R.string.section_collapsed,
                    base
                )
            )
        }
    }
}