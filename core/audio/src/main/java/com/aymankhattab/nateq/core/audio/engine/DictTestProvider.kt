package com.aymankhattab.nateq.core.audio.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor

/**
 * مزود تشخيص القاموس يعمل في عملية :tts لاختبار مسار القاموس الحقيقي
 * وكشف أي انقسام في الإدخالات بين العمليتين فوراً (الأمر 2).
 */
class DictTestProvider : ContentProvider() {

    companion object {
        const val METHOD_TEST = "test_dictionary"
        const val KEY_TEXT = "text"
        const val KEY_LANG = "lang"
        const val KEY_ORIGINAL = "original"
        const val KEY_PROCESSED = "processed"
        const val KEY_MAIN_COUNT = "main_count"
        const val KEY_TTS_COUNT = "tts_count"
        const val KEY_MATCHED_COUNT = "matched_count"
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        if (method != METHOD_TEST) return null
        val ctx = context ?: return null
        val text = arg ?: extras?.getString(KEY_TEXT).orEmpty()
        val lang = extras?.getString(KEY_LANG) ?: "ar"
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
