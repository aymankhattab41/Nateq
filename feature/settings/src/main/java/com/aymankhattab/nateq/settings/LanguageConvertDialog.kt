package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.audio.engine.UnavailableEngine
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.VoiceIdContract
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/** صف لغةٍ في إعداد التحويل التلقائي (كل صف = لغة مكتشفة عبر المحركات). */
internal data class LanguageRow(
    val languageTag: String,
    val displayName: String,
    /** المحركات التي توفّر هذه اللغة (فارغة = لا محرك قادراً عليها). */
    val engines: List<EngineWithVoices>,
    /** المحركات غير المتاحة مع شارة السبب (ع٣١-5). */
    val unavailableEngines: List<UnavailableEngine> = emptyList()
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

/** عناصر قائمة المحرك + فهرس المحرّك المحفوظ: خيار «بدون محرك» في المقدمة
 *  ثم محركات اللغة ثم المحركات غير المتاحة بشاراتها؛ ولغة بلا محرك تُعرض
 *  بسبب غيابٍ مفسَّر (غير جاهز/فشل جلب الأصوات/غير متاح) وليس صامتاً. */
internal fun engineChoice(
    row: LanguageRow,
    noEngineLabel: String,
    noOptionsLabel: String,
    savedEngine: String?
): ConvertChoice {
    if (row.engines.isEmpty() && row.unavailableEngines.isEmpty()) {
        // بند 10: سبب الغياب بنص مسموع (لا محرك/أصوات متاحة لهذه اللغة).
        return ConvertChoice(
            listOf("$noOptionsLabel (لا محرك متاح لهذه اللغة)"),
            null
        )
    }
    val items = mutableListOf(noEngineLabel)
    items += row.engines.map { it.engineLabel }
    items += row.unavailableEngines.map { unavail ->
        val reason = unavail.message ?: unavail.code ?: "معطّل"
        "${unavail.engineLabel} (غير متاح: $reason)"
    }
    val resolved = if (savedEngine == null) {
        0
    } else {
        val availIdx = row.engines.indexOfFirst {
            it.enginePackage == savedEngine
        }
        if (availIdx >= 0) {
            availIdx + 1
        } else {
            val unavailIdx = row.unavailableEngines.indexOfFirst {
                it.enginePackage == savedEngine
            }
            if (unavailIdx >= 0) {
                1 + row.engines.size + unavailIdx
            } else {
                0
            }
        }
    }
    return ConvertChoice(items, resolved)
}

/** معرّف المحرك من عنوانٍ مختار في القائمة
 *  (null لـ«بدون محرك»/«لا توجد خيارات»/عنوان غير معروف). */
internal fun enginePackageForLabel(
    row: LanguageRow,
    selectedLabel: String?,
    noEngineLabel: String,
    noOptionsLabel: String
): String? {
    if (selectedLabel.isNullOrBlank()) return null
    val trimmed = selectedLabel.trim()
    if (trimmed == noEngineLabel ||
        trimmed == noOptionsLabel ||
        trimmed.startsWith(noOptionsLabel)
    ) return null
    val matched = row.engines.firstOrNull { it.engineLabel == trimmed }
    if (matched != null) return matched.enginePackage
    val unavail = row.unavailableEngines.firstOrNull { un ->
        val reason = un.message ?: un.code ?: "معطّل"
        trimmed == "${un.engineLabel} (غير متاح: $reason)" ||
            trimmed.startsWith(un.engineLabel)
    }
    return unavail?.enginePackage
}

/** عناصر قائمة الصوت المحسومة من أسماء أصوات المحرك المختار + فهرس
 *  الصوت المحفوظ؛ قائمة فارغة (بلا محرك) → «لا توجد خيارات».
 *  بند 9: عند غياب الصوت المحفوظ لا يُختار أول صوت بصمتاً.
 *  بند 4: توافق القيم المحفوظة القديمة (اسم أساسي بلا جودة/بدائل تهجئة)
 *  عبر مطابقة قانونية موحّدة عند وجود تطابق وحيد. */
internal fun voiceChoice(
    voiceNames: List<String>,
    noOptionsLabel: String,
    savedVoice: String?
): ConvertChoice {
    if (voiceNames.isEmpty()) {
        return ConvertChoice(listOf(noOptionsLabel), null)
    }
    val idx = if (savedVoice.isNullOrBlank()) {
        0
    } else {
        val exact = voiceNames.indexOf(savedVoice)
        if (exact >= 0) {
            exact
        } else {
            // توافق قديم: طابق بالاسم القانوني/البدائل أو احتواء وحيد.
            val byCanonical = voiceNames.withIndex().filter { (_, n) ->
                VoiceIdContract.sameVoiceName(n, savedVoice) ||
                    VoiceIdContract.canonicalVoiceName(n)
                        .equals(
                            VoiceIdContract.canonicalVoiceName(savedVoice),
                            ignoreCase = true
                        )
            }
            if (byCanonical.size == 1) {
                byCanonical.first().index
            } else {
                val byContains = voiceNames.withIndex().filter { (_, n) ->
                    n.contains(savedVoice, ignoreCase = true) ||
                        savedVoice.contains(n, ignoreCase = true)
                }
                if (byContains.size == 1) byContains.first().index else null
            }
        }
    }
    return ConvertChoice(voiceNames, idx)
}

/** أسماء أصوات محركٍ مرتّبة للعرض.
 *  بند 4: لا ندمج متغيّرات مختلفة بالاسم؛ نحافظ على ترتيب الظهور ونزيل
 *  التكرار الحرفي فقط (تمييز الجودة مثل Tarik Compact/High يظهر بالاسم). */
internal fun engineVoiceNames(engine: EngineWithVoices): List<String> {
    val seen = LinkedHashSet<String>()
    for (v in engine.voices) {
        val name = v.name
        if (name.isNotBlank()) seen.add(name)
    }
    return seen.toList()
}

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

/** يحسم قيم الحفظ من حالة الشاشة الحالية: بلا محرك → null المحرك والصوت،
 *  وتُطبّق الأشرطة وحدها (سلوك الحفظ القديم محفوظ). */
internal fun convertSaveValues(
    row: LanguageRow,
    engineLabel: String?,
    voiceLabel: String?,
    volumeProgress: Int,
    pitchProgress: Int,
    rateProgress: Int,
    noEngineLabel: String,
    noOptionsLabel: String
): ConvertSaveValues {
    val engine = enginePackageForLabel(
        row, engineLabel, noEngineLabel, noOptionsLabel
    )
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
        languageTag: String,
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
    private var noEngineLabel: String = ""
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

        noEngineLabel = context.getString(R.string.auto_convert_no_engine)
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
        val savedEngine = saved.engine
        savedVoice = saved.voiceName ?: if (savedEngine != null) {
            settings.getPreferredVoiceForEngine(
                savedEngine, row.languageTag
            )
        } else {
            settings.getPreferredVoiceForEngine(
                SettingsRepository.ENGINE_LORD_TTS, row.languageTag
            )
        }

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

        val engine = engineChoice(
            row, noEngineLabel, noOptionsLabel, saved.engine
        )
        bindDropdown(actvEngine, engine.labels, engine.resolvedIndex)
        actvEngine.isEnabled =
            row.engines.isNotEmpty() || row.unavailableEngines.isNotEmpty()

        val enginePkg = enginePackageForLabel(
            row, actvEngine.text?.toString(), noEngineLabel, noOptionsLabel
        )
        bindVoiceFor(enginePkg)
        val hadMissingVoiceMsg =
            tvSaved.text?.toString()?.startsWith(
                "الصوت المحفوظ غير متاح"
            ) == true
        pending = false
        btnSave.isEnabled = false
        if (!hadMissingVoiceMsg) {
            // بند 9/10: عرض السطر الكامل أو سبب الغياب إن لم تُعرض رسالة أهم.
            val selectedEngine = enginePackageForLabel(
                row, actvEngine.text?.toString(),
                noEngineLabel, noOptionsLabel
            )
            val voiceLabel = voiceNameFromLabel(
                actvVoice.text?.toString(), noOptionsLabel
            )
            val reasonMsg = if (row.engines.isEmpty() &&
                row.unavailableEngines.isEmpty()
            ) {
                "لا محرك متاح لهذه اللغة (غير جاهز أو فشل جلب الأصوات)"
            } else {
                null
            }
            tvSaved.text = reasonMsg ?: fullSelectionLine(
                selectedEngine, voiceLabel
            )
            if (reasonMsg != null) {
                tvSaved.announceCompat(reasonMsg)
            }
        }
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
            currentRow, label, noEngineLabel, noOptionsLabel
        )
        // بند 9: عدم إسقاط الصوت عند تغيير المحرك — إن كان الصوت
        // المختار حالياً موجوداً في أصوات المحرك الجديد نُبقيه.
        val currentVoice = voiceNameFromLabel(
            actvVoice.text?.toString(), noOptionsLabel
        )
        val newEngine = currentRow.engines.firstOrNull {
            it.enginePackage == enginePkg
        }
        val newNames = newEngine?.let { engineVoiceNames(it) }.orEmpty()
        savedVoice = when {
            enginePkg == null -> null
            currentVoice != null && newNames.any { it == currentVoice } ->
                currentVoice
            else -> settings.getPreferredVoiceForEngine(
                enginePkg, currentRow.languageTag
            )
        }
        val unavail = currentRow.unavailableEngines.firstOrNull {
            it.enginePackage == enginePkg
        }
        bindVoiceFor(enginePkg)
        if (unavail != null) {
            val reason = unavail.message ?: unavail.code ?: "المحرك غير جاهز"
            val msg = "تنبيه: ${unavail.engineLabel} ($reason)"
            tvSaved.text = if (tvSaved.text.isNullOrBlank()) {
                msg
            } else {
                "$msg\n${tvSaved.text}"
            }
            tvSaved.announceCompat(msg)
            Toast.makeText(
                context,
                "${unavail.engineLabel}: $reason",
                Toast.LENGTH_LONG
            ).show()
        } else if (enginePkg == null &&
            (currentRow.engines.isEmpty() &&
                currentRow.unavailableEngines.isEmpty() ||
                actvEngine.text?.toString().orEmpty()
                    .startsWith(noOptionsLabel))
        ) {
            // بند 10: سبب الغياب مسموع.
            val msg = "لا محرك متاح لهذه اللغة (غير جاهز أو فشل جلب الأصوات)"
            tvSaved.text = msg
            tvSaved.announceCompat(msg)
        }
        markPending()
    }

    private fun onVoiceSelected(position: Int) {
        val label = itemAt(actvVoice, position) ?: return
        actvVoice.setText(label, false)
        val enginePkg = enginePackageForLabel(
            currentRow, actvEngine.text?.toString(),
            noEngineLabel, noOptionsLabel
        )
        val voice = voiceNameFromLabel(label, noOptionsLabel)
        btnPlay.isEnabled = enginePkg != null && voice != null
        tvSaved.text = fullSelectionLine(enginePkg, voice)
        markPending()
    }

    /** يملأ قائمة الصوت للمحرك الحالي ويضبط تفعيلها وزر العيّنة.
     *  بند 9: عند غياب الصوت المحفوظ لا يُختار أول صوت بصمتاً ويُعرض
     *  "الصوت المحفوظ غير متاح: <الاسم>". */
    private fun bindVoiceFor(enginePkg: String?) {
        val engine = currentRow.engines.firstOrNull {
            it.enginePackage == enginePkg
        }
        val names = engine?.let { engineVoiceNames(it) }.orEmpty()
        val vc = voiceChoice(names, noOptionsLabel, savedVoice)
        val resolvedName = vc.resolvedIndex?.let { names.getOrNull(it) }
        val missingSaved = !savedVoice.isNullOrBlank() &&
            names.isNotEmpty() &&
            resolvedName == null
        if (missingSaved) {
            // لا اختيار صامت لأول صوت: نعرض القائمة بلا تحديد.
            bindDropdown(actvVoice, vc.labels, null)
            actvVoice.setText("", false)
            val msg = "الصوت المحفوظ غير متاح: $savedVoice"
            val full = fullSelectionLine(enginePkg, null)
            tvSaved.text = "$msg\n$full"
            tvSaved.announceCompat(tvSaved.text.toString())
            btnPlay.isEnabled = false
        } else {
            // توافق قديم (بند 4): إن حُلّت القيمة المحفوظة إلى اسم مختلف
            // (بديل/جودة وحيدة) اعتمد الاسم المحلول للعرض والاختيار.
            if (resolvedName != null && savedVoice != null &&
                resolvedName != savedVoice
            ) {
                savedVoice = resolvedName
            }
            bindDropdown(actvVoice, vc.labels, vc.resolvedIndex)
            actvVoice.isEnabled =
                enginePkg != null && vc.labels.isNotEmpty()
            btnPlay.isEnabled =
                enginePkg != null && vc.resolvedIndex != null
            // بند 9: عرض السطر الكامل (اللغة، المحرك، اسم الصوت).
            val voiceLabel = voiceNameFromLabel(
                actvVoice.text?.toString(), noOptionsLabel
            )
            if (enginePkg != null || !savedVoice.isNullOrBlank()) {
                tvSaved.text = fullSelectionLine(enginePkg, voiceLabel)
            }
        }
        actvVoice.isEnabled = enginePkg != null && names.isNotEmpty()
    }

    private fun fullSelectionLine(
        enginePkg: String?,
        voiceName: String?
    ): String {
        val engineLabel = currentRow.engines.firstOrNull {
            it.enginePackage == enginePkg
        }?.engineLabel ?: enginePkg ?: "بدون محرك"
        val voice = voiceName?.takeIf { it.isNotBlank() } ?: "—"
        return "اللغة: ${currentRow.displayName}، " +
            "المحرك: $engineLabel، الصوت: $voice"
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
        var enginePkg = enginePackageForLabel(
            currentRow,
            actvEngine.text?.toString(),
            noEngineLabel,
            noOptionsLabel
        )
        val unavail = currentRow.unavailableEngines.firstOrNull {
            it.enginePackage == enginePkg
        }
        if (unavail != null) {
            val reason = unavail.message ?: unavail.code ?: "المحرك غير جاهز"
            tvSaved.text = "تعذر الحفظ: ${unavail.engineLabel} ($reason)"
            tvSaved.announceCompat(tvSaved.text.toString())
            Toast.makeText(
                context,
                "تعذر الحفظ: ${unavail.engineLabel} ($reason)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // بند 11: حفظ صوت بدون محرك — طلب اختيار المحرك أو اختياره
        // تلقائياً ولا ترك التحويل مغلقاً بصمتاً.
        val selectedVoice = voiceNameFromLabel(
            actvVoice.text?.toString(), noOptionsLabel
        )
        if (enginePkg == null && selectedVoice != null) {
            val autoEngine = currentRow.engines.firstOrNull()
            if (autoEngine == null) {
                val msg = "اختر محركاً قبل حفظ الصوت"
                tvSaved.text = msg
                tvSaved.announceCompat(msg)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                return
            }
            enginePkg = autoEngine.enginePackage
            // تحديث واجهة المحرك ليعكس الاختيار التلقائي.
            val labels = adapters[actvEngine.id]
            val idx = labels?.let { ad ->
                (0 until ad.count).firstOrNull { i ->
                    ad.getItem(i) == autoEngine.engineLabel
                }
            } ?: -1
            if (idx >= 0) {
                actvEngine.setText(autoEngine.engineLabel, false)
            }
        }

        val params = convertSaveValues(
            currentRow,
            actvEngine.text?.toString().takeIf { enginePkg != null }
                ?: currentRow.engines.firstOrNull {
                    it.enginePackage == enginePkg
                }?.engineLabel,
            selectedVoice ?: actvVoice.text?.toString(),
            seekVol.progress,
            seekPitch.progress,
            seekRate.progress,
            noEngineLabel,
            noOptionsLabel
        )
        // إن اخترنا محركاً تلقائياً يجب أن ينعكس في القيم المحفوظة.
        val saveEngine = enginePkg ?: params.engine
        val saveVoice = if (saveEngine == null) null else
            (params.voice ?: selectedVoice)
        fun performSave() {
            settings.setEnginePreferenceForLanguage(
                currentRow.languageTag,
                saveEngine,
                saveVoice,
                params.rate,
                params.pitch,
                params.volume
            )
            if (saveEngine != null && !settings.isAutoConvertEnabled()) {
                settings.setAutoConvertEnabled(true)
            }
        }

        performSave()

        val isDefault = saveEngine == null &&
            saveVoice == null &&
            params.rate == 1.0f &&
            params.pitch == 1.0f &&
            params.volume == 1.0f

        fun isVerified(): Boolean {
            val saved = settings.getEnginePreferenceForLanguage(
                currentRow.languageTag
            )
            return if (isDefault) {
                !saved.hasAdjustment
            } else {
                saved.engine == saveEngine &&
                    saved.voiceName == saveVoice &&
                    kotlin.math.abs(saved.rate - params.rate) < 0.05f &&
                    kotlin.math.abs(saved.pitch - params.pitch) < 0.05f &&
                    kotlin.math.abs(saved.volume - params.volume) < 0.05f
            }
        }

        var verified = isVerified()
        if (!verified) {
            // إعادة المحاولة مرة واحدة تلقائياً
            performSave()
            verified = isVerified()
        }

        if (verified) {
            pending = false
            btnSave.isEnabled = false
            savedVoice = params.voice
            val msg = if (isDefault) {
                "أُعيدت اللغة إلى الإعداد الافتراضي"
            } else {
                context.getString(R.string.auto_convert_saved) +
                    "\n" + fullSelectionLine(saveEngine, saveVoice)
            }
            tvSaved.text = msg
            tvSaved.announceCompat(msg)
            onChanged()
        } else {
            val errMsg = "تعذّر الحفظ، حاول مجدداً"
            tvSaved.text = errMsg
            tvSaved.announceCompat(errMsg)
            Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPreview() {
        val engine = enginePackageForLabel(
            currentRow, actvEngine.text?.toString(),
            noEngineLabel, noOptionsLabel
        ) ?: return
        val voice = voiceNameFromLabel(
            actvVoice.text?.toString(), noOptionsLabel
        ) ?: return
        val volume = seekVol.progress / 100f
        val pitch = seekPitch.progress.speedFactor()
        val rate = seekRate.progress.speedFactor()
        val langTag = currentRow.languageTag
        previewCallback?.invoke(
            engine,
            voice,
            langTag,
            volume,
            pitch,
            rate
        )
    }

    internal val volumeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(
            seekBar: SeekBar,
            progress: Int,
            fromUser: Boolean
        ) {
            tvVol.text = "$progress%"
            seekBar.setSeekStateDescription(tvVol.text)
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