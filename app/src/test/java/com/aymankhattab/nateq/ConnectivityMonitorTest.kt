package com.aymankhattab.nateq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.ConnectivityMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * اختبارات [ConnectivityMonitor] (استشعار الاتصال الاستباقي) عبر Robolectric:
 * لا إنترنت عند غياب شبكة محقّقة ([NET_CAPABILITY_VALIDATED])، وإنترنت حقيقي
 * عند وجود شبكة INTERNET + VALIDATED — وهو الأساس الذي يتراجع بموجبه المزوّد
 * فوراً للأصوات المحلية دون انتظار مهلة التوليد كاملة.
 *
 * ملاحظة: الـ [ConnectivityManager.activeNetwork] في محاكاة Robolectric يعود
 * فقط إذا كانت الشبكة «نشطة افتراضياً» ([setDefaultNetworkActive]) مع معلومات
 * شبكة علّقتها ([setActiveNetworkInfo]) بشبكة netId يوازي نوعها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectivityMonitorTest {

    private lateinit var context: Context
    private lateinit var cm: ConnectivityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // بداية نظيفة: لا شبكة ولا `netId→capabilities` باقية.
        shadowOf(cm).clearAllNetworks()
        shadowOf(cm).setActiveNetworkInfo(null)
        shadowOf(cm).setDefaultNetworkActive(false)
    }

    /**
     * تجعل شبكة Wi-Fi (netId=1 == TYPE_WIFI) نشطة بمواصفات [caps].
     *
     * ملاحظة: [ConnectivityManager.activeNetwork] في محاكاة Robolectric 4.14 لا
     * يعود إلا بشبكة «نشطة افتراضياً» ([setDefaultNetworkActive]) مع معلومات
     * شبكة معلّقتها ([setActiveNetworkInfo]) لشبكة netId يوازي نوعها — والبناء الوحيد
     * لصنع NetworkInfo في الظل يعتمد فئات/ثوابت مهملة منذ API 29، لذا يُحتوى كتم
     * التحذير في دالة واحدة موثقة ([activeWifiInfo]) بدل تكراره في كل اختبار.
     */
    private fun setActiveWifi(caps: NetworkCapabilities) {
        val info = activeWifiInfo()
        val network = ShadowNetwork.newInstance(1) // netId=1 == TYPE_WIFI — مفتاح الشبكة النشطة عند الظل
        shadowOf(cm).setActiveNetworkInfo(info)
        shadowOf(cm).setDefaultNetworkActive(true)
        shadowOf(cm).addNetwork(network, info)
        shadowOf(cm).setNetworkCapabilities(network, caps)
    }

    /** بنّاء الـ NetworkInfo المهمل — تطلبه محاكاة الظل حصراً لتعريف الشبكة النشطة. */
    @Suppress("DEPRECATION")
    private fun activeWifiInfo(): android.net.NetworkInfo =
        ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            android.net.NetworkInfo.State.CONNECTED
        )

    /**
     * نبني [NetworkCapabilities] بإضافة القدرات عبر ظلّها — مُعدِّلات
     * `addCapability/addTransportType` خفيّة في الواجهة العامة لـ API 35/37.
     */
    private fun capsWithCapabilities(vararg caps: Int): NetworkCapabilities {
        val nc = NetworkCapabilities()
        for (c in caps) shadowOf(nc).addCapability(c)
        return nc
    }

    @Test
    fun offline_whenNoActiveNetwork() {
        // لا شبكة معلّقة (وضع الطيران/انقطاع تام) → بلا إنترنت
        assertFalse(ConnectivityMonitor(context).isOnlineNow())
    }

    @Test
    fun online_whenValidatedInternetNetworkExists() {
        val caps = capsWithCapabilities(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
        setActiveWifi(caps)
        assertTrue(ConnectivityMonitor(context).isOnlineNow())
    }

    @Test
    fun offline_whenNetworkNotValidated() {
        // شبكة تحمل INTERNET لكنها غير محقّقة الوصول (لا VALIDATED): بلا إنترنت حقيقي
        setActiveWifi(
            capsWithCapabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
        assertFalse(ConnectivityMonitor(context).isOnlineNow())
    }

    @Test
    fun offline_whenOnlyWifiWithoutInternetCapability() {
        // شبكة نشطة بلا ميزة INTERNET أصلاً (Wi-Fi بلا إنترنت حقيقي)
        setActiveWifi(NetworkCapabilities())
        assertFalse(ConnectivityMonitor(context).isOnlineNow())
    }

    @Test
    fun offline_afterNetworkLost() {
        val caps = capsWithCapabilities(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
        setActiveWifi(caps)
        assertTrue(ConnectivityMonitor(context).isOnlineNow())

        // انقطاع: تُزال كل الشبكات ويُفصل النشاط → بلا إنترنت لحظياً
        shadowOf(cm).clearAllNetworks()
        shadowOf(cm).setActiveNetworkInfo(null)
        shadowOf(cm).setDefaultNetworkActive(false)
        assertFalse(ConnectivityMonitor(context).isOnlineNow())
    }
}