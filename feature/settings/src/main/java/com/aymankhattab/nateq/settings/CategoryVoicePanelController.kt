package com.aymankhattab.nateq.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * فئات السبnner العلوي في «الصوت الافتراضي»: خمس فئات صوتية + إعلان المتصل.
 * [categoryKey] هو المفتاح المحفوظ في [SettingsRepository] للفئة الصوتية،
 * أما المتصل فيُحمل عبر مفاتيحه الفرعية (عربي/إنجليزي).
 */
internal enum class CategoryPanelEntry(
    val categoryKey: String,
    val labelRes: Int
) {
    DEFAULT(
        SettingsRepository.VOICE_CATEGORY_DEFAULT,
        R.string.voice_category_default
    ),
    TIME(
        SettingsRepository.VOICE_CATEGORY_TIME,
        R.string.voice_category_time
    ),
    NUMBERS(
        SettingsRepository.VOICE_CATEGORY_NUMBERS,
        R.string.voice_category_numbers
    ),
    NOTIFICATIONS(
        SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
        R.string.voice_category_notifications
    ),
    EMOJI(
        SettingsRepository.VOICE_CATEGORY_EMOJI,
        R.string.voice_category_emoji
    ),
    CALLER(
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
        R.string.section_caller_announcement
    );

    /** هل هذه فئة «إعلان المتصل» (لوحة مستقلة بلا صف الفئة العام)؟ */
    val isCaller: Boolean
        get() = this == CALLER
}

/** مفتاح فئة المتصل الفرعي حسب اللغة المختارة في مُحدِّد لغته. */
internal fun callerSubCategory(languageTag: String): String =
    if (LanguageCode.isEnglish(languageTag)) {
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
    } else {
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
    }

/** الفئة الافتراضية بلا محرك مخصص (سطر المحرك مخفي) — بقية الفئات تملكه. */
internal fun categoryHasDedicatedEngine(categoryKey: String): Boolean =
    categoryKey != SettingsRepository.VOICE_CATEGORY_DEFAULT

/**
 * ضابط لوحة فئات الأصوات في شاشة «الصوت الافتراضي» بعد إعادة التصميم:
 * سبnner فئة علوي واحد يختار الفئة فيُعرض تحكّمها فقط (لغة/محرك/صوت/
 * أشرطة + معاينة) أو لوحة إعلان المتصل (عربي/إنجليزي عبر مُحدِّد لغة).
 *
 * التعديلات «قيد الانتظار» حتى زر الحفظ: علم «متسخ» يفرض تأكيداً عند
 * مغادرة فئة بتعديلات غير محفوظة (لا فقدان صامت ولا حفظ تلقائي)، والحفظ
 * يكتب الفئة المعروضة فقط. المعاينة تُبنى من قيم العرض الحالية وقت الضغط
 * (بند الأوامر 4) لا من القيم المحفوظة القديمة.
 */
internal class CategoryVoicePanelController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val catalog: EngineVoicesCatalog,
    private val onPreviewCaller: (languageTag: String) -> Unit
) {

    // ===== مراجع العرض (تُصفَّر في cleanup — بند 4.1) =====
    private var spinnerSelector: Spinner? = null
    private var llCategoryPanel: View? = null
    private var llCallerPanel: View? = null
    private var llCallerArGroup: View? = null
    private var llCallerEnGroup: View? = null
    private var spinnerCallerLang: Spinner? = null

    // عناصر الصف الواحد (item_category_voice) المُضخوخة في ll_category_panel.
    private var tvCategoryName: TextView? = null
    private var tvCategoryDescription: TextView? = null
    private var spinnerLanguage: Spinner? = null
    private var tvLanguageLabel: TextView? = null
    private var spinnerEngine: Spinner? = null
    private var tvEngineLabel: TextView? = null
    private var spinnerVoice: Spinner? = null
    private var seekRate: SeekBar? = null
    private var tvRateValue: TextView? = null
    private var seekPitch: SeekBar? = null
    private var tvPitchValue: TextView? = null
    private var seekVolume: SeekBar? = null
    private var tvVolumeValue: TextView? = null
    private var btnTest: View? = null

    /** الفئة المعروضة حالياً. */
    private var currentEntry: CategoryPanelEntry = CategoryPanelEntry.DEFAULT

    /** لغة اللوحة الصوتية الحالية (تُحدَّث من سبnner اللغة). */
    private var currentPanelLanguage: String = "ar"

    /** لغة إعلان المتصل المختارة في مُحدِّد اللغتين (ar/en). */
    private var currentCallerLang: String = LanguageCode.AR.tag

    /** خيارات الصوت المعروضة للغة/محرك الفئة الحالية. */
    private var voiceOptions: List<VoiceOption> = emptyList()

    /** تعديلات غير محفوظة على الفئة المعروضة (تُصفَّر بالتحميل/الحفظ). */
    private var dirtyPanel = false

    // لغات فئات الأصوات (عربية/إنجليزية ثم المكتشفة) ومحركات المتصّل.
    private var languages: List<String> = catalog.languages()
    private var languageAdapter = simpleAdapter(
        fragment.requireContext(),
        languages.map { catalog.languageDisplayName(it) }
    )
    private val categoryEngines =
        runCatching {
            EnginePicker.installedEngines(fragment.requireContext())
        }.getOrDefault(emptyList())
    private val engineAdapter = simpleAdapter(
        fragment.requireContext(),
        categoryEngines.map { it.label }
    )

    // **بند 6.2:** علم الربط البرمجي — يُسنَّع حول setSelection وsetProgress
    // حتى لا يُسجَّل اختيارٌ برمجي أو يُفسَّر إسنادُ الشريط تعديلَ مستخدم.
    private var bindingInputs = false

    /** يربط العرض: سبnner الفئة واللوحتين ومستمعي كل تحكّم، ثم يفتح
     *  على فئة «النصوص العامة» (اول الفئات منطقياً). */
    fun setup(view: View) {
spinnerSelector =
            view.findViewWithTag<Spinner>("spinner_category_selector")
        llCategoryPanel =
            view.findViewWithTag<View>("ll_category_panel")
        llCallerPanel =
            view.findViewWithTag<View>("ll_caller_default_voice_panel")
        llCallerArGroup = view.findViewWithTag<View>("ll_caller_voice_ar_group")
        llCallerEnGroup = view.findViewWithTag<View>("ll_caller_voice_en_group")
        spinnerCallerLang =
            view.findViewWithTag<Spinner>("spinner_caller_default_lang")
        inflateVoicePanelRow()
        bindSelector(view)
        bindCallerPanel(view)
        currentEntry = CategoryPanelEntry.DEFAULT
        bindEntry(currentEntry)
    }

    /** يضخ صف الفئة الواحدة (item_category_voice) ويُثبّت مستمعيه مرة
     *  واحدة — كل تعديل يُعلّم اللوحة «متسخة» بلا كتابة فورية. */
    private fun inflateVoicePanelRow() {
        val container = llCategoryPanel as? ViewGroup ?: return
        val row = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.item_category_voice, container, false)
        container.addView(row)
        tvCategoryName = row.findViewById(R.id.tv_category_name)
        tvCategoryDescription = row.findViewById(R.id.tv_category_description)
        spinnerLanguage = row.findViewById(R.id.spinner_category_language)
        tvLanguageLabel = row.findViewById(R.id.tv_category_language_label)
        spinnerEngine = row.findViewById(R.id.spinner_category_engine)
        tvEngineLabel = row.findViewById(R.id.tv_category_engine_label)
        spinnerVoice = row.findViewById(R.id.spinner_category_voice)
        seekRate = row.findViewById(R.id.seek_category_speech_rate)
        tvRateValue = row.findViewById(R.id.tv_category_speech_rate_value)
        seekPitch = row.findViewById(R.id.seek_category_pitch)
        tvPitchValue = row.findViewById(R.id.tv_category_pitch_value)
        seekVolume = row.findViewById(R.id.seek_category_volume)
        tvVolumeValue = row.findViewById(R.id.tv_category_volume_value)
        btnTest = row.findViewById(R.id.btn_test_category_voice)

        spinnerLanguage?.adapter = languageAdapter
        spinnerEngine?.adapter = engineAdapter

        // تغيير لغة الفئة: تحديث أصوات اللغة الجديدة فقط — بلا كتابة حتى
        // زر الحفظ.
        spinnerLanguage?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    if (bindingInputs) return
                    val language = languages.getOrNull(pos) ?: return
                    if (language == currentPanelLanguage) return
                    currentPanelLanguage = language
                    markDirty()
                    refreshVoiceSpinner()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        spinnerEngine?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    if (bindingInputs) return
                    // الفئة الافتراضية بلا محرك خاص — لا يُغيّر
                    // اختيارُها شيئاً.
                    if (currentEntry.isCaller ||
                        currentEntry.categoryKey ==
                        SettingsRepository.VOICE_CATEGORY_DEFAULT
                    ) {
                        return
                    }
                    markDirty()
                    refreshVoiceSpinner()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        spinnerVoice?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    if (bindingInputs) return
                    if (pos !in voiceOptions.indices) return
                    markDirty()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // أشرطة السرعة/النبرة/الصوت: تحديث التسمية والحالة ثم «متسخ».
        val appContext = fragment.requireContext()
        bindSeekBar(
            seekRate, tvRateValue, R.string.seek_category_speech_rate
        ) { value -> RateLabel.of(appContext, value.speedFactor()) }
        bindSeekBar(
            seekPitch, tvPitchValue, R.string.seek_category_pitch
        ) { value -> RateLabel.of(appContext, value.speedFactor()) }
        bindSeekBar(
            seekVolume, tvVolumeValue, R.string.seek_category_volume
        ) { value -> "$value%" }

        // زر معاينة الفئة: يُبنى من قيم العرض الحالية (سرعات/نبرة/صوت
        // معروضة حتى غير محفوظة).
        btnTest?.setOnClickListener { previewCurrentCategory() }
    }

    /** مثبّت مشترك لأشرطة اللوحة: تحديث القيمة الظاهرة ثم «متسخ» عند
     *  تحرّك المستخدم، وحفظ غير مباشر (الكتابة تنتظر الزر). */
    private fun bindSeekBar(
        seekBar: SeekBar?,
        valueView: TextView?,
        descriptionRes: Int,
        format: (Int) -> String
    ) {
        seekBar?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (bindingInputs) return
                    val text = format(progress)
                    valueView?.text = text
                    valueView?.setSeekStateDescription(text)
                    markDirty()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (bindingInputs) return
                    bindSeekBarSnap(seekBar, descriptionRes, format)
                }
            }
        )
    }

    /** لقطة نهاية اللمس: تثبيت الحد الأدنى وإعلان القيمة لقارئ الشاشة. */
    private fun bindSeekBarSnap(
        seekBar: SeekBar,
        descriptionRes: Int,
        format: (Int) -> String
    ) {
        if (currentEntry.isCaller) return
        seekBar.snapSpeedMin()
        val text = format(seekBar.progress)
        seekBar.announceCompat(text)
        valueFor(descriptionRes)?.text = text
    }

    /** يربط سبnner الفئة: تغييره يعرض فئة أخرى بتعديلاتها المحفوظة مع
     *  تأكيد عند التعديلات غير المحفوظة. */
    private fun bindSelector(view: View) {
        val adapter = simpleAdapter(
            view.context,
            CategoryPanelEntry.entries.map { entry ->
                view.context.getString(entry.labelRes)
            }
        )
        spinnerSelector?.adapter = adapter
        spinnerSelector?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    val entry = CategoryPanelEntry.entries
                        .getOrNull(pos) ?: return
                    requestSwitch(entry)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    /** يربط لوحة إعلان المتصل: مُحدِّد اللغة (عربي/إنجليزي) يبدّل ترائية
     *  المجموعتين، وزر المعاينة يقرأ القيم المعروضة للغة المختارة. */
    private fun bindCallerPanel(view: View) {
        spinnerCallerLang?.adapter = simpleAdapter(
            view.context,
            listOf(
                view.context.getString(R.string.language_arabic),
                view.context.getString(R.string.language_english)
            )
        )
        spinnerCallerLang?.setSelection(
            if (LanguageCode.isEnglish(currentCallerLang)) 1 else 0
        )
        spinnerCallerLang?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    selectCallerLanguage(
                        if (pos == 1) LanguageCode.EN.tag
                        else LanguageCode.AR.tag
                    )
                    // تغيير اللغة مجرد تنقّل داخل نفس الفئة —
                    // يُحافظ على حالة «متسخ» الحالية إن وجدت.
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        view.findViewById<View>(R.id.btnPreviewCaller)
            ?.setOnClickListener { onPreviewCaller(currentCallerLang) }
    }

    /** يعرض لغة المتصل المختارة (عربي/إنجليزي) ويُخفي الأخرى. */
    private fun selectCallerLanguage(languageTag: String) {
        val isArabic = !LanguageCode.isEnglish(languageTag)
        currentCallerLang = if (isArabic) {
            LanguageCode.AR.tag
        } else {
            LanguageCode.EN.tag
        }
        llCallerArGroup?.isVisible = isArabic
        llCallerEnGroup?.isVisible = !isArabic
    }

    /** طلب تبديل الفئة: تعديلاتٌ غير محفوظة تفرض تأكيد المستخدم. */
    private fun requestSwitch(newEntry: CategoryPanelEntry) {
        if (newEntry == currentEntry) return
        if (!dirtyPanel) {
            bindEntry(newEntry)
            return
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.categories_unsaved_dialog_title)
            .setMessage(R.string.categories_unsaved_dialog_message)
            .setPositiveButton(R.string.categories_unsaved_keep) { _, _ ->
                restoreSelectorToCurrent()
            }
            .setNegativeButton(R.string.categories_unsaved_discard) { _, _ ->
                bindEntry(newEntry)
            }
            .setOnCancelListener { restoreSelectorToCurrent() }
            .create().also { fragment.trackDialog(it) }.show()
    }

    /** يردّ سبnner الفئة لموضع الفئة الحالية دون إطلاق المستمع. */
    private fun restoreSelectorToCurrent() {
        bindingInputs = true
        try {
            spinnerSelector?.setSelection(currentEntry.ordinal)
        } finally {
            bindingInputs = false
        }
    }

    /** يعرض الفئة المختارة: لوحة الفئة العامّة أو لوحة إعلان المتصل،
     *  ويربط قيم [CategoryPanelEntry] إلى تحكّم العرض. */
    private fun bindEntry(entry: CategoryPanelEntry) {
        currentEntry = entry
        dirtyPanel = false
        if (!entry.isCaller) {
            selectorVisible()
            callerHidden()
            bindCategoryRow(entry)
        } else {
            selectorHidden()
            callerVisible()
            selectCallerLanguage(currentCallerLang)
        }
    }

    private fun selectorVisible() {
        llCallerPanel?.isVisible = false
        llCategoryPanel?.isVisible = true
    }

    private fun callerVisible() {
        llCategoryPanel?.isVisible = false
        llCallerPanel?.isVisible = true
    }

    private fun callerHidden() {
        llCallerPanel?.isVisible = false
    }

    private fun selectorHidden() {
        llCategoryPanel?.isVisible = false
    }

    /** يملأ صف الفئة العامّة بقيم [categoryKey] المحفوظة تحت علم الربط. */
    private fun bindCategoryRow(entry: CategoryPanelEntry) {
        val key = entry.categoryKey
        val context = fragment.requireContext()
        currentPanelLanguage = runCatching {
            settings.getLanguageForCategory(key)
        }.getOrNull() ?: defaultLanguageFor(key)
        tvCategoryName?.text = context.getString(entry.labelRes)
        tvCategoryDescription?.text = categoryDescription(context, key)

        val hasEngine = categoryHasDedicatedEngine(key) &&
            categoryEngines.isNotEmpty()
        tvEngineLabel?.isVisible = hasEngine
        spinnerEngine?.isVisible = hasEngine

        bindingInputs = true
        try {
            spinnerLanguage?.adapter = languageAdapter
            val langIdx = languages.indexOf(currentPanelLanguage)
            spinnerLanguage?.setSelection(if (langIdx >= 0) langIdx else 0)
            val savedEngine =
                runCatching { settings.getEngineForCategory(key) }
                    .getOrNull()
            spinnerEngine?.setSelection(
                engineIndexFor(categoryEngines, savedEngine)
            )
        } finally {
            bindingInputs = false
        }
        refreshVoiceSpinner()

        val rate = runCatching { settings.getSpeechRateForCategory(key) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        tvRateValue?.text = RateLabel.of(context, rate)
        val pitch = runCatching { settings.getPitchForCategory(key) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        tvPitchValue?.text = RateLabel.of(context, pitch)
        val volume = runCatching { settings.getVolumeForCategory(key) }
            .getOrDefault(1.0f)
        tvVolumeValue?.text = "${(volume * 100).toInt()}%"
        bindingInputs = true
        try {
            seekRate?.progress = (rate * 100).toInt().coerceIn(0, 200)
            seekPitch?.progress = (pitch * 100).toInt().coerceIn(0, 200)
            seekVolume?.progress = (volume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingInputs = false
        }
    }

    /** وصف الفئة المقروء أسفل عنوانها. */
    private fun categoryDescription(
        context: android.content.Context,
        key: String
    ): String =
        when (key) {
            SettingsRepository.VOICE_CATEGORY_TIME ->
                context.getString(R.string.voice_category_time_summary)
            SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                context.getString(R.string.voice_category_numbers_summary)
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                context.getString(R.string.voice_category_notifications_summary)
            SettingsRepository.VOICE_CATEGORY_EMOJI ->
                context.getString(R.string.voice_category_emoji_summary)
            else -> context.getString(R.string.voice_category_default_summary)
        }

    /** لغة الفئة إن لم تُحفظ صراحةً: تُستنتج من صوتها المحفوظ عبر الكتالوج،
     *  وإلا فالعربية — منطق [defaultLanguageFor] القديم دون تغيير. */
    private fun defaultLanguageFor(categoryKey: String): String {
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(categoryKey)
        }.getOrNull().orEmpty()
        if (saved.isBlank()) return "ar"
        return catalog.languageForSavedVoice(saved) ?: "ar"
    }

    /** يعيد بناء سبnner الأصوات للغة/محرك الفئة الحالية: أصوات (اللغة،
     *  محرك الفئة) مع سقوطٍ منطقي ثم اختيار الصوت المحفوظ تحت علم الربط. */
    private fun refreshVoiceSpinner() {
        val key = currentEntry.categoryKey
        val engine = if (categoryHasDedicatedEngine(key)) {
            runCatching { settings.getEngineForCategory(key) }.getOrNull()
        } else {
            null
        }
        voiceOptions = catalog.voicesFor(currentPanelLanguage, engine)
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(key)
        }.getOrNull()
        bindingInputs = true
        try {
            spinnerVoice?.adapter = simpleAdapter(
                fragment.requireContext(),
                voiceOptions.map { it.label }
            )
            val idx = voiceOptions.indexOfFirst { it.name == saved }
            spinnerVoice?.setSelection(if (idx >= 0) idx else 0)
        } finally {
            bindingInputs = false
        }
    }

    /** يعيد بناء قوائم اللغات بعد اكتمال الاكتشاف الخلفي ثم يعيد ربط
     *  الفئة المعروضة (المعادل لـ refreshLanguages القديم). */
    internal fun refreshLanguages() {
        languages = catalog.languages()
        languageAdapter = simpleAdapter(
            fragment.requireContext(),
            languages.map { catalog.languageDisplayName(it) }
        )
        if (currentEntry.isCaller) return
        bindCategoryRow(currentEntry)
    }

    /** يعيد تحميل الفئة المعروضة من القيم المحفوظة (بعد استعادة/إعادة
     *  ضبط) — يُصفّر أي تعديلات غير محفوظة سابقة. */
    internal fun reloadCurrent() {
        dirtyPanel = false
        if (currentEntry.isCaller) {
            selectCallerLanguage(currentCallerLang)
        } else {
            bindCategoryRow(currentEntry)
        }
    }

    /** حفظٌ صريح: يكتب الفئة المعروضة فقط. للمتصل يكتب فرعيَّ اللغة
     *  معاً (كلاهما محمّل في اللوحة ومحرَّر)، وللفئة الصوتية يكتب
     *  لغتها ومحركها وصوتها وأشرطتها. */
    internal fun saveCurrent() {
        val entry = currentEntry
        if (entry.isCaller) {
            saveCallerCategory()
        } else {
            saveVoiceCategory(entry.categoryKey)
        }
        dirtyPanel = false
    }

    private fun saveVoiceCategory(key: String) {
        val langIdx = spinnerLanguage?.selectedItemPosition ?: -1
        if (langIdx in languages.indices) {
            runCatching {
                settings.setLanguageForCategory(key, languages[langIdx])
            }
        }
        if (categoryHasDedicatedEngine(key)) {
            val engineIdx = spinnerEngine?.selectedItemPosition ?: -1
            if (engineIdx in categoryEngines.indices) {
                runCatching {
                    settings.setEngineForCategory(
                        key, categoryEngines[engineIdx].packageName
                    )
                }
            }
        }
        val voiceIdx = spinnerVoice?.selectedItemPosition ?: -1
        if (voiceIdx in voiceOptions.indices) {
            runCatching {
                settings.setPreferredVoiceIdForCategory(
                    key, voiceOptions[voiceIdx].name
                )
            }
        }
        runCatching {
            settings.setSpeechRateForCategory(
                key, seekRate?.progress?.speedFactor() ?: 1.0f
            )
        }
        runCatching {
            settings.setPitchForCategory(
                key, seekPitch?.progress?.speedFactor() ?: 1.0f
            )
        }
        runCatching {
            settings.setVolumeForCategory(
                key, (seekVolume?.progress ?: 100) / 100f
            )
        }
    }

    private fun saveCallerCategory() {
        saveCallerSide(
            spinnerCallerEngineArFor(),
            spinnerCallerVoiceArFor(),
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
        )
        saveCallerSide(
            spinnerCallerEngineEnFor(),
            spinnerCallerVoiceEnFor(),
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
        )
    }

    /** يكتب محرك وصوت جانبٍ واحدٍ من المتصل (عربي أو إنجليزي) من مواضع
     *  سبنريه الحالية. */
    private fun saveCallerSide(
        engineSpinner: Spinner?,
        voiceSpinner: Spinner?,
        categoryKey: String
    ) {
        val engineIdx = engineSpinner?.selectedItemPosition ?: -1
        if (engineIdx in categoryEngines.indices) {
            runCatching {
                settings.setEngineForCategory(
                    categoryKey, categoryEngines[engineIdx].packageName
                )
            }
        }
        val voice = callerVoicesFor(categoryKey)
            .getOrNull(voiceSpinner?.selectedItemPosition ?: -1)
        if (voice != null) {
            runCatching {
                settings.setPreferredVoiceIdForCategory(
                    categoryKey, voice.name
                )
            }
        }
    }

    private fun spinnerCallerEngineArFor(): Spinner? =
        fragment.view?.findViewById(R.id.spinner_caller_engine_ar)

    private fun spinnerCallerVoiceArFor(): Spinner? =
        fragment.view?.findViewById(R.id.spinner_caller_voice_ar)

    private fun spinnerCallerEngineEnFor(): Spinner? =
        fragment.view?.findViewById(R.id.spinner_caller_engine_en)

    private fun spinnerCallerVoiceEnFor(): Spinner? =
        fragment.view?.findViewById(R.id.spinner_caller_voice_en)

    /** أصوات جانب متصل محفوظة (caller_ar/caller_en) من كتالوج الفئات —
     *  تُستخدم فقط لحفظ اختيار الـ Spinner الحالي. */
    private fun callerVoicesFor(categoryKey: String): List<VoiceOption> {
        val engine = runCatching {
            settings.getEngineForCategory(categoryKey)
        }.getOrNull()
        val language = if (
            categoryKey == SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
        ) {
            "en"
        } else {
            "ar"
        }
        return catalog.voicesFor(language, engine)
    }

    /** معاينة فئة عامّة من قيم العرض الحالية (بند الأوامر 4). */
    private fun previewCurrentCategory() {
        val entry = currentEntry
        if (entry.isCaller) return
        val isArabic = LanguageCode.isArabic(currentPanelLanguage)
        val sampleText = categorySampleText(entry.categoryKey, isArabic)
        val voiceIndex = spinnerVoice?.selectedItemPosition ?: 0
        val voiceName = voiceOptions
            .getOrNull(voiceIndex)?.name.orEmpty()
        val enginePkg = if (categoryHasDedicatedEngine(entry.categoryKey)) {
            categoryEngines
                .getOrNull(spinnerEngine?.selectedItemPosition ?: 0)
                ?.packageName
        } else {
            null
        }
        fragment.previewSpeech(
            buildPreviewParamsFrom(
                voiceName = voiceName,
                languageTag = currentPanelLanguage,
                enginePkg = enginePkg,
                rateProgress = seekRate?.progress ?: 100,
                pitchProgress = seekPitch?.progress ?: 100,
                volumePercent = seekVolume?.progress ?: 100,
                sampleText = sampleText
            )
        )
    }

    /** نص عينة الفئة بلغة العرض (مطابق سلوك صف الكتالوج القديم). */
    private fun categorySampleText(
        categoryKey: String,
        isArabic: Boolean
    ): String {
        val res = fragment.resources
        return when {
            !isArabic &&
                categoryKey == SettingsRepository.VOICE_CATEGORY_TIME ->
                res.getString(R.string.sample_text_time_en)
            !isArabic &&
                categoryKey == SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                res.getString(R.string.sample_text_numbers_en)
            !isArabic &&
                categoryKey ==
                    SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                res.getString(R.string.sample_text_notifications_en)
            !isArabic &&
                categoryKey == SettingsRepository.VOICE_CATEGORY_EMOJI ->
                res.getString(R.string.sample_text_emoji_en)
            !isArabic ->
                res.getString(R.string.sample_text_default_en)
            categoryKey == SettingsRepository.VOICE_CATEGORY_TIME ->
                res.getString(R.string.sample_text_time_ar)
            categoryKey == SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                res.getString(R.string.sample_text_numbers_ar)
            categoryKey ==
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                res.getString(R.string.sample_text_notifications_ar)
            categoryKey == SettingsRepository.VOICE_CATEGORY_EMOJI ->
                res.getString(R.string.sample_text_emoji_ar)
            else -> res.getString(R.string.sample_text_default_ar)
        }
    }

    private fun markDirty() {
        dirtyPanel = true
    }

    /** عنصر القيمة المصاحب لشريط ما عند لقطة نهاية اللمس. */
    private fun valueFor(descriptionRes: Int): TextView? =
        when (descriptionRes) {
        R.string.seek_category_speech_rate -> tvRateValue
        R.string.seek_category_pitch -> tvPitchValue
        else -> tvVolumeValue
    }

    /** يصفّر كل مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        spinnerSelector = null
        llCategoryPanel = null
        llCallerPanel = null
        llCallerArGroup = null
        llCallerEnGroup = null
        spinnerCallerLang = null
        tvCategoryName = null
        tvCategoryDescription = null
        spinnerLanguage = null
        tvLanguageLabel = null
        spinnerEngine = null
        tvEngineLabel = null
        spinnerVoice = null
        seekRate = null
        tvRateValue = null
        seekPitch = null
        tvPitchValue = null
        seekVolume = null
        tvVolumeValue = null
        btnTest = null
    }
}