package com.aymankhattab.nateq.core.audio.accessibility

/**
 * متتبّع صفحات القراءة المستمرة (ع٤١-ب6).
 *
 * - عند بدء القراءة يُحفظ «ذيل» الصفحة الأولى (آخر [tailSize] أسطر).
 * - عند كل تمرير تُقارن آخرُ أسطر الصفحة الجديدة بذيل الصفحة السابقة:
 *   إن تطابقت فالمحتوى لم يتغيّر ⇒ بلغنا النهاية.
 * - وإلا تُستخرج النصوص الجديدة فقط (تخطّي التداخل في رأس الصفحة) فلا
 *   يُعاد ما سُمع.
 *
 * منطق نقيّ بلا حالة أندرويد — قابل للاختبار مباشرة.
 */
class ContinuousPageTracker(private val tailSize: Int = DEFAULT_TAIL_SIZE) {

    data class PageResult(
        val isEnd: Boolean,
        val freshTexts: List<String>
    )

    private var tail: List<String> = emptyList()

    /** يسجّل الصفحة الأولى ويعيدها كلّها للقراءة. */
    fun firstPage(texts: List<String>): List<String> {
        tail = texts.takeLast(tailSize)
        return texts
    }

    /** يقارن صفحةً جديدة بالذيل المحفوظ ويحسم النهاية أو النصوص الجديدة. */
    fun nextPage(texts: List<String>): PageResult {
        if (texts.isEmpty()) {
            return PageResult(isEnd = true, freshTexts = emptyList())
        }
        val newTail = texts.takeLast(tailSize)
        if (newTail == tail) {
            return PageResult(isEnd = true, freshTexts = emptyList())
        }
        val fresh = texts.drop(overlapWithTail(texts))
        tail = newTail
        return PageResult(isEnd = false, freshTexts = fresh)
    }

    /** أطول تداخل بين رأس الصفحة الجديدة وذيل الصفحة السابقة. */
    private fun overlapWithTail(texts: List<String>): Int {
        val max = minOf(tail.size, texts.size)
        for (k in max downTo 1) {
            if (texts.take(k) == tail.takeLast(k)) return k
        }
        return 0
    }

    companion object {
        const val DEFAULT_TAIL_SIZE = 5
    }
}
