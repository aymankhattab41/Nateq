package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * اختبارات ذرية حفظ تفضيلات اللغات والتزامن بين الخيوط والمثيل المفرد.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LanguagePrefsAtomicSaveTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun create_returnsProcessWideSingletonInstance() {
        val repo1 = SettingsRepository.create(context)
        val repo2 = SettingsRepository.create(context)
        assertSame("يجب أن يعيد create نفس المثيل دائماً", repo1, repo2)
    }

    @Test
    fun concurrentSaving_threeThreads_allValuesPersist() {
        val repo = SettingsRepository.create(context)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)

        val t1 = Thread {
            startLatch.await()
            repo.setEnginePreferenceForLanguage(
                languageTag = "ar",
                engine = "com.google.android.tts",
                voiceName = "ar-xa-x-ard-local",
                rate = 1.1f,
                pitch = 0.9f,
                volume = 0.85f
            )
            doneLatch.countDown()
        }

        val t2 = Thread {
            startLatch.await()
            repo.setEnginePreferenceForLanguage(
                languageTag = "en",
                engine = "com.acapela.tts",
                voiceName = "Ryan",
                rate = 1.25f,
                pitch = 1.0f,
                volume = 0.95f
            )
            doneLatch.countDown()
        }

        val t3 = Thread {
            startLatch.await()
            repo.setEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_TIME,
                "com.google.android.tts"
            )
            doneLatch.countDown()
        }

        t1.start()
        t2.start()
        t3.start()

        startLatch.countDown()
        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        assert(completed) { "انتهت المهلة قبل اكتمال الخيوط الثلاثة" }

        val arPref = repo.getEnginePreferenceForLanguage("ar")
        val enPref = repo.getEnginePreferenceForLanguage("en")
        val timeEngine = repo.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME
        )

        assertEquals("com.google.android.tts", arPref.engine)
        assertEquals("ar-xa-x-ard-local", arPref.voiceName)
        assertEquals(1.1f, arPref.rate, 0.05f)

        assertEquals("com.acapela.tts", enPref.engine)
        assertEquals("Ryan", enPref.voiceName)
        assertEquals(1.25f, enPref.rate, 0.05f)

        assertEquals("com.google.android.tts", timeEngine)
    }

    @Test
    fun sequentialSaving_twoInstances_bothLanguagesPersist() {
        val repo1 = SettingsRepository(context)
        val repo2 = SettingsRepository(context)

        repo1.setEnginePreferenceForLanguage(
            languageTag = "ar",
            engine = "com.google.android.tts",
            voiceName = "ar-voice-1",
            rate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f
        )

        Thread.sleep(20)

        repo2.setEnginePreferenceForLanguage(
            languageTag = "en",
            engine = "com.acapela.tts",
            voiceName = "en-voice-2",
            rate = 1.2f,
            pitch = 0.8f,
            volume = 0.9f
        )

        val freshRepo = SettingsRepository.create(context)
        freshRepo.reload()

        val arPref = freshRepo.getEnginePreferenceForLanguage("ar")
        val enPref = freshRepo.getEnginePreferenceForLanguage("en")

        assertNotNull(arPref)
        assertEquals("com.google.android.tts", arPref.engine)
        assertEquals("ar-voice-1", arPref.voiceName)

        assertNotNull(enPref)
        assertEquals("com.acapela.tts", enPref.engine)
        assertEquals("en-voice-2", enPref.voiceName)
    }
}
