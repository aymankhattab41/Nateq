package com.aymankhattab.nateq.core.audio.enginehost

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * حارس ع٢٩-5 — بروتوكول العملية الأمامية ⇄ `:engine_host`.
 *
 * العقد الذي يحرسه:
 * 1. **النتائج**: `OK` / `OK␟حمولة` / `ERR␟رمز␟رسالة` تعود كما
 *    كُتبت، وأي سلسلة فاسدة تعود فشلاً مُعلناً لا استثناءً.
 * 2. **قوائم الأصوات**: ترميز/فكّ متماثلان، والفشل يبقى فشلاً
 *    برمز معنون لا قائمةً صامتة.
 * 3. **إطارات الأنبوب**: ترتيب الصيغة/PCM/النهاية مضمون، وأي
 *    إطار مبتور أو فوق الحدّ يُرفض بـIOException.
 * 4. **علامة الانهيار**: تُكتب وتُقرأ وتُمحى — عليها يعتمد
 *    `CRASHED_LAST_RUN`.
 */
class HostProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ----------------------------------------------------------------
    // النتائج
    // ----------------------------------------------------------------

    @Test
    fun result_okWithPayload_roundTrips() {
        val raw = HostProtocol.ok("42")
        val decoded = HostProtocol.decodeResult(raw)
        assertTrue(decoded.ok)
        assertEquals("42", decoded.payload)
        assertEquals(42L, HostProtocol.decodeRequestId(raw))
    }

    @Test
    fun result_okWithoutPayload_hasNullPayload() {
        val decoded = HostProtocol.decodeResult(HostProtocol.ok())
        assertTrue(decoded.ok)
        assertNull(decoded.payload)
        assertEquals(-1L, HostProtocol.decodeRequestId(HostProtocol.ok()))
    }

    @Test
    fun result_error_roundTripsCodeAndMessage() {
        val raw = HostProtocol.error("CRASHED_LAST_RUN", "انهار")
        val decoded = HostProtocol.decodeResult(raw)
        assertFalse(decoded.ok)
        assertEquals("CRASHED_LAST_RUN", decoded.code)
        assertEquals("انهار", decoded.message)
    }

    @Test
    fun result_dirtyPayloadIsSanitizedBeforeEncode() {
        // US/RS داخل الحمولة لكسر الترميز — تُستبدل ولا تُمرَّر.
        val raw = HostProtocol.ok("a\u001fb\u001ec")
        val decoded = HostProtocol.decodeResult(raw)
        assertTrue(decoded.ok)
        val payload = decoded.payload.orEmpty()
        assertFalse(payload.contains('\u001f'))
        assertFalse(payload.contains('\u001e'))
    }

    @Test
    fun result_garbageOrBlankIsNamedFailure() {
        for (bad in listOf("", "   ", "blargh", "OK?", "ERR")) {
            val decoded = HostProtocol.decodeResult(bad)
            assertFalse("«$bad» لا يُعدّ نجاحاً", decoded.ok)
            assertEquals(
                "«$bad» يعود برمز معنون",
                "BAD_RESPONSE",
                decoded.code
            )
        }
    }

    // ----------------------------------------------------------------
    // قوائم الأصوات
    // ----------------------------------------------------------------

    @Test
    fun voices_roundTrip_preservesEveryRecord() {
        val voices = listOf(
            HostVoice("v1", "Salem", "ar-SA"),
            HostVoice("v2", "A B", "en-US"),
            HostVoice("v3", "يحتوي\u001fفاصل", "ar-EG")
        )
        val decoded = HostProtocol.decodeVoices(
            HostProtocol.encodeVoices(voices)
        )
        // السجلّ الثالث يحمل US في الاسم: يُطهر عند الترميز فلا
        // يكسر السجلّ ولا يُسقَط — كل السجلّات تعود.
        assertEquals(3, decoded.size)
        assertEquals("v1", decoded[0].id)
        assertEquals("Salem", decoded[0].name)
        assertEquals("ar-SA", decoded[0].langTag)
        assertEquals("v2", decoded[1].id)
        assertEquals("A B", decoded[1].name)
        assertEquals("v3", decoded[2].id)
        assertEquals("يحتوي فاصل", decoded[2].name)
        assertEquals("ar-EG", decoded[2].langTag)
    }

    @Test
    fun voices_emptyListEncodesPrefixOnlyAndDecodesEmpty() {
        val raw = HostProtocol.encodeVoices(emptyList())
        assertTrue(HostProtocol.decodeVoices(raw).isEmpty())
        val response = HostProtocol.decodeVoicesResponse(raw)
        assertNull(response.failure)
        assertTrue(response.voices.isEmpty())
    }

    @Test
    fun voices_failureResponse_keepsTheNamedCode() {
        val raw = HostProtocol.error("ENGINE_ERROR", "x")
        val response = HostProtocol.decodeVoicesResponse(raw)
        assertTrue(response.voices.isEmpty())
        assertEquals("ENGINE_ERROR", response.failure?.code)
        assertEquals("x", response.failure?.message)
    }

    @Test
    fun voices_garbageResponseIsNamedFailureNotSilence() {
        val response = HostProtocol.decodeVoicesResponse("nonsense")
        assertTrue(response.voices.isEmpty())
        assertEquals("BAD_RESPONSE", response.failure?.code)
    }

    @Test
    fun voices_blankResponseIsNamedFailure() {
        val response = HostProtocol.decodeVoicesResponse(null)
        assertEquals("BAD_RESPONSE", response.failure?.code)
    }

    // ----------------------------------------------------------------
    // إطارات الأنبوب
    // ----------------------------------------------------------------

    @Test
    fun frames_formatPcmEnd_roundTripInOrder() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        HostProtocol.writeFormat(data, 22050, 1)
        HostProtocol.writePcm(data, bytes, bytes.size)
        HostProtocol.writeEnd(data, HostProtocol.STATUS_OK)

        val input = DataInputStream(
            ByteArrayInputStream(out.toByteArray())
        )
        val format = HostProtocol.readFrame(input)
        assertTrue(format is HostProtocol.HostFrame.Format)
        format as HostProtocol.HostFrame.Format
        assertEquals(22050, format.sampleRate)
        assertEquals(1, format.channels)

        val pcm = HostProtocol.readFrame(input)
        assertTrue(pcm is HostProtocol.HostFrame.Pcm)
        assertArrayEquals(bytes, (pcm as HostProtocol.HostFrame.Pcm).bytes)

        val end = HostProtocol.readFrame(input)
        assertTrue(end is HostProtocol.HostFrame.End)
        end as HostProtocol.HostFrame.End
        assertEquals(HostProtocol.STATUS_OK, end.status)
        assertEquals("", end.code)

        assertNull("لا إطار بعد النهاية", HostProtocol.readFrame(input))
    }

    @Test
    fun frames_endCarriesNamedError() {
        val out = ByteArrayOutputStream()
        HostProtocol.writeEnd(
            DataOutputStream(out),
            HostProtocol.STATUS_ERROR,
            "GENERATION_TIMEOUT",
            "تجاوز الميزانية"
        )
        val end = HostProtocol.readFrame(
            DataInputStream(ByteArrayInputStream(out.toByteArray()))
        ) as HostProtocol.HostFrame.End
        assertEquals(HostProtocol.STATUS_ERROR, end.status)
        assertEquals("GENERATION_TIMEOUT", end.code)
        assertEquals("تجاوز الميزانية", end.message)
    }

    @Test
    fun frames_eofBeforeAnyFrameReturnsNull() {
        assertNull(
            HostProtocol.readFrame(
                DataInputStream(ByteArrayInputStream(ByteArray(0)))
            )
        )
    }

    @Test
    fun frames_truncatedHeaderThrows() {
        try {
            HostProtocol.readFrame(
                DataInputStream(
                    ByteArrayInputStream(byteArrayOf(2, 0, 0))
                )
            )
            fail("رأس مبتور يجب أن يُرفض")
        } catch (expected: IOException) {
            // مطلوب: لا يُقرأ إطار ناقص بصمتاً.
        }
    }

    @Test
    fun frames_negativeLengthThrows() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(2)
        data.writeInt(-1)
        data.flush()
        try {
            HostProtocol.readFrame(
                DataInputStream(ByteArrayInputStream(out.toByteArray()))
            )
            fail("طول سالب يجب أن يُرفض")
        } catch (expected: IOException) {
            // مطلوب: لا تخصيص ذاكرة بلا حدّ عبر Binder.
        }
    }

    @Test
    fun frames_unknownTypeThrows() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(99)
        data.writeInt(0)
        data.flush()
        try {
            HostProtocol.readFrame(
                DataInputStream(ByteArrayInputStream(out.toByteArray()))
            )
            fail("نوع إطار غريب يجب أن يُرفض")
        } catch (expected: IOException) {
            // مطلوب.
        }
    }

    @Test
    fun frames_pcmOverLimitIsRejectedOnWrite() {
        val huge = ByteArray(HostProtocol.MAX_PCM_FRAME + 1)
        try {
            HostProtocol.writePcm(
                DataOutputStream(ByteArrayOutputStream()),
                huge,
                huge.size
            )
            fail("إطار فوق الحدّ يجب أن يُرفض")
        } catch (expected: IOException) {
            // مطلوب: لا إرسال حمولة بلا حدّ.
        }
    }

    @Test
    fun frames_emptyPcmSliceIsSkippedNotCorrupting() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        HostProtocol.writePcm(data, ByteArray(0), 0)
        HostProtocol.writeEnd(data, HostProtocol.STATUS_OK)
        val end = HostProtocol.readFrame(
            DataInputStream(ByteArrayInputStream(out.toByteArray()))
        )
        assertTrue(
            "لا إطار PCM فارغ، النهاية أول ما يقرأ",
            end is HostProtocol.HostFrame.End
        )
    }

    // ----------------------------------------------------------------
    // علامة الانهيار
    // ----------------------------------------------------------------

    @Test
    fun marker_lifecycle_writeReadClear() {
        val dir = tempFolder.newFolder("files")
        assertFalse(HostProtocol.hasMarker(dir, "vocalizer"))

        HostProtocol.writeMarker(dir, "vocalizer", "init dir=/x")
        assertTrue(HostProtocol.hasMarker(dir, "vocalizer"))
        val content = HostProtocol.readMarker(dir, "vocalizer")
        assertTrue(content.contains("init dir=/x"))

        HostProtocol.clearMarker(dir, "vocalizer")
        assertFalse(HostProtocol.hasMarker(dir, "vocalizer"))
        assertEquals("", HostProtocol.readMarker(dir, "vocalizer"))
    }

    @Test
    fun marker_isolatesEnginesFromEachOther() {
        val dir = tempFolder.newFolder("files2")
        HostProtocol.writeMarker(dir, "vocalizer", "boom")
        assertTrue(HostProtocol.hasMarker(dir, "vocalizer"))
        assertFalse(
            "علامة محرك لا تؤثر في آخر",
            HostProtocol.hasMarker(dir, "other_engine")
        )
    }

    // ----------------------------------------------------------------
    // توحيد معرّفات المحركات (ع٣١-1)
    // ----------------------------------------------------------------

    @Test
    fun normalizeEngineId_mapsAliasesToCanonicalNames() {
        assertEquals(
            HostProtocol.ENGINE_VOCALIZER,
            HostProtocol.normalizeEngineId("vocalizer_embedded")
        )
        assertEquals(
            HostProtocol.ENGINE_VOCALIZER,
            HostProtocol.normalizeEngineId("VOCALIZER_EMBEDDED")
        )
    }

    @Test
    fun normalizeEngineId_preservesCanonicalAndOtherNamesLowercased() {
        assertEquals(
            HostProtocol.ENGINE_VOCALIZER,
            HostProtocol.normalizeEngineId("vocalizer")
        )
        assertEquals(
            "custom_engine",
            HostProtocol.normalizeEngineId("CUSTOM_ENGINE")
        )
        assertEquals(
            "google_tts",
            HostProtocol.normalizeEngineId("Google_TTS")
        )
    }
}
