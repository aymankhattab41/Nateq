package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات تحويل السرعة/النبرة وتقسيم النص لقطع محرك Acapela.
 *
 * **عقد التقسيم (MultiTTS):** قطعٌ بطول 200 حرف على حدود الكلمات؛
 * فلا تُبتَر كلمة ولا يُسقَط نص. الضمُّ يستعيد الكلمات كلها بالترتيب.
 */
class AcapelaSpeechTagTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun calculateSpeedPercent_convertsRateProperly() {
        assertEquals(100, AcapelaHostSession.calculateSpeedPercent(1.0f))
        assertEquals(150, AcapelaHostSession.calculateSpeedPercent(1.5f))
        assertEquals(200, AcapelaHostSession.calculateSpeedPercent(2.0f))
        assertEquals(50, AcapelaHostSession.calculateSpeedPercent(0.5f))
        assertEquals(30, AcapelaHostSession.calculateSpeedPercent(0.1f))
        assertEquals(400, AcapelaHostSession.calculateSpeedPercent(5.0f))
    }

    @Test
    fun calculatePitchPercent_convertsPitchProperly() {
        assertEquals(100, AcapelaHostSession.calculatePitchPercent(1.0f))
        assertEquals(120, AcapelaHostSession.calculatePitchPercent(1.2f))
        assertEquals(80, AcapelaHostSession.calculatePitchPercent(0.8f))
        assertEquals(50, AcapelaHostSession.calculatePitchPercent(0.2f))
        assertEquals(200, AcapelaHostSession.calculatePitchPercent(3.0f))
    }

    @Test
    fun splitAcapelaChunks_keepsShortTextWhole() {
        val text = "نصٌّ قصير"
        assertEquals(
            listOf(text),
            AcapelaHostSession.splitAcapelaChunks(text, 200)
        )
    }

    @Test
    fun splitAcapelaChunks_keepsEveryWordForLongText() {
        val words = (1..120).map { "كلمة$it" }
        val text = words.joinToString(" ")

        val chunks = AcapelaHostSession.splitAcapelaChunks(text, 200)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        // لا فقدان كلمة ولا بتر: الضمُّ يستعيد الكلمات بالترتيب.
        assertEquals(words, chunks.joinToString(" ").split(" "))
    }

    @Test
    fun splitAcapelaChunks_neverStartsChunkWithSpace() {
        val text = (1..60).joinToString(" ") { "word$it" }

        val chunks = AcapelaHostSession.splitAcapelaChunks(text, 120)

        assertTrue(chunks.all { !it.startsWith(" ") && !it.endsWith(" ") })
    }

    /**
     * عقد ترتيب المرشحات من ملف 0x... بلا config.yaml:
     * (a) المحتوى كاملاً، (b) الأسطر مسافةٌ، (c) السطر الأول بلا
     * اقتباس، (d) مع الاقتباس، (e) الرمز الحالي.
     */
    @Test
    fun registrationCandidates_followsTheAgreedOrder() {
        val dir = tempFolder.newFolder("voice")
        File(dir, "0x00169729").writeText(
            "\"key#9876#token\"\nABCD\nEFGH\n",
            Charsets.UTF_8
        )

        val candidates = AcapelaHostSession.registrationCandidates(
            dir,
            "7e88"
        )

        assertEquals(
            listOf('a', 'b', 'c', 'd', 'e'),
            candidates.map { it.letter }
        )
        assertEquals(
            "\"key#9876#token\"\nABCD\nEFGH",
            candidates[0].value
        )
        assertEquals(
            "\"key#9876#token\" ABCD EFGH",
            candidates[1].value
        )
        assertEquals("key#9876#token", candidates[2].value)
        assertEquals("\"key#9876#token\"", candidates[3].value)
        assertEquals("7e88", candidates[4].value)
    }

    /**
     * عقد مرشحات config.yaml: تأتي أولاً (f,g,h,i,j,k) قبل مرشحات
     * الملف، وتُشتق من سطر param (voiceCode,email,hexCode).
     *
     * - f: email,hexCode
     * - g: hexCode,email
     * - h: hexCode وحده
     * - i: email وحده
     * - j: سطر param كاملاً
     * - k: voiceCode وحده
     */
    @Test
    fun registrationCandidates_configYamlParamComesBefore0xFile() {
        val dir = tempFolder.newFolder("salma22k_hq")
        // config.yaml فيه سطر param بالصيغة: voiceCode,email,hexCode
        File(dir, "config.yaml").writeText(
            "speaker: salma\n" +
                "param: salma22k_hq," +
                "test@example.com," +
                "abcdef1234567890\n",
            Charsets.UTF_8
        )
        // ملف 0x... احتياطي
        File(dir, "0x00169729").writeText(
            "hexFileContent\n",
            Charsets.UTF_8
        )

        val candidates = AcapelaHostSession.registrationCandidates(
            dir,
            "currentReg"
        )

        // config.yaml يجب أن يأتي أولاً
        val letters = candidates.map { it.letter }
        val fIdx = letters.indexOf('f')
        val aIdx = letters.indexOf('a')
        assertTrue(
            "مرشح f (config.yaml) يجب أن يسبق مرشح a (0x...)",
            fIdx in letters.indices && aIdx in letters.indices &&
                fIdx < aIdx
        )

        // التحقق من قيم المرشحات المشتقة
        val byLetter = candidates.associateBy { it.letter }
        assertEquals(
            "test@example.com,abcdef1234567890",
            byLetter['f']?.value
        )
        assertEquals(
            "abcdef1234567890,test@example.com",
            byLetter['g']?.value
        )
        assertEquals("abcdef1234567890", byLetter['h']?.value)
        assertEquals("test@example.com", byLetter['i']?.value)
        assertEquals("salma22k_hq", byLetter['k']?.value)
    }

    /**
     * عقد splitConfigYamlParam: يُقسَّم السطر على فاصلة ويُعيد الأجزاء
     * الثلاثة، وما دون ثلاثة أجزاء يُعيد null.
     */
    @Test
    fun splitConfigYamlParam_parsesThreeParts() {
        val manifest = com.aymankhattab.nateq.core.audio.providers
            .AcapelaVoiceManifest

        val result = manifest.splitConfigYamlParam(
            "salma22k_hq,user@host.com,1234567890abcdef"
        )
        assertEquals(
            Triple(
                "salma22k_hq",
                "user@host.com",
                "1234567890abcdef"
            ),
            result
        )

        // أقل من ثلاثة أجزاء
        assertEquals(null, manifest.splitConfigYamlParam("only,two"))
        assertEquals(null, manifest.splitConfigYamlParam("single"))
        assertEquals(null, manifest.splitConfigYamlParam(""))
    }
}
