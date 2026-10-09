package com.aymankhattab.nateq.core.audio.providers

import com.acapelagroup.android.jni.NativeInterface
import com.acapelagroup.android.tts.AcattsandroidService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارسُ عقد JNI لمحرك Acapela (مولَّد من الرموز الفعلية + التصريحات).
 *
 * يقفل الاسمَ وعددَ الوسائط وأنواعها ونوعَ الإرجاع لكل دالة native، فلا
 * ينحرف عقد الجافا عن رموز libttsacattsandroidextended.so بصمت.
 * مصدرُ الحقيقة: docs/acapela-jni-contract.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaJniContractTest {

    private fun signatures(clazz: Class<*>): Map<String, String> =
        clazz.declaredMethods.associate { m ->
            val params = m.parameterTypes.joinToString(", ") {
                it.simpleName
            }
            m.name to "${m.returnType.simpleName}($params)"
        }

    @Test
    fun acattsandroidServiceDeclaresOnlyTag() {
        val fields = AcattsandroidService::class.java
            .declaredFields.map { it.name }.toSet()
        assertEquals(setOf("TAG"), fields)
    }

    @Test
    fun nativeInterfaceSignaturesAreExact() {
        assertEquals(
            mapOf(
                "nGetPhonetic" to "String(String)",
                "nSetInfo" to "int(String, String)"
            ),
            signatures(NativeInterface::class.java)
        )
    }

    @Test
    fun acattsandroidServiceSignaturesAreExact() {
        assertEquals(
            mapOf(
                "nInit" to "int(String)",
                "nShutdown" to "int()",
                "nIsLanguageAvailable" to "int(String, String, String)",
                "nloadLanguage" to "int(String, String, String)",
                "nSetLanguage" to "int(String, String, String)",
                "nSetCurrentVoice" to "int(String)",
                "nSetPreferedVoice" to "int(String, String, String)",
                "nGetClosestVoice" to "String(String, String, String)",
                "nGetLanguage" to "String[]()",
                "nSetProperty" to "int(String, String)",
                "nSetVoiceMode" to "int(String)",
                "nSpeak" to "int(String, SynthesisCallback)",
                "nStop" to "int()",
                "nStopSync" to "int()",
                "nUtf8Mode" to "int(String, String)",
                "nRefrehEnumeration" to "int()",
                "nGetPhonetic" to "String(String)"
            ),
            signatures(AcattsandroidService::class.java)
        )
    }

    @Test
    fun synthesisCallbackIsAndroidFrameworkType() {
        val export = AcattsandroidService::class.java.declaredMethods
            .first { it.name == "nSpeak" }
        assertEquals(
            "android.speech.tts.SynthesisCallback",
            export.parameterTypes[1].name
        )
        assertTrue(
            "لا فئة داخلية SynthesisCallback",
            AcattsandroidService::class.java.declaredClasses.isEmpty()
        )
    }
}
