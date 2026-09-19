package com.aymankhattab.nateq

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * اختبار أجهزة حقيقي لخدمة المحرك (بند الأوامر د.3.2): يتأكد أن حزمة نطق
 * تعرّف خدمة TTS قابلة للاكتشاف اصطلاحياً، ويُنشئ TextToSpeech مربوطاً
 * بحزمة نطق نفسها، ويُكلّفها بتوليد ملف صوت عربي فعلي يُكتب على القرص.
 * لا يُشغَّل ضمن اختبارات JVM/Robolectric إنما على جهازٍ أو محاكٍ فقط
 * (task `connectedAndroidTest` أو `assembleReleaseAndroidTest` للترجمة).
 */
@RunWith(AndroidJUnit4::class)
class TtsEngineSmokeTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private val enginePackage = "com.aymankhattab.nateq"

    @Before
    fun engineMustBeDeclaredAndDetectable() {
        val probeLatch = CountDownLatch(1)
        val probe = TextToSpeech(context, { probeLatch.countDown() })
        try {
            probeLatch.await(10, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            val found = probe.engines.any { it.name == enginePackage }
            assertTrue(
                "خدمة نطق يجب أن تظهر ضمن محركات TTS في الجهاز",
                found
            )
        } finally {
            probe.shutdown()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun synthesizesArabicSpeechToWavFile() {
        val initLatch = CountDownLatch(1)
        var initCode = Int.MIN_VALUE
        val tts = TextToSpeech(
            context,
            { code -> initCode = code; initLatch.countDown() },
            enginePackage
        )
        try {
            assertTrue(
                "في انتظار تهيئة المحرك خلال المهلة",
                initLatch.await(30, TimeUnit.SECONDS)
            )
            assertTrue(
                "تهيئة المحرك تنجح (الرمز: " + initCode + ")",
                initCode == TextToSpeech.SUCCESS
            )
            val set = tts.setLanguage(Locale("ar", "SA"))
            assertTrue(
                "اللغة العربية مدعومة (الرمز: " + set + ")",
                set == TextToSpeech.LANG_AVAILABLE ||
                    set == TextToSpeech.LANG_MISSING_DATA ||
                    set == TextToSpeech.LANG_COUNTRY_AVAILABLE
            )
            val out = File(
                context.cacheDir,
                "nateq_smoke_" + System.currentTimeMillis() + ".wav"
            )
            val speechLatch = CountDownLatch(1)
            tts.setOnUtteranceCompletedListener { id ->
                if (id == "smoke1") speechLatch.countDown()
            }
            val status = tts.synthesizeToFile(
                "ناطق يقرأ جملة اختبار للتحقق من دورة التوليد",
                null,
                out,
                "smoke1"
            )
            assertTrue(
                "طلب التوليد يُقبل من المحرك",
                status == TextToSpeech.SUCCESS
            )
            assertTrue(
                "حالة الاكتمال تصدر خلال المهلة",
                speechLatch.await(30, TimeUnit.SECONDS)
            )
            assertTrue(
                "الملف مكتوب ويتجاوز رأس WAV فارغاً",
                out.exists() && out.length() > 44
            )
        } finally {
            tts.shutdown()
        }
    }
}