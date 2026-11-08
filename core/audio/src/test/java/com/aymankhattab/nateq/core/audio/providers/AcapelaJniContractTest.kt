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
}
