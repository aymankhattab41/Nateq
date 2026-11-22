package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * اختبارات دورة حياة مخزن الإعدادات وتعارض الكتابة عبر العمليات:
 * 1. استدعاءات متكررة بالآلاف تبقي مثيلاً ومراقباً ومستقبلاً واحداً.
 * 2. كتابة متزامنة بين عمليتين (الرئيسية والفرعية) تحفظ القيمتين معاً.
 * 3. عمليات الحذف والمسح ومجموعات النصوص وتصفير الإعدادات.
 * 4. عمليات كتابة متتابعة وسريعة من خيوط متعددة عبر IPC.
 * 5. سلوك Direct Boot قبل وبعد فتح القفل.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MultiProcessSettingsLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(
            SettingsChangeProvider::class.java,
            SettingsChangeProvider.AUTHORITY
        )
        context.getSharedPreferences(
            SettingsRepository.NEW_PREFS,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        SettingsAuthority.forceIpcForTesting = false
        SettingsRepository.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        SettingsAuthority.forceIpcForTesting = false
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun create_thousandsOfCalls_maintainsSingleInstanceAndObserver() {
        SettingsRepository.resetInstanceForTesting()
        for (i in 1..2000) {
            SettingsRepository.create(context)
        }
        assertEquals(
            1,
            SettingsRepository.activeInstancesCount.get()
        )
        val repo = SettingsRepository.create(context)
        assertTrue(repo.isObserverRegisteredForTesting())
        assertTrue(repo.isReceiverRegisteredForTesting())

        repo.setSpeechRate("ar", 1.65f)
        assertEquals(1.65f, repo.getSpeechRate("ar"), 0.001f)

        repo.unregister()
        assertFalse(repo.isObserverRegisteredForTesting())
        assertFalse(repo.isReceiverRegisteredForTesting())
        assertEquals(
            0,
            SettingsRepository.activeInstancesCount.get()
        )
    }

    @Test
    fun concurrentWrites_twoProcesses_speedAndCategoryVolumePersistTogether() {
        // العملية 1 (الرئيسية): تكتب السرعة والنبرة محلياً
        SettingsAuthority.forceIpcForTesting = false
        val repoMain = SettingsRepository(context)
        repoMain.setSpeechRate("ar", 1.85f)
        repoMain.setPitch("ar", 1.15f)

        // العملية 2 (الفرعية مثل :tts): تكتب إعدادات الفئة عبر IPC
        SettingsAuthority.forceIpcForTesting = true
        val repoChild = SettingsRepository(context)
        repoChild.setVolumeForCategory("call", 0.85f)
        repoChild.setCallerAnnouncementPitch(1.25f)

        // محاكاة إعادة تشغيل العملية: إغلاق المثيلات وإعادة الفتح
        repoMain.unregister()
        repoChild.unregister()
        SettingsRepository.resetInstanceForTesting()
        SettingsAuthority.forceIpcForTesting = false

        val freshRepo = SettingsRepository(context)
        assertEquals(
            1.85f,
            freshRepo.getSpeechRate("ar"),
            0.001f
        )
        assertEquals(
            1.15f,
            freshRepo.getPitch("ar"),
            0.001f
        )
        assertEquals(
            0.85f,
            freshRepo.getVolumeForCategory("call"),
            0.001f
        )
        assertEquals(
            1.25f,
            freshRepo.getCallerAnnouncementPitchOrDefault("ar"),
            0.001f
        )
        freshRepo.unregister()
    }

    @Test
    fun multiProcess_remove_clear_putStringSet_andResetToDefault() {
        SettingsAuthority.forceIpcForTesting = true
        val repoChild = SettingsRepository(context)

        // اختبار putStringSet عبر IPC
        val testFilterSet = setOf("ar", "en", "fr")
        repoChild.setLanguageFilterSet(testFilterSet)
        assertEquals(
            testFilterSet,
            repoChild.getLanguageFilterSet()
        )

        // اختبار remove عبر IPC
        repoChild.prefs.edit().remove("language_filter_list").commit()
        assertFalse(repoChild.prefs.contains("language_filter_list"))

        // اختبار كتابة قيم ثم clear عبر IPC
        repoChild.prefs.edit()
            .putString("temp_key_1", "val1")
            .putInt("temp_key_2", 99)
            .commit()
        assertTrue(repoChild.prefs.contains("temp_key_1"))
        assertTrue(repoChild.prefs.contains("temp_key_2"))

        repoChild.prefs.edit().clear().commit()
        assertFalse(repoChild.prefs.contains("temp_key_1"))
        assertFalse(repoChild.prefs.contains("temp_key_2"))

        // اختبار resetAllToDefault
        repoChild.setSpeechRate("ar", 1.99f)
        assertEquals(1.99f, repoChild.getSpeechRate("ar"), 0.001f)
        repoChild.resetAllToDefault()
        assertEquals(1.0f, repoChild.getSpeechRate("ar"), 0.001f)

        repoChild.unregister()
    }

    @Test
    fun rapidSuccessiveWrites_multiThreadedIPC_preservesAllWrites() {
        val count = 50
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(count)

        SettingsAuthority.forceIpcForTesting = true
        val repo = SettingsRepository(context)

        for (i in 1..count) {
            val key = "rapid_ipc_key_$i"
            val value = i * 10
            executor.execute {
                try {
                    repo.prefs.edit().putInt(key, value).commit()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(
            "انتهاء كل عمليات الكتابة السريعة خلال المهلة",
            latch.await(10, TimeUnit.SECONDS)
        )
        executor.shutdown()

        for (i in 1..count) {
            val key = "rapid_ipc_key_$i"
            val expected = i * 10
            assertEquals(
                "المفتاح $key يحفظ القيمة الصحيحة",
                expected,
                repo.prefs.getInt(key, -1)
            )
        }
        repo.unregister()
    }

    @Test
    fun directBoot_lockedState_usesMirrorAndSyncsOnUnlock() {
        val um = context.getSystemService(UserManager::class.java)
        val shadowUm = Shadows.shadowOf(um)
        shadowUm.setUserUnlocked(false)

        val lockedRepo = SettingsRepository(context)
        lockedRepo.setSpeechRate("ar", 1.6f)
        assertEquals(1.6f, lockedRepo.getSpeechRate("ar"), 0.001f)

        // التحقق من الحفظ في المرآة المحمية
        val deviceContext = context.createDeviceProtectedStorageContext()
        val mirrorPrefs = deviceContext.getSharedPreferences(
            SettingsRepository.BOOT_MIRROR_PREFS,
            Context.MODE_PRIVATE
        )
        assertEquals(1.6f, mirrorPrefs.getFloat("speech_rate_ar", 0f), 0.001f)

        // محاكاة فتح القفل
        shadowUm.setUserUnlocked(true)
        context.sendBroadcast(
            Intent(Intent.ACTION_USER_UNLOCKED)
        )

        // بعد فتح القفل: المخزن الرئيسي يحدّث بياناته من جديد
        assertEquals(1.6f, lockedRepo.getSpeechRate("ar"), 0.001f)
        lockedRepo.unregister()
    }
}
