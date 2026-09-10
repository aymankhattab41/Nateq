package com.aymankhattab.nateq

import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.engine.pipeline.CleanupStep
import com.aymankhattab.nateq.engine.pipeline.PunctuationStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات خطوة نطق علامات الترقيم وفق مستوياتها الثلاثة. */
class PunctuationStepTest {

    @Test
    fun none_level_returnsInputUnchanged() {
        val step = PunctuationStep { PunctuationLevels.NONE }
        assertEquals("خمسون%", step.apply("خمسون%"))
        assertEquals("نلتقي @ 5", step.apply("نلتقي @ 5"))
        assertEquals("بين قوسين (أمر)", step.apply("بين قوسين (أمر)"))
        assertEquals("دلالة ؛ فاصلة", step.apply("دلالة ؛ فاصلة"))
    }

    @Test
    fun some_level_basicSymbolsNamed() {
        val step = PunctuationStep { PunctuationLevels.SOME }
        assertEquals("خمسون بالمئة", CleanupStep.apply(step.apply("خمسون%")))
        assertEquals("رقم 5", CleanupStep.apply(step.apply("#5")))
        assertEquals("أحمد و خالد", CleanupStep.apply(step.apply("أحمد&خالد")))
        assertEquals("نلتقي عند 5", CleanupStep.apply(step.apply("نلتقي @ 5")))
        // البريد الإلكتروني محمي: @ لا تُنطق داخله
        assertEquals(
            "مراسلتي a@b.com",
            CleanupStep.apply(step.apply("مراسلتي a@b.com"))
        )
        // الأقواس والنقاط المنقوطة ليست في مستوى «البعض»
        assertEquals(
            "بين قوسين (أمر)",
            CleanupStep.apply(step.apply("بين قوسين (أمر)"))
        )
    }

    @Test
    fun some_level_isolatedArithmeticWordsNamed_betweenDigitsKept() {
        val step = PunctuationStep { PunctuationLevels.SOME }
        // + و / و = المعزولة عن الأرقام تُنطق أسماءً
        assertEquals("a زائد b", CleanupStep.apply(step.apply("a+b")))
        assertEquals("10 و 2", CleanupStep.apply(step.apply("10&2")))
        // بين رقمين: من مسؤولية SymbolStep (لا تُنطق هنا شرطة مائلة/زائد)
        assertEquals("5/2", CleanupStep.apply(step.apply("5/2")))
        assertEquals("5+3", CleanupStep.apply(step.apply("5+3")))
    }

    @Test
    fun all_level_addsBracketsSemicolonsDashesEllipsis() {
        val step = PunctuationStep { PunctuationLevels.ALL }
        assertEquals(
            "خمسون بالمئة",
            CleanupStep.apply(step.apply("خمسون%"))
        )
        assertEquals(
            "قوس افتتاح أمر قوس إقفال",
            CleanupStep.apply(step.apply("(أمر)"))
        )
        assertEquals(
            "قوس مربع افتتاح تنبيه قوس مربع إقفال",
            CleanupStep.apply(step.apply("[تنبيه]"))
        )
        assertEquals(
            "فاصلة منقوطة",
            CleanupStep.apply(step.apply("؛"))
        )
        assertEquals(
            "جملة شرطة تفصيل",
            CleanupStep.apply(step.apply("جملة — تفصيل"))
        )
        assertEquals(
            "نقاط",
            CleanupStep.apply(step.apply("…"))
        )
        // «البعض» الأساسية ما زالت تعمل في «الكل» أيضاً
        assertEquals("رقم 7", CleanupStep.apply(step.apply("#7")))
    }
}