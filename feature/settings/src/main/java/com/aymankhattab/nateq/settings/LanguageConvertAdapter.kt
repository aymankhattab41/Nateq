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
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository

/** صف لغةٍ في قائمة إعداد التحويل التلقائي (كل صف = لغة مكتشفة عبر المحركات). */
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
 * (SettingsRepository.getEnginePreferenceForLanguage/setEnginePreferenceForLanguage).
 */
internal class LanguageConvertAdapter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rows: List<LanguageRow>,
    /** يُستدعى عند طلب معاينة نطق (ينفّذه الضابط عبر محركٍ مؤقت). */
    private val previewCallback: ((enginePackage: String, voiceName: String, volume: Float, pitch: Float, rate: Float) -> Unit)?
) : RecyclerView.Adapter<LanguageConvertAdapter.RowViewHolder>() {

    /** يُستدعى بعد كل حفظ صف (لتحديث سطر حالة القسم). */
    var onRowSaved: () -> Unit = {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_convert_language, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle = itemView.findViewById<TextView>(R.id.tv_convert_lang_title)
        private val tvSaved = itemView.findViewById<TextView>(R.id.tv_convert_row_saved)
        private val actvEngine = itemView.findViewById<MaterialAutoCompleteTextView>(R.id.actv_convert_row_engine)
        private val actvVoice = itemView.findViewById<MaterialAutoCompleteTextView>(R.id.actv_convert_row_voice)
        private val seekVol = itemView.findViewById<SeekBar>(R.id.seek_convert_row_volume)
        private val tvVol = itemView.findViewById<TextView>(R.id.tv_convert_row_volume_value)
        private val seekPitch = itemView.findViewById<SeekBar>(R.id.seek_convert_row_pitch)
        private val tvPitch = itemView.findViewById<TextView>(R.id.tv_convert_row_pitch_value)
        private val seekRate = itemView.findViewById<SeekBar>(R.id.seek_convert_row_rate)
        private val tvRate = itemView.findViewById<TextView>(R.id.tv_convert_row_rate_value)
        private val btnSave = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_row_save)
        private val btnPlay = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_row_play)

        private var rowEngines: List<EngineWithVoices> = emptyList()
        private var currentLanguageTag: String = ""

        fun bind(row: LanguageRow) {
            currentLanguageTag = row.languageTag
            tvTitle.text = row.displayName
            tvSaved.text = ""
            rowEngines = row.engines
            val saved = settings.getEnginePreferenceForLanguage(row.languageTag)

            // الأشرطة: تُزرع من القيم المحفوظة وتحدّث القيمة المعروضة مباشرة.
            seekVol.progress = (saved.volume * 100).toInt().coerceIn(0, 100)
            tvVol.text = "${seekVol.progress}%"
            seekPitch.progress = (saved.pitch * 100).toInt().coerceIn(0, 200)
            tvPitch.text = String.format(Locale.US, "%.1fx", saved.pitch)
            seekRate.progress = (saved.rate * 100).toInt().coerceIn(0, 200)
            tvRate.text = String.format(Locale.US, "%.1fx", saved.rate)

            seekVol.setOnSeekBarChangeListener(volumeListener(tvVol))
            seekPitch.setOnSeekBarChangeListener(rateLikeListener(tvPitch))
            seekRate.setOnSeekBarChangeListener(rateLikeListener(tvRate))

            if (rowEngines.isEmpty()) {
                // اللغة واردة حتى بلا محرك (حالتا ar/en المضمونتان عند تعثر الاكتشاف):
                // لا محرك للاختيار، لكن الأشرطة قابلة للحفظ رغم ذلك.
                bindDropdown(actvEngine, listOf(context.getString(R.string.auto_convert_none)), null) {}
                actvEngine.isEnabled = false
                bindDropdown(actvVoice, emptyList(), null) {}
                actvVoice.isEnabled = false
                btnPlay.isEnabled = false
            } else {
                actvEngine.isEnabled = true
                val engineLabels = rowEngines.map { it.engineLabel }
                val savedEngineIdx = rowEngines.indexOfFirst { it.enginePackage == saved.engine }
                val target = if (savedEngineIdx >= 0) savedEngineIdx else preferredEngineIndex()
                bindDropdown(actvEngine, engineLabels, engineLabels[target]) { position ->
                    if (position >= 0 && position < rowEngines.size) {
                        actvEngine.setText(engineLabels[position], false)
                        populateVoices(rowEngines[position], saved.voiceName)
                    }
                }
                // فتح القائمة عند الضغط على حقل المحرك (إظهار كل المحركات).
                actvEngine.setOnClickListener { actvEngine.showDropDown() }
                populateVoices(rowEngines[target], saved.voiceName)
                btnPlay.isEnabled = true
            }

            btnSave.setOnClickListener { saveRow() }
            btnPlay.setOnClickListener { playRow() }
        }

        /** فهرس المحرك المفضّل (نفس نكهة EnginePicker: الطرفي أولاً ثم جوجل). */
        private fun preferredEngineIndex(): Int {
            val preferred = EnginePicker.pickPreferredEngineFrom(rowEngines.map { it.enginePackage })
            val idx = rowEngines.indexOfFirst { it.enginePackage == preferred }
            return if (idx >= 0) idx else 0
        }

        /** يملأ قائمة الصوت بأصوات المحرك الحالي لهذه اللغة (بلا تكرار). */
        private fun populateVoices(engine: EngineWithVoices, savedVoice: String?) {
            val voices = engine.voices.distinctBy { it.name }
            if (voices.isEmpty()) {
                bindDropdown(actvVoice, listOf(context.getString(R.string.auto_convert_none)), null) {}
                return
            }
            val voiceLabels = voices.map { it.name }
            val current = voices
                .firstOrNull { it.name == savedVoice }
                ?.let { it.name }
                ?: voiceLabels.firstOrNull()
            bindDropdown(actvVoice, voiceLabels, current) { position ->
                if (position >= 0 && position < voiceLabels.size) {
                    actvVoice.setText(voiceLabels[position], false)
                }
            }
            actvVoice.setOnClickListener { actvVoice.showDropDown() }
        }

        /** يعرض حقل قائمة (كومبو بوكس) بعناصر جاهزة واختيارٍ أولي، مع معالج اختيار. */
        private fun bindDropdown(
            actv: MaterialAutoCompleteTextView,
            items: List<String>,
            current: String?,
            onSelect: (Int) -> Unit
        ) {
            val adapter = ArrayAdapter(
                actv.context,
                android.R.layout.simple_list_item_1,
                items
            )
            actv.setAdapter(adapter)
            actv.setText(if (current != null && current in items) current else items.firstOrNull().orEmpty(), false)
            actv.setOnItemClickListener { _, _, position, _ -> onSelect(position) }
        }

        /** اسم صوتٍ صالح حالي (أو null إن لم يكن اختيار اللسان صوتَ آخرَ). */
        private fun currentVoiceName(): String? =
            actvVoice.text?.toString()?.takeIf {
                it != context.getString(R.string.auto_convert_none)
            }

        private fun saveRow() {
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f
            val engineLabel = actvEngine.text?.toString().orEmpty()
            val engine = rowEngines.firstOrNull { it.engineLabel == engineLabel }?.enginePackage
            settings.setEnginePreferenceForLanguage(currentLanguageTag, engine, currentVoiceName(), rate, pitch, volume)
            tvSaved.text = context.getString(R.string.auto_convert_saved)
            tvSaved.announceCompat(context.getString(R.string.auto_convert_saved))
            onRowSaved()
        }

        private fun playRow() {
            val engineLabel = actvEngine.text?.toString().orEmpty()
            val engine = rowEngines.firstOrNull { it.engineLabel == engineLabel }?.enginePackage ?: return
            val voiceName = currentVoiceName() ?: return
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f
            previewCallback?.invoke(engine, voiceName, volume, pitch, rate)
        }

        private fun volumeListener(label: TextView) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = "$progress%"
                seekBar.setSeekStateDescription(label.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.announceCompat("${seekBar.progress}%")
            }
        }

        private fun rateLikeListener(label: TextView) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                label.text = String.format(Locale.US, "%.1fx", v)
                seekBar.setSeekStateDescription(label.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val v = seekBar.progress / 100f
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", v))
            }
        }
    }
}
