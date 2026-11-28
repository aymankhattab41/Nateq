package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AcapelaLatencyAndJitterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SalmaSpeechTracker.clearForTest()
        SpeechLatencyTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        SalmaSpeechTracker.clearForTest()
        SpeechLatencyTracker.resetForTesting()
    }

    @Test
    fun percentileAndStats_emptyList_returnsSafeDefaultsAndNoPhrasesMessage() {
        val summary = SalmaSpeechTracker.formatAcapelaLatencySummary(
            emptyList()
        )
        assertTrue(
            "يجب أن يتضمن التقرير عنوان الملخص",
            summary.contains("=== ملخص زمن استجابة وفجوات نطق Acapela ===")
        )
        assertTrue(
            "يجب أن يتضمن التقرير عبارة 'لا عبارات مسجلة بعد' عند خلو البيانات",
            summary.contains("لا عبارات مسجلة بعد")
        )
    }

    @Test
    fun percentileAndStats_singleChunkPhrase_calculatesZeroGaps() {
        val req = SalmaSpeechTracker.startRequest(
            correlationId = "single_chunk_req",
            category = SalmaSpeechTracker.Category.WARM,
            voiceName = "salma",
            text = "مرحبا"
        )
        req.markQueueWaitStart()
        req.markQueueWaitEnd()
        req.onPcmChunkReceived(1024)
        val rec = req.complete(SalmaSpeechTracker.Status.SUCCESS)

        assertNotNull(rec)
        assertEquals(0L, rec!!.maxGapMs)
        assertEquals(0, rec.gapCountOverThreshold)
        assertEquals(0L, rec.maxGapOffsetMs)

        val summary = SalmaSpeechTracker.formatAcapelaLatencySummary()
        assertFalse(summary.contains("لا عبارات مسجلة بعد"))
        assertTrue(summary.contains("• إجمالي العبارات المسجلة: 1"))
        assertTrue(summary.contains("أكبر فجوة داخلية: متوسط=0.0ms"))
        assertTrue(summary.contains("p50=0ms"))
        assertTrue(summary.contains("p95=0ms"))
    }

    @Test
    fun percentileAndStats_syntheticData_calculatesCorrectPercentiles() {
        val records = mutableListOf<SalmaSpeechTracker.Record>()
        for (i in 1..20) {
            val totalDur = i * 100L
            val pcm = i * 20L
            val gap = if (i % 2 == 0) 160L else 40L
            val gapsOver150 = if (i % 2 == 0) 2 else 0
            val qWait = i * 5L
            records.add(
                SalmaSpeechTracker.Record(
                    correlationId = "synth_$i",
                    requestStartTimeMs = 1700000000000L + (i * 1000L),
                    category = if (i == 20) {
                        SalmaSpeechTracker.Category.COLD
                    } else {
                        SalmaSpeechTracker.Category.WARM
                    },
                    status = SalmaSpeechTracker.Status.SUCCESS,
                    textLength = i * 10,
                    textHash = "%08x".format(i),
                    voiceName = "salma",
                    queueWaitMs = qWait,
                    voiceResolveMs = 0L,
                    discoveryVerifyMs = 0L,
                    hostBindMs = 0L,
                    engineInitMs = 0L,
                    warmupMs = 0L,
                    speakCallMs = totalDur - qWait,
                    firstPcmMs = pcm,
                    firstTrackWriteMs = pcm + 5L,
                    playbackHeadStartMs = pcm + 15L,
                    totalDurationMs = totalDur,
                    maxGapMs = gap,
                    gapCountOverThreshold = gapsOver150,
                    maxGapOffsetMs = pcm + 30L
                )
            )
        }

        // اختبار دالة percentile الرياضية
        val durations = records.map { it.totalDurationMs }.sorted()
        // حجم 20: فهرس p50 = (0.50 * 19).toInt() = 9 -> durations[9] = 1000L
        assertEquals(1000L, SalmaSpeechTracker.percentile(durations, 0.50))
        // فهرس p95 = (0.95 * 19).toInt() = 18 -> durations[18] = 1900L
        assertEquals(1900L, SalmaSpeechTracker.percentile(durations, 0.95))

        val summary = SalmaSpeechTracker.formatAcapelaLatencySummary(records)
        assertTrue(summary.contains("• إجمالي العبارات المسجلة: 20"))
        assertTrue(summary.contains("p50=1000ms"))
        assertTrue(summary.contains("p95=1900ms"))
        assertTrue(summary.contains("أقصى=2000ms"))

        // التحقق من قائمة أبطأ 10 عبارات
        assertTrue(summary.contains("• أبطأ 10 عبارات زمنياً:"))
        assertTrue(
            "أبطأ عبارة يجب أن تكون إجمالي 2000ms",
            summary.contains("1. [") && summary.contains("إجمالي: 2000ms")
        )
        // التحقق من ظهور حالة بارد عند الخمول
        assertTrue(summary.contains("حالة: بارد (بعد خمول)"))
        assertTrue(summary.contains("حالة: دافئ"))
    }

    @Test
    fun textHash_isDeterministic_andCanaryWordNeverAppearsInReport() {
        val canary = "SECRET_CANARY_WORD_XYZ999"
        val sampleText = "هذه رسالة خاصة جداً تحوي كلمة سرية: $canary"

        val hash1 = SalmaSpeechTracker.computeTextHash(sampleText)
        val hash2 = SalmaSpeechTracker.computeTextHash(sampleText)
        assertEquals(
            "البصمة يجب أن تكون قطعية لنفس النص دائماً",
            hash1,
            hash2
        )
        assertEquals(8, hash1.length)
        assertTrue(hash1.matches(Regex("^[0-9a-f]{8}$")))

        // تشغيل طلب بنص يحوي الكلمة السرية
        val req = SalmaSpeechTracker.startRequest(
            correlationId = "canary_test",
            category = SalmaSpeechTracker.Category.WARM,
            voiceName = "salma",
            text = sampleText
        )
        req.markFirstPcm()
        req.complete(SalmaSpeechTracker.Status.SUCCESS)

        val latencySummary = SalmaSpeechTracker.formatAcapelaLatencySummary()
        val salmaReport = SalmaSpeechTracker.formatReport()

        // إضافة العبارة لمتتبع السرعة أيضاً
        SpeechLatencyTracker.recordUtterance(
            SpeechLatencyTracker.UtteranceMetric(
                text = sampleText,
                preprocessDurationMs = 10L,
                planningDurationMs = 5L,
                timeToFirstByteMs = 150L,
                totalDurationMs = 400L
            )
        )
        val speedReport = SpeechLatencyTracker.formatSpeedReport()

        // التحقق القاطع: لا كلمة سرية في أي تقرير إطلاقاً
        assertFalse(
            "يُحظر تماماً ظهور النص السري في ملخص Acapela",
            latencySummary.contains(canary)
        )
        assertFalse(
            "يُحظر تماماً ظهور النص السري في تقرير SalmaSpeechTracker",
            salmaReport.contains(canary)
        )
        assertFalse(
            "يُحظر تماماً ظهور النص السري في تقرير SpeechLatencyTracker",
            speedReport.contains(canary)
        )

        // التحقق من ظهور البصمة وطول النص بدلاً من النص
        assertTrue(latencySummary.contains("#$hash1"))
        assertTrue(latencySummary.contains("${sampleText.length} حرفاً"))
        assertTrue(speedReport.contains("#$hash1"))
    }

    @Test
    fun maxHistory_limitOf200_isStrictlyEnforced() {
        for (i in 1..250) {
            val req = SalmaSpeechTracker.startRequest(
                correlationId = "overflow_req_$i",
                category = SalmaSpeechTracker.Category.WARM,
                voiceName = "leila",
                textLength = 15,
                textHash = "%08x".format(i)
            )
            req.markFirstPcm()
            req.complete(SalmaSpeechTracker.Status.SUCCESS)
        }

        val records = SalmaSpeechTracker.getAllRecords()
        assertEquals(
            "الحد الأقصى للذاكرة هو 200 عبارة بدقة",
            200,
            records.size
        )
        // التحقق من طرد أول 50 عبارة وبقاء آخر 200
        assertEquals("overflow_req_51", records.first().correlationId)
        assertEquals("overflow_req_250", records.last().correlationId)
    }

    @Test
    fun deviceProfile_containsRequiredHardwareInfoAndNoPersonalIdentifiers() {
        val profile = AcapelaVoiceDiagnostics.getDeviceProfile(context)
        assertNotNull(profile)
        assertTrue(
            profile.contains("=== مواصفات بيئة الجهاز وموارد النظام ===")
        )
        assertTrue(profile.contains("• الشركة والطراز:"))
        assertTrue(profile.contains("• نظام أندرويد:"))
        assertTrue(profile.contains("• المعالج والمعمارية:"))
        assertTrue(profile.contains("• الذاكرة (RAM):"))
        assertTrue(profile.contains("• وضع توفير الطاقة:"))
        assertTrue(profile.contains("• تحسين البطارية للتطبيق:"))

        // التحقق من خلو الملف من أي معرفات شخصية
        assertFalse(profile.contains("ANDROID_ID"))
        assertFalse(profile.contains("imei"))
        assertFalse(profile.contains("mac_address"))
    }

    @Test
    fun export_worksWithoutData_andIncludesDeviceProfile() {
        val summary = SalmaSpeechTracker.formatAcapelaLatencySummary()
        assertTrue(summary.contains("لا عبارات مسجلة بعد"))

        val sb = StringBuilder()
        AcapelaVoiceDiagnostics.appendAcapelaLatencyAndDeviceProfile(
            sb, context
        )
        val fullAppended = sb.toString()
        assertTrue(
            fullAppended.contains("=== مواصفات بيئة الجهاز وموارد النظام ===")
        )
        assertTrue(
            fullAppended.contains(
                "=== ملخص زمن استجابة وفجوات نطق Acapela ==="
            )
        )
        assertTrue(fullAppended.contains("لا عبارات مسجلة بعد"))
    }
}
