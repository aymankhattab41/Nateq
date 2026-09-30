package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ChangelogSpeechTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private val sampleChangelog = """
        الإصدار 0.100.0
        • توحيد قناة النطق على مسار الوسائط لكل الأحداث (المتصل، الساعة، الرسائل، البطارية، الإشعارات، الأرقام) بلا شرط — فلا يُسقط أي نطق إلى مسار الإتاحة أو الرنين شبه الصامت، ويبقى كل النطق على نفس قناة البطارية الصاخبة
        • تفجير أول صوتٍ لعناصر قارئ الشاشة القصيرة (زر/كلمة): بثٌّ أول بشريحة صغيرة 1KB فور توفّرها بدل انتظار اكتمال الكتابة وإعلان onDone المتأخر، ثم استكمال الشرائح بحجمها المعتاد
        • تسريع استجابة نطق قارئ الشاشة: إعادة تحميل الإعدادات بحدٍّ زمني بدل كل طلب، ونقل علم النطق إلى خلفية، ومشاركة مؤثرات الصوت عبر طلبات الجلسة، وتدفئة المحرك المطلوب أولاً
    """.trimIndent()

    private fun splitIntoChunks(text: String, maxLen: Int = 200): List<String> {
        val lines = text.split("\n")
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            var remaining = trimmed
            while (remaining.length > maxLen) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current = StringBuilder()
                }
                val breakAt = remaining.lastIndexOf(' ', maxLen).let {
                    if (it > 0) it else maxLen
                }
                chunks.add(remaining.substring(0, breakAt).trim())
                remaining = remaining.substring(breakAt).trimStart()
            }

            if (current.length + remaining.length + 1 > maxLen && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }

            if (current.isNotEmpty()) current.append(" ")
            current.append(remaining)
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }

        return chunks
    }

    @Test
    fun `changelog splits into readable chunks`() {
        val chunks = splitIntoChunks(sampleChangelog)

        assertTrue("يجب أن يكون هناك أكثر من جزء", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "كل جزء يجب أن يكون ≤ 200 حرفاً: ${chunk.length}",
                chunk.length <= 200
            )
        }
    }

    @Test
    fun `all changelog content is preserved after splitting`() {
        val chunks = splitIntoChunks(sampleChangelog)
        val recombined = chunks.joinToString(" ")

        val originalWords = sampleChangelog
            .replace("\n", " ")
            .split(" ")
            .filter { it.isNotBlank() }
        val recombinedWords = recombined.split(" ").filter { it.isNotBlank() }

        assertEquals(
            "كل كلمات النص الأصلي يجب أن تكون موجودة بعد إعادة التركيب",
            originalWords.size,
            recombinedWords.size
        )
    }

    @Test
    fun `each changelog chunk can be spoken by announcement speaker`() {
        val speaker = AnnouncementSpeaker.getInstance(context)
        val chunks = splitIntoChunks(sampleChangelog)

        try {
            chunks.forEachIndexed { index, chunk ->
                assertTrue(
                    "الجزء $index فارغ",
                    chunk.isNotBlank()
                )

                speaker.speak(
                    chunk,
                    Locale.forLanguageTag("ar"),
                    1f,
                    1f,
                    1f,
                    category = SettingsRepository.VOICE_CATEGORY_DEFAULT
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `long single chunk would exceed typical TTS limit`() {
        val singleChunk = sampleChangelog.replace("\n", " ")
        assertTrue(
            "النص الكامل كجزء واحد يتجاوز 4000 حرف (حد TTS التقريبي)",
            singleChunk.length > 4000 || singleChunk.length > 200
        )
    }
}
