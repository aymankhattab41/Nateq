package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.audio.providers.EnginePicker

/** موضع المحرك المحفوظ داخل قائمة المحركات؛ غياب الحفظ (أو حزمة غير
 *  معروفة) → أول محرك (لا خيار «تلقائي» سابق ليُحفظ). */
internal fun engineIndexFor(
    engines: List<EnginePicker.InstalledEngine>,
    savedEngine: String?
): Int {
    val idx = engines.indexOfFirst { it.packageName == savedEngine }
    return if (idx >= 0) idx else 0
}

/**
 * مسند فئات الأصوات: لكل فئة (الافتراضية/الوقت/الأرقام/الإشعارات) محرك وصوت
 * وسرعة ونبرة ومستوى صوت باسم زر اختبار نُطقه. أُخرج من الفصيل إلى مستوى
 * الملف ليكون قابلاً لإعادة الاستخدام دون احتياج العضو الداخلي الضمني.
 */
internal class CategoryVoiceAdapter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val catalog: EngineVoicesCatalog,
    // **بند 4.3:** العامل الفئة (?category) يُمرَّر مع القراءة — من دونه
    // تسمع المعاينةُ صوتَ الفئة الافتراضية لا صوت الصف المختار تماماً.
    private val onTestVoice: (
        category: String,
        languageTag: String,
        text: String
    ) -> Unit
) : RecyclerView.Adapter<CategoryVoiceAdapter.CatVH>() {

    private val categoryList = listOf(
        SettingsRepository.VOICE_CATEGORY_DEFAULT,
        SettingsRepository.VOICE_CATEGORY_TIME,
        SettingsRepository.VOICE_CATEGORY_NUMBERS,
        SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
        SettingsRepository.VOICE_CATEGORY_EMOJI
    )

    // لغات الفئات المعروضة (عربية/إنجليزية ثم المكتشفة): يُعاد
    // مسندها بعد اكتشاف المحركات ثم تُعاد ربط الصفوف.
    private var languages: List<String> = catalog.languages()
    private var languageAdapter = simpleAdapter(
        context,
        languages.map { catalog.languageDisplayName(it) }
    )

    // محركات الفئات المثبتة: نفس القائمة لجميع الصفوف
    private val categoryEngines =
        runCatching { EnginePicker.installedEngines(context) }
            .getOrDefault(emptyList())

    // **بند 6.2:** عَلَمُ الربط البرمجي — يُسنَّع حول setSelection في
    // onBindViewHolder لأن إسنادَ موضعٍ للسبنر يطلق onItemSelected فيسجّل
    // الصوت/المحرك السعد برمجياً (مثلاً صوت 0 لفئةٍ بلا صوتٍ مخصص) في
    // الذاكرة الدائمة ويهدم وراثة الصوت الافتراضي عند تصفح القائمة.
    private var bindingAdapterInputs = false
    private val engineOptionsAdapter = simpleAdapter(
        context,
        categoryEngines.map { it.label }
    )

    // الصفوف المرتبطة حالياً (فئة ← حامل) لحفظها صراحةً عبر saveAll
    // من زر الحفظ أسفل القائمة.
    private val holdersByCategory = HashMap<String, CatVH>()

    /** إعادة بناء قوائم اللغات بعد اكتمال الاكتشاف الخلفي ثم إعادة
     *  ربط الصفوف كاملة (مسند اللغة ومراجع الأصوات المرئية). */
    internal fun refreshLanguages() {
        languages = catalog.languages()
        languageAdapter = simpleAdapter(
            context,
            languages.map { catalog.languageDisplayName(it) }
        )
        notifyDataSetChanged()
    }

    /** لغة الصف إن لم تُحفظ صراحة: تُستنتج من صوت الفئة المحفوظ عبر الكتالوج
     *  (صيغ اللورد المنطقية أو الأسماء المكتشفة فعلياً من المحركات)، وإلا
     *  فالعربية الافتراضية. يصلح ارتداد صوتٍ إنجليزي محفوظ من محركٍ مكتشف
     *  (مثل Google) إلى العربية عند إعادة فتح الشاشة. */
    private fun defaultLanguageFor(category: String): String {
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(category)
        }.getOrNull().orEmpty()
        if (saved.isBlank()) return "ar"
        return catalog.languageForSavedVoice(saved) ?: "ar"
    }

    /** إعادة بناء سبنر الأصوات للصف: أصوات (اللغة، محرك الفئة) من
     *  الكتالوج مع سقوطٍ للأصوات المنطقية ثم اختيار الصوت المحفوظ —
     *  كل الإسناد تحت عَلَم الربط حتى لا يُسجَّل اختيار برمجي. */
    private fun refreshVoiceSpinner(holder: CatVH) {
        val engine = if (
            holder.category == SettingsRepository.VOICE_CATEGORY_DEFAULT
        ) {
            null
        } else {
            runCatching {
                settings.getEngineForCategory(holder.category)
            }.getOrNull()
        }
        holder.voiceOptions = catalog.voicesFor(
            holder.currentLanguage, engine
        )
        val saved = runCatching {
            settings.getPreferredVoiceIdForCategory(holder.category)
        }.getOrNull()
        bindingAdapterInputs = true
        try {
            holder.spinnerVoice.adapter = simpleAdapter(
                context, holder.voiceOptions.map { it.label }
            )
            val idx = holder.voiceOptions.indexOfFirst { it.name == saved }
            holder.spinnerVoice.setSelection(if (idx >= 0) idx else 0)
        } finally {
            bindingAdapterInputs = false
        }
    }

    /** حفظٌ صريح من زر «حفظ تغييرات الأصوات» أسفل القائمة: يكتب لكل صفٍّ
     *  مربوطٍ اللغةَ الفعلية الحالية (حتى لو لم تُغيَّر) مع المحرك والصوت
     *  والأشرطة — فيستقر الإعداد بلا اعتمادٍ على الاستدلال اللغوي عند
     *  إعادة الربط (يصلح ارتداد صوت الإنجليزية إلى العربية). */
    internal fun saveAll() {
        for ((category, holder) in holdersByCategory) {
            val langIdx = holder.spinnerLanguage.selectedItemPosition
            if (langIdx in languages.indices) {
                runCatching {
                    settings.setLanguageForCategory(
                        category, languages[langIdx]
                    )
                }
            }
            if (category != SettingsRepository.VOICE_CATEGORY_DEFAULT) {
                val engineIdx = holder.spinnerEngine.selectedItemPosition
                if (engineIdx in categoryEngines.indices) {
                    runCatching {
                        settings.setEngineForCategory(
                            category,
                            categoryEngines[engineIdx].packageName
                        )
                    }
                }
            }
            val voiceIdx = holder.spinnerVoice.selectedItemPosition
            if (voiceIdx in holder.voiceOptions.indices) {
                runCatching {
                    settings.setPreferredVoiceIdForCategory(
                        category, holder.voiceOptions[voiceIdx].name
                    )
                }
            }
            runCatching {
                settings.setSpeechRateForCategory(
                    category, holder.seekRate.progress.speedFactor()
                )
            }
            runCatching {
                settings.setPitchForCategory(
                    category, holder.seekPitch.progress.speedFactor()
                )
            }
            runCatching {
                settings.setVolumeForCategory(
                    category, holder.seekVolume.progress / 100f
                )
            }
        }
    }

    inner class CatVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** الفئة المرتبطة حالياً بالحامل (تُحدَّث في bind). */
        var category: String = ""
        /** اللغة المختارة حالياً لهذا الصف (تُحدَّث من سبنر اللغة). */
        var currentLanguage: String = "ar"
        /** خيارات الأصوات المعروضة (تُعاد عند تبديل اللغة أو المحرك). */
        var voiceOptions: List<VoiceOption> = emptyList()
        val tvCategory: TextView = itemView.findViewById(R.id.tv_category_name)
        val tvCategoryDescription: TextView =
            itemView.findViewById(R.id.tv_category_description)
        val spinnerLanguage: Spinner =
            itemView.findViewById(R.id.spinner_category_language)
        val tvLanguageLabel: TextView =
            itemView.findViewById(R.id.tv_category_language_label)
        val spinnerEngine: Spinner =
            itemView.findViewById(R.id.spinner_category_engine)
        val tvEngineLabel: TextView =
            itemView.findViewById(R.id.tv_category_engine_label)
        val spinnerVoice: Spinner =
            itemView.findViewById(R.id.spinner_category_voice)
        val seekRate: SeekBar =
            itemView.findViewById(R.id.seek_category_speech_rate)
        val tvRateValue: TextView =
            itemView.findViewById(R.id.tv_category_speech_rate_value)
        val seekPitch: SeekBar =
            itemView.findViewById(R.id.seek_category_pitch)
        val tvPitchValue: TextView =
            itemView.findViewById(R.id.tv_category_pitch_value)
        val seekVolume: SeekBar =
            itemView.findViewById(R.id.seek_category_volume)
        val tvVolumeValue: TextView =
            itemView.findViewById(R.id.tv_category_volume_value)
        val btnTest: View = itemView.findViewById(R.id.btn_test_category_voice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_voice, parent, false)
        val holder = CatVH(view)
        // المستمعات تُثبَّت مرة واحدة عند إنشاء الحامل وتقرأ الفئة المرتبطة
        // حالياً، فلا تُنشأ كائنات جديدة مع كل تمرير أو إعادة ربط.
        holder.spinnerLanguage.adapter = languageAdapter
        holder.spinnerLanguage.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                pos: Int,
                id: Long
            ) {
                if (bindingAdapterInputs) return
                val category = holder.category
                if (category.isEmpty() || pos !in languages.indices) {
                    return
                }
                val language = languages[pos]
                if (language == holder.currentLanguage) return
                holder.currentLanguage = language
                runCatching {
                    settings.setLanguageForCategory(category, language)
                }
                refreshVoiceSpinner(holder)
            }

            override fun onNothingSelected(
                parent: android.widget.AdapterView<*>?
            ) {}
        }
        holder.spinnerEngine.adapter = engineOptionsAdapter
        holder.spinnerEngine.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                pos: Int,
                id: Long
            ) {
                if (bindingAdapterInputs) return
                val category = holder.category
                if (category.isEmpty()) return
                // الفئة الافتراضية بلا محرك خاص (مخفاة) — لا تُكتب قيمة لها
                if (category == SettingsRepository.VOICE_CATEGORY_DEFAULT) {
                    return
                }
                val engine = categoryEngines
                    .getOrNull(pos)?.packageName
                runCatching {
                    settings.setEngineForCategory(category, engine)
                }
                // تبديل المحرك يُجدد أصوات اللغة الحالية.
                refreshVoiceSpinner(holder)
            }

            override fun onNothingSelected(
                parent: android.widget.AdapterView<*>?
            ) {}
        }
        holder.spinnerVoice.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                pos: Int,
                id: Long
            ) {
                val category = holder.category
                // **بند 6.2:** إسنادُ الربط البرمجي ليس اختيارَ مستخدم —
                // لا يُكتب بعده شيء (وإلا حُفظ صوت 0 لكل فئةٍ بلا مخصص).
                if (category.isEmpty() ||
                    pos !in holder.voiceOptions.indices ||
                    bindingAdapterInputs
                ) {
                    return
                }
                runCatching {
                    settings.setPreferredVoiceIdForCategory(
                        category, holder.voiceOptions[pos].name
                    )
                }
            }

            override fun onNothingSelected(
                parent: android.widget.AdapterView<*>?
            ) {}
        }
        holder.seekRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingAdapterInputs) return
                val value = progress.speedFactor()
                holder.tvRateValue.text = RateLabel.of(
                    holder.itemView.context,
                    value
                )
                holder.seekRate.setSeekStateDescription(
                    holder.tvRateValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setSpeechRateForCategory(holder.category, value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (holder.category.isEmpty()) return
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching {
                    settings.setSpeechRateForCategory(holder.category, value)
                }
                holder.seekRate.announceCompat(
                    RateLabel.of(holder.itemView.context, value)
                )
            }
        })
        holder.seekPitch.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingAdapterInputs) return
                val value = progress.speedFactor()
                holder.tvPitchValue.text = RateLabel.of(
                    holder.itemView.context,
                    value
                )
                holder.seekPitch.setSeekStateDescription(
                    holder.tvPitchValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setPitchForCategory(holder.category, value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (holder.category.isEmpty()) return
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching {
                    settings.setPitchForCategory(holder.category, value)
                }
                holder.seekPitch.announceCompat(
                    RateLabel.of(holder.itemView.context, value)
                )
            }
        })
        holder.seekVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingAdapterInputs) return
                holder.tvVolumeValue.text = "$progress%"
                holder.seekVolume.setSeekStateDescription(
                    holder.tvVolumeValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setVolumeForCategory(
                        holder.category, progress / 100f
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (holder.category.isEmpty()) return
                runCatching {
                    settings.setVolumeForCategory(
                        holder.category, seekBar.progress / 100f
                    )
                }
                holder.seekVolume.announceCompat("${seekBar.progress}%")
            }
        })
        holder.btnTest.setOnClickListener {
            val category = holder.category
            if (category.isEmpty()) return@setOnClickListener
            // **بند 4.2:** كتالوج الأصوات المعروض قد يصل فارغاً من محركٍ
            // بلا أصوات — الفهرس المُختار يتجاوز حدود القائمة فلا يُسقط
            // النقرُ أو التمريرُ التطبيق بمؤشرٍ خارج الحدود.
            val voiceIndex = holder.spinnerVoice.selectedItemPosition
            if (voiceIndex !in holder.voiceOptions.indices) {
                return@setOnClickListener
            }
            val isArabic = LanguageCode.isArabic(holder.currentLanguage)
            val text = when {
                !isArabic && category ==
                    SettingsRepository.VOICE_CATEGORY_TIME ->
                    context.getString(R.string.sample_text_time_en)
                !isArabic && category ==
                    SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                    context.getString(R.string.sample_text_numbers_en)
                !isArabic && category ==
                    SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                    context.getString(R.string.sample_text_notifications_en)
                !isArabic && category ==
                    SettingsRepository.VOICE_CATEGORY_EMOJI ->
                    context.getString(R.string.sample_text_emoji_en)
                !isArabic ->
                    context.getString(R.string.sample_text_default_en)
                category == SettingsRepository.VOICE_CATEGORY_TIME ->
                    context.getString(R.string.sample_text_time_ar)
                category == SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                    context.getString(R.string.sample_text_numbers_ar)
                category == SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                    context.getString(R.string.sample_text_notifications_ar)
                category == SettingsRepository.VOICE_CATEGORY_EMOJI ->
                    context.getString(R.string.sample_text_emoji_ar)
                else -> context.getString(R.string.sample_text_default_ar)
            }
            onTestVoice(category, holder.currentLanguage, text)
        }
        return holder
    }

    override fun onBindViewHolder(holder: CatVH, position: Int) {
        val category = categoryList[position]
        holder.category = category
        holdersByCategory[category] = holder
        val catLabel = when (category) {
            SettingsRepository.VOICE_CATEGORY_TIME ->
                context.getString(R.string.voice_category_time)
            SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                context.getString(R.string.voice_category_numbers)
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                context.getString(R.string.voice_category_notifications)
            SettingsRepository.VOICE_CATEGORY_EMOJI ->
                context.getString(R.string.voice_category_emoji)
            else -> context.getString(R.string.voice_category_default)
        }
        holder.tvCategory.text = catLabel
        // تُميّز أشرطة السرعة/النبرة/الصوت ومختار الصوت باسم فئتها حتى لا
        // تتكرر الأوصاف نفسها بلا تمييز لمستخدم قارئ الشاشة
        holder.seekRate.contentDescription = context.getString(
            R.string.seek_category_speech_rate, catLabel
        )
        holder.seekPitch.contentDescription = context.getString(
            R.string.seek_category_pitch, catLabel
        )
        holder.seekVolume.contentDescription = context.getString(
            R.string.seek_category_volume, catLabel
        )
        holder.spinnerVoice.contentDescription = context.getString(
            R.string.cd_category_voice_spinner, catLabel
        )
        holder.spinnerLanguage.contentDescription = context.getString(
            R.string.cd_category_language_spinner_for, catLabel
        )
        holder.btnTest.contentDescription = context.getString(
            R.string.cd_test_voice_button_for, catLabel
        )
        holder.tvCategoryDescription.text = when (category) {
            SettingsRepository.VOICE_CATEGORY_TIME ->
                context.getString(R.string.voice_category_time_summary)
            SettingsRepository.VOICE_CATEGORY_NUMBERS ->
                context.getString(R.string.voice_category_numbers_summary)
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ->
                context.getString(
                    R.string.voice_category_notifications_summary
                )
            SettingsRepository.VOICE_CATEGORY_EMOJI ->
                context.getString(R.string.voice_category_emoji_summary)
            else -> context.getString(R.string.voice_category_default_summary)
        }

        // الفئة الافتراضية بلا محرك خاص: محركها هو العام المختار في الأعلى.
        // القائمة الفارغة (لا محركات مثبتة) تخفي السبنر كالفئة الافتراضية.
        val isEnginePerCategory = category !=
            SettingsRepository.VOICE_CATEGORY_DEFAULT &&
            categoryEngines.isNotEmpty()
        holder.tvEngineLabel.visibility = if (isEnginePerCategory) {
            View.VISIBLE
        } else {
            View.GONE
        }
        holder.spinnerEngine.visibility = if (isEnginePerCategory) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val savedEngine =
            runCatching { settings.getEngineForCategory(category) }
                .getOrNull()
        val engineIdx = engineIndexFor(categoryEngines, savedEngine)

        // اللغة المختارة للفئة (محفوظة أو مستنتجة من صوتها) ثم موضعها
        // تحت عَلَم الربط — الأصوات تُبنى عبر refreshVoiceSpinner.
        holder.currentLanguage = runCatching {
            settings.getLanguageForCategory(category)
        }.getOrNull() ?: defaultLanguageFor(category)
        bindingAdapterInputs = true
        try {
            holder.spinnerLanguage.adapter = languageAdapter
            val langIdx = languages.indexOf(holder.currentLanguage)
            holder.spinnerLanguage.setSelection(
                if (langIdx >= 0) langIdx else 0
            )
        } finally {
            bindingAdapterInputs = false
        }
        // **بند 6.2:** اختيارُ الموضع أثناء الربط (0 لفئةٍ بلا محرك محفوظ)
        // لا يجوز أن يكون اختياراً مسجَّلاً — يُكبَح عليه عَلَمُ الربط.
        bindingAdapterInputs = true
        try {
            holder.spinnerEngine.setSelection(engineIdx)
        } finally {
            bindingAdapterInputs = false
        }
        refreshVoiceSpinner(holder)

        val rate = runCatching { settings.getSpeechRateForCategory(category) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        holder.tvRateValue.text = RateLabel.of(
            holder.itemView.context, rate
        )
        bindingAdapterInputs = true
        try {
            holder.seekRate.progress = (rate * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingAdapterInputs = false
        }

        val pitch = runCatching { settings.getPitchForCategory(category) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        holder.tvPitchValue.text = RateLabel.of(
            holder.itemView.context, pitch
        )
        bindingAdapterInputs = true
        try {
            holder.seekPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingAdapterInputs = false
        }

        val volume = runCatching { settings.getVolumeForCategory(category) }
            .getOrDefault(1.0f)
        holder.tvVolumeValue.text = "${(volume * 100).toInt()}%"
        bindingAdapterInputs = true
        try {
            holder.seekVolume.progress =
                (volume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingAdapterInputs = false
        }
    }

    override fun getItemCount(): Int = categoryList.size

    override fun onViewRecycled(holder: CatVH) {
        super.onViewRecycled(holder)
        holdersByCategory.values.remove(holder)
    }
}

/** معيار DiffUtil لإدخالات القاموس: التماثل بالمفتاح (الكلمة) والمحتوى
 *  بالمزاوجة كاملة — يتيح للمسند تحديث الإدخالات المتغيّرة فقط. */
internal val DictEntryDiff = object :
    DiffUtil.ItemCallback<Pair<String, String>>() {
    override fun areItemsTheSame(
        oldItem: Pair<String, String>,
        newItem: Pair<String, String>
    ): Boolean = oldItem.first == newItem.first

    override fun areContentsTheSame(
        oldItem: Pair<String, String>,
        newItem: Pair<String, String>
    ): Boolean = oldItem == newItem
}

/**
 * مسند إدخالات قاموس النطق (كلمة → نُطق). مسند قائمة (ListAdapter) يحسب
 * الفرق عبر [DictEntryDiff] فيُحدَّث صفوف التغيير فقط بدل إعادة بناء القائمة
 * كاملة؛ المستمعان الثابتان يُثبَّتان عند إنشاء الحامل ويقرآن إدخال الربط
 * الحالي. أُخرج من الفصيل إلى مستوى الملف ليكون قابلًا لإعادة الاستخدام.
 */
internal class PronunciationDictAdapter(
    private val onRowClick: (Pair<String, String>) -> Unit
) : ListAdapter<Pair<String, String>, PronunciationDictAdapter.DictVH>(
    DictEntryDiff
) {

    inner class DictVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvWord: TextView = itemView.findViewById(R.id.tv_dict_word)
        val tvPhonetic: TextView = itemView.findViewById(R.id.tv_dict_phonetic)
        var entry: Pair<String, String>? = null

        init {
            // النقر (نقرتان من TalkBack) أو الضغطة المطولة: تعديل/حذف
            // الإدخال — النقر الجهازي مكافئ لقائمة الأدوات، فلا يضيع
            // الإجراء على مستخدمي القارئ.
            itemView.setOnClickListener { entry?.let(onRowClick) }
            itemView.setOnLongClickListener {
                entry?.let(onRowClick)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DictVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dict_entry, parent, false)
        return DictVH(view)
    }

    override fun onBindViewHolder(holder: DictVH, position: Int) {
        val entry = getItem(position)
        holder.entry = entry
        holder.tvWord.text = entry.first
        holder.tvPhonetic.text = entry.second
        // وصف مدمج لعقدة الصف الواحدة (الأطفال معطَّلون في XML)
        holder.itemView.contentDescription = "${entry.first}. ${entry.second}"
    }
}
