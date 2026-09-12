package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.aymankhattab.nateq.core.data.SettingsRepository

/** صف لغةٍ في قائمة إعداد التحويل التلقائي
 *  (كل صف = لغة مكتشفة عبر المحركات). */
internal data class LanguageRow(
    val languageTag: String,
    val displayName: String,
    /** المحركات التي توفّر هذه اللغة (فارغة = لا محرك قادراً عليها). */
    val engines: List<EngineWithVoices>
)

/**
 * معرّف قائمة لغات التحويل التلقائي: صف لكل لغةٍ فيه اختيار المحرك ← الصوت
 * داخل ذاك المحرك، وأشرطة مستوى الصوت/النبرة/السرعة، ومعاينة النطق، وحفظ.
 * القراءة/الكتابة تتم عبر الخريطة الديناميكية
 * (SettingsRepository.getEnginePreferenceForLanguage/
 *  setEnginePreferenceForLanguage).
 */
internal class LanguageConvertAdapter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rows: List<LanguageRow>,
    /** يُستدعى عند طلب معاينة نطق (ينفّذه الضابط عبر محركٍ مؤقت). */
    private val previewCallback: (
        enginePackage: String,
        voiceName: String,
        volume: Float,
        pitch: Float,
        rate: Float
    ) -> Unit?
) : RecyclerView.Adapter<LanguageConvertAdapter.RowViewHolder>() {

    /** يُستدعى بعد كل حفظ صف (لتحديث سطر حالة القسم). */
    var onRowSaved: () -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_convert_language, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RowViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {
        private val tvTitle =
            itemView.findViewById<TextView>(R.id.tv_convert_lang_title)
        private val tvSaved =
            itemView.findViewById<TextView>(R.id.tv_convert_row_saved)
        private val actvEngine =
            itemView.findViewById<MaterialAutoCompleteTextView>(
                R.id.actv_convert_row_engine
            )
        private val actvVoice =
            itemView.findViewById<MaterialAutoCompleteTextView>(
                R.id.actv_convert_row_voice
            )
        private val seekVol =
            itemView.findViewById<SeekBar>(R.id.seek_convert_row_volume)
        private val tvVol =
            itemView.findViewById<TextView>(R.id.tv_convert_row_volume_value)
        private val seekPitch =
            itemView.findViewById<SeekBar>(R.id.seek_convert_row_pitch)
        private val tvPitch =
            itemView.findViewById<TextView>(R.id.tv_convert_row_pitch_value)
        private val seekRate =
            itemView.findViewById<SeekBar>(R.id.seek_convert_row_rate)
        private val tvRate =
            itemView.findViewById<TextView>(R.id.tv_convert_row_rate_value)
        private val btnSave = itemView.findViewById(
            R.id.btn_convert_row_save
        ) as com.google.android.material.button.MaterialButton
        private val btnPlay = itemView.findViewById(
            R.id.btn_convert_row_play
        ) as com.google.android.material.button.MaterialButton

        private var rowEngines: List<EngineWithVoices> = emptyList()
        private var currentLanguageTag: String = ""
        private var savedVoiceName: String? = null
        private var currentVoiceLabels: List<String> = emptyList()

        // المستمعات تُنشأ مرة واحدة عند صنع الحامل (لا تُنشأ كائنات جديدة مع
        // كل إعادة ربط) وتقرأ حالة الصف الحالية وقت الحدث عبر الحقول أعلاه.
        private val volumeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                tvVol.text = "$progress%"
                seekBar.setSeekStateDescription(tvVol.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.announceCompat("${seekBar.progress}%")
            }
        }

        private val pitchListener = rateLikeListenerFor(tvPitch)
        private val rateListener = rateLikeListenerFor(tvRate)

        init {
            seekVol.setOnSeekBarChangeListener(volumeListener)
            seekPitch.setOnSeekBarChangeListener(pitchListener)
            seekRate.setOnSeekBarChangeListener(rateListener)
            // فتح القائمة عند الضغط على حقل المحرك (إظهار كل المحركات).
            actvEngine.setOnClickListener { actvEngine.showDropDown() }
            actvVoice.setOnClickListener { actvVoice.showDropDown() }
            actvEngine.setOnItemClickListener { _, _, pos, _ ->
                onEngineSelected(pos)
            }
            actvVoice.setOnItemClickListener { _, _, pos, _ ->
                onVoiceSelected(pos)
            }
            btnSave.setOnClickListener { saveRow() }
            btnPlay.setOnClickListener { playRow() }
        }

        fun bind(row: LanguageRow) {
            currentLanguageTag = row.languageTag
            tvTitle.text = row.displayName
            tvSaved.text = ""
            rowEngines = row.engines
            val saved = settings.getEnginePreferenceForLanguage(
                row.languageTag
            )
            savedVoiceName = saved.voiceName

            // الأشرطة: تُزرع من القيم المحفوظة وتحدّث القيمة المعروضة مباشرة.
            seekVol.progress = (saved.volume * 100).toInt().coerceIn(0, 100)
            tvVol.text = "${seekVol.progress}%"
            seekPitch.progress = (saved.pitch * 100).toInt().coerceIn(0, 200)
                .coerceAtLeast(MIN_SPEED_PITCH_PERCENT)
            tvPitch.text = RateLabel.of(
                context,
                saved.pitch.coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
            )
            seekRate.progress = (saved.rate * 100).toInt().coerceIn(0, 200)
                .coerceAtLeast(MIN_SPEED_PITCH_PERCENT)
            tvRate.text = RateLabel.of(
                context,
                saved.rate.coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
            )

            if (rowEngines.isEmpty()) {
                // اللغة واردة حتى بلا محرك (حالتا ar/en المضمونتان عند تعثر
                // الاكتشاف): لا محرك للاختيار، لكن الأشرطة قابلة للحفظ.
                bindDropdown(
                    actvEngine,
                    listOf(context.getString(R.string.auto_convert_none)),
                    null
                )
                actvEngine.isEnabled = false
                bindDropdown(actvVoice, emptyList(), null)
                actvVoice.isEnabled = false
                btnPlay.isEnabled = false
            } else {
                actvEngine.isEnabled = true
                val engineLabels = rowEngines.map { it.engineLabel }
                val savedEngineIdx = rowEngines.indexOfFirst {
                    it.enginePackage == saved.engine
                }
                val target = if (savedEngineIdx >= 0) {
                    savedEngineIdx
                } else {
                    preferredEngineIndex()
                }
                bindDropdown(actvEngine, engineLabels, engineLabels[target])
                populateVoices(rowEngines[target], savedVoiceName)
                btnPlay.isEnabled = true
            }
        }

        /** فهرس المحرك المفضّل (نفس نكهة EnginePicker:
     *  الطرفي/النظامي أولاً، وجوجل أخير الملاذات). */
        private fun preferredEngineIndex(): Int {
            val preferred = EnginePicker.pickPreferredEngineFrom(
                rowEngines.map { it.enginePackage }
            )
            val idx = rowEngines.indexOfFirst { it.enginePackage == preferred }
            return if (idx >= 0) idx else 0
        }

        /** يملأ قائمة الصوت بأصوات المحرك الحالي لهذه اللغة (بلا تكرار). */
        private fun populateVoices(
        engine: EngineWithVoices,
        savedVoice: String?
    ) {
            val voices = engine.voices.distinctBy { it.name }
            if (voices.isEmpty()) {
                currentVoiceLabels = emptyList()
                bindDropdown(
                    actvVoice,
                    listOf(context.getString(R.string.auto_convert_none)),
                    null
                )
                return
            }
            currentVoiceLabels = voices.map { it.name }
            val current = currentVoiceLabels.firstOrNull {
                it == savedVoice
            } ?: currentVoiceLabels.firstOrNull()
            bindDropdown(actvVoice, currentVoiceLabels, current)
        }

        /** يعرض حقل قائمة (كومبو بوكس) بعناصر جاهزة واختيارٍ أولي؛
         *  المستمعان (الفتح والنقر) مثبّتان في init الحامل. */
        private fun bindDropdown(
            actv: MaterialAutoCompleteTextView,
            items: List<String>,
            current: String?
        ) {
            val adapter = ArrayAdapter(
                actv.context,
                android.R.layout.simple_list_item_1,
                items
            )
            actv.setAdapter(adapter)
            actv.setText(
                if (current != null && current in items) {
                    current
                } else {
                    items.firstOrNull().orEmpty()
                },
                false
            )
        }

        /** اختيار محركٍ من القائمة: يعرضه ويُعيد ملء قائمة الأصوات لمحركه. */
        private fun onEngineSelected(position: Int) {
            if (position < 0 || position >= rowEngines.size) return
            actvEngine.setText(rowEngines[position].engineLabel, false)
            populateVoices(rowEngines[position], savedVoiceName)
        }

        /** اختيار صوتٍ من القائمة: يعرضه في الحقل. */
        private fun onVoiceSelected(position: Int) {
            if (position < 0 || position >= currentVoiceLabels.size) return
            actvVoice.setText(currentVoiceLabels[position], false)
        }

        /** اسم صوتٍ صالح حالي (أو null إن لم يكن اختيار اللسان صوتَ آخرَ). */
        private fun currentVoiceName(): String? =
            actvVoice.text?.toString()?.takeIf {
                it != context.getString(R.string.auto_convert_none)
            }

        private fun saveRow() {
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress.speedFactor()
            val rate = seekRate.progress.speedFactor()
            val engineLabel = actvEngine.text?.toString().orEmpty()
            val engine = rowEngines.firstOrNull {
                it.engineLabel == engineLabel
            }?.enginePackage
            settings.setEnginePreferenceForLanguage(
                currentLanguageTag,
                engine,
                currentVoiceName(),
                rate,
                pitch,
                volume
            )
            // اختيار محرك صريح يفعّل «التحويل التلقائي» تلقائياً بحيث تُنطق
            // الإعلانات بالمحرك المختار فوراً (كما في معالج الإعداد الأولي).
            // «لا شيء»/مسح المحرك لا يغيّر الحالة الحالية.
            if (engine != null && !settings.isAutoConvertEnabled()) {
                settings.setAutoConvertEnabled(true)
            }
            tvSaved.text = context.getString(R.string.auto_convert_saved)
            tvSaved.announceCompat(
            context.getString(R.string.auto_convert_saved)
        )
            onRowSaved()
        }

        private fun playRow() {
            val engineLabel = actvEngine.text?.toString().orEmpty()
            val engine = rowEngines.firstOrNull {
                it.engineLabel == engineLabel
            }?.enginePackage ?: return
            val voiceName = currentVoiceName() ?: return
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress.speedFactor()
            val rate = seekRate.progress.speedFactor()
            previewCallback?.invoke(engine, voiceName, volume, pitch, rate)
        }

/** مستمع سرعة/نبرة يُنشأ مرة واحدة لكل حامل (يقرأ قيمة progress وقتها). */
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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val v = seekBar.progress.speedFactor()
                seekBar.announceCompat(RateLabel.of(context, v))
            }
        }
    }
}
