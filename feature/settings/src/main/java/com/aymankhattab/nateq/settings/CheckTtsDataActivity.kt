package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.util.LocaleUtils

/**
 * Activity لفحص بيانات TTS — مطلوبة من نظام أندرويد (وخاصة سامسونج)
 * لتحديد اللغات والأصوات المثبتة المتاحة في المحرك.
 * تستجيب لـ android.speech.tts.engine.CHECK_TTS_DATA
 */
class CheckTtsDataActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // اللغات المطلوب فحصها صراحةً من النظام (EXTRA_CHECK_VOICE_DATA_FOR —
        // ثابتٌ مُهمَل في المنصة بلا بديلٍ له: هو مفتاح العقد نفسه الذي يُرسله
        // النظام في CHECK_TTS_DATA فيبقى مروراً كما هو) — إن غاب المفتاح
        // نفحص كل الأصوات المعلنة.
        val requested = intent.getStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR
        ).orEmpty()

        // أصوات اللغات المعلنة في tts_engine.xml — نفس المصدر الذي يقرأ منه
        // onGetVoices (عبر supportedVoices) فيتطابق الجوابان حرفاً بحرف بدل
        // قائمة ar/en الثابتة القديمة التي كانت تتعارض مع الملف.
        val declared = VoiceCatalog.declaredVoices()

        // القائمة المثبتة فعليةً: تمرر عبر filterVoicesWithInstalledData
        // (المعيار نفسه في الاكتشاف). كل أصواتنا مدمجة محلياً (embedded/
        // offline) فلا يُستبعد صوت الالتزام بالمعايير، لكن إن استُبعد صوت
        // يوماً (أصبح يتطلب شبكة مثلاً) وُضع في EXTRA_UNAVAILABLE_VOICES
        // تلقائياً بدل تركها فارغة دوماً.
        val availableVoices = VoiceCatalog.filterVoicesWithInstalledData(
            declared
        ) {
            TextToSpeech.LANG_AVAILABLE
        }
        val availableNames = availableVoices.map { it.name }
        val unavailableNames = declared
            .map { it.name }
            .filterNot { it in availableNames }

        // تحديد النتيجة للغات المطلوبة إن وُجدت — بمطابقة جذر اللغة
        // ("ar-EG"/"ara"/"fr" ⇒ نفس صوت الإعلان) لا النص الحرفي.
        val select: (List<String>) -> List<String> =
            if (requested.isEmpty()) {
                { it }
            } else {
                { names -> names.filter { name ->
                    requested.any { req ->
                        LocaleUtils.languageRoot(name) ==
                            LocaleUtils.languageRoot(req)
                    }
                } }
            }

        val returnData = Intent()
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            ArrayList(select(availableNames))
        )
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            ArrayList(select(unavailableNames))
        )
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }
}