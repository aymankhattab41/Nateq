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

        // الأصوات المدعومة (أسماء Voices كما في onGetVoices).
        // يجب أن تطابق أسماء الـ Voice المُعلنة في tts_engine.xml/onGetVoices
        // ("ar-local"/"en-local") بالضبط وإلا تختفي الأصوات أو يضيع voiceId
        // المختار في شاشة إعدادات TTS على سامسونج (آلية CHECK_TTS_DATA).
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            arrayListOf("ar-local", "en-local")
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