package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PronunciationDictionaryTransactionAndExportTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "dictionary_v2.json").delete()
        File(context.filesDir, "dictionary_v2.json.bak").delete()
        PronunciationDictionary.resetSharedForTests()
        dict = PronunciationDictionary(context)
        dict.clear()
    }

    @Test
    fun exportFullToJson_includesAllScopesModesAndValueLangs() {
        dict.addEntry("اختصار", "شرح_عام", null, "WHOLE", "ar")
        dict.addEntry("hello", "halo", "en", "ANYWHERE", "en-US")

        val json = dict.exportFullToJson()
        assertTrue(json.contains("nateq_dictionary"))
        assertTrue(json.contains("اختصار"))
        assertTrue(json.contains("شرح_عام"))
        assertTrue(json.contains("hello"))
        assertTrue(json.contains("halo"))
        assertTrue(json.contains("WHOLE"))
        assertTrue(json.contains("ANYWHERE"))
        assertTrue(json.contains("en-US"))

        val fresh = PronunciationDictionary(context)
        fresh.clear()
        assertTrue(fresh.importFromJson(json, merge = false))
        assertEquals("شرح_عام", fresh.getAllEntries()["اختصار"])
        assertEquals("halo", fresh.getAllEntries("en")["hello"])
        assertEquals("WHOLE", fresh.getMatchMode("اختصار", null))
        assertEquals("ANYWHERE", fresh.getMatchMode("hello", "en"))
        assertEquals("ar", fresh.getValueLang("اختصار", null))
        assertEquals("en-US", fresh.getValueLang("hello", "en"))
    }

    @Test
    fun importSingleScope_replacesOnlyTargetScopeWithoutWipingOthers() {
        dict.addEntry("عام", "نطق_عام", null, "WHOLE")
        dict.addEntry("chat", "tchat", "fr", "ANYWHERE")

        val scopeExport = dict.exportScopeToJson("fr")
        assertTrue(scopeExport.contains("tchat"))
        assertFalse(scopeExport.contains("نطق_عام"))

        // Update fr scope with new entries
        val newFrJson = """
        {
            "format": "nateq_dictionary_scope",
            "version": 2,
            "scope": "fr",
            "entries": { "merci": "شكرا" },
            "modes": { "merci": "WHOLE" },
            "value_langs": { "merci": "ar" }
        }
        """.trimIndent()

        assertTrue(dict.importFromJson(newFrJson, merge = false, "fr"))
        assertEquals("نطق_عام", dict.getAllEntries()["عام"])
        assertEquals("WHOLE", dict.getMatchMode("عام", null))
        assertNull(dict.getAllEntries("fr")["chat"])
        assertEquals("شكرا", dict.getAllEntries("fr")["merci"])
        assertEquals("WHOLE", dict.getMatchMode("merci", "fr"))
        assertEquals("ar", dict.getValueLang("merci", "fr"))
    }

    @Test
    fun mergeCleanlyUpdatesMatchingKeysWithoutLeavingStaleModes() {
        dict.addEntry("كلمة", "قديم", null, "WHOLE", "ar")
        dict.addEntry("باقية", "ثابت", null, "ANYWHERE", "ar")

        val mergeJson = """
        {
            "format": "nateq_dictionary",
            "version": 2,
            "entries": { "كلمة": "جديد" },
            "scopes": {},
            "modes": { "|كلمة": "AUTO" },
            "value_langs": { "|كلمة": "en" }
        }
        """.trimIndent()

        assertTrue(dict.importFromJson(mergeJson, merge = true))
        assertEquals("جديد", dict.getAllEntries()["كلمة"])
        assertEquals("ثابت", dict.getAllEntries()["باقية"])
        assertEquals("AUTO", dict.getMatchMode("كلمة", null))
        assertEquals("ANYWHERE", dict.getMatchMode("باقية", null))
        assertEquals("en", dict.getValueLang("كلمة", null))
        assertEquals("ar", dict.getValueLang("باقية", null))
    }

    @Test
    fun invalidStructureOrCorruptData_doesNotMutateInMemoryState() {
        dict.addEntry("أصلية", "قيمة_أصلية")
        val invalidJson = "{ corrupt json [ "

        assertFalse(dict.importFromJson(invalidJson, merge = false))
        assertEquals("قيمة_أصلية", dict.getAllEntries()["أصلية"])
    }

    @Test
    fun backupFileRestoresDataWhenMainFileIsLostOrEmpty() {
        dict.addEntry("مهمة", "قيمة_محفوظة")
        val mainFile = File(context.filesDir, "dictionary_v2.json")
        val bakFile = File(context.filesDir, "dictionary_v2.json.bak")

        // Trigger another save so bakFile is populated
        dict.addEntry("إضافية", "قيمة_ثانية")
        assertTrue(bakFile.exists())

        // Corrupt main file to 0 bytes
        mainFile.writeText("")

        PronunciationDictionary.resetSharedForTests()
        val reloaded = PronunciationDictionary(context)
        assertNotNull(reloaded.getAllEntries()["مهمة"])
    }
}
