package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.FrancoArabic

/** كشف الفرانكو-آراب (بند الأوامر د.3.6): كلماتٌ لاتينية بأرقام بديلة
 *  تُحوَّل عربية في بداية المسار العربي — متحفِّظٌ لا يمس الإنجليزية. */
internal class FrancoArabicStep : TextProcessingStep {

    override fun apply(input: String): String = FrancoArabic.convert(input)
}