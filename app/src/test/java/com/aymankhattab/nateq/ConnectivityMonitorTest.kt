package com.aymankhattab.nateq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
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

    /** تجعل شبكة Wi-Fi نشطة بمواصفات [caps] (netId=1 == TYPE_WIFI). */
    private fun setActiveWifi(caps: NetworkCapabilities) {
        // عبر مصنع الظل لا المُنشئ المباشر (ثابتات NetworkInfo أعطّلت في المحاكاة).
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        shadowOf(cm).setActiveNetworkInfo(info)
        shadowOf(cm).setDefaultNetworkActive(true)
        shadowOf(cm).addNetwork(network, info)
        shadowOf(cm).setNetworkCapabilities(network, caps)
    }

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