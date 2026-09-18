package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils

/**
 * Activity لتوفير نص تجريبي لكل لغة — مطلوبة من نظام TTS
 * تستجيب لـ android.speech.tts.engine.GET_SAMPLE_TEXT
 */
class GetSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // **بند 4.1:** كانت القيمة النصية ترسل مفتاح اللغة نصياً وكان يُرجع
        // RESULT_OK (-1) الذي يعني في عقد المحركات LANG_MISSING_DATA
        // فيعطّل زر المعاينة. مفتاح اللغة «language» لا ثابتاً عاماً له
        // (KEY_PARAM_LANGUAGE مخفي @hide) فيبقى نَصياً؛ أما إخراج عيّنة
        // النص فبالثابت العام Engine.EXTRA_SAMPLE_TEXT ورمز التوافق
        // TextToSpeech.LANG_AVAILABLE.
        val lang = intent.getStringExtra("language")

        val returnData = Intent()

        // نص تجريبي مناسب لكل لغة معلنة (ar/en/fr/de/es) حسب جذر لغة
        // الطلب ("ar-EG"/"ara"/"fra"/"fr" ⇒ root واحد) — بدل النصين اللذين
        // كانا يخدمان الأساسيتين فقط. اللغات المكتشفة خارج الإعلان تسقط
        // أماناً على نص العربية الافتراضي.
        val sampleTextRes = when (LocaleUtils.languageRoot(lang ?: "")) {
            LanguageCode.EN.tag -> R.string.sample_text_activity_en
            "fr" -> R.string.sample_text_activity_fr
            "de" -> R.string.sample_text_activity_de
            "es" -> R.string.sample_text_activity_es
            else -> R.string.sample_text_activity_ar
        }

        returnData.putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            getString(sampleTextRes)
        )
        setResult(TextToSpeech.LANG_AVAILABLE, returnData)
        finish()
    }
}