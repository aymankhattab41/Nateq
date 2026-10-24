package com.aymankhattab.nateq.core.audio.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.core.audio.announcement.ArabicSpeechNormalizer
import com.aymankhattab.nateq.engine.TextProcessor

/**
 * مزود تشخيص القاموس يعمل في عملية :tts لاختبار مسار القاموس الحقيقي
 * وكشف أي انقسام في الإدخالات بين العمليتين فوراً (الأمر 2).
 */
class DictTestProvider : ContentProvider() {

    companion object {
        const val METHOD_TEST = "test_dictionary"
        const val METHOD_TRACE = "trace_dictionary"
        const val KEY_TEXT = "text"
        const val KEY_LANG = "lang"
        const val KEY_ORIGINAL = "original"
        const val KEY_PROCESSED = "processed"
        const val KEY_MAIN_COUNT = "main_count"
        const val KEY_TTS_COUNT = "tts_count"
        const val KEY_MATCHED_COUNT = "matched_count"
        const val KEY_STANDALONE_MAX = "standalone_max"
        const val KEY_TRACE_LINES = "trace_lines"
        const val METHOD_FULL_TRACE = "full_pipeline_trace"
        const val METHOD_GET_LAST_SPOKEN = "get_last_spoken"
        const val KEY_FULL_TRACE_NAMES = "full_trace_names"
        const val KEY_FULL_TRACE_VALUES = "full_trace_values"
        const val KEY_LAST_SPOKEN_LIST = "last_spoken_list"
        const val KEY_LAST_SPOKEN_DISPLAYS = "last_spoken_displays"
        const val KEY_LAST_SPOKEN_SOURCES = "last_spoken_sources"
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val ctx = context ?: return null
        val text = arg ?: extras?.getString(KEY_TEXT).orEmpty()
        val lang = extras?.getString(KEY_LANG) ?: "ar"

        if (method == METHOD_TEST) {
            val mainCount = extras?.getInt(KEY_MAIN_COUNT) ?: -1
            val dict = PronunciationDictionary.shared(ctx)
            dict.reloadIfChanged()
            val ttsCount = dict.getAllEntries().size

            val processor = TextProcessor(ctx, injectedDict = dict)
            val processed = processor.applyDictionaryFirst(text, lang)
            val matched = if (processed != text) 1 else 0

            return Bundle().apply {
                putString(KEY_ORIGINAL, text)
                putString(KEY_PROCESSED, processed)
                putInt(KEY_MAIN_COUNT, mainCount)
                putInt(KEY_TTS_COUNT, ttsCount)
                putInt(KEY_MATCHED_COUNT, matched)
            }
        }

        if (method == METHOD_TRACE) {
            val dict = PronunciationDictionary.shared(ctx)
            dict.reloadIfChanged()
            val standaloneMax = dict.getStandaloneMaxLength()
            val processor = TextProcessor(ctx, injectedDict = dict)
            val processed = processor.applyDictionaryFirst(text, lang)
            val traces = dict.traceMatches(text, lang)

            val traceLines = ArrayList<String>()
            for (t in traces) {
                val status = if (t.accepted) "مقبول ✓" else "مرفوض ✗"
                traceLines.add(
                    "المفتاح: «${t.key}» ← «${t.value}» | " +
                        "الوضع: ${t.matchMode} | القرار: $status (${t.reason})"
                )
            }

            return Bundle().apply {
                putString(KEY_ORIGINAL, text)
                putString(KEY_PROCESSED, processed)
                putInt(KEY_STANDALONE_MAX, standaloneMax)
                putStringArrayList(KEY_TRACE_LINES, traceLines)
            }
        }

        if (method == METHOD_FULL_TRACE) {
            val processor = TextProcessor(ctx)
            val traces = processor.processWithTrace(text, lang)
            val names = ArrayList<String>()
            val values = ArrayList<String>()
            var lastText = text
            for ((k, v) in traces) {
                names.add(k)
                values.add(v)
                lastText = v
            }
            // مرحلة 6 و7 في مسار :tts
            val forEngine = ArabicSpeechNormalizer.prepareArabicForEngine(
                text = lastText,
                languageTag = lang
            )
            names.add("6. بعد تجهيز النص للمحرك (prepareArabicForEngine)")
            values.add(forEngine)
            names.add("7. النص النهائي المُرسل للمحرك")
            values.add(forEngine)

            return Bundle().apply {
                putStringArrayList(KEY_FULL_TRACE_NAMES, names)
                putStringArrayList(KEY_FULL_TRACE_VALUES, values)
            }
        }

        if (method == METHOD_GET_LAST_SPOKEN) {
            val entries = LastSpokenTextsTracker.getRecentEntries()
            val rawList = ArrayList(entries.map { it.rawText })
            val displays = ArrayList(entries.map { it.formatDisplay() })
            val sources = ArrayList(entries.map { it.source })
            return Bundle().apply {
                putStringArrayList(KEY_LAST_SPOKEN_LIST, rawList)
                putStringArrayList(KEY_LAST_SPOKEN_DISPLAYS, displays)
                putStringArrayList(KEY_LAST_SPOKEN_SOURCES, sources)
            }
        }

        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
