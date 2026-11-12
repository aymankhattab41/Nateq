package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * فك تشفير بيانات تسجيل حزم Acapela
 * من ملف assets/acapela/package-codec.dat.
 */
internal class AcapelaMetadataCodec(
    private val source: () -> InputStream
) {

    companion object {
        private const val MAGIC = 1297498435
        private const val VERSION = 1
        private const val MAX_ENCODED_LEN = 2048

        fun fromAssets(context: Context? = null): AcapelaMetadataCodec =
            AcapelaMetadataCodec {
                val cl = AcapelaMetadataCodec::class.java.classLoader
                val path1 = "assets/acapela/package-codec.dat"
                val path2 = "acapela/package-codec.dat"
                context?.assets?.open(path2)
                    ?: cl?.getResourceAsStream(path1)
                    ?: cl?.getResourceAsStream(path2)
                    ?: throw IOException("package-codec.dat not found")
            }

        fun trim(s: String?): String = s?.trim().orEmpty()
    }

    private fun key(): ByteArray {
        DataInputStream(source()).use { input ->
            val magic = input.readInt()
            val ver = input.readInt()
            if (magic != MAGIC || ver != VERSION) {
                throw IOException("Unsupported Acapela metadata codec")
            }
            val keyBytes = ByteArray(16)
            input.readFully(keyBytes)
            if (input.read() != -1) {
                Arrays.fill(keyBytes, 0.toByte())
                throw IOException("Invalid Acapela metadata codec")
            }
            return keyBytes
        }
    }

    private fun crypt(data: ByteArray, mode: Int): ByteArray {
        val keyBytes = key()
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"))
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Invalid encoded Acapela package metadata", e)
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    fun decode(encoded: String): String {
        val trimmed = encoded.trim()
        if (trimmed.length > MAX_ENCODED_LEN ||
            !trimmed.matches(Regex("[A-Za-z0-9+/]+={0,2}")) ||
            trimmed.length % 4 != 0
        ) {
            throw IOException("Invalid encoded Acapela package metadata")
        }
        val decoded = try {
            Base64.getDecoder().decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid Base64 in Acapela metadata", e)
        }
        if (decoded.isEmpty() || decoded.size % 16 != 0) {
            throw IOException("Invalid encoded Acapela package metadata")
        }
        val decrypted = crypt(decoded, Cipher.DECRYPT_MODE)
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(decrypted)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("Malformed UTF-8 in Acapela metadata", e)
        } finally {
            Arrays.fill(decrypted, 0.toByte())
        }
    }
}
