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
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar
import com.aymankhattab.nateq.core.data.SettingsRepository

/** بطاقة قسم في القائمة الرئيسية: رأس + سهم + حالة + محتوى
 *  (يُفتح كشاشة فرعية). */
internal data class AccordionEntry(
    val header: View,
    val arrow: TextView,
    val status: TextView?,
    val content: View
)

/**
 * ضابط التنقّل على ثلاثة مستويات (الرئيسية ← المجموعة ← القسم):
 * القائمة الرئيسية تعرض المفتاح الرئيسي وأزرار المجموعات الثلاث المستقلة
 * (إعدادات خاصة/عامة/متقدمة)، والضغط على أي زر يدخل إلى شاشة مجموعته،
 * ومنها تُفتح بطاقات الأقسام كشاشات فرعية، مع زر رجوع تدريجي ومفتاح النظام.
 */
internal class SettingsAccordionController(
    private val fragment: Fragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val engines: List<EngineInfo>,
    private val spinnerEngine: android.widget.Spinner
) {

    private val accordionEntries = mutableListOf<AccordionEntry>()

    // ===== المجموعات (إعدادات خاصة / عامة / متقدمة) =====
    private class GroupState(val headerId: Int, val contentId: Int)

    private val groupStates = listOf(
        GroupState(
            R.id.ll_group_special_header,
            R.id.ll_group_special_content
        ),
        GroupState(
            R.id.ll_group_general_header,
            R.id.ll_group_general_content
        ),
        GroupState(
            R.id.ll_group_advanced_header,
            R.id.ll_group_advanced_content
        )
    )

    /** مستويات التنقّل: الرئيسية / شاشة مجموعة / شاشة قسم فرعي */
    private enum class Level { HOME, GROUP, SECTION }

    private var level = Level.HOME

    /** المجموعة المفتوحة حالياً (شاشة مجموعتها أو قسمٌ داخلها) */
    private var currentGroup: GroupState? = null

    // ===== التنقّل بين المستويات: الرئيسية ومجموعة وقسم =====
    private var llDetailBack: android.widget.LinearLayout? = null
    private var llMasterSwitch: android.widget.LinearLayout? = null
    private var svSettingsScroll: NestedScrollView? = null
    private var tvSectionTitle: TextView? = null
    private var tvBackToList: MaterialButton? = null
    private var switchHome: SwitchMaterial? = null

    /** تحذير لمرة واحدة في الجلسة إذا كان إذن الإشعارات مرفوضاً
 *  (الأزرار لن تظهر). */
    private var notificationsHiddenWarned = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            goBack()
        }
    }

    /** يربط عروض التنقّل ويسجّل بطاقات الأقسام ومستمع زر العودة
 *  وأزرار المجموعات. */
    fun setup(view: View, owner: LifecycleOwner) {
        llDetailBack = view.findViewById(R.id.ll_detail_back)
        llMasterSwitch = view.findViewById(R.id.ll_master_switch)
        svSettingsScroll = view.findViewById(R.id.sv_settings_scroll)
        tvSectionTitle = view.findViewById(R.id.tv_detail_section_title)
        tvBackToList = view.findViewById(R.id.btn_back_to_list)
        switchHome = view.findViewById(R.id.switch_all_announcements)
        view.findViewById<View>(R.id.btn_back_to_list)
            .setOnClickListener { goBack() }
        fragment.requireActivity().onBackPressedDispatcher
            .addCallback(owner, backCallback)
        setupAccordionSections(view)
        setupGroupSections(view)
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
        // داخل شاشة المجموعة: فتح شاشة القسم عند الضغط على البطاقة
        header.setOnClickListener { openSection(content) }
        content.visibility = View.GONE
        arrow.text = sectionArrowGlyph()
        refreshCardDesc(content)
    }

    /** سهم بطاقة القسم: يشير لليسار في RTL (اتجاه التقدّم) ولليمين في LTR */
    private fun sectionArrowGlyph(): String {
        val rtl = fragment.resources.configuration.layoutDirection ==
            View.LAYOUT_DIRECTION_RTL
        return if (rtl) "‹" else "›"
    }

    /** وصف وصول موحّد لبطاقة القسم: الأساس + الحالة */
    private fun refreshCardDesc(content: View) {
        val e = accordionEntries.firstOrNull {
            it.content === content
        } ?: return
        val base = e.header.tag as? String ?: ""
        val statusText = e.status?.text?.toString()?.trim().orEmpty()
        e.header.contentDescription = if (statusText.isNotEmpty()) {
            base + "، " + statusText
        } else {
            base
        }
    }

    private fun setSectionStatus(contentId: Int, text: String) {
        val e = accordionEntries.firstOrNull {
            it.content.id == contentId
        } ?: return
        e.status?.text = text
        refreshCardDesc(e.content)
    }

    /** فتح شاشة قسم فرعي: إخفاء كل شيء عدا القسم المطلوب + شريط العودة */
    private fun openSection(content: View) {
        level = Level.SECTION
        backCallback.isEnabled = true
        llDetailBack?.visibility = View.VISIBLE
        llMasterSwitch?.visibility = View.GONE
        // المجموعة الحاوية للقسم تُبقى ظاهرة (محتوى القسم يعيش داخلها)
        val group = groupStates.firstOrNull { g ->
            val container = fragment.view?.findViewById<View>(g.contentId)
            container != null && isDescendantOf(content, container)
        }
        currentGroup = group
        applyGroupVisibility(false, group)
        setSectionDividersVisible(false)
        svSettingsScroll?.scrollTo(0, 0)
        var sectionName = ""
        for (e in accordionEntries) {
            val target = e.content === content
            if (target) sectionName = e.header.tag as? String ?: ""
            // رأس القسم المفتوح يُخفى أيضاً: tvSectionTitle يعرض اسمه
            // أعلى الشاشة
            e.header.visibility = View.GONE
            e.status?.visibility = if (target) View.VISIBLE else View.GONE
            e.arrow.visibility = View.GONE
            e.content.visibility = if (target) View.VISIBLE else View.GONE
        }
        tvSectionTitle?.text = sectionName
        // زر العودة في مستوى القسم يعود إلى شاشته المجموعة
        // (وليس القائمة الرئيسية)
        tvBackToList?.text = if (group != null) {
            fragment.getString(R.string.back_to_group, groupTitle(group))
        } else {
            fragment.getString(R.string.back_label)
        }
        // إعلان مسموع لفتح القسم + نقل تركيز الوصول إلى أول عنصر
        // تفاعلي في المحتوى
        val focusTarget = findFirstFocusableView(content)
            ?: tvBackToList
        focusTarget?.let {
            it.announceCompat(
                fragment.getString(R.string.section_opened, sectionName)
            )
            focusForAccessibility(it)
        }
    }

    /** فتح شاشة مجموعة (إعدادات خاصة/عامة/متقدمة): بطاقات المجموعة
 *  فقط + شريط العودة */
    private fun openGroup(group: GroupState) {
        level = Level.GROUP
        currentGroup = group
        backCallback.isEnabled = true
        llDetailBack?.visibility = View.VISIBLE
        llMasterSwitch?.visibility = View.GONE
        applyGroupVisibility(false, group)
        svSettingsScroll?.scrollTo(0, 0)
        val container = fragment.view
            ?.findViewById<View>(group.contentId) ?: return
        for (e in accordionEntries) {
            val inGroup = isDescendantOf(e.header, container)
            e.header.visibility = if (inGroup) View.VISIBLE else View.GONE
            e.status?.visibility = if (inGroup) View.VISIBLE else View.GONE
            e.arrow.visibility = if (inGroup) View.VISIBLE else View.GONE
            e.arrow.text = sectionArrowGlyph()
            e.content.visibility = View.GONE
        }
        val groupTitle = groupTitle(group)
        tvSectionTitle?.text = groupTitle
        // زر العودة في مستوى المجموعة يعود للقائمة الرئيسية
        tvBackToList?.text = fragment.getString(R.string.back_label)
        val focusTarget = findFirstFocusableView(container)
            ?: tvBackToList
        focusTarget?.let {
            it.announceCompat(
                fragment.getString(R.string.section_opened, groupTitle)
            )
            focusForAccessibility(it)
        }
    }

    /** إظهار/إخفاء رؤوس وحاويات المجموعات: الرئيسية تُظهر الرؤوس فقط،
 *  وشاشة المجموعة حاويتها */
    private fun applyGroupVisibility(home: Boolean, active: GroupState?) {
        groupStates.forEach { g ->
            fragment.view?.findViewById<View>(g.headerId)?.visibility =
                if (home) View.VISIBLE else View.GONE
            fragment.view?.findViewById<View>(g.contentId)?.visibility =
                if (!home && active != null &&
                    g.contentId == active.contentId
                ) View.VISIBLE
                else View.GONE
        }
    }

    /** رجوع تدريجي: قسم ← مجموعة ← رئيسية (أو تسليم المفتاح
 *  للنظام فوق الرئيسية) */
    private fun goBack() {
        val group = currentGroup
        when {
            level == Level.SECTION && group != null -> openGroup(group)
            level == Level.GROUP -> showHome()
            level == Level.SECTION -> showHome()
            else -> {
            // في الرئيسية: يعالج مفتاح الرجوع النظامي الخروج من الشاشة
        }
        }
    }

    /** إيجاد أول عرض قابل للتركيز في الشجرة (أول عنصر تفاعلي لفتح القسم) */
    private fun findFirstFocusableView(root: View): View? {
        var found: View? = null
        forEachView(root) { v ->
            if (found == null && v.isFocusable &&
                v.visibility == View.VISIBLE
            ) {
                found = v
            }
        }
        return found
    }

    /** تحذير لمرة واحدة في الجلسة إذا كان إذن الإشعارات مرفوضاً
 *  (الأزرار لن تظهر). */
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
            fragment.getString(
                R.string.notification_permission_actions_hidden
            ),
            Toast.LENGTH_LONG
        ).show()
        fragment.view?.announceCompat(
            fragment.getString(R.string.notification_permission_actions_hidden)
        )
    }

    /** العودة إلى القائمة الرئيسية: المفتاح الرئيسي + أزرار
 *  المجموعات الثلاث فقط */
    fun showHome() {
        val returning = level != Level.HOME
        level = Level.HOME
        currentGroup = null
        backCallback.isEnabled = false
        llDetailBack?.visibility = View.GONE
        llMasterSwitch?.visibility = View.VISIBLE
        applyGroupVisibility(true, null)
        setSectionDividersVisible(true)
        svSettingsScroll?.scrollTo(0, 0)
        // بطاقات الأقسام لا تظهر إلا داخل شاشات مجموعاتها
        for (e in accordionEntries) {
            e.header.visibility = View.GONE
            e.status?.visibility = View.GONE
            e.arrow.visibility = View.GONE
            e.content.visibility = View.GONE
        }
        // عند العودة من مستوى أعمق: نعيد تركيز المفتاح الرئيسي مع إعلان مسموع
        if (returning) {
            switchHome?.announceCompat(
                fragment.getString(R.string.back_to_home)
            )
            switchHome?.let { focusForAccessibility(it) }
        }
    }

    /** نقل تركيز الوصول إلى عرض معيّن عبر واجهات عامة */
    private fun focusForAccessibility(target: View) {
        target.post {
            target.requestFocus(View.FOCUS_FORWARD)
            target.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent
                    .TYPE_VIEW_ACCESSIBILITY_FOCUSED
            )
        }
    }

    /** إظهار/إخفاء الفواصل بين بطاقات القائمة (تُخفى داخل شاشات الأقسام) */
    private fun setSectionDividersVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        val root = fragment.view ?: return
        forEachView(root) {
            if (it.tag == "section_divider") it.visibility = v
        }
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

    /** هل child داخل سلالة ancestor في شجرة العروض؟
 *  (بديل isDescendantOf API 33+) */
    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    /** تسجيل الأقسام كبطاقات تُفتح كلٌّ منها شاشةً فرعية */
    private fun setupAccordionSections(view: View) {
        accordionEntries.clear()
        val engine = fragment.getString(R.string.section_voice_selection)
        val dict = fragment.getString(R.string.section_pronunciation_dict)
        val tools = fragment.getString(R.string.tools_section)
        val help = fragment.getString(R.string.help_section)
        val emoji = fragment.getString(R.string.section_emoji_reading)
        val cats = fragment.getString(R.string.voice_category_default)
        val time = fragment.getString(R.string.section_time_announcement)
        val num = fragment.getString(R.string.section_number_reading)
        val battery = fragment.getString(R.string.section_battery_announcement)
        val notif = fragment.getString(R.string.section_notification_reading)
        val caller = fragment.getString(R.string.section_caller_announcement)
        val sms = fragment.getString(R.string.section_sms_reading)
        val general = fragment.getString(R.string.section_general_settings)
        val deviceHealth = fragment.getString(R.string.section_device_health)

        // مجموعة «إعدادات خاصة»: الوقت / الإشعارات / الرسائل / نطق المتصل
        accordionEntry(
            view,
            R.id.ll_time_announcement_header,
            R.id.tv_time_announcement_arrow,
            R.id.tv_time_announcement_status,
            R.id.ll_time_announcement_settings,
            time
        )
        accordionEntry(
            view,
            R.id.ll_notification_reading_header,
            R.id.tv_notification_reading_arrow,
            R.id.tv_notification_reading_status,
            R.id.ll_notification_reading_settings,
            notif
        )
        accordionEntry(
            view, R.id.ll_sms_reading_header, R.id.tv_sms_reading_arrow,
            R.id.tv_sms_reading_status, R.id.ll_sms_reading_settings,
            sms
        )
        accordionEntry(
            view,
            R.id.ll_caller_announcement_header,
            R.id.tv_caller_announcement_arrow,
            R.id.tv_caller_announcement_status,
            R.id.ll_caller_announcement_settings,
            caller
        )

        // مجموعة «إعدادات عامة»: فئات الأصوات / قراءة الأرقام / البطارية
        // / عام / صحة الجهاز
        accordionEntry(
            view, R.id.ll_categories_header, R.id.tv_categories_arrow,
            R.id.tv_categories_status, R.id.ll_categories_content,
            cats
        )
        accordionEntry(
            view, R.id.ll_number_reading_header, R.id.tv_number_reading_arrow,
            R.id.tv_number_reading_status, R.id.ll_numbers_content,
            num
        )
        accordionEntry(
            view,
            R.id.ll_battery_announcement_header,
            R.id.tv_battery_announcement_arrow,
            R.id.tv_battery_announcement_status,
            R.id.ll_battery_announcement_settings,
            battery
        )
        accordionEntry(
            view,
            R.id.ll_general_settings_header,
            R.id.tv_general_settings_arrow,
            R.id.tv_general_settings_status,
            R.id.ll_general_settings_content,
            general
        )
        accordionEntry(
            view, R.id.ll_device_health_header, R.id.tv_device_health_arrow,
            R.id.tv_device_health_status, R.id.ll_device_health_content,
            deviceHealth
        )

        // مجموعة «إعدادات متقدمة»: اختيار الأصوات / قاموس النطق / أدوات
        // التطبيق / مساعدة
        accordionEntry(
            view, R.id.ll_engine_header, R.id.tv_engine_arrow,
            R.id.tv_engine_status, R.id.ll_engine_content,
            engine
        )
        accordionEntry(
            view, R.id.ll_dict_header, R.id.tv_dict_arrow,
            0, R.id.ll_dict_content,
            dict
        )
        accordionEntry(
            view, R.id.ll_tools_header, R.id.tv_tools_arrow,
            0, R.id.ll_tools_content,
            tools
        )
        accordionEntry(
            view, R.id.ll_help_header, R.id.tv_help_arrow,
            0, R.id.ll_help_content,
            help
        )
        accordionEntry(
            view, R.id.ll_emoji_header, R.id.tv_emoji_arrow,
            R.id.tv_emoji_status, R.id.ll_emoji_content,
            emoji
        )
    }

    /** تحديث أسطر الحالة لكل قسم (يُستدعى عند التهيئة وبعد كل تغيير أساسي) */
    fun updateSectionStatuses() {
        setSectionStatus(R.id.ll_engine_content, buildEngineStatus())
        setSectionStatus(R.id.ll_categories_content, buildCategoriesStatus())
        setSectionStatus(R.id.ll_time_announcement_settings, buildTimeStatus())
        setSectionStatus(R.id.ll_numbers_content, buildNumberStatus())
        setSectionStatus(
            R.id.ll_battery_announcement_settings,
            buildBatteryStatus()
        )
        setSectionStatus(
            R.id.ll_notification_reading_settings,
            buildNotificationStatus()
        )
        setSectionStatus(
            R.id.ll_caller_announcement_settings,
            buildCallerStatus()
        )
        setSectionStatus(R.id.ll_sms_reading_settings, buildSmsStatus())
        setSectionStatus(
            R.id.ll_general_settings_content,
            buildGeneralStatus()
        )
        setSectionStatus(
            R.id.ll_device_health_content,
            buildDeviceHealthStatus()
        )
        setSectionStatus(R.id.ll_emoji_content, buildEmojiStatus())
    }

    private fun buildEngineStatus(): String {
        val auto =
            runCatching { settings.isAutoConvertEnabled() }
                .getOrDefault(false)
        val engine = engines.getOrNull(
            spinnerEngine.selectedItemPosition
        )?.label
            ?: fragment.getString(R.string.no_voices_available)
        val autoLabel = if (auto) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        return fragment.getString(R.string.auto_convert_enabled) + ": " +
            autoLabel + "، " + engine
    }

    private fun buildCategoriesStatus(): String {
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        }.getOrNull()
        val name = voices.firstOrNull { it.name == saved }?.displayName
            ?: voices.firstOrNull()?.displayName
            ?: fragment.getString(R.string.no_voices_available)
        return fragment.getString(R.string.voice_category_default) +
            ": " + name
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
        val quietStart =
            runCatching {
                settings.getQuietStartForDay(Calendar.DAY_OF_WEEK)
            }.getOrDefault(23)
        val quietEnd =
            runCatching {
                settings.getQuietEndForDay(Calendar.DAY_OF_WEEK)
            }.getOrDefault(7)
        return buildString {
            val on = if (enabled) fragment.getString(R.string.toggle_on)
            else fragment.getString(R.string.toggle_off)
            append(on)
            append("، ").append(intervalLabel)
            append("، ")
                .append(fragment.getString(R.string.time_quiet_schedule_title))
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
            append("، ")
                .append(fragment.getString(R.string.notification_apps_title))
            append(": ").append(apps)
        }
    }

    private fun buildCallerStatus(): String {
        val enabled = runCatching { settings.isCallerAnnouncementEnabled() }
            .getOrDefault(false)
        val repeat = runCatching { settings.getCallerAnnouncementRepeat() }
            .getOrDefault(1).coerceIn(1, 5)
        val interval =
            runCatching {
                settings.getCallerAnnouncementIntervalSeconds()
            }.getOrDefault(3).coerceIn(1, 10)
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
        val intervalLabel = fragment.resources.getQuantityString(
            R.plurals.caller_announcement_interval_seconds, interval, interval
        )
        return "$on، $label، $intervalLabel"
    }

    private fun buildEmojiStatus(): String {
        val enabled = runCatching { settings.isEmojiPronunciationEnabled() }
            .getOrDefault(true)
        val on = if (enabled) fragment.getString(R.string.toggle_on)
        else fragment.getString(R.string.toggle_off)
        return fragment.getString(R.string.emoji_reading_enabled) + ": " + on
    }

    private fun buildSmsStatus(): String {
        val mode =
            runCatching { settings.getSmsReadingMode() }
                .getOrDefault("off")
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
        val rate =
            runCatching { settings.getDefaultSpeechRate() }
                .getOrDefault(1.0f)
        val volume =
            runCatching { settings.getDefaultVolume() }
                .getOrDefault(1.0f)
        val rateText = String.format(java.util.Locale.US, "%.1fx", rate)
        val volumeText = (volume * 100).toInt().toString() + "%"
        return fragment.getString(R.string.default_speech_rate_label) + ": " +
            rateText + "، " + volumeText
    }

    private fun buildDeviceHealthStatus(): String {
        val items = runCatching { settings.getDeviceHealthItems() }
            .getOrDefault(SettingsRepository.DEFAULT_DEVICE_HEALTH_ITEMS)
        val labels = mutableListOf<String>()
        if (SettingsRepository.DEVICE_HEALTH_BATTERY in items) {
            labels.add(
                fragment.getString(R.string.device_health_battery_label)
            )
        }
        if (SettingsRepository.DEVICE_HEALTH_CHARGING in items) {
            labels.add(
                fragment.getString(R.string.device_health_charging_label)
            )
        }
        if (SettingsRepository.DEVICE_HEALTH_STORAGE in items) {
            labels.add(
                fragment.getString(R.string.device_health_storage_label)
            )
        }
        if (SettingsRepository.DEVICE_HEALTH_MEMORY in items) {
            labels.add(fragment.getString(R.string.device_health_memory_label))
        }
        return if (labels.isEmpty()) fragment.getString(R.string.toggle_off)
        else labels.joinToString("، ")
    }

    /** تسجيل أزرار المجموعات كأزرار مستقلة: ضغطة تفتح شاشة المجموعة */
    private fun setupGroupSections(view: View) {
        groupStates.forEach { g ->
            val header = view.findViewById<View>(g.headerId) ?: return@forEach
            val arrow = view.findViewById<TextView>(
                when (g.headerId) {
                    R.id.ll_group_special_header -> R.id.tv_group_special_arrow
                    R.id.ll_group_general_header -> R.id.tv_group_general_arrow
                    else -> R.id.tv_group_advanced_arrow
                }
            )
            header.contentDescription = groupTitle(g)
            arrow?.text = sectionArrowGlyph()
            header.setOnClickListener { openGroup(g) }
        }
    }

    /** عنوان المجموعة المقروء (إعدادات خاصة/عامة/متقدمة) */
    private fun groupTitle(group: GroupState): String = fragment.getString(
        when (group.headerId) {
            R.id.ll_group_special_header -> R.string.group_special_title
            R.id.ll_group_general_header -> R.string.group_general_title
            else -> R.string.group_advanced_title
        }
    )
}
