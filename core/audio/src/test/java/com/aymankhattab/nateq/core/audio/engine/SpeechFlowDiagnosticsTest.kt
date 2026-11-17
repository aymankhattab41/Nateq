package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس عقد تشخيص محرك SpeechFlow (البند 26):
 * - الحقول الخاصة بالنداء تُصفَّر مع كل نداء جديد فلا تتسرب قيمٌ قديمة.
 * - التقرير المشترك لا يحتفظ بأي معاينة لنص الكلام (خصوصية).
 * - تُحصى الشريحة الكاملة وشريحة تصريف الذيل معاً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechFlowDiagnosticsTest {

    @Before
    fun setUp() {
        SpeechFlowDiagnostics.clear()
    }

    /**
     * الحارس الذي يثبت الخطأ المُصلَح: قبل الإصلاح كان النداء الثاني
     * يرث بايتات/عينات/حالة/استثناء النداء الأول في التقرير.
     */
    @Test
    fun secondCall_resetsPerCallFields_regression() {
        SpeechFlowDiagnostics.recordCall("النداء الأول")
        SpeechFlowDiagnostics.recordAudioChunk(246)
        SpeechFlowDiagnostics.recordResponse(-7)
        SpeechFlowDiagnostics.recordCompletion(
            "FIRST_STATE", IllegalStateException("FIRST_EX")
        )

        val first = SpeechFlowDiagnostics.buildReport()
        assertTrue(first.contains("246 بايت"))
        assertTrue(first.contains("123 عينة"))
        assertTrue(
            first.contains("رمز استجابة synthesizeToFile: -7")
        )
        assertTrue(first.contains("حالة الإتمام: FIRST_STATE"))
        assertTrue(first.contains("FIRST_EX"))

        SpeechFlowDiagnostics.recordCall("النداء الثاني")
        val second = SpeechFlowDiagnostics.buildReport()
        assertFalse(second.contains("246 بايت"))
        assertFalse(second.contains("123 عينة"))
        assertFalse(second.contains("FIRST_STATE"))
        assertFalse(second.contains("FIRST_EX"))
        assertTrue(second.contains("(0 بايت، 0 عينة)"))
        assertTrue(
            second.contains("رمز استجابة synthesizeToFile: لم يُسجل")
        )
        assertTrue(
            second.contains("حالة الإتمام: قيد المعالجة")
        )
        assertTrue(
            second.contains("لا توجد استثناءات مسجلة")
        )
        assertTrue(second.contains("عدد مرات الاستدعاء: 2"))
    }

    /** التقرير المشارك لا يُبقي أي أثر لنص الكلام المنطوق. */
    @Test
    fun report_neverRetainsSpeechText_privacyGuard() {
        val secret = "كلمة سرية خاصة بالمستخدم 9876543210"
        SpeechFlowDiagnostics.recordCall(secret)
        SpeechFlowDiagnostics.recordResponse(0)
        SpeechFlowDiagnostics.recordCompletion("done")

        val report = SpeechFlowDiagnostics.buildReport()
        assertFalse(report.contains(secret))
        assertFalse(report.contains("9876543210"))
        assertFalse(report.contains("آخر نص مطلوب"))
    }

    /** الشريحة غير الموجبة لا تُحصى، والموجبة تُجمع بايتاتٍ وعيناتٍ. */
    @Test
    fun recordAudioChunk_accumulatesBytesAndIgnoresNonPositive() {
        SpeechFlowDiagnostics.recordCall("")
        SpeechFlowDiagnostics.recordAudioChunk(100)
        SpeechFlowDiagnostics.recordAudioChunk(50)
        SpeechFlowDiagnostics.recordAudioChunk(0)
        SpeechFlowDiagnostics.recordAudioChunk(-20)

        val report = SpeechFlowDiagnostics.buildReport()
        assertTrue(report.contains("150 بايت"))
        assertTrue(report.contains("75 عينة"))
    }

    /**
     * عقد المسارين في مزوّد الصوت: المسار الكامل (شريحة ملف واحدة)
     * ومسار تصريف الذيل (المتبقي بعد آخر شريحة) كلاهما يُسجَّل.
     */
    @Test
    fun recordAudioChunk_countsFullFileAndDrainedRemainderPaths() {
        SpeechFlowDiagnostics.recordCall("")
        SpeechFlowDiagnostics.recordAudioChunk(1000)
        SpeechFlowDiagnostics.recordAudioChunk(200)

        val report = SpeechFlowDiagnostics.buildReport()
        assertTrue(report.contains("1200 بايت"))
        assertTrue(report.contains("600 عينة"))
    }
}
