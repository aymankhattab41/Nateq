package com.aymankhattab.nateq

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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

    companion object {
        private const val TAG = "NATEQ_TTFU"

        /** ميزانية زمن أول نطق على الجهاز المرجعي (بند الأداء 7). */
        private const val TTFU_BUDGET_MS = 800L

        private const val TTFU_ID = "ttfu1"
    }

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

    /**
     * مقياس زمن أول نطق الرسمي (بند الأداء 7): من استدعاء أول تخليق
     * إلى وصول أول بايت صوت فعلي (onAudioAvailable) — يفشل فوق
     * [TTFU_BUDGET_MS] على الجهاز المرجعي فيتحول «الخفة» لرقمٍ يُراقَب.
     *
     * إحماءٌ واحد غير مقاس يسبق القياس لعزل كلفة العملية الباردة
     * (ربط المحرك/بناء القاموس الكسول) — مغطاةٌ بمقياس الإقلاع لا هنا.
     */
    @Suppress("DEPRECATION")
    @Test
    fun timeToFirstAudio_underThreshold() {
        val initLatch = CountDownLatch(1)
        val tts = TextToSpeech(
            context,
            { initLatch.countDown() },
            enginePackage
        )
        try {
            assertTrue(
                "تهيئة المحرك خلال المهلة",
                initLatch.await(30, TimeUnit.SECONDS)
            )
            tts.language = Locale("ar", "SA")
            // إحماء غير مقاس: يستقر الربط والقاموس قبل القياس.
            warmUpOnce(tts)
            val out = File(
                context.cacheDir,
                "nateq_ttfu_" + System.currentTimeMillis() + ".wav"
            )
            val doneLatch = CountDownLatch(1)
            val firstAudioLatch = CountDownLatch(1)
            @Volatile var firstAudioAt = -1L
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        doneLatch.countDown()
                    }
                    override fun onError(id: String?) {}
                    override fun onAudioAvailable(
                        id: String?,
                        audio: ByteArray?
                    ) {
                        if (id == TTFU_ID && firstAudioAt < 0) {
                            firstAudioAt =
                                SystemClock.elapsedRealtime()
                            firstAudioLatch.countDown()
                        }
                    }
                }
            )
            val t0 = SystemClock.elapsedRealtime()
            assertTrue(
                "طلب القياس يُقبل",
                tts.synthesizeToFile(
                    "ناطق يقيس زمن أول صوت",
                    null,
                    out,
                    TTFU_ID
                ) == TextToSpeech.SUCCESS
            )
            if (Build.VERSION.SDK_INT >= 26) {
                assertTrue(
                    "أول بايت صوت يصل خلال المهلة",
                    firstAudioLatch.await(30, TimeUnit.SECONDS)
                )
                val ttfu = firstAudioAt - t0
                Log.i(TAG, "timeToFirstAudioMs=" + ttfu)
                assertTrue(
                    "TTFU=" + ttfu + "ms يتجاوز الميزانية " +
                        TTFU_BUDGET_MS + "ms",
                    ttfu <= TTFU_BUDGET_MS
                )
            }
            assertTrue(
                "اكتمال النطق خلال المهلة",
                doneLatch.await(30, TimeUnit.SECONDS)
            )
            assertTrue(
                "الملف مكتوب ويتجاوز رأس WAV فارغاً",
                out.exists() && out.length() > 44
            )
        } finally {
            tts.shutdown()
        }
    }

    /** تخليق إحماء واحد غير مقاس لاستقرار الجلسة قبل قياس TTFU. */
    @Suppress("DEPRECATION")
    private fun warmUpOnce(tts: TextToSpeech) {
        val latch = CountDownLatch(1)
        val out = File(
            context.cacheDir,
            "nateq_ttfu_warm_" + System.currentTimeMillis() + ".wav"
        )
        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                latch.countDown()
            }
            override fun onError(id: String?) {
                latch.countDown()
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        if (tts.synthesizeToFile(
                "إحماء",
                null,
                out,
                "ttfu_warm"
            ) == TextToSpeech.SUCCESS
        ) {
            latch.await(30, TimeUnit.SECONDS)
        }
    }
}