package com.aymankhattab.nateq.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun singleTag_zeroRelease_equivalence() {
        // خلال مرحلة 0.x: الوسم الأحادي «v6» يعني الإصدار 0.6.0 — ليس تحديثاً.
        assertFalse(UpdateChecker.isNewerVersion("v6", "0.6.0"))
        assertFalse(UpdateChecker.isNewerVersion("0.6.0", "v6"))
        // الوسم الأحادي الأرقى في مرحلة الصفر «v7» أحدث من «0.6.0».
        assertTrue(UpdateChecker.isNewerVersion("v7", "0.6.0"))
        assertFalse(UpdateChecker.isNewerVersion("v6", "0.7.0"))
        // خارج مرحلة الصفر تبقى المقارنة SemVer القياسية بلا أي تسوية.
        assertFalse(UpdateChecker.isNewerVersion("v6", "6.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v7", "6.0.0"))
    }

    /** بند 7.3: بصمة النشر من ملاحظات الإصدار (سطر SHA-256 أو SHA256SUMS). */
    @Test
    fun sha256_extractedFromReleaseNoteLine() {
        val hex =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "الإصدار 0.7.0\n$hex  lord_tts.apk"
            )
        )
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "بعض الملاحظات\nSHA-256: $hex\nبعدها"
            )
        )
        // إصدار كبير من أحرف البصمة؟ لا تُقبَل.
        // مقطع سداسي أقصر من 64 حرفاً أو نص بلا بصمة: لا تُقبَل.
        assertNull(UpdateChecker.extractSha256FromReleaseNote("SHA-256: 0123"))
        assertNull(UpdateChecker.extractSha256FromReleaseNote(null))
        assertNull(UpdateChecker.extractSha256FromReleaseNote("بلا بصمة هنا"))
    }
}