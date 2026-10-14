package com.aymankhattab.nateq.core.audio.providers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حرّاس فكّ تشفير CF لمحرك Vocalizer المدمج.
 *
 * أهمّها [decrypt_realVoiceHead_matchesEngineBytes]: عيّنة 1024 بايت
 * حقيقية من ملف صوت Laila المشفَّر وناتجه المفكوك على الجهاز — تُثبّت
 * كل بايتات المفتاح (KEY[0..1023]) ضد خطأ النسخ، فلا يُقبل مفتاحٌ مغلوط
 * يبدو صحيحاً من رؤوس السحر وحده.
 */
class VocalizerCfDecryptorTest {

    @Test
    fun key_isOneThousandTwentyThreeChars() {
        assertEquals(1023, VocalizerCfDecryptor.KEY_ASCII.length)
    }

    @Test
    fun cfMagic_xorKey_yieldsSvMagic() {
        val cipher = VocalizerCfDecryptor.CF_MAGIC.copyOf()
        VocalizerCfDecryptor.xorInPlace(cipher, 0, cipher.size)
        assertArrayEquals(VocalizerCfDecryptor.SV_MAGIC, cipher)
    }

    @Test
    fun decrypt_realVoiceHead_matchesEngineBytes() {
        val cipher = Base64.getDecoder().decode(CIPHER_1024)
        val expectedPlain = Base64.getDecoder().decode(PLAIN_1024)

        assertTrue(
            VocalizerCfDecryptor.isEncrypted(cipher, cipher.size)
        )
        assertArrayEquals(
            VocalizerCfDecryptor.SV_MAGIC,
            expectedPlain.copyOf(4)
        )

        val out = ByteArrayOutputStream()
        val decrypted = VocalizerCfDecryptor.copyDecryptingIfNeeded(
            ByteArrayInputStream(cipher),
            out
        ) { }
        assertTrue(decrypted)
        assertArrayEquals(expectedPlain, out.toByteArray())
    }

    @Test
    fun plainStream_isCopiedUnchanged() {
        val plain = buildPlain()
        val out = ByteArrayOutputStream()
        val decrypted = VocalizerCfDecryptor.copyDecryptingIfNeeded(
            ByteArrayInputStream(plain),
            out
        ) { }
        assertFalse(decrypted)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun shortStream_isCopiedUnchanged() {
        val short = byteArrayOf(1, 2, 3)
        val out = ByteArrayOutputStream()
        VocalizerCfDecryptor.copyDecryptingIfNeeded(
            ByteArrayInputStream(short),
            out
        ) { }
        assertArrayEquals(short, out.toByteArray())
    }

    @Test
    fun encryptedRoundTrip_recoversPlaintext() {
        val plain = buildPlain()
        val cipher = plain.copyOf()
        VocalizerCfDecryptor.xorInPlace(cipher, 0, cipher.size)
        assertTrue(VocalizerCfDecryptor.isEncrypted(cipher, 4))

        val out = ByteArrayOutputStream()
        val decrypted = VocalizerCfDecryptor.copyDecryptingIfNeeded(
            ByteArrayInputStream(cipher),
            out
        ) { }
        assertTrue(decrypted)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun sharedBlobs_areRecognized() {
        assertTrue(VocalizerCfDecryptor.isSharedBlob("clm.dat"))
        assertTrue(VocalizerCfDecryptor.isSharedBlob("lid.dat"))
        assertTrue(VocalizerCfDecryptor.isSharedBlob("sysdct.dat"))
        assertTrue(VocalizerCfDecryptor.isSharedBlob("msp.dat"))
        assertTrue(
            VocalizerCfDecryptor.isSharedBlob("synth_med_fxd_bet3f22.dat")
        )
        assertFalse(
            VocalizerCfDecryptor.isSharedBlob(
                "arw_laila_embedded-high_1-0-5.dat"
            )
        )
    }

    @Test
    fun decIdableDat_onlyForVocalizerNonShared() {
        val voice = "arw_laila_embedded-high_1-0-5.dat"
        assertTrue(
            VocalizerCfDecryptor.isDecryptableDat("vocalizer", voice)
        )
        assertFalse(
            VocalizerCfDecryptor.isDecryptableDat("acapela", voice)
        )
        assertFalse(
            VocalizerCfDecryptor.isDecryptableDat("vocalizer", "clm.dat")
        )
        assertFalse(
            VocalizerCfDecryptor.isDecryptableDat("vocalizer", "sysdct.dat")
        )
    }

    private fun buildPlain(): ByteArray {
        val size = 5000
        val b = ByteArray(size)
        System.arraycopy(VocalizerCfDecryptor.SV_MAGIC, 0, b, 0, 4)
        for (i in 4 until size) {
            b[i] = (i * 31 + 7).toByte()
        }
        return b
    }

    private companion object {
        /** عيّنة حقيقية: أول 1024 بايت من ملف Laila المشفَّر. */
        private const val CIPHER_1024 =
            "rUqhbEJwSziDvWdBXlVHS2wSWU14aFN5ozJTcFBXdSBnVU9NdDA1ZWl1VFlY5bQr" +
            "x6A5TmlETWu5a7Fiad3eJpHfCDJjMU9pOWUUBnd2Tn5oOEo8ZzYqFgQWXSggVElI" +
            "YX9RGStVcSAtNjJeBC9vTwZaYEF4DEN3CmELIBo9UmRFYzBnIAQpFFIDLy4ADABd" +
            "ZVZhZnwkInspC09+dCZ1dgEPAVRTFXVuBCs4cwk7NAI6IR9ydhoxOxU6XyIUSDYC" +
            "VidRNkolJ14fOE4LeEcyJhsQMVZdDGgiIS0LGFhGXkICSQUACzxXCjAvUW50Ghwe" +
            "E2EWHB16Kx8OXUMWCwI0JCYXJi4xTlNrfwZ5HQgVMzljJGcVTkJIGGoTDx8FIxwn" +
            "Ewg8UmZpZ1FpCwBZVyhEJQYsWSwLF1cOEUlBLzY/MyAOPlRNZhVlR1BxCwkCPi43" +
            "VjZQFQc+BR5ZESgUBT0iAH9meRltQV5cUg09GBYsNgoZH2sZNkhuLlAiHygIDixZ" +
            "aUsUS3F2NBIZIhw0IkMCLgBYWB5PR31ZZHx1ZAVtOyoIJRw0NR0KAjp4TkVsd3FM" +
            "QCkTISNEAC8qChJzSiFZXydSfT8WQxZyaxgKOgUVABQ+GDkcLWhlSFFGUmQcBDUv" +
            "USg4JjUbJSosUGBrFRFxDiYoSUMfBAAZXihSZFtdanRSHzQEQUETRCAbFh1UZkcU" +
            "SnlUKygcDg4CeiQdPAMUEgRHDDMbMjEkcUZyZE1pfw8vC00PPEAaOEh+VzEMX20Q" +
            "UVQQaxYLGRI9DjI/EXBlTgEOCldXaztWR2JweR0eGyYyFytmIy0NfkUAPQozLg1K" +
            "aGxzWEl2X1gRCSVdOUAie1M0N19rAgkhKz5dOBNiVEFWHQASLlhUVDEXcwkFD0VS" +
            "ZFIvVWcXCR9AXBpBPCQPMCASIRsKdxoPKRFTVFcLLwJNM2dqQVVzDh0sB1QUCw8J" +
            "DScyWA0fEUZAF0wuAlcQJCQJIx0VfVJtYnRNSjMjQQolCTklKD8ALyAEJlxSJyQe" +
            "PSRYKAJnVhRQBjUUNSl+XQgeEld1VwN5A1BbLVMNCgMyC1pYVj8jFSU/bUVVc1VK" +
            "ezUTEhg/UwMmUTMPQVJPezcOJi0wGAdHEwgRH1M9Y21hEn5QDCouLlIDMAcqJjUk" +
            "RD81BhoPbEALKwsuDBQEAj0bESI/Ox8MXU1WMmxPUUkMMh9WMUMlOiosBg4sXlwo" +
            "cjElKQxfVi1TOB85EhEgMxstXFckdllYdBJXbSsuMz8nD11oNigrChB5a148Rio+" +
            "Ni1kaFEQWml1KBwSFzcEVgQgDjRrZWF9fX9/bSkTKyA3IV42ThIcCFlRRkVOag5c" +
            "ATQfWwgjEyVREFw1RTwfYw=="

        /** عيّنة حقيقية: ناتج فكّ تشفير العيّنة أعلاه من الجهاز. */
        private const val PLAIN_1024 =
            "5zLmHwAAAQDq7VICBAABAABAFAAAAAAA6mA6IhgQGBAUAAAAAAABAAEAAAAfreYf" +
            "ou1SAjwAAADyA4chH63mH6LtUgIAAAkATlZBUgUCACQAAAAIAHBpcGVsaW5lNg4A" +
            "ADw/eG1sIHZlcnNpb249IjEuMCI/Pgo8TlVBTkNFPgo8VkVSU0lPTj5OVUFOXzEu" +
            "MDwvVkVSU0lPTj4KPEhFQURFUj4KICA8QlJPS0VSU1RSSU5HPnBpcGVsaW5lL0Fy" +
            "YWJpYy9sYWlsYS8yMi9lbWJlZGRlZC1oaWdoLzEuMC41L3RleHQvcGNtPC9CUk9L" +
            "RVJTVFJJTkc+CiAgPFBSSU9SSVRZPjUzNjI8L1BSSU9SSVRZPgogIDxQQVJBTUVU" +
            "RVJTPgogICAgPGxhbmd1YWdlPkFyYWJpYzwvbGFuZ3VhZ2U+CiAgICA8bGFuZ2Nv" +
            "ZGU+QVJXPC9sYW5nY29kZT4KICAgIDxsYW5naWV0Zj5hci1XVzwvbGFuZ2lldGY+" +
            "CiAgICA8bGFuZ3ZlcnNpb24+OS41LjAwMjAwMDwvbGFuZ3ZlcnNpb24+CiAgICA8" +
            "dm9pY2U+TGFpbGE8L3ZvaWNlPgogICAgPHZvaWNldmVyc2lvbj4xLjAuMDwvdm9p" +
            "Y2V2ZXJzaW9uPgogICAgPHZvcHZlcnNpb24+MS4wLjU8L3ZvcHZlcnNpb24+CiAg" +
            "ICA8Z2VuZGVyPkZlbWFsZTwvZ2VuZGVyPgogICAgPGFnZT5BZHVsdDwvYWdlPgog" +
            "ICAgPGZlY2ZnPmNmZzM8L2ZlY2ZnPgogICAgPHZvaWNlbWw+bm88L3ZvaWNlbWw+" +
            "CiAgICA8bWxzZXQ+YXJ3LGVuZzwvbWxzZXQ+CiAgICA8ZXh0Y2xjY2ZnPmVuZysq" +
            "PWNsYy9lbmcvbXB0aHJlZXZhZG1sPC9leHRjbGNjZmc+CiAgICA8ZXh0cmFlc2Ns" +
            "YW5nPmVuZzwvZXh0cmFlc2NsYW5nPgogICAgPGRhdGFwYWNrYWdlbmFtZT5hcncv" +
            "bGFpbGEvZW1iZWRkZWQtaGlnaC8xLTAtNTwvZGF0YXBhY2thZ2VuYW1lPgogICAg" +
            "PGZlZGF0YXByZWZpeD48L2ZlZGF0YXByZWZpeD4KICAgIDxmZWV4dGNmZ2RhdGFw" +
            "cmVmaXg+PC9mZWV4dGNmZ2RhdGFwcmVmaXg+CiAgICA8ZmVkYXRhcGFja2FnaW5n" +
            "PmNsYzwvZmVkYXRhcGFja2FnaW5nPgogICAgPGZldm9pY2U+TGFpbGE8L2Zldm9p" +
            "Y2U+CiAgICA8ZnJlcXVlbmN5aHo+MjIwNTA8L2ZyZXF1ZW5jeWh6PgogICAgPHZv" +
            "aWNlbW9kZWw+dm9wMV9zYw=="
    }
}
