package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PronunciationDictionaryRound48Test {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary
    private lateinit var processor: TextProcessor
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "dictionary_v2.json").delete()
        File(context.filesDir, "dictionary_v2.json.tmp").delete()
        File(context.filesDir, "dictionary_legacy_backup.json").delete()
        PronunciationDictionary.resetSharedForTests()

        dict = PronunciationDictionary(context)
        settings = SettingsRepository.create(context)
        processor = TextProcessor(
            context,
            injectedSettings = settings,
            injectedDict = dict
        )
    }

    @After
    fun tearDown() {
        dict.clearAll()
        File(context.filesDir, "dictionary_v2.json").delete()
        File(context.filesDir, "dictionary_v2.json.tmp").delete()
        File(context.filesDir, "dictionary_legacy_backup.json").delete()
        PronunciationDictionary.resetSharedForTests()
    }

    @Test
    fun testDictionaryPriorityOverTimeStep() {
        // (أ) إدخال «م ← ميم»
        dict.addEntry("م", "ميم")
        val withM1 = processor.applyDictionaryFirst("الساعة 5 م", "ar")
        val processed1 = processor.process(withM1, "ar")
        assertTrue(processed1.contains("ميم"))

        val withM2 = processor.applyDictionaryFirst("5م", "ar")
        val processed2 = processor.process(withM2, "ar")
        assertTrue(processed2.contains("ميم"))

        val withM3 = processor.applyDictionaryFirst("م", "ar")
        assertEquals("ميم", withM3)

        // وبلا إدخال:
        dict.clearAll()
        // في وضع «حسب السياق»:
        settings.setTimeSuffixMMode(SettingsRepository.TIME_SUFFIX_M_CONTEXT)
        val full1 = processor.process("الساعة 5 م", "ar")
        assertTrue(
            "Expected 'مساءً' in full1 but got: $full1",
            full1.contains("مساءً")
        )

        val full2 = processor.process("5م", "ar")
        // «5م» لا يتحول إلى وقت في وضع «حسب السياق»
        assertTrue(
            "Expected not converting to evening in 5م but got: $full2",
            !full2.contains("مساءً")
        )
    }

    @Test
    fun testCaseInsensitiveLatinMatching() {
        // (ب) «usb» يطابق «USB»
        dict.testCaseInsensitive = true
        dict.addEntry("usb", "يو إس بي")

        val result1 = dict.apply("USB")
        assertEquals("يو إس بي", result1)

        val result2 = dict.apply("I love Usb devices")
        assertEquals("I love يو إس بي devices", result2)

        val result3 = dict.apply("micro-usb cable")
        assertEquals("micro-يو إس بي cable", result3)
    }

    @Test
    fun testProcessSyncWithinOneSecond() {
        // (ج) كتابة من نسخة وقراءة من نسخة أخرى فوراً
        val dictMain = PronunciationDictionary(context)
        val dictTts = PronunciationDictionary(context)

        dictMain.addEntry("testKey", "testValue")
        // dictTts يقرأ التغيير فوراً عبر reloadIfChanged
        val changed = dictTts.reloadIfChanged()
        assertTrue(changed)
        assertEquals("testValue", dictTts.getAllEntries()["testKey"])
    }

    @Test
    fun testMigrationFromLegacyStorage() {
        // (د) قاموس مكتوب سابقاً بالطريقة المشفّرة أو العادية يُنقل
        val legacyPrefs = context.getSharedPreferences(
            "nateq_pronunciation_dict",
            Context.MODE_PRIVATE
        )
        legacyPrefs.edit()
            .putString("dictionary", """{"legacyWord":"نطق_قديم"}""")
            .commit()

        File(context.filesDir, "dictionary_v2.json").delete()

        val migratedDict = PronunciationDictionary(context)
        assertEquals("نطق_قديم", migratedDict.getAllEntries()["legacyWord"])
        assertTrue(File(context.filesDir, "dictionary_v2.json").exists())
    }

    @Test
    fun testFaultTolerantApplyDictionaryFirst() {
        // (هـ) استثناء مفتعل في الآلة لا يوقف النطق
        val faultyDict = object : PronunciationDictionary(context) {
            override fun apply(text: String, languageTag: String?): String {
                throw RuntimeException("Simulated failure in dictionary")
            }
        }
        val faultyProcessor = TextProcessor(
            context,
            injectedSettings = settings,
            injectedDict = faultyDict
        )
        val input = "نص اختبار السلامة"
        val out = faultyProcessor.applyDictionaryFirst(input, "ar")
        assertNotNull(out)
        assertEquals(input, out)
    }
}
