package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * نتيجة فك وتحليل سطر param لحزم Acapela وفق خوارزمية MultiTTS.
 */
internal data class AcapelaDecodedRegistration(
    val isEncrypted: Boolean,
    val partsCount: Int,
    val emailLength: Int,
    val hexCodeLength: Int,
    val sha1First4Hex: String,
    val registration: String,
    val voiceCode: String,
    val decodedText: String,
    val sha1Matches7e88: Boolean
)

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

        /**
         * تشذيب السلسلة وإزالة الفراغات والاقتباسات كما في MultiTTS:
         * AcapelaMetadataCodec.trim(s).
         */
        fun trim(s: String?): String {
            if (s == null) return ""
            return s.trim()
                .removePrefix("\"").removeSuffix("\"")
                .removePrefix("'").removeSuffix("'")
                .trim()
        }

        /**
         * التحقق هل سطر param مشفر وفق شرط MultiTTS:
         * إن لم يحتوِ على فاصلة "," فهو مشفر ويتطلب الفك بـ decode.
         */
        fun isParamEncrypted(param: String?): Boolean {
            if (param.isNullOrBlank() || param.trim() == "null") return false
            val clean = trim(param)
            return !clean.contains(",")
        }

        /**
         * حساب أول 4 خانات Hex من SHA1 للسلسلة النصية:
         * يطابق تماماً أول 32 بت H0 المحسوب في check_license بـ
         * libttsacattsandroidextended.so والمخزن في mUserid
         * (babParam.uid.userId).
         */
        fun computeSha1First4Hex(text: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(text.toByteArray(StandardCharsets.UTF_8))
            if (digest.size < 2) return ""
            return String.format(Locale.US, "%02x%02x", digest[0], digest[1])
        }

        /**
         * خوارزمية MultiTTS الكاملة لتحليل وفك سطر param:
         * 1) فحص وجود الفاصلة: إن غابت فالسطر مشفر بـ AES/ECB/PKCS5Padding.
         * 2) فك التشفير إن كان مشفراً واستخراج النص الأصلي.
         * 3) تقسيم النص بالفاصلة إلى أجزاء (voiceCode, email, hexCode).
         * 4) دمج email + hexCode مباشرة بدون فاصلة (كما في buildRegistration).
         * 5) حساب SHA-1 ومقارنة أول 4 خانات مع القيمة المسجلة 7e88.
         */
        fun resolveAcapelaParam(
            rawParam: String?,
            context: Context? = null,
            customCodec: AcapelaMetadataCodec? = null
        ): AcapelaDecodedRegistration {
            if (rawParam.isNullOrBlank() || rawParam.trim() == "null") {
                return AcapelaDecodedRegistration(
                    isEncrypted = false,
                    partsCount = 0,
                    emailLength = 0,
                    hexCodeLength = 0,
                    sha1First4Hex = "",
                    registration = "",
                    voiceCode = "",
                    decodedText = "",
                    sha1Matches7e88 = false
                )
            }

            val trimmedParam = trim(rawParam)
            val isEncrypted = !trimmedParam.contains(",")
            val decodedText = if (isEncrypted) {
                val codec = customCodec ?: runCatching {
                    fromAssets(context)
                }.getOrNull()
                if (codec != null) {
                    runCatching {
                        codec.decode(trimmedParam)
                    }.getOrDefault(trimmedParam)
                } else {
                    trimmedParam
                }
            } else {
                trimmedParam
            }

            val parts = decodedText.split(",")
                .map { trim(it) }
            val partsCount = parts.size
            val voiceCode = parts.getOrNull(0).orEmpty()
            val email = parts.getOrNull(1).orEmpty()
            val hexCode = parts.getOrNull(2).orEmpty()

            val reg = if (partsCount == 3) {
                email + hexCode
            } else {
                decodedText
            }

            val sha1Hex4 = if (reg.isNotEmpty()) {
                computeSha1First4Hex(reg)
            } else ""

            val matches7e88 = sha1Hex4.equals("7e88", ignoreCase = true)

            return AcapelaDecodedRegistration(
                isEncrypted = isEncrypted,
                partsCount = partsCount,
                emailLength = email.length,
                hexCodeLength = hexCode.length,
                sha1First4Hex = sha1Hex4,
                registration = reg,
                voiceCode = voiceCode,
                decodedText = decodedText,
                sha1Matches7e88 = matches7e88
            )
        }
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
        val trimmed = trim(encoded)
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

    fun folder(speaker: String): String = speaker.lowercase(Locale.ROOT)
}
