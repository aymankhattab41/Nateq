package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ «عدم الإسقاط الصامت» — علةُ «النصّ لا يكتمل» المُبلَّغة بعد
 * 1.6.1: مقطعٌ يتعثّر فيه محرّكُه كان يُسقَط بصمتٍ تامّ (كلماتٌ
 * تختفي بلا صوتٍ ولا خطأ)، فبدا النصُّ «متحسّناً» لكنه ناقص.
 *
 * العقدُ المُختبَر هنا:
 *  1. نصٌّ دون العتبة **لا يُقسَّم** (طلبٌ واحدٌ متّصل كما كان).
 *  2. نصٌّ فوق العتبة يُقسَّم، وكل جزءٍ **ضمن العتبة** فلا تتباعد
 *     الأجزاء بلا نهاية.
 *  3. القطعُ يقع على **حدٍّ لغوي** فلا تُبتَر كلمةٌ في منتصفها.
 *  4. **فقدانُ لا شيء:** بضمّ الأجزاء يُستعاد النصُّ الأصلي كاملاً
 *     (بعدّ الحروف والمسافات الهامشية) — فلا تُنسى كلمة.
 */
class SegmentFailureSplitTest {

    @Test
    fun shortText_notSplit_keptAsSingleRequest() {
        val text = "قصير جداً لا يحتاج تقسيماً"
        assertEquals(
            listOf(text),
            splitOnFailureBoundary(text)
        )
    }

    @Test
    fun textAtThreshold_notSplit() {
        val text = "ا".repeat(SEGMENT_SPLIT_FALLBACK_CHARS)
        assertEquals(1, splitOnFailureBoundary(text).size)
    }

    @Test
    fun longText_splitOnSentenceBoundaries_only() {
        // 200 جملة من 20 حرفاً = 4000 حرف، كلُّها تنتهي بنقطة.
        val sentence = "ا".repeat(19) + "."
        val text = sentence.repeat(200)
        val parts = splitOnFailureBoundary(text)
        assertTrue(
            "يجب أن يُقسَّم النصُّ الطويل، أجزاء=${
                parts.size
            }",
            parts.size > 1
        )
        parts.forEach { part ->
            assertTrue(
                "كل جزءٍ يجب أن يكون ضمن العتبة كان ${
                    part.length
                }",
                part.length <= SEGMENT_SPLIT_FALLBACK_CHARS
            )
        }
    }

    @Test
    fun splitCuts_atPunctuation_notMidWord() {
        // حدٌّ لغويٌّ واضحٌ داخل العتبة: القطعُ يجب أن يقع عليه لا في
        // منتصف كلمة. والعتبةُ حدُّ ميزانيةٍ لا يُتجاوز، فالبحثُ عنها
        // **عكسيّ**: أطولُ جزءٍ ضمن 1500 ينتهي على الفاصلة.
        val head = "ا".repeat(1_400)
        val text = head + "،" + "ب".repeat(SEGMENT_SPLIT_FALLBACK_CHARS)
        val parts = splitOnFailureBoundary(text)
        assertTrue("يجب أن يُقسَّم: ${parts.size}", parts.size > 1)
        assertEquals('،', parts[0].last())
        parts.forEach {
            assertTrue(
                "كل جزءٍ ضمن العتبة: ${it.length}",
                it.length <= SEGMENT_SPLIT_FALLBACK_CHARS
            )
        }
    }

    /** النصُّ فوق 3000 حرف كان **الأخطر**: فمنتصفُه يتجاوز العتبة
     *  فيصير نطاقُ البحث الأماميُ [midpoint, limit] فارغاً، فيُقطعَ
     *  عند العتبةِ في منتصف كلمةٍ بلا بحثٍ عن حدٍّ أصلاً. هذا الحارسُ
     *  يمسك الإصلاح: القطعُ على حدٍّ لغويٍّ لا على الحدِّ الثابت. */
    @Test
    fun longText_over3000Chars_stillCutsOnRealBoundary() {
        val text = "سطر. ".repeat(1_000)
        assertTrue("اختبارُ غير صالح: ${text.length}", text.length > 3_000)
        val parts = splitOnFailureBoundary(text)
        assertTrue("يجب أن يُقسَّم: ${parts.size}", parts.size > 1)
        parts.forEach {
            assertTrue(
                "جزءٌ تجاوز العتبة: ${it.length}",
                it.length <= SEGMENT_SPLIT_FALLBACK_CHARS
            )
        }
        // آخرُ جزءٍ قد يكون نُذِرَ بلا حدٍّ؛ ما قبله فلا بدّ أن يكون
        // على حدٍّ — وهذا ما ينهار لولا الإصلاح.
        parts.dropLast(1).forEach {
            assertTrue(
                "جزءٌ مقطوعٌ في منتصف كلمة: '${it.takeLast(16)}'",
                it.isEmpty() || it.last() in SEGMENT_BOUNDARIES ||
                    it.last() == ' '
            )
        }
    }

    @Test
    fun noBoundaryText_stillSplitsAtLimit() {
        // نصٌّ عملاقٌ بلا أي حدٍّ لغوي — يجب ألا يبقى قطعةً واحدة
        // عملاقة (وإلا تعثّر ونُسقط صامتاً كسابقه).
        val text = "ا".repeat(SEGMENT_SPLIT_FALLBACK_CHARS * 3)
        val parts = splitOnFailureBoundary(text)
        assertEquals(3, parts.size)
    }

    @Test
    fun nothingIsLost_concatenationRestoresText() {
        val sentence = "كلمةٌ طويلة هنا. "
        val text = sentence.repeat(150)
        val parts = splitOnFailureBoundary(text)
        assertTrue(parts.size > 1)
        // الأجزاءُ مفصولةٌ بمسافاتٍ هامشية فقط: بضمّها (بلا مسافةٍ زائدة)
        // يُستعاد النصُّ الأصلي.
        val rejoined = parts.joinToString(" ") { it }
            .replace(Regex("\\s+"), " ").trim()
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), rejoined)
    }
}