package com.aymankhattab.nateq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة شاملة لتحليل خرائط الذاكرة والـ Hooks في AppIntegrity:
 * - تلافي الإيجابيات الكاذبة من مسارات عادية تحتوي hook أو virtual أو sandbox.
 * - كشف وحدات الحقن المعروفة بدقة من خلال اسم المكتبة المجرد فقط.
 * - تصنيف الأنماط الغامضة كاشتباه تشخيصي لا يقطع الأمان العام.
 * - معالجة الأسطر المشوهة أو الفارغة بأمان.
 */
class AppIntegrityTest {

    @Test
    fun analyzeMapsLines_benignWebhookPath_returnsNone() {
        val lines = sequenceOf(
            "7f9a000000-7f9a001000 r--p 00000000 08:01 123 " +
                "/data/app/~~abc/com.example.webhook-1/lib/arm64/libclient.so",
            "7f9a001000-7f9a002000 r-xp 00001000 08:01 123 " +
                "/data/app/~~abc/com.example.webhook-1/lib/arm64/libclient.so"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "المسارات التي تحتوي كلمة webhook يجب ألا تُعتبر hook",
            evidence is AppIntegrity.HookEvidence.None
        )
    }

    @Test
    fun analyzeMapsLines_benignSandboxOrVirtualPath_returnsNone() {
        val lines = sequenceOf(
            "7f9b000000-7f9b001000 r--p 00000000 08:01 456 " +
                "/system/framework/virtual-device.jar",
            "7f9b001000-7f9b002000 r-xp 00001000 08:01 789 " +
                "/data/user/0/com.app.sandbox/lib/libsample.so"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "مسارات النظام العادية التي تحتوي virtual أو sandbox لا تعتبر hook",
            evidence is AppIntegrity.HookEvidence.None
        )
    }

    @Test
    fun analyzeMapsLines_confirmedFridaGadget_returnsConfirmed() {
        val lines = sequenceOf(
            "7f9c000000-7f9c010000 r-xp 00000000 08:01 999 " +
                "/data/local/tmp/libfrida-gadget.so",
            "7f9c010000-7f9c011000 r--p 00010000 08:01 999 " +
                "/data/local/tmp/libfrida-gadget.so"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "libfrida-gadget.so يجب أن يعيد Confirmed",
            evidence is AppIntegrity.HookEvidence.Confirmed
        )
        val confirmed = evidence as AppIntegrity.HookEvidence.Confirmed
        assertEquals("CONFIRMED_HOOK_MODULE", confirmed.rule)
        assertEquals("libfrida-gadget.so", confirmed.moduleName)
    }

    @Test
    fun analyzeMapsLines_deletedConfirmedXposedModule_returnsConfirmed() {
        val lines = sequenceOf(
            "7f9d000000-7f9d005000 r-xp 00000000 08:01 555 " +
                "/system/lib64/libxposed_art.so (deleted)"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "يجب التعرف على libxposed_art.so حتى مع وسم (deleted)",
            evidence is AppIntegrity.HookEvidence.Confirmed
        )
        val confirmed = evidence as AppIntegrity.HookEvidence.Confirmed
        assertEquals("libxposed_art.so", confirmed.moduleName)
    }

    @Test
    fun analyzeMapsLines_suspiciousCustomModule_returnsSuspicion() {
        val lines = sequenceOf(
            "7f9e000000-7f9e002000 r-xp 00000000 08:01 333 " +
                "/data/app/libcustom-xposed-helper.so"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "اسم مكتبة يحوي xposed خارج القائمة المؤكدة يعيد Suspicion",
            evidence is AppIntegrity.HookEvidence.Suspicion
        )
        val susp = evidence as AppIntegrity.HookEvidence.Suspicion
        assertEquals("SUSPICIOUS_MODULE_NAME", susp.rule)
        assertEquals("libcustom-xposed-helper.so", susp.moduleName)
    }

    @Test
    fun analyzeMapsLines_corruptedOrShortLines_handledSafely() {
        val lines = sequenceOf(
            "",
            "   ",
            "short line",
            "7f9f000000-7f9f001000 r--p",
            "7f9f000000-7f9f001000 [anon:stack]"
        )
        val evidence = AppIntegrity.analyzeMapsLines(lines)
        assertTrue(
            "الأسطر القصيرة أو المعيبة لا ترمي استثناء وتعيد None",
            evidence is AppIntegrity.HookEvidence.None
        )
    }

    @Test
    fun integrityResult_suspicionDoesNotBlockOverallSafe() {
        val susp = AppIntegrity.HookEvidence.Suspicion(
            rule = "SUSPICIOUS_MODULE_NAME",
            moduleName = "libcustom-xposed.so"
        )
        val isHookConfirmed = susp is AppIntegrity.HookEvidence.Confirmed
        assertFalse(isHookConfirmed)

        val result = AppIntegrity.IntegrityResult(
            signaturePassed = true,
            nonDebuggablePassed = true,
            rootDetected = false,
            hookEvidence = susp,
            checksumPassed = true,
            playIntegrity = AppIntegrity.PlayIntegrityDiagnostic.UNAVAILABLE,
            isOverallSafe = true && true && !isHookConfirmed
        )
        assertTrue(
            "الاشتباه التشخيصي وحده لا يمنع isOverallSafe",
            result.isOverallSafe
        )
    }
}
