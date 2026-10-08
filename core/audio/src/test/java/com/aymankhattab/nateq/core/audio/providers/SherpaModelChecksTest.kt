package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ع٢٩-6: حارس التحقق المسبق من نماذج Sherpa — يمنع وصول نموذج
 * ناقص (ملف صغير، tokens فارغ، espeak مقطوع) إلى المكتبة
 * الأصلية، وهي العلة التي كانت تُنهي عملية التطبيق كلها.
 */
class SherpaModelChecksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** بناء مجلد espeak مكتمل بالمدخلات الأساسية. */
    private fun writeCompleteEspeak(dir: File) {
        dir.mkdirs()
        listOf("phondata", "phontab", "phonindex", "intonations")
            .forEach { File(dir, it).writeText("data") }
        File(dir, "voices").mkdirs()
        File(dir, "lang").mkdirs()
        File(File(dir, "voices"), "ar").writeText("voice")
        File(File(dir, "lang"), "ar").writeText("lang")
    }

    /** ملف نموذج صالح (يتجاوز 100KB بالضبط). */
    private fun writeBigModel(file: File) {
        file.writeBytes(
            ByteArray((SherpaModelChecks.MIN_MODEL_BYTES + 1).toInt())
        )
    }

    @Test
    fun missingModelFile_reportsModelFileMissing() {
        val e = assertThrows(SherpaModelException::class.java) {
            SherpaModelChecks.validate(
                modelFile = File(tempFolder.root, "gone.onnx"),
                tokensFile = tokens(tempFolder.root),
                espeakDir = espeak(tempFolder.root)
            )
        }
        assertEquals("MODEL_FILE_MISSING", e.code)
    }

    @Test
    fun modelFileAtExactly100kb_isRejected() {
        val model = File(tempFolder.root, "tiny.onnx")
        model.writeBytes(
            ByteArray(SherpaModelChecks.MIN_MODEL_BYTES.toInt())
        )
        val e = assertThrows(SherpaModelException::class.java) {
            SherpaModelChecks.validate(
                modelFile = model,
                tokensFile = tokens(tempFolder.root),
                espeakDir = espeak(tempFolder.root)
            )
        }
        assertEquals("MODEL_FILE_MISSING", e.code)
    }

    @Test
    fun emptyTokens_reportsTokensMissing() {
        val model = model(tempFolder.root)
        val empty = File(tempFolder.root, "tokens.txt")
        empty.writeText("")
        val e = assertThrows(SherpaModelException::class.java) {
            SherpaModelChecks.validate(
                modelFile = model,
                tokensFile = empty,
                espeakDir = espeak(tempFolder.root)
            )
        }
        assertEquals("TOKENS_MISSING", e.code)
    }

    @Test
    fun absentTokensReportsTokensMissing() {
        val model = model(tempFolder.root)
        val e = assertThrows(SherpaModelException::class.java) {
            SherpaModelChecks.validate(
                modelFile = model,
                tokensFile = File(tempFolder.root, "absent.txt"),
                espeakDir = espeak(tempFolder.root)
            )
        }
        assertEquals("TOKENS_MISSING", e.code)
    }

    @Test
    fun truncatedEspeakReportsIncomplete() {
        val dir = File(tempFolder.root, "espeak-ng-data")
        writeCompleteEspeak(dir)
        File(dir, "voices").deleteRecursively()
        val e = assertThrows(SherpaModelException::class.java) {
            SherpaModelChecks.validate(
                modelFile = model(tempFolder.root),
                tokensFile = tokens(tempFolder.root),
                espeakDir = dir
            )
        }
        assertEquals("ESPEAK_DATA_INCOMPLETE", e.code)
        assertTrue(e.message.contains("voices"))
    }

    @Test
    fun completeSetPassesValidation() {
        val dir = File(tempFolder.root, "espeak-ng-data")
        writeCompleteEspeak(dir)
        SherpaModelChecks.validate(
            modelFile = model(tempFolder.root),
            tokensFile = tokens(tempFolder.root),
            espeakDir = dir
        )
    }

    @Test
    fun missingEspeakDirReportsAllEntries() {
        val missing = SherpaModelChecks.missingEspeakEntries(
            File(tempFolder.root, "absent")
        )
        assertEquals(
            SherpaModelChecks.ESPEAK_REQUIRED_ENTRIES,
            missing
        )
    }

    @Test
    fun emptyEspeakEntriesCountAsMissing() {
        val dir = File(tempFolder.root, "espeak-ng-data")
        dir.mkdirs()
        SherpaModelChecks.ESPEAK_REQUIRED_ENTRIES.forEach {
            when (it) {
                "voices", "lang" -> File(dir, it).mkdirs()
                else -> File(dir, it).writeText("")
            }
        }
        assertEquals(
            SherpaModelChecks.ESPEAK_REQUIRED_ENTRIES,
            SherpaModelChecks.missingEspeakEntries(dir)
        )
    }

    private fun model(dir: File): File =
        File(dir, "voice.onnx").also { writeBigModel(it) }

    private fun tokens(dir: File): File =
        File(dir, "tokens.txt").also { it.writeText("tok") }

    private fun espeak(dir: File): File =
        File(dir, "espeak-ng-data").also { writeCompleteEspeak(it) }
}
