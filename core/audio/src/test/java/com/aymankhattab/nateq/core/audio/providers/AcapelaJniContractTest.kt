package com.aymankhattab.nateq.core.audio.providers

import com.acapelagroup.android.tts.acattsandroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * حارس عقد JNI لمحرك Acapela.
 *
 * يمسك أي انحراف في توقيعات دوال JNI المصدرة من
 * libacattsandroid.so، وبخاصة دالّة الرخصة
 * [acattsandroid.nSetLicense] التي تتطلب ثلاثة
 * معاملات (userId: Long, password: Long, license: String)
 * بدلاً من معامل واحد.
 */
class AcapelaJniContractTest {

    @Test
    fun nSetLicense_hasThreeParameters() {
        val method = acattsandroid::class.java.getDeclaredMethod(
            "nSetLicense",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        )
        assertNotNull(
            "دالّة nSetLicense بالمعاملات الثلاثة موجودة",
            method
        )
        assertTrue(
            "دالّة nSetLicense أصلية (native)",
            Modifier.isNative(method.modifiers)
        )
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun nSetTTSSetting_takesStringAndInt() {
        val method = acattsandroid::class.java.getDeclaredMethod(
            "nSetTTSSetting",
            String::class.java,
            Int::class.javaPrimitiveType
        )
        assertNotNull(
            "دالّة nSetTTSSetting بـ (String, int) موجودة",
            method
        )
        assertTrue(Modifier.isNative(method.modifiers))
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun licenseCredentials_areValidConstants() {
        assertEquals(0x46515867L, AcapelaLicense.USER_ID)
        assertEquals(0x0030ab63L, AcapelaLicense.PASSWORD)
        assertTrue(AcapelaLicense.LICENSE.contains("COMMERCIAL#VOICE DREAM"))
    }

    @Test
    fun callbacks_haveExactSignatures() {
        val samplesCb = acattsandroid::class.java.getDeclaredMethod(
            "samplesCallback",
            ShortArray::class.java,
            Long::class.javaPrimitiveType
        )
        assertNotNull(samplesCb)

        val eventsCb = acattsandroid::class.java.getDeclaredMethod(
            "eventsCallback",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )
        assertNotNull(eventsCb)
    }

    @Test
    fun acattsandroidService_idfunctionReturnsRegistration() {
        val regStr = "test@domain.com1234567890abcdef"
        val srv = com.acapelagroup.android.tts.AcattsandroidService(
            null,
            regStr
        )
        assertEquals(regStr, srv.idfunction())
    }

    @Test
    fun acattsandroidService_nativeMethodsMatchSignatures() {
        val cls = com.acapelagroup.android.tts.AcattsandroidService::class.java
        val nInit = cls.getDeclaredMethod("nInit", String::class.java)
        assertTrue(Modifier.isNative(nInit.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nInit.returnType)

        val nSetVoiceMode = cls.getDeclaredMethod(
            "nSetVoiceMode",
            String::class.java
        )
        assertTrue(Modifier.isNative(nSetVoiceMode.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nSetVoiceMode.returnType)

        val nSetLanguage = cls.getDeclaredMethod(
            "nSetLanguage",
            String::class.java,
            String::class.java,
            String::class.java
        )
        assertTrue(Modifier.isNative(nSetLanguage.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nSetLanguage.returnType)

        val nSetCurrentVoice = cls.getDeclaredMethod(
            "nSetCurrentVoice",
            String::class.java
        )
        assertTrue(Modifier.isNative(nSetCurrentVoice.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nSetCurrentVoice.returnType)

        val nSetProperty = cls.getDeclaredMethod(
            "nSetProperty",
            String::class.java,
            String::class.java
        )
        assertTrue(Modifier.isNative(nSetProperty.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nSetProperty.returnType)

        val nSetPreferedVoice = cls.getDeclaredMethod(
            "nSetPreferedVoice",
            String::class.java,
            String::class.java,
            String::class.java
        )
        assertTrue(Modifier.isNative(nSetPreferedVoice.modifiers))
        assertEquals(
            Int::class.javaPrimitiveType,
            nSetPreferedVoice.returnType
        )

        val nRefrehEnumeration = cls.getDeclaredMethod(
            "nRefrehEnumeration"
        )
        assertTrue(Modifier.isNative(nRefrehEnumeration.modifiers))
        assertEquals(
            Int::class.javaPrimitiveType,
            nRefrehEnumeration.returnType
        )

        val nloadLanguage = cls.getDeclaredMethod(
            "nloadLanguage",
            String::class.java,
            String::class.java,
            String::class.java
        )
        assertTrue(Modifier.isNative(nloadLanguage.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nloadLanguage.returnType)
    }
}
