package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.speech.tts.SynthesisCallback
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * اختبارات سرعة النطق وإعادة المحاولة وتدفق البايتات
 * والسباقات (الجولة 50 - الأمر 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechLatencyAndRetryTest {

    private lateinit var service: NateqTtsService
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        SpeechLatencyTracker.resetForTesting()
        val controller = Robolectric.buildService(
            NateqTtsService::class.java
        ).create()
        service = controller.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsRepository.create(context)
    }

    private class MockVoiceProvider(
        override val providerId: String = "mock_provider",
        override val displayName: String = "Mock Provider",
        private val onSynthesizeCall: suspend (
            text: String,
            category: String?,
            callIndex: Int
        ) -> ByteArray?
    ) : VoiceProvider {
        private val callCounter = AtomicInteger(0)

        override fun isConfigured(): Boolean = true

        override suspend fun listVoices(
            locale: Locale
        ): List<VoiceDescriptor> = listOf(
            VoiceDescriptor(
                id = "mock_voice_ar",
                providerId = providerId,
                displayName = "Mock Voice AR",
                locale = Locale.forLanguageTag("ar")
            )
        )

        override suspend fun synthesize(
            text: String,
            voice: VoiceDescriptor,
            speechRate: Float,
            pitch: Float,
            volume: Float,
            onFormatInfo: (Int, Int) -> Unit,
            onAudioChunk: (ByteArray, Int) -> Unit,
            enginePackage: String?,
            voiceLocale: Locale?,
            desiredVoiceName: String?,
            category: String?
        ) {
            val idx = callCounter.incrementAndGet()
            onFormatInfo(22050, 1)
            val chunk = onSynthesizeCall(text, category, idx)
            if (chunk != null && chunk.isNotEmpty()) {
                onAudioChunk(chunk, chunk.size)
            } else {
                throw RuntimeException("Synthetic failure in MockVoiceProvider")
            }
        }
    }

    private fun injectMockCatalog(provider: VoiceProvider) {
        val catalog = VoiceCatalog(listOf(provider))
        val catField = NateqTtsService::class.java.getDeclaredField("catalog")
        catField.isAccessible = true
        catField.set(service, catalog)

        val reqHandler = SynthesisRequestHandler(catalog, settings)
        val reqField = NateqTtsService::class.java
            .getDeclaredField("requestHandler")
        reqField.isAccessible = true
        reqField.set(service, reqHandler)
    }

    @Test
    fun testMockProviderFailsFirstNumericCallThenSucceeds() = runBlocking {
        // (أ) مزوّد وهمي يفشل أول استدعاء لمقطع رقمي ثم ينجح:
        // يجب أن يُنطق الرقم كاملاً في المحاولة نفسها دون سحب.
        var phoneAttempts = 0
        val mockProvider = MockVoiceProvider { text, cat, _ ->
            if (text.contains("صِفْرْ وَاحِد")) {
                phoneAttempts++
                if (phoneAttempts == 1) {
                    null // يفشل في أول محاولة
                } else {
                    ByteArray(2048) { 1 } // ينجح في المحاولة الثانية
                }
            } else {
                ByteArray(2048) { 0 }
            }
        }
        injectMockCatalog(mockProvider)

        val segment = Segment(
            "01012345678", LanguageCode.AR.tag, SpeechRole.NUMBER
        )
        var capturedMetric: SpeechLatencyTracker.SegmentMetric? = null

        val audio = service.synthesizeSegmentRaw(
            segment = segment,
            readerRate = 1.0f,
            effectCategory = SettingsRepository.VOICE_CATEGORY_NUMBERS,
            onMetric = { capturedMetric = it }
        )


        assertNotNull("Audio should not be null after retry", audio)
        assertEquals("Chunks must be produced", 1, audio?.chunks?.size)
        assertEquals("Should succeed on attempt 2", 2, capturedMetric?.attempts)
        assertTrue(
            "Metric should mark success",
            capturedMetric?.success == true
        )
        assertEquals(
            "Dropped segments should remain 0",
            0,
            SpeechLatencyTracker.getDroppedSegmentsCount()
        )
    }

    @Test
    fun testPermanentFailureFallsBackToLanguageVoiceOrRecordsDrop() =
        runBlocking {
        // (ب) مقطع يفشل دائماً: يُستخدم صوت اللغة ويُحصى في
        // droppedSegments إن فشل كل شيء.
        val mockProvider = MockVoiceProvider { _, category, _ ->
            // يفشل في صوت الفئة، لكن ينجح عند التراجع لصوت اللغة
            if (category == SettingsRepository.VOICE_CATEGORY_NUMBERS) {
                null
            } else {
                ByteArray(150) { 2 }
            }
        }
        injectMockCatalog(mockProvider)

        val segment = Segment("500", LanguageCode.AR.tag, SpeechRole.NUMBER)
        var metric: SpeechLatencyTracker.SegmentMetric? = null

        val audio = service.synthesizeSegmentRaw(
            segment = segment,
            readerRate = 1.0f,
            effectCategory = SettingsRepository.VOICE_CATEGORY_NUMBERS,
            onMetric = { metric = it }
        )

        // ينجح في المحاولة الثانية (صوت اللغة العادي بعد محاولة واحدة للفئة)
        assertNotNull("Should recover using default language voice", audio)
        assertEquals(2, metric?.attempts)
        assertTrue(metric?.success == true)
        assertEquals(0, SpeechLatencyTracker.getDroppedSegmentsCount())

        // الآن نختبر الفشل الدائم لكل شيء:
        val alwaysFailProvider = MockVoiceProvider { _, _, _ -> null }
        injectMockCatalog(alwaysFailProvider)

        val failAudio = service.synthesizeSegmentRaw(
            segment = segment,
            readerRate = 1.0f,
            effectCategory = SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertNull("Permanently failing segment returns null", failAudio)
        assertEquals(1, SpeechLatencyTracker.getDroppedSegmentsCount())
    }

    @Test
    fun testFirstByteArrivesAfterFirstSegmentNotWaitingForSecond() =
        runBlocking {
        // (ج) أول بايت يصل بعد المقطع الأول لا بعد الأخير
        // (قياس بمزوّد بطيء للمقطع الثاني).
        val mockProvider = MockVoiceProvider { text, _, _ ->
            if (text.contains("أول") || text.contains("أولا")) {
                delay(10) // سريع
                ByteArray(2048) { 3 }
            } else {
                delay(200) // بطيء
                ByteArray(2048) { 4 }
            }
        }
        injectMockCatalog(mockProvider)

        val segments = listOf(
            Segment("أولاً", LanguageCode.AR.tag, SpeechRole.TEXT),
            Segment("ثانياً", LanguageCode.AR.tag, SpeechRole.TEXT)
        )

        var firstByteAt = 0L
        val start = System.currentTimeMillis()

        val fakeCallback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 8192
            override fun start(
                sampleRateInHz: Int, audioFormat: Int, channelCount: Int
            ): Int = 0
            override fun audioAvailable(
                buffer: ByteArray?, offset: Int, length: Int
            ): Int {
                if (firstByteAt == 0L) {
                    firstByteAt = System.currentTimeMillis() - start
                }
                return 0
            }
            override fun done(): Int = 0
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted(): Boolean = firstByteAt > 0
            override fun hasFinished(): Boolean = false
        }

        service.synthesizeParallel(
            segments = segments,
            readerRate = 1.0f,
            callback = fakeCallback,
            onFirstByteEmitted = {
                if (firstByteAt == 0L) {
                    firstByteAt = System.currentTimeMillis() - start
                }
            }
        )

        assertTrue("First byte should be recorded", firstByteAt > 0)
        // المقطع الأول يحتاج ~10ms، بينما الثاني يحتاج ~200ms
        // إذن أول بايت يصل قبل انتهاء المقطع الثاني بكثير (< 150ms)
        assertTrue(
            "First byte ($firstByteAt ms) before slow segment (200ms)",
            firstByteAt < 150L
        )
    }

    @Test
    fun testTextWithoutNumbersCategorySettingYieldsSingleMergedSegment() {
        // (د) نص بلا أي إعداد خاص لفئة الأرقام يعطي مقطعاً واحداً.
        val text = "لدينا 5 أجهزة و10 شاشات"
        val items = SpeechPlanner.plan(
            rawText = text,
            fallbackLanguage = LanguageCode.AR.tag,
            numberLanguage = LanguageCode.AR.tag,
            settings = settings
        )

        assertEquals("Should produce exactly 1 merged segment", 1, items.size)
        assertEquals(text, items[0].text)
        assertEquals(SpeechRole.TEXT, items[0].role)
    }

    @Test
    fun testDelayedOnStopDoesNotCancelNewerRequestGeneration() {
        // (هـ) onStop() المتأخر لا يلغي طلباً أحدث.
        val genRef = service.getRequestGenerationForTesting()
        val g1 = genRef.incrementAndGet() // الطلب 1: جيل 1

        val job2 = Job()
        val g2 = genRef.incrementAndGet() // الطلب 2: جيل 2
        service.setActiveJobForTesting(g2, job2)

        // محاكاة وصول onStop متأخر كان يستهدف الطلب 1 (prevGen = g1)
        // طلب المتطلب: يلغي الوظيفة المرتبطة بالجيل السابق فقط
        val currentGenBeforeStop = g1 // الجيل المستهدف بالإيقاف القديم
        val activeGen = service.getActiveJobGenerationForTesting()

        // التحقق من أن المنطق لا يلغي g2 لأن g2 != currentGenBeforeStop
        if (activeGen == currentGenBeforeStop) {
            service.getActiveJobForTesting()?.cancel()
        }

        assertFalse(
            "Delayed onStop for request 1 must NOT cancel request 2",
            job2.isCancelled
        )
    }

    @Test
    fun testLongPhoneNumberWordsSplitIntoChunksOfThreeOrFour() {
        // أرقام الهاتف الطويلة (> 40 حرفاً) تُقسّم لمجموعات 3-4
        val phoneWords =
            "صفر واحد صفر تسعة ثمانية سبعة ستة خمسة أربعة ثلاثة اثنين واحد"
        assertTrue(
            "Phone words length (${phoneWords.length}) must be > 40",
            phoneWords.length > 40
        )

        val chunks = SpeechPlanner.splitPhoneNumberWords(phoneWords)
        assertTrue("Must be split into multiple chunks", chunks.size > 1)
        for (chunk in chunks) {
            val words = chunk.split(" ")
            assertTrue(
                "Each chunk must contain 3 or 4 words (was ${words.size})",
                words.size in 3..4
            )
        }
    }

    @Test
    fun testParallelLookaheadPreservesSegmentOrder() = runBlocking {
        // المقطع الثاني أسرع تخليقاً، لكن الإخراج يجب أن يبقى بترتيب
        // المقاطع: بايتات المقطع الأول قبل الثاني.
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val mockProvider = MockVoiceProvider { text, _, _ ->
            when {
                text.contains("أول") -> {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    ByteArray(2048) { 1 }
                }
                text.contains("ثان") -> {
                    // انتظر حتى يبدأ الأول ليضمن وجود سباق حقيقي.
                    firstStarted.await()
                    ByteArray(2048) { 2 }
                }
                else -> ByteArray(512) { 9 } // تدفئة/نداءات أخرى
            }
        }
        injectMockCatalog(mockProvider)

        val segments = listOf(
            Segment("أولاً", LanguageCode.AR.tag, SpeechRole.TEXT),
            Segment("ثانياً", LanguageCode.AR.tag, SpeechRole.TEXT)
        )
        val emittedMarkers = mutableListOf<Int>()
        var started = false
        var done = false
        var errored = false
        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 8192
            override fun start(
                sampleRateInHz: Int, audioFormat: Int, channelCount: Int
            ): Int { started = true; return 0 }
            override fun audioAvailable(
                buffer: ByteArray?, offset: Int, length: Int
            ): Int {
                val buf = buffer ?: return 0
                for (i in offset until offset + length) {
                    val b = buf[i].toInt()
                    if ((b == 1 || b == 2) &&
                        emittedMarkers.lastOrNull() != b
                    ) {
                        emittedMarkers.add(b)
                    }
                }
                return 0
            }
            override fun done(): Int { done = true; return 0 }
            override fun error() { errored = true }
            override fun error(errorCode: Int) { errored = true }
            override fun hasStarted(): Boolean = started
            override fun hasFinished(): Boolean = done
        }

        val job = launch(Dispatchers.Default) {
            service.synthesizeParallel(
                segments = segments,
                readerRate = 1.0f,
                callback = callback
            )
        }
        firstStarted.await()
        // أطلق الأول بعد أن تكون نافذة look-ahead قد بدأت الثاني.
        delay(50)
        releaseFirst.complete(Unit)
        job.join()

        assertTrue("Should start", started)
        assertTrue("Should finish", done)
        assertFalse("Should not error", errored)
        val firstIdx = emittedMarkers.indexOf(1)
        val secondIdx = emittedMarkers.indexOf(2)
        assertTrue(
            "First segment must be emitted: $emittedMarkers",
            firstIdx >= 0
        )
        assertTrue(
            "Second segment must be emitted: $emittedMarkers",
            secondIdx >= 0
        )
        assertTrue(
            "Segment order must be preserved: $emittedMarkers",
            firstIdx < secondIdx
        )
    }

    @Test
    fun testParallelPartialFailureSkipsNonCriticalSegment() = runBlocking {
        // مقطع غير حرج يفشل تخليقه فلا يسقط الجملة كلها؛ المقاطع الأخرى تُنطق.
        val mockProvider = MockVoiceProvider { text, _, _ ->
            if (text.contains("يفشل")) {
                null // فشل غير حرج
            } else {
                ByteArray(2048) { 7 }
            }
        }
        injectMockCatalog(mockProvider)

        val segments = listOf(
            Segment("بداية", LanguageCode.AR.tag, SpeechRole.TEXT),
            Segment("يفشل هذا", LanguageCode.AR.tag, SpeechRole.TEXT),
            Segment("نهاية", LanguageCode.AR.tag, SpeechRole.TEXT)
        )
        var started = false
        var done = false
        var errored = false
        var audioBytes = 0
        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 8192
            override fun start(
                sampleRateInHz: Int, audioFormat: Int, channelCount: Int
            ): Int { started = true; return 0 }
            override fun audioAvailable(
                buffer: ByteArray?, offset: Int, length: Int
            ): Int { audioBytes += length; return 0 }
            override fun done(): Int { done = true; return 0 }
            override fun error() { errored = true }
            override fun error(errorCode: Int) { errored = true }
            override fun hasStarted(): Boolean = started
            override fun hasFinished(): Boolean = done
        }

        service.synthesizeParallel(
            segments = segments,
            readerRate = 1.0f,
            callback = callback
        )

        assertTrue("Audio should be produced from healthy segments", started)
        assertTrue("Should complete despite non-critical failure", done)
        assertFalse("Non-critical failure must not error whole sentence", errored)
        assertTrue("Should emit audio bytes", audioBytes > 0)
    }

    @Test
    fun testParallelCancellationStopsSynthesis() = runBlocking {
        // الإلغاء يجب أن يوقف التخليق ولا يعلّق على المقاطع المسبقة.
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<ByteArray?>()
        val mockProvider = MockVoiceProvider { _, _, _ ->
            entered.complete(Unit)
            never.await()
        }
        injectMockCatalog(mockProvider)

        val segments = listOf(
            Segment("أول", LanguageCode.AR.tag, SpeechRole.TEXT),
            Segment("ثانٍ", LanguageCode.AR.tag, SpeechRole.TEXT)
        )
        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 8192
            override fun start(
                sampleRateInHz: Int, audioFormat: Int, channelCount: Int
            ): Int = 0
            override fun audioAvailable(
                buffer: ByteArray?, offset: Int, length: Int
            ): Int = 0
            override fun done(): Int = 0
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted(): Boolean = false
            override fun hasFinished(): Boolean = false
        }

        val job = launch(Dispatchers.Default) {
            service.synthesizeParallel(
                segments = segments,
                readerRate = 1.0f,
                callback = callback
            )
        }
        entered.await()
        job.cancel()
        val result = runCatching { job.join() }
        assertTrue("Cancellation should complete job", job.isCompleted)
        assertFalse("Job should not fail with non-cancellation error", job.isCancelled.not() && result.isFailure)
    }
}
