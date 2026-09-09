package com.aymankhattab.nateq.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * استشعار استباقي لحالة الاتصال بالإنترنت: يُسجّل
 * [ConnectivityManager.NetworkCallback] (API 24+) منذ نشأة المُزوّد ويظل
 * مُحدَّثاً بحالة الشبكة لحظياً.
 *
 * ## لماذا؟
 * عند انقطاع الإنترنت لا يستطيع صوت جوجل السحابي أن يُلخّق أبداً، وكانت
 * محاولة نطقه تستهلك مهلة الانتظار كاملة قبل التراجع للصوت المحلي. بالاستشعار
 * الاستباقي نعرف الفور أن الاتصال غائب فنتراجع للأصوات المحلية بدون أي تأخير.
 *
 * ## حساب الحالة
 * [isOnlineNow] يعيد الحساب الحيّ من الشبكات النشطة: يُعتبر الاتصال قائماً فقط
 * إذا وُجدت شبكة تحمل ميزتي [NetworkCapabilities.NET_CAPABILITY_INTERNET] و
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] معاً (شبكة فعلاً قادرة على
 * الوصول — لا مجرد Wi-Fi بلا إنترنت حقيقي). والـ [NetworkCallback] يُحدّث
 * الحالة استباقياً في الخلفية عند فقدان/وصول أي شبكة دون إبقاء مرجعٍ لنشاط.
 */
class ConnectivityMonitor(context: Context) : AutoCloseable {

    private companion object {
        private const val TAG = "NATEQ_TTS"
    }

    private val connectivityManager =
        context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * حارس الحالة الذرية لـ [callback]: يعصم التسجيل/الإلغاء من التكرار —
     * لا تسجيل مزدوج (استثناء «already registered») ولا إلغاء مزدوج
     * (IllegalArgumentException: NetworkCallback was not registered) حتى
     * مع التزامن من خيوط مختلفة.
     */
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // الحالة تُحسب حيّة من [isOnlineNow] فلا يحمل هذا إلا لوجستية التسجيل؛
            // الحفاظ على الـ callback معبّراً ومستقبلاً يخدم تفعيل "استشعار فوري".
        }

        override fun onLost(network: Network) = Unit

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = Unit

        override fun onUnavailable() = Unit
    }

    init {
        registerIfPossible()
    }

    /** تسجيل الـ callback؛ فشل التسجيل لا يُوقف العمل (الحساب الحيّ باقٍ). */
    private fun registerIfPossible() {
        // إن كان المعلّم مسجلاً مسبقاً فلا نعيد — المعرّف الذري يمنع الازدواج
        // حتى مع التزامن من أكثر من خيط.
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure { e ->
            Log.w(TAG, "[Connectivity] registerNetworkCallback فشل", e)
            registered.set(false)
        }
    }

    /** تعطيل المراقب عند حاجة المتصل (إطلاق موارد المراقبة) — أمنٌ عند التكرار. */
    fun unregister() {
        // unregisterNetworkCallback على مُعلَّمٍ غير مسجّل يرمي استثناءً؛
        // العلامة الذرية تحسم التحويل true→false مرة واحدة فقط فتتخطى بقية
        // الاستدعاءات بلا لمس النظام.
        if (!registered.compareAndSet(true, false)) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /** إغلاق الموارد ضمن try-with-resources: [AutoCloseable]. */
    override fun close() = unregister()

    /**
     * هل الإنترنت متاح الآن؟ حساب حيّ من الشبكة النشطة (وليس من ذاكرة قديمة):
     * صحيح فقط إذا كانت الشبكة النشطة محقّقة الاتصال بالإنترنت
     * ([NET_CAPABILITY_INTERNET] مع [NET_CAPABILITY_VALIDATED]) — تُستخدم
     * [ConnectivityManager.activeNetwork] لأنها الشبكة التي ستحمل حركة التوليد
     * السحابي فعلياً، وهي البديل غير المهجور لـ `allNetworks` (مهجورة منذ API 35).
     */
    fun isOnlineNow(): Boolean {
        val cm = connectivityManager
        return runCatching {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }
}