package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.engine.pipeline.NumberWordsConverter

/**
 * مقطع نطق واحد: نص عادي يُنطق بإعدادات الإعلان نفسه، أو اسم إيموجي يُنطق
 * بإعدادات فئة «نطق الإيموجي» (صوت/سرعة/نبرة/مستوى صوت) المستقلة.
 */
data class SpeechPart(val text: String, val isEmojiName: Boolean)

/**
 * يقسم نصاً إلى مقاطع متناوبة (نص / اسم إيموجي) بنفس تمييز [TextProcessor]
 * وتسمية [EmojiNames]، حتى يُنطق كل اسم على حدة. مستقل عن مسار المحرك
 * (يُستخدمه متحدث الإعلانات قبل إرسال النص لمحرك خارجي).
 */
object EmojiSpeech {

    /** يقسم النص إلى مقاطع حسب لغة تسمية الأسماء (عربية/إنجليزية). */
    fun split(raw: String, arabic: Boolean): List<SpeechPart> {
        if (raw.isEmpty()) return emptyList()
        val base = EmojiNames.applyAsciiEmoticons(raw, arabic)
        val fallback = if (arabic) EmojiNames.AR_FALLBACK
        else EmojiNames.EN_FALLBACK
        val parts = mutableListOf<SpeechPart>()
        val pending = StringBuilder()
        var i = 0
        val len = base.length
        // تجميع الإيموجي المتكرر المتطابق المتلاصق (مثل 😂😂😂) في اسمٍ واحدٍ
        // مسبوقٍ بالعدد («ثلاثة وجه يضحك بدموع») بدل تكرار الاسم نفسه خمس وسِت
        // مرات متتالية مزعجة. يُدمج التكرار المتجاور فقط، وأي حرفٍ نصٍّ يفصلها.
        var groupIdent: String? = null
        var groupName = ""
        var groupCount = 0

        fun flushGroup() {
            if (groupIdent != null) {
                val text = if (groupCount > 1) {
                    countWord(groupCount, arabic) + " " + groupName
                } else {
                    groupName
                }
                parts.add(SpeechPart(text, true))
                groupIdent = null
                groupName = ""
                groupCount = 0
            }
        }

        fun flushText() {
            if (pending.isNotEmpty()) {
                parts.add(SpeechPart(pending.toString(), false))
                pending.setLength(0)
            }
        }

        /** يضمّ إيموجيًّا (باسمه وهويته الكاملة) لمجموعة التكرار،
         *  أو يفتتح مجموعةً جديدة. */
        fun absorb(name: String, ident: String) {
            if (groupIdent == ident) {
                groupCount++
            } else {
                flushGroup()
                flushText()
                groupIdent = ident
                groupName = name
                groupCount = 1
            }
        }

        while (i < len) {
            val cp = base.codePointAt(i)
            val chars = Character.charCount(cp)
            val startOfUnit = i
            when {
                EmojiNames.isEmojiModifier(cp) -> {
                    // تعديلات منفصلة (ZWJ/ألوان بشرة/مؤشر أشكال) تُسقط بصمت
                    i += chars
                }
                EmojiNames.isRegionalIndicator(cp) -> {
                    val nextIdx = i + chars
                    if (nextIdx < len) {
                        val next = base.codePointAt(nextIdx)
                        if (EmojiNames.isRegionalIndicator(next)) {
                            val ident = base.substring(
                                startOfUnit, nextIdx + Character.charCount(next)
                            )
                            absorb(
                                EmojiNames.flagReadingName(
                                    EmojiNames.buildCountryCode(cp, next),
                                    arabic
                                ),
                                ident
                            )
                            i = nextIdx + Character.charCount(next)
                            continue
                        }
                    }
                    // علم غير مكتمل (رمز واحد بلا قرين): نطق عام
                    absorb(
                        fallback,
                        base.substring(startOfUnit, startOfUnit + chars)
                    )
                    i += chars
                }
                EmojiNames.isEmojiBlockCp(cp) -> {
                    val name = if (arabic) EmojiNames.arName(cp)
                    else EmojiNames.enName(cp)
                    i += chars
                    // تجاوز بقية المجموعة: ألوان بشرة، مؤشرات أشكال، وعناصر
                    // ما بعد ZWJ (عائلة/مهنة) حتى لا تُنطق مقاطع متناثرة
                    var zwjSeen = false
                    while (i < len) {
                        val c2 = base.codePointAt(i)
                        val c2chars = Character.charCount(c2)
                        when {
                            c2 in 0x1F3FB..0x1F3FF ||
                            c2 in 0xFE0E..0xFE0F -> i += c2chars
                            c2 == 0x200D -> {
                                zwjSeen = true; i += c2chars
                            }
                            EmojiNames.isEmojiBlockCp(c2) && zwjSeen -> {
                                i += c2chars; zwjSeen = false
                            }
                            else -> break
                        }
                    }
                    absorb(name ?: fallback, base.substring(startOfUnit, i))
                }
                else -> {
                    flushGroup()
                    pending.append(base, i, i + chars)
                    i += chars
                }
            }
        }
        flushGroup()
        flushText()
        return parts
    }

    /** عدد التكرار ككلمةٍ منطوقة بلغة تسمية الأسماء (ثلاثة/three). */
    private fun countWord(count: Int, arabic: Boolean): String =
        if (arabic) NumberWordsConverter.numberToWords(count)
        else NumberSpeech.toEnglishWords(count)
}