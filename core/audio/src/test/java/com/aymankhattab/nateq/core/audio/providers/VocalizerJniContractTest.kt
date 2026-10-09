package com.aymankhattab.nateq.core.audio.providers

import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerSpeechMark
import com.nuance.android.vocalizer.VocalizerVersion
import com.nuance.android.vocalizer.VocalizerVoice
import com.nuance.android.vocalizer.internal.VocalizerFileInfo
import com.nuance.android.vocalizer.internal.VocalizerResourceInfo
import com.nuance.android.vocalizer.internal.VocalizerStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * حارسُ عقد JNI لمحرك Vocalizer (الأمر ع٢٩-1).
 *
 * يمسك أي حقلٍ/طريقةٍ في عقد libNuanceVocalizer.so يُحذف أو يُعاد
 * تسميته مستقبلاً. غيابُ إحداها (مثل mNativeEnginePtr) يُfork
 * انهيار "fid == null in call to SetLongField". التفاصيل كاملة في
 * docs/vocalizer-jni-contract.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocalizerJniContractTest {

    private fun checkFields(
        clazz: Class<*>,
        expected: Map<String, String>
    ) {
        val fields = clazz.declaredFields.associateBy { it.name }
        for ((name, javaType) in expected) {
            val f = fields[name]
            if (f == null) {
                org.junit.Assert.fail(
                    "حقل مفقود من العقد: ${clazz.simpleName}#$name"
                )
                return
            }
            assertEquals(
                "نوع الحقل ${clazz.simpleName}#$name",
                javaType,
                f.type.simpleName
            )
        }
    }

    private fun checkMethods(
        clazz: Class<*>,
        expected: Set<String>
    ) {
        val methods = clazz.declaredMethods.map { it.name }.toSet()
        for (m in expected) {
            assertTrue(
                "طريقة مفقودة من العقد: ${clazz.simpleName}#$m",
                methods.contains(m)
            )
        }
    }

    @Test
    fun engineContractFields() {
        checkFields(VocalizerEngine::class.java, mapOf(
            // الحقل الناقص الذي كان يسبب الانهيار fid==null
            "mNativeEnginePtr" to "long"
        ))
    }

    @Test
    fun engineContractMethods() {
        checkMethods(VocalizerEngine::class.java, setOf(
            // الطريقتان اللتان تستدعيهما C++ من خيط أصلي (instance)
            "openAssetFile",
            "closeAssetFile"
        ))
    }

    @Test
    fun engineNativePointerIsInstanceLong() {
        val f = VocalizerEngine::class.java
            .getDeclaredField("mNativeEnginePtr")
        assertTrue(
            "mNativeEnginePtr يجب ألا يكون static",
            !Modifier.isStatic(f.modifiers)
        )
        assertTrue(
            "mNativeEnginePtr يجب ألا يكون final",
            !Modifier.isFinal(f.modifiers)
        )
    }

    @Test
    fun speechMarkContractFields() {
        checkFields(VocalizerSpeechMark::class.java, mapOf(
            "mMarkType" to "int",
            "mSrcPos" to "int",
            "mSrcTextLen" to "int",
            "mDestPos" to "int",
            "mDestLen" to "int",
            "mPhonemeId" to "int",
            "mMarkId" to "int",
            "mJawOpen" to "int",
            "mTeethUpVisible" to "int",
            "mTeethLoVisible" to "int",
            "mMouthHeight" to "int",
            "mMouthWidth" to "int",
            "mMouthUpturn" to "int",
            "mLipTension" to "int",
            "mTonguePos" to "int",
            // الحقل المفقود كان سيحطم GetFieldID
            "mLHPhoneme" to "String",
            "mAudioData" to "short[]"
        ))
    }

    @Test
    fun versionContract() {
        checkFields(VocalizerVersion::class.java, mapOf(
            "mMajor" to "int",
            "mMinor" to "int",
            "mBuild" to "int",
            "mBuildYear" to "int",
            "mBuildMonth" to "int",
            "mBuildDay" to "int",
            "mBuildInfo" to "String"
        ))
    }

    @Test
    fun voiceContract() {
        checkFields(VocalizerVoice::class.java, mapOf(
            "mVoiceName" to "String",
            "mSampleRate" to "int",
            "mLanguage" to "String",
            "mVoiceOperatingPoint" to "String",
            "mLanguageVersion" to "String",
            "mLanguageTLW" to "String",
            "mVoiceAge" to "String",
            "mVoiceType" to "String",
            "mVoiceVersion" to "String",
            "mLangId" to "int"
        ))
    }

    @Test
    fun fileInfoContract() {
        checkFields(VocalizerFileInfo::class.java, mapOf(
            "descriptor" to "int",
            "startOffset" to "long",
            "length" to "long"
        ))
    }

    @Test
    fun resourceInfoContract() {
        checkFields(VocalizerResourceInfo::class.java, mapOf(
            "mHandle" to "long"
        ))
    }

    @Test
    fun statusInfoContract() {
        checkFields(VocalizerStatusInfo::class.java, mapOf(
            "mHeapBlocks" to "int",
            "mSystemFileHandles" to "int",
            "mAssetFileHandles" to "int"
        ))
    }

    @Test
    fun fileInfoGettersReturnValues() {
        // تعبئة VocalizerFileInfo كما يملؤه الكود الأصلي بعد openAssetFile
        val info = VocalizerFileInfo()
        info.descriptor = 7
        info.startOffset = 4096L
        info.length = 204800L
        assertEquals(7, info.descriptor)
        assertEquals(4096L, info.startOffset)
        assertEquals(204800L, info.length)
    }

    @Test
    fun engineErrorTrackingContractMethods() {
        checkMethods(
            VocalizerEngine::class.java,
            setOf(
                "isNativeCreated",
                "getCreateError",
                "getLastError",
                "getInitError"
            )
        )
    }
}