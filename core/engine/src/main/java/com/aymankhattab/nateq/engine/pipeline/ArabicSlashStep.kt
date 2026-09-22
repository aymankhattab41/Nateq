package com.aymankhattab.nateq.engine.pipeline

/**
 * معالجة علامة الشرطة المائلة «/» في الجمل العربية:
 * تُستبدل بمسافة حتى لا تُنطق إطلاقاً (سواء كـ «على» بين الأرقام مثل نتائج
 * المباريات «خسر 1/0» التي تتحول إلى «خسر 1 0» ثم تُنطق «خسر واحد صفر»،
 * أو كـ «شرطة مائلة» بين الكلمات مثل «نعم/لا»).
 *
 * لا تؤثر على الروابط لأن الروابط تُحجب مسبقاً عبر [maskUrls]، ولا على
 * التواريخ لأن [DateStep] يسبق هذه الخطوة في خط المعالجة.
 * كما لا تؤثر على اللغة الإنجليزية (تُعاد كما هي في [applyEnglish]).
 */
internal object ArabicSlashStep : TextProcessingStep {

    override fun apply(input: String): String {
        if (!input.contains('/')) return input
        return input.replace('/', ' ')
    }

    override fun applyEnglish(input: String): String = input
}
