package com.aymankhattab.nateq.core.audio.accessibility

import android.graphics.Rect

/**
 * ترتيب نصوص الشاشة بصرياً حسب موضعها (ع٤١-ب6، ب7).
 *
 * - يُرتَّب من أعلى الشاشة إلى أسفلها بمقارنة مركز العنصر رأسياً.
 * - عند تساوي الصفّ يُرتَّب حسب اتجاه الكتابة: من اليمين لليسار في
 *   الكتابات العربية/العبرية، ومن اليسار لليمين في غيرها.
 * - تُدمج النصوص المتطابقة المتتالية (العنصر وأبوه غالباً يحملان النصّ
 *   نفسه) فلا يُسمع النصّ مرّتين.
 *
 * منطق نقيّ بلا حالة — قابل للاختبار مباشرة.
 */
object ScreenTextOrder {

    fun orderedTexts(views: List<NodeView>, rtl: Boolean): List<String> {
        val entries = views.mapNotNull { view ->
            val text = view.readableText().trim()
            if (text.isEmpty()) null else text to view.boundsInScreen
        }
        return dedupeConsecutive(orderEntries(entries, rtl).map { it.first })
    }

    private fun orderEntries(
        entries: List<Pair<String, Rect>>,
        rtl: Boolean
    ): List<Pair<String, Rect>> {
        val comparator = if (rtl) {
            compareBy<Pair<String, Rect>> { it.second.centerY() }
                .thenByDescending { it.second.right }
        } else {
            compareBy<Pair<String, Rect>> { it.second.centerY() }
                .thenBy { it.second.left }
        }
        return entries.sortedWith(comparator)
    }

    /** يدمج المتطابقات المتتالية، فلا يتكرّر النصّ المتصل. */
    fun dedupeConsecutive(texts: List<String>): List<String> {
        val out = ArrayList<String>(texts.size)
        for (text in texts) {
            if (out.lastOrNull() != text) out.add(text)
        }
        return out
    }
}
