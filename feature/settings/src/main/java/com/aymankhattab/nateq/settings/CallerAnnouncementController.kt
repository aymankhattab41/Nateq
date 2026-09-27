package com.aymankhattab.nateq.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode

/** ضابط قسم «إعلان اسم المتصل»: التفعيل بالأذونات، التكرار، السرعة،
 *  القالب والأصوات. */
internal class CallerAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val catalog: EngineVoicesCatalog,
    private val onStatusChanged: () -> Unit
) {

    companion object {
        /** بوّابة تفعيل نطق اسم المتصل. [requiresCallLog] صحيح على أندرويد 12+
         *  (API 31+): ثم لا يُسلَّم رقم المتصل في بث PHONE_STATE إلا بإذن
         *  READ_CALL_LOG صراحةً — حتى مع READ_CONTACTS — فيُشرَط ضرورةً،
         *  وإلا ينطق التطبيق عبارةً عامة بلا اسم. قبل API 31 يكفي أحد
         *  مصدرَي الاسم (السجل أو جهات الاتصال). */
        internal fun canEnable(
            phoneGranted: Boolean,
            callLogGranted: Boolean,
            contactsGranted: Boolean,
            requiresCallLog: Boolean
        ): Boolean = if (requiresCallLog) {
            phoneGranted && callLogGranted
        } else {
            phoneGranted && (callLogGranted || contactsGranted)
        }
    }

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var switchCallerAnnouncement: SwitchMaterial? = null
    private var cbCallerAnnounceDuringCall:
        androidx.appcompat.widget.AppCompatCheckBox? = null
    private var spinnerCallerRepeat: Spinner? = null
    private var spinnerCallerInterval: Spinner? = null
    private var seekCallerRate: SeekBar? = null
    private var tvCallerRateValue: TextView? = null
    private var seekCallerVolume: SeekBar? = null
    private var tvCallerVolumeValue: TextView? = null
    private var seekCallerPitch: SeekBar? = null
    private var tvCallerPitchValue: TextView? = null
    private var etCallerTemplate:
        com.google.android.material.textfield.TextInputEditText? = null
    private var templateWatcher: android.text.TextWatcher? = null
    private var spinnerCallerVoiceAr: Spinner? = null
    private var spinnerCallerVoiceEn: Spinner? = null
    private var spinnerCallerEngineAr: Spinner? = null
    private var spinnerCallerEngineEn: Spinner? = null
    private var tvCallerEngineArLabel: TextView? = null
    private var tvCallerEngineEnLabel: TextView? = null

    /** خيارات محرك نطق المتصل: المحركات المثبتة */
    private var callerEngineOptions: List<EnginePicker.InstalledEngine> =
        emptyList()

    /** يمنع مناداة المستمع من رد الطلب (تفادي إعادة طلب الأذونات دورياً) */
    private var callerSwitchGuard = false

    // **بند 6.3:** علمُ الربط البرمجي — يُسنَّع حول setProgress في setup حتى
    // لا يُفسَّر الإسنادُ البرمجي تعديلَ مستخدم. دون الحفظ في
    // onProgressChanged كان تعديل TalkBack (عبر أداء الوصول، لا يمر عبر
    // onStopTrackingTouch إطلاقاً) يفقد أي تعديل على أشرطة التمرير.
    private var bindingSlider = false

    // أصوات مسارات المتصل (عربي/إنجليزي) وأعلام الربط.
    private var callerVoiceOptionsAr: List<VoiceOption> = emptyList()
    private var callerVoiceOptionsEn: List<VoiceOption> = emptyList()
    private var bindingVoices = false

    /** يمنع تكرار حوار «أُلغيت أذونات المتصل» أكثر من مرة
     *  لكل دورة فتح إعدادات */
    private var callerRevokedDialogShown = false

    fun setup(view: View) {
        switchCallerAnnouncement =
            view.findViewById(R.id.switch_caller_announcement)
        // المربع بلا android:id (منشئ الـ binding في حدود 255 معاملاً) —
        // يُسترجَع عبر android:tag بدل findViewById.
        cbCallerAnnounceDuringCall =
            view.findViewWithTag<
                androidx.appcompat.widget.AppCompatCheckBox
                >("cb_caller_announce_during_call")
        spinnerCallerRepeat = view.findViewById(R.id.spinner_caller_repeat)
        spinnerCallerInterval = view.findViewById(R.id.spinner_caller_interval)
        seekCallerRate = view.findViewById(R.id.seek_caller_rate)
        tvCallerRateValue = view.findViewById(R.id.tv_caller_rate_value)
        seekCallerVolume = view.findViewById(R.id.seek_caller_volume)
        tvCallerVolumeValue = view.findViewById(R.id.tv_caller_volume_value)
        seekCallerPitch = view.findViewById(R.id.seek_caller_pitch)
        tvCallerPitchValue = view.findViewById(R.id.tv_caller_pitch_value)
        etCallerTemplate = view.findViewById(R.id.et_caller_template)
        spinnerCallerVoiceAr = view.findViewById(R.id.spinner_caller_voice_ar)
        spinnerCallerVoiceEn = view.findViewById(R.id.spinner_caller_voice_en)
        spinnerCallerEngineAr =
            view.findViewById(R.id.spinner_caller_engine_ar)
        spinnerCallerEngineEn =
            view.findViewById(R.id.spinner_caller_engine_en)
        tvCallerEngineArLabel =
            view.findViewById(R.id.tv_caller_engine_ar_label)
        tvCallerEngineEnLabel =
            view.findViewById(R.id.tv_caller_engine_en_label)

        // محركات TTS المثبتة (قائمة واحدة مشتركة للسبنرين).
        callerEngineOptions = runCatching {
            EnginePicker.installedEngines(fragment.requireContext())
        }.getOrDefault(emptyList())
        setupCallerEngineSpinner(
            spinnerCallerEngineAr,
            tvCallerEngineArLabel,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            refreshArabic = true
        )
        setupCallerEngineSpinner(
            spinnerCallerEngineEn,
            tvCallerEngineEnLabel,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN,
            refreshEnglish = true
        )

        // المفتاح الرئيسي: عند التفعيل نطلب الأذونات أولاً
        // (لا نفعّل إلا بمنحها)
        switchCallerAnnouncement?.isChecked =
            runCatching { settings.isCallerAnnouncementEnabled() }
                .getOrDefault(false)
        switchCallerAnnouncement?.setOnCheckedChangeListener { _, checked ->
            if (callerSwitchGuard) return@setOnCheckedChangeListener
            if (checked) {
                requestCallerPermissionsWithRationale()
            } else {
                runCatching { settings.setCallerAnnouncementEnabled(false) }
                AnnouncementSchedulerService.syncIfRunning(
                    fragment.requireContext()
                )
                onStatusChanged()
                fragment.view?.announceCompat(
                    fragment.getString(R.string.announcement_turned_off)
                )
            }
        }

        // مربع نطق اسم المتصل الوارد أثناء مكالمة نشطة (مكالمة انتظار) —
        // غير محدد افتراضياً، مستقل عن مفتاح التفعيل.
        cbCallerAnnounceDuringCall?.isChecked =
            runCatching {
                settings.isCallerAnnouncementDuringCallEnabled()
            }.getOrDefault(false)
        cbCallerAnnounceDuringCall?.setOnCheckedChangeListener { _, checked ->
            runCatching {
                settings.setCallerAnnouncementDuringCallEnabled(checked)
            }
            onStatusChanged()
            fragment.view?.announceCompat(
                if (checked) {
                    fragment.getString(R.string.announcement_turned_on)
                } else {
                    fragment.getString(R.string.announcement_turned_off)
                }
            )
        }

        // عدد مرات التكرار
        val repeats = listOf(
            fragment.getString(R.string.repeat_once),
            fragment.getString(R.string.repeat_twice),
            fragment.getString(R.string.repeat_3),
            fragment.getString(R.string.repeat_4),
            fragment.getString(R.string.repeat_5)
        )
        spinnerCallerRepeat?.adapter = fragment.simpleAdapter(repeats)
        val savedRepeat =
            runCatching { settings.getCallerAnnouncementRepeat() }
                .getOrDefault(1)
        spinnerCallerRepeat?.setSelection(
            (savedRepeat - 1).coerceIn(0, repeats.size - 1)
        )
        spinnerCallerRepeat?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setCallerAnnouncementRepeat(position + 1)
                }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // الفاصل الزمني (بالثواني) بين كل مرة نطق
        val intervals = (1..10).map { s ->
            fragment.requireContext().resources.getQuantityString(
                R.plurals.caller_announcement_interval_seconds, s, s
            )
        }
        spinnerCallerInterval?.adapter = fragment.simpleAdapter(intervals)
        val savedInterval =
            runCatching { settings.getCallerAnnouncementIntervalSeconds() }
                .getOrDefault(3)
        spinnerCallerInterval?.setSelection(
            (savedInterval - 1).coerceIn(0, intervals.size - 1)
        )
        spinnerCallerInterval?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setCallerAnnouncementIntervalSeconds(position + 1)
                }
                onStatusChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val callerRate =
            runCatching { settings.getCallerAnnouncementRate() }
                .getOrDefault(1.0f)
                .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        tvCallerRateValue?.text = RateLabel.of(
            fragment.requireContext(), callerRate
        )
        bindingSlider = true
        try {
            seekCallerRate?.progress =
                (callerRate * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        seekCallerRate?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvCallerRateValue?.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvCallerRateValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching { settings.setCallerAnnouncementRate(value) }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching { settings.setCallerAnnouncementRate(value) }
                seekBar.announceCompat(
                    RateLabel.of(fragment.requireContext(), value)
                )
                onStatusChanged()
            }
        })

        // مستوى الصوت
        val callerVolume =
            runCatching { settings.getCallerAnnouncementVolume() }
                .getOrDefault(1.0f)
        tvCallerVolumeValue?.text = "${(callerVolume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekCallerVolume?.progress =
                (callerVolume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        seekCallerVolume?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                tvCallerVolumeValue?.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvCallerVolumeValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setCallerAnnouncementVolume(progress / 100f)
                }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching {
                    settings.setCallerAnnouncementVolume(
                        seekBar.progress / 100f
                    )
                }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // نبرة إعلان المتصل (بند 2.2) — مستقلة عن نبرة نطق اللغة؛
        // إن لم تُضبط تُعرض نبرةُ لغة التطبيق (ما سيُستعمل فعلاً).
        val callerAppLang = runCatching { settings.getAppLanguage() }
            .getOrNull() ?: "ar"
        val callerPitch =
            runCatching {
                settings.getCallerAnnouncementPitchOrDefault(callerAppLang)
            }
                .getOrDefault(1.0f)
                .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        tvCallerPitchValue?.text = RateLabel.of(
            fragment.requireContext(), callerPitch
        )
        bindingSlider = true
        try {
            seekCallerPitch?.progress =
                (callerPitch * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        seekCallerPitch?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvCallerPitchValue?.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvCallerPitchValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching { settings.setCallerAnnouncementPitch(value) }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching {
                    settings.setCallerAnnouncementPitch(value)
                }
                seekBar.announceCompat(
                    RateLabel.of(fragment.requireContext(), value)
                )
                onStatusChanged()
            }
        })

        // قالب إعلان المتصل: {name} لاسم المتصل
        etCallerTemplate?.setText(
            runCatching { settings.getCallerAnnouncementTemplate() }
                .getOrNull()
        )
        templateWatcher?.let { etCallerTemplate?.removeTextChangedListener(it) }
        val callerWatcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    settings.setCallerAnnouncementTemplate(
                        s?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
        templateWatcher = callerWatcher
        etCallerTemplate?.addTextChangedListener(callerWatcher)

        // صوت نطق الأسماء العربية في إعلان المتصل (من الكتالوج).
        spinnerCallerVoiceAr?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (bindingVoices) return
                val name = callerVoiceOptionsAr.getOrNull(position)?.name
                    ?: return
                runCatching {
                    settings.setCallerAnnouncementArabicVoiceId(name)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // صوت نطق الأسماء الإنجليزية في إعلان المتصل (من الكتالوج).
        spinnerCallerVoiceEn?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (bindingVoices) return
                val name = callerVoiceOptionsEn.getOrNull(position)?.name
                    ?: return
                runCatching {
                    settings.setCallerAnnouncementEnglishVoiceId(name)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshCallerVoices()

        // استرداد ذكي: إذا كانت ميزة المتصّل مفعّلة لكن أذوناتها سُحبت (سحب
        // النظام التلقائي للأذونات غير المستخدمة، خصوصاً على أندرويد 11+)
        // نكتشف ذلك فور فتح الإعدادات ونعرض إعادة المنح بدل تركه صامتاً.
        checkRevokedPermissionsAndRecover()
    }

    /** يضبط سبنر محرك إحدى اللغتين: يُبنى بـ«تلقائي» ثم المحركات المثبتة،
     *  يحدد القيمة المحفوظة لفئته، ويحفظ اختيار المستخدم لفئته فقط ثم
     *  يُحدّث أصوات تلك اللغة. */
    private fun setupCallerEngineSpinner(
        spinner: Spinner?,
        label: TextView?,
        category: String,
        refreshArabic: Boolean = false,
        refreshEnglish: Boolean = false
    ) {
        val labels = callerEngineOptions.map { it.label }
        // لا محركات مثبتة: إخفاء سبنر المحرك وتسميته (لا خيار «تلقائي»
        // ليعرضه).
        val visibility = if (callerEngineOptions.isEmpty()) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        spinner?.visibility = visibility
        label?.visibility = visibility
        spinner?.adapter = fragment.simpleAdapter(labels)
        val saved = runCatching {
            settings.getEngineForCategory(category)
        }.getOrNull()
        spinner?.setSelection(
            engineIndexFor(callerEngineOptions, saved)
        )
        spinner?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?,
                pos: Int, id: Long
            ) {
                val pkg = callerEngineOptions
                    .getOrNull(pos)?.packageName
                runCatching {
                    settings.setEngineForCategory(category, pkg)
                }
                if (refreshArabic) refreshCallerArabicVoices()
                if (refreshEnglish) refreshCallerEnglishVoices()
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** معاينة «متصل من أحمد» باللغة المختارة (عربي/إنجليزي) بصوت ومحرك
     *  تلك اللغة وتقدم الشرائط الحالية (لا القيم المحفوظة القديمة) —
     *  تُنادَى من لوحة «إعلان المتصل» في شاشة الصوت الافتراضي. */
    fun previewCaller(languageTag: String) {
        val isArabic = !LanguageCode.isEnglish(languageTag)
        val engineIdx = if (isArabic) {
            spinnerCallerEngineAr?.selectedItemPosition
        } else {
            spinnerCallerEngineEn?.selectedItemPosition
        } ?: 0
        val enginePkg = callerEngineOptions
            .getOrNull(engineIdx)?.packageName
        val voices = if (isArabic) {
            callerVoiceOptionsAr
        } else {
            callerVoiceOptionsEn
        }
        val voiceSpinner = if (isArabic) {
            spinnerCallerVoiceAr
        } else {
            spinnerCallerVoiceEn
        }
        val sample = fragment.getString(R.string.sample_text_caller_preview)
        fragment.previewSpeech(
            buildPreviewParamsFrom(
                voiceName = voices.getOrNull(
                    voiceSpinner?.selectedItemPosition ?: 0
                )?.name.orEmpty(),
                languageTag = if (isArabic) "ar" else "en",
                enginePkg = enginePkg,
                rateProgress = seekCallerRate?.progress ?: 100,
                pitchProgress = seekCallerPitch?.progress ?: 100,
                volumePercent = seekCallerVolume?.progress ?: 100,
                sampleText = sample
            )
        )
    }

    /** إعادة بناء سبنري أصوات المتصل: كل لغة بمحركها الخاص (عربي ← محرك
     *  الأسماء العربية، إنجليزي ← محرك الأسماء الإنجليزية) — عند بدء
     *  الإعداد وعند اكتمال اكتشاف المحركات خلفياً
     *  ([VoiceSelectionFragment]). */
    fun refreshCallerVoices() {
        refreshCallerArabicVoices()
        refreshCallerEnglishVoices()
    }

    /** يبني أصوات العربية المتاحة لمحرك الأسماء العربية. */
    private fun refreshCallerArabicVoices() {
        val engine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
            )
        }.getOrNull()
        callerVoiceOptionsAr = catalog.voicesFor("ar", engine)
        bindCallerVoices(
            spinnerCallerVoiceAr,
            callerVoiceOptionsAr,
            runCatching {
                settings.getCallerAnnouncementArabicVoiceId()
            }.getOrNull()
        )
    }

    /** يبني أصوات الإنجليزية المتاحة لمحرك الأسماء الإنجليزية. */
    private fun refreshCallerEnglishVoices() {
        val engine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
            )
        }.getOrNull()
        callerVoiceOptionsEn = catalog.voicesFor("en", engine)
        bindCallerVoices(
            spinnerCallerVoiceEn,
            callerVoiceOptionsEn,
            runCatching {
                settings.getCallerAnnouncementEnglishVoiceId()
            }.getOrNull()
        )
    }

    private fun bindCallerVoices(
        spinner: Spinner?,
        options: List<VoiceOption>,
        saved: String?
    ) {
        bindingVoices = true
        try {
            spinner?.adapter = fragment.simpleAdapter(options.map { it.label })
            val idx = options.indexOfFirst { it.name == saved }
            spinner?.setSelection(if (idx >= 0) idx else 0)
        } finally {
            bindingVoices = false
        }
    }

    /**
     * إذا أُبقيت ميزة المتصّل مفعّلة لكن أذوناتها اللازمة سُحبت تلقائياً
     * (بلا READ_PHONE_STATE لا يُسلَّم بث PHONE_STATE أصلاً فيُصمت الإعلان
     * تماماً؛ وعلى أندرويد 12+ بلا READ_CALL_LOG لا يصل رقم المتصل فيُصمت
     * أيضاً — وقد يكون لهذا تعليق "الاسم لا يصل" عند منح الأسماء فقط)،
     * نعرض حواراً يشرح السبب ويقدّم إعادة الطلب أو فتح إعدادات النظام.
     */
    private fun checkRevokedPermissionsAndRecover() {
        val enabled =
            runCatching { settings.isCallerAnnouncementEnabled() }
                .getOrDefault(false)
        if (!enabled || callerRevokedDialogShown) return
        val phoneGranted = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val callLogGranted = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        // على أندرويد 12+ يُشرَط READ_CALL_LOG أيضاً (رقم المتصل لا يُسلَّم
        // بدونه) — فسحبُه من إعدادات النظام يُعتبَر سحب الأذونات اللازمة.
        val requiredGranted = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            phoneGranted && callLogGranted
        } else {
            phoneGranted
        }
        if (requiredGranted) return
        callerRevokedDialogShown = true
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.caller_permission_revoked_title)
            .setMessage(R.string.caller_permission_revoked_message)
            .setPositiveButton(
                R.string.caller_permission_grant_again
            ) { _, _ ->
                // إعادة طلب الأذونات المفقودة مع شرح أهمية سجل المكالمات — نفس
                // مسار التفعيل الأول (حوار التبرير قبل حوار النظام).
                requestCallerPermissionsWithRationale()
            }
            .setNegativeButton(R.string.permission_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = Uri.fromParts(
                        "package",
                        fragment.requireActivity().packageName,
                        null
                    )
                }
                fragment.startActivity(intent)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .create().also { fragment.trackDialog(it) }.show()
    }

    /**
     * يبني مجموعة أذونات إعلان المتصل (حالة الهاتف + سجل المكالمات + دفتر
     * الاتصالات إن لم يُمنح) ويرسلها مع شرحٍ مسبق عند الحاجة: إن كان سجل
     * المكالمات ما يزال مفقوداً نعرض حواراً يوضحُ أهميةَ إذنه للمكفوفين
     * (بدونه لا يصل رقم المتصل على أندرويد 12+ ولا يُنطق الاسم) ثم نطلق
     * طلب النظام عند «متابعة»؛ أما إن كان قد مُنح فلا حاجة للشرح.
     */
    private fun requestCallerPermissionsWithRationale() {
        val needed = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            if (ContextCompat.checkSelfPermission(
                fragment.requireContext(),
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_CONTACTS)
            }
        }.toTypedArray()
        val callLogMissing = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
        if (!callLogMissing) {
            fragment.callerPermLauncher.launch(needed)
            return
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.caller_permission_rationale_title)
            .setMessage(R.string.caller_permission_rationale_message)
            .setPositiveButton(
                R.string.caller_permission_rationale_continue
            ) { _, _ ->
                fragment.callerPermLauncher.launch(needed)
            }
            .setNegativeButton(
                android.R.string.cancel
            ) { _, _ ->
                // **بند 6.5:** إلغاء التبرير يُعيد المفتاح إلى «متوقف»
                // — لم يُمنح الإذن فلن تُفعَّل الميزة، وكان المفتاح يعلق
                // مفعّلاً بصرياً بينما الميزة معطلة فعلياً (تضليل).
                callerSwitchGuard = true
                switchCallerAnnouncement?.isChecked = false
                callerSwitchGuard = false
                onStatusChanged()
            }
            .create().also { fragment.trackDialog(it) }.show()
    }

    /** نتيجة طلب أذونات المتصل: التفّعيل الفعلي لا يتم إلا بعد منح أي إذن. */
    fun onPermissionsResult(granted: Map<String, Boolean>) {
        val ctx = fragment.context ?: return
        val phoneGranted =
            granted[Manifest.permission.READ_PHONE_STATE] == true
        val callLogGranted =
            granted[Manifest.permission.READ_CALL_LOG] == true
        val contactsGranted =
            granted[Manifest.permission.READ_CONTACTS] == true
        if (canEnable(
            phoneGranted,
            callLogGranted,
            contactsGranted,
            requiresCallLog = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )) {
            runCatching { settings.setCallerAnnouncementEnabled(true) }
            // حارس يمنع المستمع من إعادة طلب الأذونات عند تعيين قيمة
            // المفتاح هنا
            callerSwitchGuard = true
            switchCallerAnnouncement?.isChecked = true
            callerSwitchGuard = false
            AnnouncementSchedulerService.requestStart(ctx)
            onStatusChanged()
            // مع بوّابة canEnable لا تصل رسالة «حالة الهاتف فقط» — من منح
            // ما عداها معها فله مصدرُ اسمٍ واحدٍ على الأقل.
            val msg = if (callLogGranted) {
                R.string.caller_permission_granted_both
            } else {
                R.string.caller_permission_granted_contacts_only
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            if (fragment.isAdded) {
                fragment.view?.announceCompat(fragment.getString(msg))
            }
        } else {
            // بند 4.10: كان تغيير المفتاح هنا بلا حارس فيطلق المستمع فينفّذ
            // مسار «التعطيل» مرتين (إعلانان صوتيان وتحديثان للملخص). الحارس
            // يجعله تغييراً برمجياً صامتاً — المعالجة تجري هنا مرة واحدة.
            callerSwitchGuard = true
            switchCallerAnnouncement?.isChecked = false
            callerSwitchGuard = false
            runCatching { settings.setCallerAnnouncementEnabled(false) }
            AnnouncementSchedulerService.syncIfRunning(ctx)
            onStatusChanged()
            Toast.makeText(
                ctx,
                R.string.caller_permission_needed,
                Toast.LENGTH_LONG
            ).show()
            if (fragment.isAdded) {
                fragment.view?.announceCompat(
                    fragment.getString(R.string.caller_permission_needed)
                )
            }
        }
    }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        switchCallerAnnouncement = null
        cbCallerAnnounceDuringCall = null
        spinnerCallerRepeat = null
        spinnerCallerInterval = null
        seekCallerRate = null
        tvCallerRateValue = null
        seekCallerVolume = null
        tvCallerVolumeValue = null
        seekCallerPitch = null
        tvCallerPitchValue = null
        templateWatcher?.let {
            etCallerTemplate?.removeTextChangedListener(it)
        }
        templateWatcher = null
        etCallerTemplate = null
        spinnerCallerVoiceAr = null
        spinnerCallerVoiceEn = null
        spinnerCallerEngineAr = null
        spinnerCallerEngineEn = null
        tvCallerEngineArLabel = null
        tvCallerEngineEnLabel = null
    }
}

