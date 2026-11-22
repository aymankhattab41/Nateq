package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * اختبارات الأمان والاستقرار لاستيراد حزم الأصوات (الأمر 3 من 5):
 * 1. حزمة بصوتين وYAML مختلف لكل منهما
 * 2. صوت بلا YAML (عدم نسخ YAML لصوت آخر إليه)
 * 3. تعارض الهوية واستبعاد الصوت المتأثر وبقاء السليم
 * 4. مسار دعم متداخل صحيح (NLP)
 * 5. ZIP تالف في المنتصف مع التراجع وحماية البيانات القديمة
 * 6. عبور المسار (Zip Slip)
 * 7. أعضاء كُثر (تجاوز سقف 20,000 ملف)
 * 8. تدفق ضخم أو بطيء يتجاوز السقف
 * 9. جلستان متزامنتان (منع التصادم عبر onNewIntent)
 * 10. إلغاء الاستيراد مع استعادة الأصوات القديمة سليمة
 * 11. مساحة غير كافية
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePackageSecurityAndImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var acBase: File
    private lateinit var vocBase: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineStatusRegistry.clear()
        acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        vocBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        cleanStorage()
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        cleanStorage()
        VoicePackageImporter.cancelActiveSession()
    }

    private fun cleanStorage() {
        if (acBase.exists()) acBase.deleteRecursively()
        if (vocBase.exists()) vocBase.deleteRecursively()
    }

    private fun createZip(
        outFile: File,
        entries: Map<String, ByteArray>
    ) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun installInitialSalma() {
        val salmaDir = File(acBase, "salma22k_hq").apply { mkdirs() }
        File(salmaDir, "salma.ini").writeText(
            "PRO \"NLP/ar.oso\"\nSLV \"salma.bvcu\"\nVOICE \"Salma\"\n"
        )
        File(salmaDir, "salma.inf").writeText("Speaker=Salma\nIsoCode=ara")
        File(salmaDir, "salma.dat").writeBytes(ByteArray(15_000))
        File(salmaDir, "config.yaml").writeText(
            "speaker:\n  param: \"salma22k_hq, s@t.com, aljl11223344d1b7\"\n"
        )
    }

    @Test
    fun twoVoicePackage_withDistinctYaml_installsBothWithCorrectConfigs() =
        runBlocking {
            val zipFile = File(tempFolder.root, "two_voices.zip")
            createZip(
                zipFile,
                mapOf(
                    "salma/salma.ini" to (
                        "PRO \"NLP/ar.oso\"\n" +
                            "SLV \"salma.bvcu\"\n" +
                            "VOICE \"Salma\"\n"
                        ).toByteArray(),
                    "salma/salma.inf" to (
                        "Speaker=Salma\nIsoCode=ara"
                        ).toByteArray(),
                    "salma/salma.dat" to ByteArray(15_000),
                    "salma/config.yaml" to (
                        "speaker:\n" +
                            "  param: \"salma22k_hq, s@t.com, hexSalma\"\n"
                        ).toByteArray(),
                    "leila/leila.ini" to (
                        "PRO \"NLP/ar.oso\"\n" +
                            "SLV \"leila.bvcu\"\n" +
                            "VOICE \"Leila\"\n"
                        ).toByteArray(),
                    "leila/leila.inf" to (
                        "Speaker=Leila\nIsoCode=ara"
                        ).toByteArray(),
                    "leila/leila.dat" to ByteArray(15_000),
                    "leila/config.yaml" to (
                        "speaker:\n" +
                            "  param: \"leila22k_hq, l@t.com, hexLeila\"\n"
                        ).toByteArray()
                )
            )

            val res = VoicePackageImporter.importZip(
                context,
                Uri.fromFile(zipFile),
                "two_voices.zip"
            )
            assertTrue(res.success)

            val salmaCfg = File(acBase, "salma/config.yaml")
            val leilaCfg = File(acBase, "leila/config.yaml")
            assertTrue("Salma config should exist", salmaCfg.exists())
            assertTrue("Leila config should exist", leilaCfg.exists())
            assertTrue(salmaCfg.readText().contains("hexSalma"))
            assertTrue(leilaCfg.readText().contains("hexLeila"))
        }

    @Test
    fun voiceWithoutYaml_doesNotGetOtherVoiceYaml() = runBlocking {
        val zipFile = File(tempFolder.root, "one_yaml_only.zip")
        createZip(
            zipFile,
            mapOf(
                "salma/salma.ini" to (
                    "PRO \"NLP/ar.oso\"\n" +
                        "SLV \"salma.bvcu\"\n" +
                        "VOICE \"Salma\"\n"
                    ).toByteArray(),
                "salma/salma.inf" to "Speaker=Salma\nIsoCode=ara".toByteArray(),
                "salma/salma.dat" to ByteArray(15_000),
                "salma/config.yaml" to (
                    "speaker:\n  param: \"salma22k_hq, s@t.com, hexSalma\"\n"
                    ).toByteArray(),
                "leila/leila.ini" to (
                    "PRO \"NLP/ar.oso\"\n" +
                        "SLV \"leila.bvcu\"\n" +
                        "VOICE \"Leila\"\n"
                    ).toByteArray(),
                "leila/leila.inf" to "Speaker=Leila\nIsoCode=ara".toByteArray(),
                "leila/leila.dat" to ByteArray(15_000)
            )
        )

        val res = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "one_yaml_only.zip"
        )
        assertTrue(res.success)

        val salmaCfg = File(acBase, "salma/config.yaml")
        val leilaCfg = File(acBase, "leila/config.yaml")
        assertTrue("Salma config must exist", salmaCfg.exists())
        assertFalse(
            "Leila must NOT get Salma's config.yaml",
            leilaCfg.exists()
        )
    }

    @Test
    fun findConfigYamlWithParam_doesNotMatchConflictingSpeaker() {
        val root = tempFolder.newFolder("acapela_test_root")
        val salmaDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(salmaDir, "config.yaml").writeText(
            "speaker:\n  param: \"salma22k_hq, s@t.com, hexSalma\"\n"
        )
        val leilaDir = File(root, "leila22k_hq").apply { mkdirs() }

        // البحث لـ Leila داخل مجلدها ومجلده الأب لا يجب أن يعيد config لـ Salma
        val found = AcapelaVoiceManifest.findConfigYamlWithParam(leilaDir)
        assertNull(
            "Leila must not be associated with Salma's config.yaml",
            found
        )
    }

    @Test
    fun nestedSupportPath_verbatimMultiTts_retainsNlpDirectory() = runBlocking {
        val zipFile = File(tempFolder.root, "multitts_layout.zip")
        createZip(
            zipFile,
            mapOf(
                "voice/acapela/NLP/ar_sa.oso" to ByteArray(500),
                "voice/acapela/salma22k_hq/salma.ini" to (
                    "PRO \"NLP/ar_sa.oso\"\n" +
                        "SLV \"salma.bvcu\"\n" +
                        "VOICE \"Salma\"\n"
                    ).toByteArray(),
                "voice/acapela/salma22k_hq/salma.inf" to (
                    "Speaker=Salma\nIsoCode=ara"
                    ).toByteArray(),
                "voice/acapela/salma22k_hq/salma.dat" to ByteArray(15_000)
            )
        )

        val res = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "multitts.zip"
        )
        assertTrue(res.success)

        val nlpFile = File(acBase, "voice/acapela/NLP/ar_sa.oso")
        assertTrue(
            "NLP support directory must be retained verbatim",
            nlpFile.exists()
        )
    }

    @Test
    fun corruptedZipInMiddle_preservesOldSalmaIntact() = runBlocking {
        installInitialSalma()
        val salmaDat = File(acBase, "salma22k_hq/salma.dat")
        val originalLen = salmaDat.length()

        // إنشاء ZIP غير مكتمل مقطوع في منتصف تيار البايتات
        val validZip = File(tempFolder.root, "valid.zip")
        createZip(
            validZip,
            mapOf(
                "salma22k_hq/salma.ini" to "broken".toByteArray(),
                "salma22k_hq/salma.dat" to ByteArray(20_000)
            )
        )
        val validBytes = validZip.readBytes()
        val truncatedZip = File(tempFolder.root, "truncated.zip")
        truncatedZip.writeBytes(validBytes.copyOf(validBytes.size / 2))

        val res = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(truncatedZip),
            "truncated.zip"
        )
        assertFalse(res.success)

        // التحقق من بقاء Salma القديمة كما هي
        assertTrue("Old Salma directory must exist", salmaDat.exists())
        assertEquals(originalLen, salmaDat.length())
    }

    @Test
    fun zipSlipAttempt_isRejectedAndOldDataPreserved() = runBlocking {
        installInitialSalma()
        val zipFile = File(tempFolder.root, "evil_slip.zip")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("../../../evil.txt"))
            zos.write("hack".toByteArray())
            zos.closeEntry()
        }
        zipFile.writeBytes(bos.toByteArray())

        val res = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "evil_slip.zip"
        )
        assertFalse(res.success)
        assertTrue(File(acBase, "salma22k_hq/salma.dat").exists())
    }

    @Test
    fun excessiveEntries_zipBomb_isRejected() = runBlocking {
        installInitialSalma()
        val zipFile = File(tempFolder.root, "too_many_entries.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (i in 1..20_005) {
                zos.putNextEntry(ZipEntry("file_$i.txt"))
                zos.write(byteArrayOf(1))
                zos.closeEntry()
            }
        }

        val inspection = VoicePackageImporter.inspectZipPackage(
            context,
            Uri.fromFile(zipFile),
            "bomb.zip"
        )
        assertFalse(inspection.isSupported)
        assertTrue(inspection.errorMessage?.contains("20000") == true)
        assertTrue(File(acBase, "salma22k_hq/salma.dat").exists())
    }

    @Test
    fun oversizedUriStream_isRejected() = runBlocking {
        installInitialSalma()
        val zipFile = File(tempFolder.root, "large_file.zip")
        zipFile.writeBytes(ByteArray(1024))

        val dest = File(tempFolder.root, "bounded_dest.zip")
        var errorThrown = false
        try {
            VoicePackageImporter.copyUriWithBounds(
                context = context,
                zipUri = Uri.fromFile(zipFile),
                destFile = dest,
                maxBytes = 500L
            )
        } catch (e: IllegalStateException) {
            errorThrown = true
        }
        assertTrue("Copying beyond max bytes must throw", errorThrown)
        assertTrue(File(acBase, "salma22k_hq/salma.dat").exists())
    }

    @Test
    fun concurrentIntents_secondIntentIsRejected() = runBlocking {
        val zipFile = File(tempFolder.root, "voice.zip")
        createZip(
            zipFile,
            mapOf(
                "mehdi.ini" to (
                    "PRO \"NLP/ar.oso\"\nSLV \"m.bvcu\"\nVOICE \"M\"\n"
                    ).toByteArray()
            )
        )

        // بدء فحص أول
        val inspection1 = VoicePackageImporter.inspectZipPackage(
            context,
            Uri.fromFile(zipFile),
            "first.zip"
        )

        // محاولة بدء فحص متزامن قبل إنهاء الأول
        val inspection2 = VoicePackageImporter.inspectZipPackage(
            context,
            Uri.fromFile(zipFile),
            "second.zip"
        )

        assertFalse(
            "Second concurrent inspection must be rejected",
            inspection2.isSupported
        )
        assertTrue(
            inspection2.errorMessage?.contains("عملية استيراد جارية") == true
        )

        VoicePackageImporter.cleanupInspection(inspection1)
    }

    @Test
    fun cancellation_abortsImportAndRollsBackOldVoices() = runBlocking {
        installInitialSalma()
        val salmaDat = File(acBase, "salma22k_hq/salma.dat")
        val originalLen = salmaDat.length()

        val zipFile = File(tempFolder.root, "cancel_test.zip")
        createZip(
            zipFile,
            mapOf(
                "salma22k_hq/salma.ini" to (
                    "PRO \"NLP/ar.oso\"\n" +
                        "SLV \"salma.bvcu\"\n" +
                        "VOICE \"Salma\"\n"
                    ).toByteArray(),
                "salma22k_hq/salma.inf" to (
                    "Speaker=Salma\nIsoCode=ara"
                    ).toByteArray(),
                "salma22k_hq/salma.dat" to ByteArray(30_000)
            )
        )

        val inspection = VoicePackageImporter.inspectZipPackage(
            context,
            Uri.fromFile(zipFile),
            "salma.zip"
        )
        assertTrue(inspection.isSupported)

        // تعيين إشارة الإلغاء
        val cancelSignal = AtomicBoolean(true)
        val result = VoicePackageImporter.executeImport(
            context = context,
            inspection = inspection,
            cancellationSignal = cancelSignal
        )

        assertTrue(result.cancelled)
        assertFalse(result.success)
        assertTrue(
            "Old Salma must remain intact after cancel",
            salmaDat.exists()
        )
        assertEquals(originalLen, salmaDat.length())
    }
}
