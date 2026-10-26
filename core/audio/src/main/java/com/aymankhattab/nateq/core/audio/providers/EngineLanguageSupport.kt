package com.aymankhattab.nateq.core.audio.providers

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * سجلّ مؤقت في الذاكرة لتتبع اللغات غير المدعومة لكل محرك TTS (الجولة 56).
 *
 * عندما يُرجع محرك خارجي (مثل سامسونج) النتيجة LANG_NOT_SUPPORTED أو
 * LANG_MISSING_DATA للغة معينة (مثل العربية "ar")، تُسجَّل هنا حتى لا يُعاد
 * اختياره لتلك اللغة في نفس الجلسة ولا يحدث تذبذب وإسقاط للمقاطع.
 */
object EngineLanguageSupport {

    private val unsupported =
        ConcurrentHashMap<String, MutableSet<String>>()

    /** يُسجّل أن المحرك [engine] لا يدعم اللغة [lang]. */
    fun recordUnsupported(engine: String, lang: String) {
        val normLang = normalizeTag(lang)
        if (normLang.isEmpty()) return
        unsupported.computeIfAbsent(engine) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(normLang)
    }

    /** يتحقق هل اللغة [lang] مدعومة لدى المحرك [engine]. */
    fun isLanguageSupported(engine: String, lang: String): Boolean {
        val normLang = normalizeTag(lang)
        if (normLang.isEmpty()) return true
        val set = unsupported[engine] ?: return true
        return !set.contains(normLang)
    }

    /** يُعيد قائمة اللغات غير المدعومة لمحرك معين (للفحص والاختبار). */
    fun unsupportedLanguagesFor(engine: String): Set<String> {
        return unsupported[engine]?.toSet() ?: emptySet()
    }

    /** تفريغ السجل (للاختبارات وإعادة التعيين). */
    fun clear() {
        unsupported.clear()
    }

    private fun normalizeTag(tag: String): String =
        tag.trim().lowercase().take(2)
}
