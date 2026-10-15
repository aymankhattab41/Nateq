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
        // الحالة الفعلية للنشر الجاري: التطبيق المثبَّت يعرض 0.36.0 بينما
        // وسم GitHub v37 = 0.37.0 — لابد أن يُعدّ تحديثاً متاحاً.
        assertTrue(UpdateChecker.isNewerVersion("v37", "0.36.0"))
        // خارج مرحلة الصفر تبقى المقارنة SemVer القياسية بلا أي تسوية.
        assertFalse(UpdateChecker.isNewerVersion("v6", "6.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v7", "6.0.0"))
    }

    /** حدّ الانتقال من مرحلة 0.x إلى أول SemVer حقيقي «1.6.1» (بداية
     *  ترقيم `scripts/release.ps1` بعد وسم «0.106.0»): كل مستخدم على
     *  «0.106.0» يجب أن يُعرض عليه التحديث، ثم تكمل المقارنة بزيادة
     *  Patch وحده. الحالة حرجة عند القفزة من 0.x إلى 1.x تحديداً لأن
     *  أرقام النسخة تتراجع ظاهرياً (106 → 6) فيلوهم المقارن الساذج
     *  بأن 1.6.1 أقدم من 0.106.0. */
    @Test
    fun zeroToSemVer_boundary_firstOneIsNewer() {
        assertTrue(UpdateChecker.isNewerVersion("1.6.1", "0.106.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.6.1", "0.106.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.6.1", "1.6.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.6.2", "1.6.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.6.1", "1.6.2"))
    }

    /**
     * السيناريو الحالي: التطبيق على 1.6.29
     * والإصدار الأخير على GitHub هو v1.6.29
     * — يجب أن يُعدّ مطابِقاً (ليس تحديثاً).
     */
    @Test
    fun currentRelease_notNewerWhenEqual() {
        assertFalse(UpdateChecker.isNewerVersion("v1.6.29", "1.6.29"))
        assertFalse(UpdateChecker.isNewerVersion("1.6.29", "v1.6.29"))
        assertFalse(UpdateChecker.isNewerVersion("v1.6.29", "v1.6.29"))
    }

    /** بند المرفق المتغيّر: النسخة المثبّتة التي تبحث عن اسمٍ سابق
     *  لا ترى مرفقاً باسم `nateq.apk` فتُعلن «محدّثاً» وهمياً — الاختيار
     *  يجب أن يلتقط أي مرفق `.apk` مع أفضلية الاسم المتوقع. */
    @Test
    fun apkAsset_picksExpectedNameFirst_thenAnyApk() {
        val names: List<String> = listOf(
            "nateq.apk", "legacy_app.apk", "notes.txt"
        )
        val picked: String? = UpdateChecker.pickApkAsset(names) { it }
        assertEquals("nateq.apk", picked)
        // النسخة القديمة: لا `nateq.apk` في المرفقات — يلتقط legacy_app.apk.
        val oldRelease: List<String> = listOf("legacy_app.apk", "sha256.txt")
        val pickedOld: String? =
            UpdateChecker.pickApkAsset(oldRelease) { it }
        assertEquals("legacy_app.apk", pickedOld)
        // بلا أي مرفق APK: لا تحديث قابل للتنزيل.
        val noApk: List<String> = listOf("readme.md")
        assertNull(UpdateChecker.pickApkAsset(noApk) { it })
        assertNull(
            UpdateChecker.pickApkAsset(emptyList<String>()) { it }
        )
        // الأحرف الكبيرة في الامتداد مقبولة أيضاً.
        val upper: List<String> = listOf("MyApp.APK")
        val pickedUpper: String? =
            UpdateChecker.pickApkAsset(upper) { it }
        assertEquals("MyApp.APK", pickedUpper)
    }

    /** الأرفاق صارت مستقلة لكل معمارية (`nateq-<abi>.apk`): يُختار مرفق
     *  معمارية الجهاز أولاً فلا يُنزَّل مرفق 32-بت فوق جهاز 64. */
    @Test
    fun pickApkAsset_prefersDeviceAbiOverListingOrder() {
        val names: List<String> = listOf(
            "nateq-armeabi-v7a.apk",
            "nateq-arm64-v8a.apk",
            "notes.txt"
        )
        // جهاز 64-بت: arm64 حتى لو بُرمج arm أولاً في أرفاق GitHub.
        assertEquals(
            "nateq-arm64-v8a.apk",
            UpdateChecker.pickApkAsset(
                names,
                listOf("nateq-arm64-v8a.apk", "nateq-armeabi-v7a.apk")
            ) { it }
        )
        // جهاز 32-بت فقط: لا يختار arm64.
        assertEquals(
            "nateq-armeabi-v7a.apk",
            UpdateChecker.pickApkAsset(
                names, listOf("nateq-armeabi-v7a.apk")
            ) { it }
        )
        // معمارية الجهاز غير موجودة في الأرفاق → يسقط إلى nateq.apk.
        val fallback: List<String> = listOf("nateq.apk", "legacy_app.apk")
        assertEquals(
            "nateq.apk",
            UpdateChecker.pickApkAsset(
                fallback, listOf("nateq-arm64-v8a.apk")
            ) { it }
        )
    }

    /** مشتقة أسماء الأرفاق من معماريات النظام: `armeabi` تنسدل إلى
     *  `armeabi-v7a`، والتكرار يُزال، وغير المدعوم يُتجاهل. */
    @Test
    fun deviceApkCandidates_mapsAndDedupesAbis() {
        assertEquals(
            listOf("nateq-arm64-v8a.apk", "nateq-armeabi-v7a.apk"),
            UpdateChecker.deviceApkCandidates(
                listOf("arm64-v8a", "armeabi-v7a")
            )
        )
        assertEquals(
            listOf("nateq-armeabi-v7a.apk"),
            UpdateChecker.deviceApkCandidates(
                listOf("armeabi", "armeabi-v7a")
            )
        )
        assertEquals(
            listOf("nateq-arm64-v8a.apk"),
            UpdateChecker.deviceApkCandidates(
                listOf("x86_64", "arm64-v8a")
            )
        )
    }

    /** بند 7.3: بصمة النشر من ملاحظات الإصدار (سطر SHA-256 أو SHA256SUMS). */
    @Test
    fun sha256_extractedFromReleaseNoteLine() {
        val hex =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "الإصدار 0.7.0\n$hex  nateq.apk"
            )
        )
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "بعض الملاحظات\nSHA-256: $hex\nبعدها"
            )
        )
        // دعم صياغة Markdown بعلامات اقتباس كود سفلية (backticks)
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "### المجموع الاختباري SHA-256\n`$hex`  nateq.apk"
            )
        )
        // إصدار كبير من أحرف البصمة؟ لا تُقبَل.
        // مقطع سداسي أقصر من 64 حرفاً أو نص بلا بصمة: لا تُقبَل.
        assertNull(UpdateChecker.extractSha256FromReleaseNote("SHA-256: 0123"))
        assertNull(UpdateChecker.extractSha256FromReleaseNote(null))
        assertNull(UpdateChecker.extractSha256FromReleaseNote("بلا بصمة هنا"))
        // بند 3.9: بصمةٌ غير مقترنة بصريح الـ nateq.apk ولا سطر SHA-256
        // (كملف مصدر أو mapping أو سطر عارٍ) لا تُقبَل — كانت البادئة
        // الاختيارية تلتقط أي 64 خانة سداسية فتُفشل التحقق.
        val bare = "9f86d081884c7d659a2feaa0c55ad015a" +
            "3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertNull(UpdateChecker.extractSha256FromReleaseNote(bare))
        assertNull(
            UpdateChecker.extractSha256FromReleaseNote(
                "$bare  source.tar.gz"
            )
        )
        assertNull(
            UpdateChecker.extractSha256FromReleaseNote(
                "$bare  mapping.txt"
            )
        )
    }

    /** بصمة المرفق لكل معمارية (`nateq-<abi>.apk`): يُقبَل الاسم المختار
     *  بالضبط لا اسم معمارية أخرى. */
    @Test
    fun sha256_extractedForSelectedAbiApkName() {
        val hex =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(
            hex,
            UpdateChecker.extractSha256FromReleaseNote(
                "### المجموع الاختباري SHA-256\n`$hex`  " +
                    "nateq-arm64-v8a.apk",
                "nateq-arm64-v8a.apk"
            )
        )
        // بصمة معمارية أخرى لا تُقبَل للهدف المطلوب (arm64).
        assertNull(
            UpdateChecker.extractSha256FromReleaseNote(
                "`$hex`  nateq-armeabi-v7a.apk",
                "nateq-arm64-v8a.apk"
            )
        )
    }
}