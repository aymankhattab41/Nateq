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
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * مسند فئات الأصوات: لكل فئة (الافتراضية/الوقت/الأرقام/الإشعارات) صوت وسرعة
 * ونبرة ومستوى صوت باسم زر اختبار نُطقه. أُخرج من الفصيل إلى مستوى الملف ليكون
 * قابلاً لإعادة الاستخدام دون احتياج العضو الداخلي الضمني.
 */
internal class CategoryVoiceAdapter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val onTestVoice: (languageTag: String, text: String) -> Unit
) : RecyclerView.Adapter<CategoryVoiceAdapter.CatVH>() {

    private val categoryList = listOf(
        SettingsRepository.VOICE_CATEGORY_DEFAULT,
        SettingsRepository.VOICE_CATEGORY_TIME,
        SettingsRepository.VOICE_CATEGORY_NUMBERS,
        SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
        SettingsRepository.VOICE_CATEGORY_EMOJI
    )

    // قائمة أسماء الأصوات واحدة لكل الفئات: تُبنى مرة واحدة بدل إعادة إنشائها
    // مع كل ربط صف، ومسندها يُسند لسبنّر كل حاملٍ عند إنشائه فقط.
    private val voiceNameAdapter =
        simpleAdapter(context, voices.map { it.displayName })

    inner class CatVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** الفئة المرتبطة حالياً بالحامل (تُحدَّث في bind). */
        var category: String = ""
        val tvCategory: TextView = itemView.findViewById(R.id.tv_category_name)
        val tvCategoryDescription: TextView =
            itemView.findViewById(R.id.tv_category_description)
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
        holder.spinnerVoice.adapter = voiceNameAdapter
        holder.spinnerVoice.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                pos: Int,
                id: Long
            ) {
                val category = holder.category
                if (category.isEmpty() || pos >= voices.size) return
                runCatching {
                    settings.setPreferredVoiceIdForCategory(
                        category, voices[pos].name
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
                val value = progress.speedFactor()
                holder.tvRateValue.text =
                    String.format(Locale.US, "%.1fx", value)
                holder.seekRate.setSeekStateDescription(
                    holder.tvRateValue.text
                )
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
                    String.format(Locale.US, "%.1fx", value)
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
                val value = progress.speedFactor()
                holder.tvPitchValue.text =
                    String.format(Locale.US, "%.1fx", value)
                holder.seekPitch.setSeekStateDescription(
                    holder.tvPitchValue.text
                )
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
                    String.format(Locale.US, "%.1fx", value)
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
                holder.tvVolumeValue.text = "$progress%"
                holder.seekVolume.setSeekStateDescription(
                    holder.tvVolumeValue.text
                )
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
            val voice = voices[holder.spinnerVoice.selectedItemPosition]
            val isArabic = !LanguageCode.isEnglish(voice.languageTag)
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
            onTestVoice(voice.languageTag, text)
        }
        return holder
    }

    override fun onBindViewHolder(holder: CatVH, position: Int) {
        val category = categoryList[position]
        holder.category = category
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

        val saved =
            runCatching { settings.getPreferredVoiceIdForCategory(category) }
                .getOrNull()
        val idx = voices.indexOfFirst { it.name == saved }
        holder.spinnerVoice.setSelection(if (idx >= 0) idx else 0)

        val rate = runCatching { settings.getSpeechRateForCategory(category) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        holder.tvRateValue.text = String.format(Locale.US, "%.1fx", rate)
        holder.seekRate.progress = (rate * 100).toInt().coerceIn(0, 200)

        val pitch = runCatching { settings.getPitchForCategory(category) }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        holder.tvPitchValue.text = String.format(Locale.US, "%.1fx", pitch)
        holder.seekPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)

        val volume = runCatching { settings.getVolumeForCategory(category) }
            .getOrDefault(1.0f)
        holder.tvVolumeValue.text = "${(volume * 100).toInt()}%"
        holder.seekVolume.progress = (volume * 100).toInt().coerceIn(0, 100)
    }

    override fun getItemCount(): Int = categoryList.size
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
