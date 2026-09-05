package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Activity لفحص بيانات TTS — مطلوبة من نظام أندرويد (وخاصة سامسونج)
 * لتحديد اللغات والأصوات المثبتة المتاحة في المحرك.
 * تستجيب لـ android.speech.tts.engine.CHECK_TTS_DATA
 */
class CheckTtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val returnData = Intent()

        // الأصوات المدعومة (أسماء Voices كما في onGetVoices)
        // أسماء قياسية BCP-47 ("ar", "en") تطابق onGetVoices تماماً.
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            arrayListOf("ar", "en")
        )

        // لا توجد أصوات غير متاحة (الكل متاح)
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            arrayListOf()
        )

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }
}