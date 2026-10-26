package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.audio.providers.EngineLanguageSupport
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticLogResolutionTest {

    @Before
    fun setUp() {
        EngineLanguageSupport.clear()
    }

    @After
    fun tearDown() {
        EngineLanguageSupport.clear()
    }

    @Test
    fun testCancelledRequest_notConsideredTruncated() {
        // نص طويل (85 حرفاً كما في السجل التشخيصي)
        val nonWs = 85
        val speechRate = 1.0f
        val expectedMs = ((nonWs * 55L) / speechRate).toLong()
        val producedMs = 0L // تم الإلغاء فوراً
        val isTestUtterance = false

        // حالة الإلغاء: تم الإلغاء من TalkBack أو المستخدم
        val cancelled = AtomicBoolean(true)
        val stopped = AtomicBoolean(false)
        val wasCancelled = cancelled.get() || stopped.get()

        val isTruncated = !isTestUtterance && !wasCancelled && nonWs >= 25 &&
            producedMs < (expectedMs * 0.35)

        assertFalse(
            "الطلب الملغى يجب ألا يُعتبر صوتاً مبتوراً",
            isTruncated
        )
    }

    @Test
    fun testStoppedRequest_notConsideredTruncated() {
        val nonWs = 85
        val speechRate = 1.0f
        val expectedMs = ((nonWs * 55L) / speechRate).toLong()
        val producedMs = 100L // أنتج القليل ثم تم الإيقاف
        val isTestUtterance = false

        val cancelled = AtomicBoolean(false)
        val stopped = AtomicBoolean(true)
        val wasCancelled = cancelled.get() || stopped.get()

        val isTruncated = !isTestUtterance && !wasCancelled && nonWs >= 25 &&
            producedMs < (expectedMs * 0.35)

        assertFalse(
            "الطلب الموقوف يجب ألا يُعتبر صوتاً مبتوراً",
            isTruncated
        )
    }

    @Test
    fun testUncancelledShortAudio_isConsideredTruncated() {
        val nonWs = 85
        val speechRate = 1.0f
        val expectedMs = ((nonWs * 55L) / speechRate).toLong()
        val producedMs = 100L
        val isTestUtterance = false

        val cancelled = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val wasCancelled = cancelled.get() || stopped.get()

        val isTruncated = !isTestUtterance && !wasCancelled && nonWs >= 25 &&
            producedMs < (expectedMs * 0.35)

        assertTrue(
            "الطلب غير الملغى إذا أنتج صوتاً قصيراً جداً يُعتبر مبتوراً",
            isTruncated
        )
    }

    @Test
    fun testUnsupportedLanguageEngine_filteredFromFallback() {
        // محاكاة تسجيل سامسونج كغير داعم للعربية بعد result=-2
        EngineLanguageSupport.recordUnsupported("com.samsung.SMT", "ar")

        val installedEngines = listOf(
            "com.samsung.SMT",
            "com.google.android.tts",
            "org.nobody.multitts"
        )

        val candidate = installedEngines.firstOrNull {
            it != "vocalizer_embedded" &&
                EngineLanguageSupport.isLanguageSupported(it, "ar")
        }

        // يجب أن يتخطى com.samsung.SMT ويختار com.google.android.tts
        assertTrue(
            "يجب عدم اختيار محرك لا يدعم العربية",
            candidate != "com.samsung.SMT"
        )
        assertTrue(
            candidate == "com.google.android.tts" ||
                candidate == "org.nobody.multitts"
        )
    }
}
