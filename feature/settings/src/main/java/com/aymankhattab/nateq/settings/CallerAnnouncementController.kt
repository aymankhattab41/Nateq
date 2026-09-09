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
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «إعلان اسم المتصل»: التفعيل بالأذونات، التكرار، السرعة،
 *  القالب والأصوات. */
internal class CallerAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
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

    private lateinit var switchCallerAnnouncement: SwitchMaterial
    private lateinit var spinnerCallerRepeat: Spinner
    private lateinit var spinnerCallerInterval: Spinner
    private lateinit var seekCallerRate: SeekBar
    private lateinit var tvCallerRateValue: TextView
    private lateinit var seekCallerVolume: SeekBar
    private lateinit var tvCallerVolumeValue: TextView
    private lateinit var etCallerTemplate:
        com.google.android.material.textfield.TextInputEditText
    private lateinit var spinnerCallerVoiceAr: Spinner
    private lateinit var spinnerCallerVoiceEn: Spinner
    private lateinit var spinnerCallerEngine: Spinner

    /** خيارات محرك نطق المتصل: «تلقائي» ثم المحركات المثبتة */
    private var callerEngineOptions: List<EnginePicker.InstalledEngine> =
        emptyList()

    /** يمنع مناداة المستمع من رد الطلب (تفادي إعادة طلب الأذونات دورياً) */
    private var callerSwitchGuard = false

    /** يمنع تكرار حوار «أُلغيت أذونات المتصل» أكثر من مرة
     *  لكل دورة فتح إعدادات */
    private var callerRevokedDialogShown = false

    fun setup(view: View) {
        switchCallerAnnouncement =
            view.findViewById(R.id.switch_caller_announcement)
        spinnerCallerRepeat = view.findViewById(R.id.spinner_caller_repeat)
        spinnerCallerInterval = view.findViewById(R.id.spinner_caller_interval)
        seekCallerRate = view.findViewById(R.id.seek_caller_rate)
        tvCallerRateValue = view.findViewById(R.id.tv_caller_rate_value)
        seekCallerVolume = view.findViewById(R.id.seek_caller_volume)
        tvCallerVolumeValue = view.findViewById(R.id.tv_caller_volume_value)
        etCallerTemplate = view.findViewById(R.id.et_caller_template)
        spinnerCallerVoiceAr = view.findViewById(R.id.spinner_caller_voice_ar)
        spinnerCallerVoiceEn = view.findViewById(R.id.spinner_caller_voice_en)
        spinnerCallerEngine = view.findViewById(R.id.spinner_caller_engine)

        // محركات TTS المثبتة + خيار تلقائي
        callerEngineOptions = runCatching {
            EnginePicker.installedEngines(fragment.requireContext())
        }.getOrDefault(emptyList())
        val engineLabels = buildList {
            add(fragment.getString(R.string.first_run_engine_auto))
            addAll(callerEngineOptions.map { it.label })
        }
        spinnerCallerEngine.adapter = fragment.simpleAdapter(engineLabels)
        val savedCallerEngine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            )
        }.getOrNull()
        val callerEngineIdx = callerEngineOptions
            .indexOfFirst { it.packageName == savedCallerEngine }
        spinnerCallerEngine.setSelection(
            if (callerEngineIdx >= 0) callerEngineIdx + 1 else 0
        )
        spinnerCallerEngine.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?,
                pos: Int, id: Long
            ) {
                val pkg = callerEngineOptions
                    .getOrNull(pos - 1)?.packageName
                runCatching {
                    settings.setEngineForCategory(
                        SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
                        pkg
                    )
                }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // المفتاح الرئيسي: عند التفعيل نطلب الأذونات أولاً
        // (لا نفعّل إلا بمنحها)
        switchCallerAnnouncement.isChecked =
            runCatching { settings.isCallerAnnouncementEnabled() }
                .getOrDefault(false)
        switchCallerAnnouncement.setOnCheckedChangeListener { _, checked ->
            if (callerSwitchGuard) return@setOnCheckedChangeListener
            if (checked) {
                val needed = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG
                )
                val hasContacts = ContextCompat.checkSelfPermission(
                    fragment.requireContext(),
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasContacts) needed.add(Manifest.permission.READ_CONTACTS)
                fragment.callerPermLauncher.launch(needed.toTypedArray())
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

        // عدد مرات التكرار
        val repeats = listOf(
            fragment.getString(R.string.repeat_once),
            fragment.getString(R.string.repeat_twice),
            fragment.getString(R.string.repeat_3),
            fragment.getString(R.string.repeat_4),
            fragment.getString(R.string.repeat_5)
        )
        spinnerCallerRepeat.adapter = fragment.simpleAdapter(repeats)
        val savedRepeat =
            runCatching { settings.getCallerAnnouncementRepeat() }
                .getOrDefault(1)
        spinnerCallerRepeat.setSelection(
            (savedRepeat - 1).coerceIn(0, repeats.size - 1)
        )
        spinnerCallerRepeat.onItemSelectedListener =
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
        spinnerCallerInterval.adapter = fragment.simpleAdapter(intervals)
        val savedInterval =
            runCatching { settings.getCallerAnnouncementIntervalSeconds() }
                .getOrDefault(3)
        spinnerCallerInterval.setSelection(
            (savedInterval - 1).coerceIn(0, intervals.size - 1)
        )
        spinnerCallerInterval.onItemSelectedListener =
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
        tvCallerRateValue.text =
            String.format(Locale.US, "%.1fx", callerRate)
        seekCallerRate.progress = (callerRate * 100).toInt().coerceIn(0, 200)
        seekCallerRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                val value = progress.speedFactor()
                tvCallerRateValue.text =
                    String.format(Locale.US, "%.1fx", value)
                seekBar.setSeekStateDescription(
                    tvCallerRateValue.text
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching { settings.setCallerAnnouncementRate(value) }
                seekBar.announceCompat(
                    String.format(Locale.US, "%.1fx", value)
                )
                onStatusChanged()
            }
        })

        // مستوى الصوت
        val callerVolume =
            runCatching { settings.getCallerAnnouncementVolume() }
                .getOrDefault(1.0f)
        tvCallerVolumeValue.text = "${(callerVolume * 100).toInt()}%"
        seekCallerVolume.progress =
            (callerVolume * 100).toInt().coerceIn(0, 100)
        seekCallerVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                tvCallerVolumeValue.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvCallerVolumeValue.text
                )
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

        // قالب إعلان المتصل: {name} لاسم المتصل
        etCallerTemplate.setText(
            runCatching { settings.getCallerAnnouncementTemplate() }
                .getOrNull()
        )
        etCallerTemplate.addTextChangedListener(object : TextWatcher {
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
        })

        // صوت نطق الأسماء العربية في إعلان المتصل
        spinnerCallerVoiceAr.adapter =
            fragment.simpleAdapter(voices.map { it.displayName })
        val savedCallerVoiceAr =
            runCatching { settings.getCallerAnnouncementArabicVoiceId() }
                .getOrNull()
        if (savedCallerVoiceAr != null) {
            val idx = voices.indexOfFirst { it.name == savedCallerVoiceAr }
            if (idx >= 0) spinnerCallerVoiceAr.setSelection(idx)
        }
        spinnerCallerVoiceAr.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setCallerAnnouncementArabicVoiceId(
                        voices[position].name
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // صوت نطق الأسماء الإنجليزية في إعلان المتصل
        spinnerCallerVoiceEn.adapter =
            fragment.simpleAdapter(voices.map { it.displayName })
        val savedCallerVoiceEn =
            runCatching { settings.getCallerAnnouncementEnglishVoiceId() }
                .getOrNull()
        if (savedCallerVoiceEn != null) {
            val idx = voices.indexOfFirst { it.name == savedCallerVoiceEn }
            if (idx >= 0) spinnerCallerVoiceEn.setSelection(idx)
        }
        spinnerCallerVoiceEn.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setCallerAnnouncementEnglishVoiceId(
                        voices[position].name
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // استرداد ذكي: إذا كانت ميزة المتصّل مفعّلة لكن أذوناتها سُحبت (سحب
        // النظام التلقائي للأذونات غير المستخدمة، خصوصاً على أندرويد 11+)
        // نكتشف ذلك فور فتح الإعدادات ونعرض إعادة المنح بدل تركه صامتاً.
        checkRevokedPermissionsAndRecover()
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
                // إعادة طلب الأذونات المفقودة (نفس مجموعة التفعيل الأولى)
                val needed = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG
                )
                if (ContextCompat.checkSelfPermission(
                        fragment.requireContext(),
                        Manifest.permission.READ_CONTACTS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    needed.add(Manifest.permission.READ_CONTACTS)
                }
                fragment.callerPermLauncher.launch(needed.toTypedArray())
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

    /** نتيجة طلب أذونات المتصل: التفّعيل الفعلي لا يتم إلا بعد منح أي إذن. */
    fun onPermissionsResult(granted: Map<String, Boolean>) {
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
            switchCallerAnnouncement.isChecked = true
            callerSwitchGuard = false
            AnnouncementSchedulerService.requestStart(
                fragment.requireContext()
            )
            onStatusChanged()
            // مع بوّابة canEnable لا تصل رسالة «حالة الهاتف فقط» — من منح
            // ما عداها معها فله مصدرُ اسمٍ واحدٍ على الأقل.
            val msg = if (callLogGranted) {
                R.string.caller_permission_granted_both
            } else {
                R.string.caller_permission_granted_contacts_only
            }
            Toast.makeText(
                fragment.requireContext(), msg, Toast.LENGTH_LONG
            ).show()
            fragment.view?.announceCompat(fragment.getString(msg))
        } else {
            switchCallerAnnouncement.isChecked = false
            runCatching { settings.setCallerAnnouncementEnabled(false) }
            AnnouncementSchedulerService.syncIfRunning(
                fragment.requireContext()
            )
            onStatusChanged()
            Toast.makeText(
                fragment.requireContext(),
                R.string.caller_permission_needed,
                Toast.LENGTH_LONG
            ).show()
            fragment.view?.announceCompat(
                fragment.getString(R.string.caller_permission_needed)
            )
        }
    }
}
