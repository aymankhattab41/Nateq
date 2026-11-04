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
 * المدخل الموحد لكل عمليات النطق في التطبيق (الجولة 53 - الأمر 4).
 * يطبق الترتيب الإلزامي:
 * 1. حارس الحرف المفرد
 * 2. القاموس أولاً
 * 3. المعالجة الدلالية
 * 4. التخطيط
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
        BATTERY_EVENT("إعلان البطارية")
    }

    fun prepare(
        rawText: String,
        source: Source,
        context: Context
    ): String {
        if (rawText.isBlank()) return rawText

        // تسجيل النص في سجل النصوص المنطوقة مع المصدر والسداسي للمحارف
        LastSpokenTextsTracker.record(rawText, source)

        // 1. حارس الحرف المفرد
        val settings = SettingsRepository(context)
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
        val normalized = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        val dictText = dict.apply(normalized)

        // 3. المعالجة الدلالية
        val processor = TextProcessor(context, settings, dict)
        val markNumbers = source == Source.SCREEN_READER
        val semanticText = processor.processSemantics(
            dictText,
            LanguageCode.AR.tag,
            markNumbers = markNumbers
        )

        // 4. التخطيط
        return if (source == Source.SCREEN_READER) {
            semanticText
        } else {
            processor.process(semanticText, applyDictionary = false)
        }
    }
}

