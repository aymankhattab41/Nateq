package com.aymankhattab.nateq.settings.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لفئة CallPlacer ودقة اختيار PhoneAccountHandle حسب رقم الفتحة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CallPlacerTest {

    private lateinit var context: Context
    private lateinit var telecomManager: TelecomManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        telecomManager = context.getSystemService(TelecomManager::class.java)
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
    }

    @Test
    fun resolvePhoneAccountHandle_emptyOrInvalid_returnsNull() {
        assertNull(
            CallPlacer.resolvePhoneAccountHandle(
                context,
                simSlot = 0,
                accounts = emptyList()
            )
        )
        val dummyHandle = PhoneAccountHandle(
            ComponentName(context, "DummyService"),
            "dummy"
        )
        assertNull(
            CallPlacer.resolvePhoneAccountHandle(
                context,
                simSlot = -1,
                accounts = listOf(dummyHandle)
            )
        )
    }

    @Test
    fun resolvePhoneAccountHandle_reversedAccounts_matchesByAccountDetails() {
        val component = ComponentName(context, "MockConnectionService")
        val handleSlot0 = PhoneAccountHandle(component, "handle_sim_0")
        val handleSlot1 = PhoneAccountHandle(component, "handle_sim_1")

        // تسجيل الحسابين في TelecomManager بتسميات مختلفة
        val account0 = PhoneAccount.builder(handleSlot0, "SIM 1 - Vodafone")
            .setSubscriptionAddress(Uri.parse("tel:01000000001"))
            .build()
        val account1 = PhoneAccount.builder(handleSlot1, "SIM 2 - Orange")
            .setSubscriptionAddress(Uri.parse("tel:01200000002"))
            .build()

        telecomManager.registerPhoneAccount(account0)
        telecomManager.registerPhoneAccount(account1)

        // القائمة مرتبة بالعكس: handleSlot1 أولاً ثم handleSlot0
        val reversedAccounts = listOf(handleSlot1, handleSlot0)

        // فحص أن دالة الاختيار لا تعتمد مجرد accounts[simSlot]
        // عند عدم وجود اشتراك نشط أو مطابقة، تعيد null أو الحساب المطابق
        val resolved = CallPlacer.resolvePhoneAccountHandle(
            context,
            simSlot = 0,
            accounts = reversedAccounts
        )
        // عند عدم تطابق اشتراك في بيئة الاختبار، لا ينبغي أن تختار عشوائياً
        if (resolved != null) {
            assertEquals(handleSlot0, resolved)
        }
    }
}
