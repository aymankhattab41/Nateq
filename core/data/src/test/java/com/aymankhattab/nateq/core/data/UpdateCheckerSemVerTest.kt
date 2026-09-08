package com.aymankhattab.nateq.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * منطق مقارنة SemVer في [UpdateChecker.isNewerVersion] — اختبار JVM نقي
 * بلا Robolectric (لا يلمس أي Android API). يثبّت أن «v0.4.1» أُحدث من
 * «0.4.0» مكوّنٌ مكوّن، وأن الغثّ من الوسوم لا يُعدّ تحديثاً أبداً.
 */
class UpdateCheckerSemVerTest {

    @Test
    fun remoteNewer_detectedAcrossAllSegments() {
        assertTrue(UpdateChecker.isNewerVersion("v0.4.1", "0.4.0"))
        assertTrue(UpdateChecker.isNewerVersion("0.5.0", "v0.4.9"))
        assertTrue(UpdateChecker.isNewerVersion("V1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("0.4.1", "0.4"))
    }

    @Test
    fun sameOrOlder_notNewer() {
        assertFalse(UpdateChecker.isNewerVersion("0.4.0", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("v0.4.0", "0.4.1"))
        assertFalse(UpdateChecker.isNewerVersion("0.4", "0.4.1"))
        assertFalse(UpdateChecker.isNewerVersion("0.4.0", "0.5.0"))
    }

    @Test
    fun leadingV_strippedCaseInsensitively() {
        assertTrue(UpdateChecker.isNewerVersion("v0.4.1", "0.4.0"))
        assertTrue(UpdateChecker.isNewerVersion("V0.4.1", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("v0.4.0", "0.4.1"))
    }

    @Test
    fun garbageOrBlankTag_neverNewer() {
        assertFalse(UpdateChecker.isNewerVersion("latest", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("release-candidate", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("  ", "0.4.0"))
    }
}