package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode

/**
 * Activity لتوفير نص تجريبي لكل لغة — مطلوبة من نظام TTS
 * تستجيب لـ android.speech.tts.engine.GET_SAMPLE_TEXT
 */
class GetSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // المفاتيح النصية للـ Intent extras (قيم TextToSpeech.EXTRA_*
        // غير متاحة في هذا API)
        val lang = intent.getStringExtra("language")

        val returnData = Intent()

        // نص تجريبي مناسب لكل لغة (يُرسل النظام رمزاً مثل "ar" أو "ar-EG")
        val sampleText = if (lang?.let { LanguageCode.isEnglish(it) } == true) {
            getString(R.string.sample_text_activity_en)
        } else {
            getString(R.string.sample_text_activity_ar)
        }

        returnData.putExtra("sample", sampleText)
        setResult(RESULT_OK, returnData)
        finish()
    }
}