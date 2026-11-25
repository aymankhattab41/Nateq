package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import com.aymankhattab.nateq.core.audio.accessibility.SpellTable
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.engine.SingleLetterGuard
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.util.LanguageCode
import java.text.Normalizer

/**
 * المدخل الموحد لكل عمليات النطق في التطبيق.
 * يطبق الترتيب الإلزامي:
 * 1. حارس الحرف المفرد
 * 2. القاموس أولاً
 * 3. المعالجة الدلالية بسياق لغة الطلب/المقطع
 * 4. التخطيط بسياق اللغة المطابق
 */
object SpeechEntryPoints {

    enum class Source(val labelAr: String) {
        SCREEN_READER("قارئ الشاشة"),
        CHAT_LIVE("الرسائل الحية"),
        NOTIFICATION("إشعار"),
        CALLER("المتصل"),
        REPEAT_LAST("إعادة آخر نطق"),
        SPELL_LAST("هجاء آخر نطق"),
        KEY_SHORTCUT("اختصار لوحة المفاتيح"),
        LORD_SYSTEM("نظام لورد"),
        TIME_EVENT("إعلان الوقت"),
        BATTERY_EVENT("إعلان البطارية"),
        FULL_URL("قراءة الرابط كاملاً")
    }

    fun prepare(
        rawText: String,
        source: Source,
        context: Context,
        requestLanguage: String? = null,
        bypassUrlShortening: Boolean = (source == Source.FULL_URL)
    ): String {
        if (rawText.isBlank()) return rawText

        // تسجيل النص في سجل النصوص المنطوقة
        LastSpokenTextsTracker.record(rawText, source)

        // 1. حارس الحرف المفرد
        val settings = SettingsRepository.create(context)
        val mode = runCatching { settings.getSingleLetterMode() }
            .getOrNull() ?: "name"
        if (source != Source.SPELL_LAST) {
            val guarded = SingleLetterGuard.resolve(rawText, mode)
            if (guarded != null) {
                return guarded
            }
        } else {
            val trimmed = rawText.trim()
            if (trimmed.length == 1) {
                return SpellTable.nameOf(trimmed[0])
            }
            return rawText
        }

        // 2. القاموس أولاً
        val dict = PronunciationDictionary.shared(context)
        dict.reloadIfChanged()
        val cleanedRaw = TextProcessor.stripCommasBetweenDigits(rawText)
        val normalized = Normalizer.normalize(cleanedRaw, Normalizer.Form.NFC)
        val dictText = dict.apply(normalized)

        // 3. المعالجة الدلالية
        val processor = TextProcessor(context, settings, dict)
        val markNumbers = source == Source.SCREEN_READER
        val resolvedLang = resolveLanguage(dictText, requestLanguage, settings)
        val semanticText = processor.processSemantics(
            dictText,
            resolvedLang,
            markNumbers = markNumbers,
            bypassUrlShortening = bypassUrlShortening
        )

        // 4. التخطيط
        return if (source == Source.SCREEN_READER) {
            semanticText
        } else {
            processor.process(
                semanticText,
                languageTag = resolvedLang,
                applyDictionary = false,
                bypassUrlShortening = bypassUrlShortening
            )
        }
    }

    /**
     * استنتاج لغة المقطع/الطلب لتفادي فرض العربية على النصوص الإنجليزية
     * مع احترام لغة نطق الأرقام المستقلة ولغة التطبيق والقاموس.
     */
    private fun resolveLanguage(
        text: String,
        requestLanguage: String?,
        settings: SettingsRepository
    ): String {
        if (!requestLanguage.isNullOrBlank()) {
            return requestLanguage
        }

        var hasArabic = false
        var hasLatin = false
        var hasDigits = false

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isDigit(cp)) {
                hasDigits = true
            } else if (Character.isLetter(cp)) {
                val block = Character.UnicodeBlock.of(cp)
                if (block == Character.UnicodeBlock.ARABIC ||
                    block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
                    block == Character.UnicodeBlock.ARABIC_EXTENDED_A ||
                    block ==
                    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                    block ==
                    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
                ) {
                    hasArabic = true
                    break
                } else {
                    hasLatin = true
                }
            }
        }

        if (hasArabic) {
            return LanguageCode.AR.tag
        }
        if (hasLatin) {
            return LanguageCode.EN.tag
        }
        if (hasDigits) {
            val numLang = runCatching {
                settings.getNumberReadingLanguage()
            }.getOrNull()
            if (numLang == LanguageCode.EN.tag) {
                return LanguageCode.EN.tag
            }
        }
        return LanguageCode.AR.tag
    }
}
