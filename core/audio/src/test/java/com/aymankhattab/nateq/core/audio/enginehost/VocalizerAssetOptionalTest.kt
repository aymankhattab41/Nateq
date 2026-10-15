package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس عقد القاموس: ملفات userdct_* اختيارية فلا يُبَلَّغ عنها خطأً.
 */
class VocalizerAssetOptionalTest {

    @Test
    fun userDictionaryPaths_areOptional() {
        assertTrue(
            VocalizerDataProvider.isOptionalUserDictionary("userdct/arw")
        )
        assertTrue(
            VocalizerDataProvider.isOptionalUserDictionary("userdct_arw.xmf")
        )
        assertTrue(
            VocalizerDataProvider.isOptionalUserDictionary("userdct/enu/ava")
        )
    }

    @Test
    fun coreAssets_areNotOptional() {
        assertFalse(
            VocalizerDataProvider.isOptionalUserDictionary("sysdct.xmf")
        )
        assertFalse(
            VocalizerDataProvider.isOptionalUserDictionary("clm.xmf")
        )
        assertFalse(
            VocalizerDataProvider.isOptionalUserDictionary(
                "vocalizer/lid.xmf"
            )
        )
        assertFalse(
            VocalizerDataProvider.isOptionalUserDictionary(null)
        )
        assertFalse(
            VocalizerDataProvider.isOptionalUserDictionary("   ")
        )
    }
}
