package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/** صف لغةٍ في إعداد التحويل التلقائي (كل صف = لغة مكتشفة عبر المحركات). */
internal data class LanguageRow(
    val languageTag: String,
    val displayName: String,
    /** المحركات التي توفّر هذه اللغة (فارغة = لا محرك قادراً عليها). */
    val engines: List<EngineWithVoices>
)

/** حالة محرك اللغة للتبليغ عنها في سطر حالة قسم المحرك. */
internal enum class EngineStatus {
    NOT_SELECTED,
    DISABLED_OR_MISSING,
    CONFIGURED
}

/** محلل حالة محرك لغةٍ (تُعتمد في سطر حالة القسم العام). */
internal object EngineStatusResolver {
    fun resolveStatus(
        savedEngine: String?,
        installedPackages: Collection<String>
    ): EngineStatus = when {
        savedEngine == null -> EngineStatus.NOT_SELECTED
        !installedPackages.contains(savedEngine) ->
            EngineStatus.DISABLED_OR_MISSING
        else -> EngineStatus.CONFIGURED
    }
}

// ── منطق نقي لحوار «إعداد جميع اللغات» (قابل للاختبار الآلي) ──

/** نتيجة قائمة كمبو: عناصرها + فهرس المختار (null = بلا اختيار معيّن). */
internal data class ConvertChoice(
    val labels: List<String>,
    val resolvedIndex: Int?
)

/** عناصر قائمة المحرك + فهرس المحرّك المحفوظ: محركات اللغة فقط (لا خيار
 *  «بدون محرك» — غيابُ التخصيص معناه إسنادٌ تلقائيٌ صامت خارج الحوار)؛
 *  ولغة بلا محرك تُعرض بعنصرٍ وحيد «لا توجد خيارات». */
internal fun engineChoice(
    row: LanguageRow,
    noOptionsLabel: String,
    savedEngine: String?
): ConvertChoice {
    if (row.engines.isEmpty()) {
        return ConvertChoice(listOf(noOptionsLabel), null)
    }
    val items = row.engines.map { it.engineLabel }
    val resolved = row.engines.indexOfFirst { it.enginePackage == savedEngine }
        .takeIf { it >= 0 } ?: 0
    return ConvertChoice(items, resolved)
}

/** معرّف المحرك من عنوانٍ مختار في القائمة (null لـ«لا توجد خيارات»/فارغ
 *  /عنوان غير معروف). */
internal fun enginePackageForLabel(
    row: LanguageRow,
    selectedLabel: String?,
    noOptionsLabel: String
): String? {
    if (selectedLabel.isNullOrBlank()) return null
    val trimmed = selectedLabel.trim()
    if (trimmed == noOptionsLabel) return null
    return row.engines.firstOrNull { it.engineLabel == trimmed }
        ?.enginePackage
}

/** عناصر قائمة الصوت المحسومة من أسماء أصوات المحرك المختار + فهرس
 *  الصوت المحفوظ؛ قائمة فارغة (بلا محرك) → «لا توجد خيارات». */
internal fun voiceChoice(
    voiceNames: List<String>,
    noOptionsLabel: String,
    savedVoice: String?
): ConvertChoice {
    if (voiceNames.isEmpty()) {
        return ConvertChoice(listOf(noOptionsLabel), null)
    }
    val idx = voiceNames.indexOf(savedVoice).takeIf { it >= 0 } ?: 0
    return ConvertChoice(voiceNames, idx)
}

/** أسماء أصوات محركٍ (بعد إزالة تكرار الأسماء) — مدخل الـ UI لصياغة القائمة. */
internal fun engineVoiceNames(engine: EngineWithVoices): List<String> =
    engine.voices.distinctBy { it.name }.map { it.name }

/** الاسم الصوتي من العنوان المختار (null لـ«لا توجد خيارات»/فارغ). */
internal fun voiceNameFromLabel(
    selectedLabel: String?,
    noOptionsLabel: String
): String? = selectedLabel
    ?.takeIf { it.isNotBlank() && it.trim() != noOptionsLabel }
    ?.trim()

/** عناوين اللغات بترتيب الصفوف (لكمبو اللغة). */
internal fun languageLabels(rows: List<LanguageRow>): List<String> =
    rows.map { it.displayName }

/** الصف المطابق لعنوان لغةٍ مختار في الكمبو (null إن تعذّر). */
internal fun languageRowForLabel(
    rows: List<LanguageRow>,
    displayLabel: String
): LanguageRow? = rows.firstOrNull { it.displayName == displayLabel }

/** قيم الحفظ المحسومة من حالة الاختيار الحالية (حفظ تلقائي فوري). */
internal data class ConvertSaveValues(
    val engine: String?,
    val voice: String?,
    val rate: Float,
    val pitch: Float,
    val volume: Float
)

/** يحسم قيم الحفظ من حالة الشاشة الحالية: لغةٌ بلا محركات → null المحرك
 *  والصوت (إسنادٌ تلقائيٌ صامت خارج الحوار)، وتُطبّق الأشرطة وحدها. */
internal fun convertSaveValues(
    row: LanguageRow,
    engineLabel: String?,
    voiceLabel: String?,
    volumeProgress: Int,
    pitchProgress: Int,
    rateProgress: Int,
    noOptionsLabel: String
): ConvertSaveValues {
    val engine = enginePackageForLabel(row, engineLabel, noOptionsLabel)
    val voice = if (engine == null) {
        null
    } else {
        voiceNameFromLabel(voiceLabel, noOptionsLabel)
    }
    return ConvertSaveValues(
        engine,
        voice,
        rateProgress.speedFactor(),
        pitchProgress.speedFactor(),
        volumeProgress / 100f
    )
}

// ── ضابط الحوار المدمج: كمبو لغة/محرك/صوت + أشرطة ──

/**
 *  ضابط حوار «إعداد جميع اللغات» المبسّط: كمبو واحد للغة، وكمبو للمحرك،
 *  وكمبو للصوت، والأشرطة الثلاثة، وعيّنة النطق، وزر حفظ صريح. أي اختيار من
 *  الكمبوهات أو الأشرطة يُعلَّم «تغييرات غير محفوظة» ولا يُكتب إلا عند الضغط
 *  على «حفظ» (بدل الحفظ التلقائي الفوري) — وفق طلب المستخدم.
 *  القراءة/الكتابة عبر الخريطة الديناميكية
 * (SettingsRepository.getEnginePreferenceForLanguage/
 *  setEnginePreferenceForLanguage).
 */
internal class LanguageConvertDialogController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rows: List<LanguageRow>,
    /** معاينة نطق (ينفّذها الضابط عبر محرك/صوت مؤقتين دون حفظ). */
    private val previewCallback: (
        enginePackage: String,
        voiceName: String,
        volume: Float,
        pitch: Float,
        rate: Float
    ) -> Unit?
) {

    private lateinit var actvLang: MaterialAutoCompleteTextView
    private lateinit var actvEngine: MaterialAutoCompleteTextView
    private lateinit var actvVoice: MaterialAutoCompleteTextView
    private lateinit var seekVol: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var seekRate: SeekBar
    private lateinit var tvVol: TextView
    private lateinit var tvPitch: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvSaved: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnSave: MaterialButton

    private var currentRow: LanguageRow =
        LanguageRow("", "", emptyList())
    private var savedVoice: String? = null
    /** هل توجد تغييرات غير محفوظة منذ آخر ضغط «حفظ» أو تبديل لغة؟ */
    private var pending = false
    private var noOptionsLabel: String = ""
    private var onChanged: () -> Unit = {}

    fun bindTo(view: View, onChanged: () -> Unit) {
        this.onChanged = onChanged
        actvLang = view.findViewById(R.id.actv_convert_dialog_lang)
        actvEngine = view.findViewById(R.id.actv_convert_dialog_engine)
        actvVoice = view.findViewById(R.id.actv_convert_dialog_voice)
        seekVol = view.findViewById(R.id.seek_convert_dialog_volume)
        seekPitch = view.findViewById(R.id.seek_convert_dialog_pitch)
        seekRate = view.findViewById(R.id.seek_convert_dialog_rate)
        tvVol = view.findViewById(R.id.tv_convert_dialog_volume_value)
        tvPitch = view.findViewById(R.id.tv_convert_dialog_pitch_value)
        tvRate = view.findViewById(R.id.tv_convert_dialog_rate_value)
        tvSaved = view.findViewById(R.id.tv_convert_dialog_saved)
        btnPlay = view.findViewById(
            R.id.btn_convert_dialog_play
        ) as MaterialButton
        btnSave = view.findViewById(
            R.id.btn_convert_dialog_save
        ) as MaterialButton

        noOptionsLabel = context.getString(R.string.auto_convert_none)

        actvLang.setOnClickListener { actvLang.showDropDown() }
        actvEngine.setOnClickListener { actvEngine.showDropDown() }
        actvVoice.setOnClickListener { actvVoice.showDropDown() }
        actvLang.setOnItemClickListener { _, _, pos, _ ->
            onLanguageSelected(pos)
        }
        actvEngine.setOnItemClickListener { _, _, pos, _ ->
            onEngineSelected(pos)
        }
        actvVoice.setOnItemClickListener { _, _, pos, _ ->
            onVoiceSelected(pos)
        }

        seekVol.setOnSeekBarChangeListener(volumeListener)
        seekPitch.setOnSeekBarChangeListener(pitchListener)
        seekRate.setOnSeekBarChangeListener(rateListener)
        btnPlay.setOnClickListener { playPreview() }
        btnSave.setOnClickListener { saveCurrent() }

        bindLanguageList()
    }

    /** يملأ كمبو اللغة ويُظهر أول صف (العربية عادةً). */
    private fun bindLanguageList() {
        if (rows.isEmpty()) return
        bindDropdown(
            actvLang, languageLabels(rows), 0
        )
        bindRow(rows.first())
    }

    /** يسيطر واجهة الأكمبوهات والأشرطة لصف لغةٍ محدد بقيمه المحفوظة. */
    private fun bindRow(row: LanguageRow) {
        currentRow = row
        btnPlay.contentDescription = context.getString(
            R.string.cd_convert_play_for_language, row.displayName
        )
        btnSave.contentDescription = context.getString(
            R.string.cd_convert_save_for_language, row.displayName
        )
        val saved = settings.getEnginePreferenceForLanguage(row.languageTag)
        savedVoice = saved.voiceName

        seekVol.progress = (saved.volume * 100).toInt().coerceIn(0, 100)
        tvVol.text = "${seekVol.progress}%"
        seekPitch.progress = (saved.pitch * 100).toInt().coerceIn(0, 200)
            .coerceAtLeast(MIN_SPEED_PITCH_PERCENT)
        tvPitch.text = RateLabel.of(
            context, saved.pitch.coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        )
        seekRate.progress = (saved.rate * 100).toInt().coerceIn(0, 200)
            .coerceAtLeast(MIN_SPEED_PITCH_PERCENT)
        tvRate.text = RateLabel.of(
            context, saved.rate.coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        )

        val engine = engineChoice(row, noOptionsLabel, saved.engine)
        bindDropdown(actvEngine, engine.labels, engine.resolvedIndex)
        actvEngine.isEnabled = row.engines.isNotEmpty()

        val enginePkg = enginePackageForLabel(
            row, actvEngine.text?.toString(), noOptionsLabel
        )
        bindVoiceFor(enginePkg)
        pending = false
        tvSaved.text = ""
        btnSave.isEnabled = false
    }

    private fun onLanguageSelected(position: Int) {
        val row = rows.getOrNull(position) ?: return
        actvLang.setText(row.displayName, false)
        bindRow(row)
    }

    private fun onEngineSelected(position: Int) {
        val label = itemAt(actvEngine, position) ?: return
        actvEngine.setText(label, false)
        val enginePkg = enginePackageForLabel(
            currentRow, label, noOptionsLabel
        )
        bindVoiceFor(enginePkg)
        markPending()
    }

    private fun onVoiceSelected(position: Int) {
        val label = itemAt(actvVoice, position) ?: return
        actvVoice.setText(label, false)
        btnPlay.isEnabled = true
        markPending()
    }

    /** يملأ قائمة الصوت للمحرك الحالي ويضبط تفعيلها وزر العيّنة. */
    private fun bindVoiceFor(enginePkg: String?) {
        val engine = currentRow.engines.firstOrNull {
            it.enginePackage == enginePkg
        }
        val names = engine?.let { engineVoiceNames(it) }.orEmpty()
        val vc = voiceChoice(names, noOptionsLabel, savedVoice)
        bindDropdown(actvVoice, vc.labels, vc.resolvedIndex)
        actvVoice.isEnabled = enginePkg != null && vc.labels.isNotEmpty()
        btnPlay.isEnabled = enginePkg != null && vc.resolvedIndex != null
    }

    private val adapters = HashMap<Int, ArrayAdapter<String>>()

    private fun itemAt(
        actv: MaterialAutoCompleteTextView,
        position: Int
    ): String? = adapters[actv.id]?.getItem(position)

    /** يعلّم «تغييرات غير محفوظة» ويفعّل زر الحفظ — بدل الحفظ الفوري. */
    private fun markPending() {
        if (pending || !::btnSave.isInitialized) return
        pending = true
        btnSave.isEnabled = true
        tvSaved.text = context.getString(R.string.auto_convert_pending)
        tvSaved.announceCompat(tvSaved.text.toString())
    }

    /** حفظ صريح: يكتب إعدادات اللغة المختارة حالياً. */
    private fun saveCurrent() {
        val params = convertSaveValues(
            currentRow,
            actvEngine.text?.toString(),
            actvVoice.text?.toString(),
            seekVol.progress,
            seekPitch.progress,
            seekRate.progress,
            noOptionsLabel
        )
        settings.setEnginePreferenceForLanguage(
            currentRow.languageTag,
            params.engine,
            params.voice,
            params.rate,
            params.pitch,
            params.volume
        )
        // اختيار محرك صريح يفعّل «التحويل التلقائي» فوراً؛ لغةٌ بلا محركات
        // (engine الصفر) لا تغيّر حالة المفتاح.
        if (params.engine != null && !settings.isAutoConvertEnabled()) {
            settings.setAutoConvertEnabled(true)
        }
        pending = false
        btnSave.isEnabled = false
        savedVoice = params.voice
        tvSaved.text = context.getString(R.string.auto_convert_saved)
        tvSaved.announceCompat(tvSaved.text.toString())
        onChanged()
    }

    private fun playPreview() {
        val engine = enginePackageForLabel(
            currentRow, actvEngine.text?.toString(), noOptionsLabel
        ) ?: return
        val voice = voiceNameFromLabel(
            actvVoice.text?.toString(), noOptionsLabel
        ) ?: return
        val volume = seekVol.progress / 100f
        val pitch = seekPitch.progress.speedFactor()
        val rate = seekRate.progress.speedFactor()
        previewCallback?.invoke(engine, voice, volume, pitch, rate)
    }

    internal val volumeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(
            seekBar: SeekBar,
            progress: Int,
            fromUser: Boolean
        ) {
            tvVol.text = "$progress%"
            seekBar.setSeekStateDescription(tvVol.text)
            // **إتاحة TalkBack:** تعديل قارئ الشاشة يمر هنا حصراً (أداء
            // الوصول لا يُطلق onStopTrackingTouch) — فيُفعَّل «حفظ» فوراً.
            if (fromUser) markPending()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            seekBar.announceCompat("${seekBar.progress}%")
            markPending()
        }
    }

    /** مستمع سرعة/نبرة مشترك (تفعيل «حفظ» عند تغيّر المستخدم). */
    private fun rateLikeListenerFor(label: TextView) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                val v = progress.speedFactor()
                label.text = RateLabel.of(context, v)
                seekBar.setSeekStateDescription(label.text)
                // **إتاحة TalkBack:** تعديل قارئ الشاشة يمر هنا حصراً (أداء
                // الوصول لا يُطلق onStopTrackingTouch) — فيُفعَّل «حفظ» فوراً.
                if (fromUser) markPending()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val v = seekBar.progress.speedFactor()
                seekBar.announceCompat(RateLabel.of(context, v))
                markPending()
            }
        }

    // **كسولة (lazy):** تُقرأ tvPitch/tvRate (lateinit) عند أول استخدام في
    // bindTo — بعد تهيئتهما — لا عند إنشاء الكائن. كان التقييمُ الفوري
    // يرمي «lateinit property … has not been initialized» في خيط
    // DefaultDispatcher-worker عند دخول «إعداد جميع اللغات» (يُنشأ
    // الحوارُ داخل lifecycleScope.launch(AppDispatchers.io)).
    internal val pitchListener by lazy { rateLikeListenerFor(tvPitch) }
    internal val rateListener by lazy { rateLikeListenerFor(tvRate) }

    /** يعرض حقل قائمة (كمبو بوكس) بعناصر جاهزة وفهرس اختيار أولي. */
    private fun bindDropdown(
        actv: MaterialAutoCompleteTextView,
        items: List<String>,
        resolvedIndex: Int?
    ) {
        val adapter = ArrayAdapter(
            actv.context,
            android.R.layout.simple_list_item_1,
            items
        )
        adapters[actv.id] = adapter
        actv.setAdapter(adapter)
        actv.setText(
            if (resolvedIndex != null && resolvedIndex in items.indices) {
                items[resolvedIndex]
            } else {
                items.firstOrNull().orEmpty()
            },
            false
        )
    }
}