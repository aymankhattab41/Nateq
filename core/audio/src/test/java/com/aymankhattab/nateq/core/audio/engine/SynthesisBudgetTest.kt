package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * العقدُ الحاكم لميزانية التخليق: **كل مهلةٍ خارجية لا تقلّ عن
 * ميزانيتها الداخلية**. هذا الاختبارُ هو ما يمنع تكرار الانقطاع
 * الجذري الذي عولج هنا: قبل v104 كانت مهلة الطلب في الخدمة أقصرَ
 * من ميزانية التخليق داخل المزوّد، فتُلغى الطلبات الطويلة في
 * منتصفها.
 */
class SynthesisBudgetTest {

    private val charCounts = listOf(
        0, 1, 29, 200, 301, 1_000, 5_000, 20_000
    )

    /** السقفُ المطلق لكل مهلة خارجية في المشروع. */
    private val absoluteCeiling = 15 * 60_000L

    @Test
    fun requestBudget_neverBelowProviderBudget() {
        for (chars in charCounts) {
            val request = NateqTtsService.synthesisTimeoutMs(chars)
            val internal = SynthesisBudget.pieceTimeoutMs(chars)
            assertTrue(
                "ميزانية الطلب ${request}ms أقلّ من ميزانية المزوّد" +
                    " ${internal}ms عند $chars حرفاً",
                request >= internal
            )
        }
    }

    @Test
    fun announcementBudget_neverBelowSumOfUnitBudgets() {
        // قائمة وحدات إعلانات: مجموع ميزانياتها لا يجوز أن يتجاوز
        // سقف الحارس، وإلا قُطع الإعلان بعد وحداته بوقتٍ طويل.
        for (chars in charCounts) {
            val units = SpeechChunker.split("ا".repeat(chars))
            val watch = SynthesisBudget.unitsTimeoutMs(
                units.map { it.length }
            )
            val sum = units.sumOf { SynthesisBudget.pieceTimeoutMs(it.length) }
            assertTrue(
                "حارس الإعلانات $watch ms أقلّ من مجموع الوحدات" +
                    " $sum ms عند $chars حرفاً",
                watch >= sum
            )
        }
    }

    @Test
    fun pieceBudget_growsWithLengthAndIsCapped() {
        assertEquals(1_500L, SynthesisBudget.pieceTimeoutMs(1))
        assertEquals(1_500L, SynthesisBudget.pieceTimeoutMs(10))
        assertEquals(3_000L, SynthesisBudget.pieceTimeoutMs(80))
        assertEquals(8_000L, SynthesisBudget.pieceTimeoutMs(300))
        assertEquals(8_030L, SynthesisBudget.pieceTimeoutMs(301))
        assertEquals(5 * 60_000L, SynthesisBudget.pieceTimeoutMs(1_000_000))
        var previous = 0L
        for (chars in listOf(1, 50, 150, 400, 900, 2_500, 9_000)) {
            val current = SynthesisBudget.pieceTimeoutMs(chars)
            assertTrue(
                "ميزانية متناقصة عند $chars حرفاً",
                current >= previous
            )
            previous = current
        }
    }

    @Test
    fun readerBudget_coversEverySegmentNotJustTotalChars() {
        // **هذا هو الحارسُ الذي كان غائباً** وينتج عنه القطع.
        //
        // الاختبارُ القديم ([requestBudget_neverBelowProviderBudget])
        // قارن الطلبَ بميزانية **قطعةٍ واحدة** بطول النص كلِّه، وهو
        // ما لا يحدث في المسار الحقيقي: النصُّ يُقطَّع إلى عدة مقاطع،
        // وكل مقطعٍ له ميزانيتُه. فالمقارنةُ الصحيحة أن يُقارَن طلبٌ
        // محسوبٌ من الأطوالِ الفعلية بمجموع ميزانياتِ القطع.
        //
        // الأرقامُ من واقع المسار: نصٌّ عربيٌّ غنيٌّ بالأرقام
        // (URL، تواريخ، مبالغ) ينقسم إلى عشرات المقاطع القصيرة،
        // فيكون مجموعُ ميزانياتها **أكبرَ بمرات** من ميزانية الحروف
        // الإجمالية. وهذا يفسّر لماذا كان القصُّ يبتدئ بعد audiou
        // مقداره ويشتدّ كلما كثُرَت الأرقام.
        val chunkCounts = listOf(
            // نص قصير: مقطع واحد.
            listOf(120),
            // نص متوسط: قطعٌ قصيرة متعددة (نصٌّ كثير الأرقام).
            List(8) { 100 },
            List(20) { 90 },
            // نص طويل: قطع متوسطة.
            List(8) { 200 },
            List(15) { 400 },
            // نص طويل جداً.
            List(40) { 300 }
        )
        for (counts in chunkCounts) {
            val total = counts.sum()
            val readerTimeout =
                NateqTtsService.synthesisTimeoutMsForSegments(counts)
            val internal = counts.sumOf {
                SynthesisBudget.pieceTimeoutMs(it)
            }
            assertTrue(
                "مهلة القارئ ${readerTimeout}ms عند ${counts.size} قطعة" +
                    " (${total} حرفاً) أقلّ من مجموع ميزانيات القطع" +
                    " ${internal}ms — القطعُ مضمون",
                readerTimeout >= internal
            )
            // **وأهمّ:** لا يجوز أن تكون مهلةُ القارئ أقلّ من الحساب
            // القديم أبداً (قد نكون أخطأنا فجعلناها أضيق).
            assertTrue(
                "مهلة القارئ ${readerTimeout}ms أضيقُ من الحساب" +
                    " القديم عند ${counts.size} قطعة",
                readerTimeout >= NateqTtsService.synthesisTimeoutMs(total)
            )
        }
    }

    /** حالةُ الانقطاع المرجعية: نصٌّ واحدٌ كبير vs ثماني قطع —
     *  يوثّق الفارق رقمياً فيبقى ظاهراً إن عاد الخلل. */
    @Test
    fun budgetGap_isVisibleForNumberDenseText() {
        val onePiece = NateqTtsService.synthesisTimeoutMs(800)
        val eightPieces = NateqTtsService.synthesisTimeoutMsForSegments(
            List(8) { 100 }
        )
        // الحسابُ القديم يظنّ أن العملَ قطعةٌ واحدة فيعطي ~25s،
        // والحقيقةُ ثماني وحداتٍ فيحتاج ~80s. الفارقُ هو العجزُ الذي
        // كان يقتل الطلب.
        assertTrue(
            "متوقّع فارقٌ جوهري، لكنه $onePiece مقابل $eightPieces",
            eightPieces > onePiece * 2
        )
    }

    @Test
    fun budgets_respectFloorAndAbsoluteCeiling() {
        // حدٌ أدنى معقولٌ لنصٍّ قصير (لا انتظار عبثيّ).
        assertTrue(NateqTtsService.synthesisTimeoutMs(0) >= 20_000L)
        // النصّ الشاذُّ يُقصّ على سقف الوحدة (5 دقائق) لا على
        // السقف المطلق (15 دقيقة) — أقصرُهما هو الحاكم.
        assertEquals(
            5 * 60_000L + 2_000L,
            NateqTtsService.synthesisTimeoutMs(Int.MAX_VALUE)
        )
        // وحارسُ الإعلانات على قائمةٍ هائلة يُقصّ على السقف المطلق.
        assertTrue(
            SynthesisBudget.unitsTimeoutMs(List(100_000) { 1_000 }) <=
                absoluteCeiling
        )
    }
}