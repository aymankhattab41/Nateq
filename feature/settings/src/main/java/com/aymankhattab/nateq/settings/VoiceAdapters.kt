package com.aymankhattab.nateq.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
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