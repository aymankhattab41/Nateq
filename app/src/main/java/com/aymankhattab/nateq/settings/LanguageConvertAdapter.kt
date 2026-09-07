package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.EngineWithVoices
import com.aymankhattab.nateq.providers.EnginePicker
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale

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
        private val spEngine = itemView.findViewById<Spinner>(R.id.spinner_convert_row_engine)
        private val spVoice = itemView.findViewById<Spinner>(R.id.spinner_convert_row_voice)
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
                spEngine.adapter = simpleAdapter(context, listOf(context.getString(R.string.auto_convert_none)))
                spEngine.isEnabled = false
                spVoice.adapter = simpleAdapter(context, emptyList())
                spVoice.isEnabled = false
                btnPlay.isEnabled = false
            } else {
                spEngine.isEnabled = true
                spEngine.adapter = simpleAdapter(context, rowEngines.map { it.engineLabel })
                spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position >= 0 && position < rowEngines.size) {
                            populateVoices(rowEngines[position], saved.voiceName)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                val savedEngineIdx = rowEngines.indexOfFirst { it.enginePackage == saved.engine }
                val target = if (savedEngineIdx >= 0) savedEngineIdx else preferredEngineIndex()
                spEngine.setSelection(target)
                // setSelection لا يستدعي onItemSelected دائماً — نملأ الأصوات يدوياً.
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

        /** يملأ spinner الأصوات بأصوات المحرك الحالي لهذه اللغة (بلا تكرار). */
        private fun populateVoices(engine: EngineWithVoices, savedVoice: String?) {
            val voices = engine.voices.distinctBy { it.name }
            if (voices.isEmpty()) {
                spVoice.adapter = simpleAdapter(context, listOf(context.getString(R.string.auto_convert_none)))
                return
            }
            spVoice.adapter = simpleAdapter(context, voices.map { it.name })
            if (savedVoice != null) {
                val vi = voices.indexOfFirst { it.name == savedVoice }
                if (vi >= 0) spVoice.setSelection(vi)
            }
        }

        /** اسم صوتٍ صالح حالي (أو null إن لم يكن اختيار اللسان صوتَ آخرَ). */
        private fun currentVoiceName(): String? =
            spVoice.selectedItem?.toString()?.takeIf {
                it != context.getString(R.string.auto_convert_none)
            }

        private fun saveRow() {
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f
            val engine = rowEngines.getOrNull(spEngine.selectedItemPosition)?.enginePackage
            settings.setEnginePreferenceForLanguage(currentLanguageTag, engine, currentVoiceName(), rate, pitch, volume)
            tvSaved.text = context.getString(R.string.auto_convert_saved)
            tvSaved.announceCompat(context.getString(R.string.auto_convert_saved))
            onRowSaved()
        }

        private fun playRow() {
            val engine = rowEngines.getOrNull(spEngine.selectedItemPosition)?.enginePackage ?: return
            val voiceName = currentVoiceName() ?: return
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f
            previewCallback?.invoke(engine, voiceName, volume, pitch, rate)
        }

        private fun volumeListener(label: TextView) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = "$progress%"
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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val v = seekBar.progress / 100f
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", v))
            }
        }
    }
}