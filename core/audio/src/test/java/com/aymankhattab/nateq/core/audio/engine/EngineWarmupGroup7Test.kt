package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineWarmupGroup7Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PronunciationDictionary.resetSharedForTests()
        com.aymankhattab.nateq.core.common.VisibleEngines.update(false, null)
    }

    private fun createService(
        repo: SettingsRepository,
        dict: PronunciationDictionary? = null
    ): NateqTtsService {
        val service = NateqTtsService::class.java
            .getDeclaredConstructor().newInstance()
        val attachMethod = ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attachMethod.isAccessible = true
        attachMethod.invoke(service, context)

        val settingsField = NateqTtsService::class.java
            .getDeclaredField("settings")
        settingsField.isAccessible = true
        settingsField.set(service, repo)

        if (dict != null) {
            val dictField = NateqTtsService::class.java
                .getDeclaredField("pronunciationDictionary")
            dictField.isAccessible = true
            dictField.set(service, dict)
        }
        return service
    }

    private class TrackingDict(context: Context) :
        PronunciationDictionary(context) {
        val prebuildThread = AtomicReference<Thread?>()
        override fun prebuildMatcher() {
            prebuildThread.compareAndSet(null, Thread.currentThread())
            super.prebuildMatcher()
        }
    }

    @Test
    fun textProcessorLazyBuild_prebuildsMatcherOffMainThread() {
        val repo = SettingsRepository.create(context)
        val dict = TrackingDict(context)
        val service = createService(repo, dict)

        val field = NateqTtsService::class.java
            .getDeclaredField("textProcessor${'$'}delegate")
        field.isAccessible = true
        val lazyDelegate = field.get(service) as Lazy<*>

        val latch = CountDownLatch(1)
        val builtOn = AtomicReference<Thread?>()
        val worker = Thread({
            lazyDelegate.value
            builtOn.set(dict.prebuildThread.get())
            latch.countDown()
        }, "group7-warmup")
        worker.start()

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val mainThread = Looper.getMainLooper().thread
        assertNotEquals(mainThread, builtOn.get())
        assertTrue(dict.isMatcherBuiltForTesting())
    }

    @Test
    fun prewarmList_includesAllCategoriesLanguagesAndLastUsed() {
        val repo = SettingsRepository.create(context)

        repo.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, "com.engine.time"
        )
        repo.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY, "com.engine.battery"
        )
        repo.setEnginePreferenceForLanguage(
            "ar", "com.engine.ar", null, 1.0f, 1.0f, 1.0f
        )
        repo.setEnginePreferenceForLanguage(
            "en", "com.engine.en", null, 1.0f, 1.0f, 1.0f
        )
        repo.setEnginePreferenceForLanguage(
            "fr", "com.engine.fr", null, 1.0f, 1.0f, 1.0f
        )
        repo.recordLastUsedTtsEngine("com.engine.last")

        val list = repo.getAllConfiguredEnginePackagesForPrewarm() +
            repo.getLastUsedTtsEngines()

        assertTrue(list.contains("com.engine.time"))
        assertTrue(list.contains("com.engine.battery"))
        assertTrue(list.contains("com.engine.ar"))
        assertTrue(list.contains("com.engine.en"))
        assertTrue(list.contains("com.engine.fr"))
        assertTrue(list.contains("com.engine.last"))
    }

    @Test
    fun lastUsedTtsEngines_areDedupedCappedAndOrdered() {
        val repo = SettingsRepository.create(context)

        for (i in 1..10) {
            repo.recordLastUsedTtsEngine("com.engine.$i")
        }
        repo.recordLastUsedTtsEngine("com.engine.5")

        val last = repo.getLastUsedTtsEngines()
        assertEquals(8, last.size)
        assertEquals("com.engine.5", last[0])
        assertFalse(last.contains("com.engine.1"))
        assertFalse(last.contains("com.engine.2"))
        assertTrue(last.contains("com.engine.10"))
    }
}
