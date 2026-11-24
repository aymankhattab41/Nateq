package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.accessibility.A11yVoiceResolver
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.audio.accessibility.OtpReader
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * خدمة الاستماع للإشعارات — تقرأ إشعارات التطبيقات المهمة
 * (واتساب، تلجرام، إلخ) بالصوت.
 * يجب منح الإذن يدوياً من: الإعدادات ← التطبيقات الخاصة ← الوصول للإشعارات.
 */
@AndroidEntryPoint
class NateqNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NATEQ_NOTIF"

        // سقفُ عدد الحزم المتتبَّعة في نافذة منع التكرار (بند [10]) — كل إصدار
        // يتحدث قائمة الحزم الشائعة، والحزم الجديدة تتسرب القديمة (LRU).
        private const val MAX_TRACKED_NOTIF_PACKAGES = 64

        @Volatile
        var isConnected: Boolean = false
            internal set

        @Volatile
        var activeInstance: NateqNotificationListener? = null
            private set

        /** فك ربط خدمة الإشعارات بأمان أثناء جلسة التطبيق المصرفي. */
        fun unbindSafely() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { activeInstance?.requestUnbind() }
            }
        }

        /** إعادة ربط خدمة الإشعارات بأمان بعد انتهاء جلسة التطبيق المصرفي. */
        fun rebindSafely(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    requestRebind(
                        ComponentName(
                            context,
                            NateqNotificationListener::class.java
                        )
                    )
                }
            }
        }

        /** التحقق مما إذا كان التطبيق لديه إذن الاستماع للإشعارات. */
        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(
                context, NateqNotificationListener::class.java
            )
            return flat.contains(cn.flattenToString())
        }

        /**
         * إعادة تفعيل المكوّن برمجياً لإجبار النظام على ربطه دون تدخل
         * (الأمر 6).
         */
        fun rebindIfDisabled(context: Context) {
            if (isPermissionGranted(context) && !isConnected) {
                runCatching {
                    val cn = ComponentName(
                        context, NateqNotificationListener::class.java
                    )
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

        /** مفتاحُ هوية مكالمة الإشعار: حزمةُ التطبيق + الرقم + الاسم — به
         *  تُميَّز مكالمةٌ عن تحديثٍ لإشعار المكالمة ذاتها. خالص. */
        internal fun collectCallNotificationCandidates(
            notification: Notification
        ): List<String> {
            val list = mutableListOf<String>()
            val extras = notification.extras ?: return list
            extras.getCharSequence(
                Notification.EXTRA_TITLE_BIG
            )?.toString()?.trim()?.takeIf {
                it.isNotEmpty()
            }?.let { list.add(it) }
            extras.getCharSequence(
                "android.conversationTitle"
            )?.toString()?.trim()?.takeIf {
                it.isNotEmpty()
            }?.let { list.add(it) }
            notification.tickerText?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val person = extras.getParcelable<android.app.Person>(
                        Notification.EXTRA_CALL_PERSON
                    )
                    person?.name?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
                    person?.uri?.trim()?.let { uri ->
                        val clean = if (uri.startsWith("tel:")) {
                            uri.removePrefix("tel:").trim()
                        } else uri
                        if (clean.isNotEmpty()) list.add(clean)
                    }
                }
                runCatching {
                    extras.getParcelableArrayList<android.app.Person>(
                        Notification.EXTRA_PEOPLE_LIST
                    )?.forEach { person ->
                        person.name?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
                        person.uri?.trim()?.let { uri ->
                            val clean = if (uri.startsWith("tel:")) {
                                uri.removePrefix("tel:").trim()
                            } else uri
                            if (clean.isNotEmpty()) list.add(clean)
                        }
                    }
                }
            }
            runCatching {
                @Suppress("DEPRECATION")
                extras.getStringArray(Notification.EXTRA_PEOPLE)?.forEach { u ->
                    val clean = if (u.startsWith("tel:")) {
                        u.removePrefix("tel:").trim()
                    } else u.trim()
                    if (clean.isNotEmpty()) list.add(clean)
                }
            }
            return list
        }

        fun notificationCallKey(
            pkg: String?,
            number: String?,
            name: String?
        ): String = "${pkg.orEmpty()}|${number.orEmpty()}|" +
            "${name.orEmpty()}"

        /**
         * بند ١٨: مفتاح هوية الإشعار — حدّ التكرار صار لكل محادثة/رسالة
         * لا للحزمة كلها، فلا تُسقط رسالة مختلفة من شخص آخر في نفس التطبيق.
         * يُضمّن نصّ الرسالة (بصمته) لتمييز الرسائل المختلفة.
         */
        fun notificationIdentityKey(
            pkg: String?,
            title: String?,
            text: String?,
            notificationId: Int = 0,
            tag: String? = null
        ): String = "${pkg.orEmpty()}|${tag.orEmpty()}|" +
            "$notificationId|${title.orEmpty()}|" +
            "${text.orEmpty().hashCode()}"

        /** هل يحوي عنوان/نص الإشعار رمز تحقق سري ينبغي حجبه؟ يُطبَّق فقط مع
         * تفعيل حماية الخصوصية — نفس منطق فلتر OTP الخاص بالرسائل النصية. */
        fun shouldMaskOtp(
            privacyEnabled: Boolean,
            title: String?,
            text: String?
        ): Boolean = privacyEnabled && LocaleUtils.containsOtp(
            "${title.orEmpty()} ${text.orEmpty()}".trim()
        )

        /** بند ١٧: استخراج أنصّ وأكمل نص متاح من الإشعار بدل تفضيل
         *  EXTRA_TEXT القصير على BIG_TEXT وTEXT_LINES الأطول. */
        fun notificationBodyText(extras: android.os.Bundle): String? {
            val candidates = listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()?.trim(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?.toString()?.trim(),
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.filterNotNull()?.joinToString("\n")?.trim()
            ).filter { it.isNotEmpty() }
            return candidates.maxByOrNull { it.length }
        }

        /**
         * بند ١٧ و٢٥: استخراج الرسائل الجديدة فقط من MessagingStyle
         * دون إعادة نطق كامل السجل عند كل رسالة جديدة (البند 25).
         */
        fun notificationBodyText(
            notification: Notification?,
            conversationKey: String? = null
        ): String? {
            if (notification == null) return null
            val hasMessaging = runCatching {
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
            }.getOrNull() != null

            if (hasMessaging) {
                return MessagingStyleTracker.extractFreshText(
                    notification = notification,
                    conversationKey = conversationKey
                )
            }
            return notificationBodyText(notification.extras)
        }
    }

    internal object MessagingStyleTracker {
        private const val MAX_CONVERSATIONS = 100
        private const val MAX_MESSAGES_PER_CONV = 100
        private val seenByConv = LinkedHashMap<String, MutableSet<String>>(
            MAX_CONVERSATIONS, 0.75f, true
        )

        @Synchronized
        fun extractFreshText(
            notification: Notification?,
            conversationKey: String?
        ): String? {
            if (notification == null) return null
            val messagingStyle = runCatching {
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
            }.getOrNull()
            val messages = messagingStyle?.messages
            if (messages.isNullOrEmpty()) {
                return notificationBodyText(notification.extras)
            }

            val convKey = conversationKey ?: deriveScope(notification)
            if (convKey.isBlank()) {
                return messages.lastOrNull()?.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: notificationBodyText(notification.extras)
            }

            val seen = seenByConv.getOrPut(convKey) { LinkedHashSet() }
            val freshMessages = ArrayList<String>()

            for ((idx, msg) in messages.withIndex()) {
                val text = msg.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sender = msg.person?.name?.toString()
                    ?: msg.sender?.toString().orEmpty()
                val ts = msg.timestamp
                val id = "$sender\u0000$ts\u0000$idx\u0000$text"
                if (seen.add(id)) {
                    freshMessages.add(text)
                }
            }

            while (seen.size > MAX_MESSAGES_PER_CONV) {
                val first = seen.iterator().next()
                seen.remove(first)
            }
            while (seenByConv.size > MAX_CONVERSATIONS) {
                val oldestKey = seenByConv.keys.iterator().next()
                seenByConv.remove(oldestKey)
            }

            return if (freshMessages.isNotEmpty()) {
                freshMessages.joinToString("\n")
            } else {
                null
            }
        }

        private fun deriveScope(notification: Notification): String {
            val extras = notification.extras ?: return ""
            val convTitle = extras.getCharSequence(
                Notification.EXTRA_CONVERSATION_TITLE
            )?.toString()?.trim().orEmpty()
            val title = extras.getCharSequence(
                Notification.EXTRA_TITLE
            )?.toString()?.trim().orEmpty()
            val shortcutId = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                notification.shortcutId.orEmpty()
            } else ""
            val channelId = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                notification.channelId.orEmpty()
            } else ""
            return "$channelId#$shortcutId#$convTitle#$title"
        }

        @Synchronized
        fun clear() {
            seenByConv.clear()
        }
    }

    internal object SmsNlsDeduplicator {
        private const val MAX_ENTRIES = 50
        private const val THROTTLE_WINDOW_MS = 10_000L
        private val recentSms = LinkedHashMap<String, Long>(
            MAX_ENTRIES, 0.75f, true
        )

        @Synchronized
        fun shouldDrop(
            notificationKey: String,
            notificationId: Int,
            sender: String?,
            content: String,
            nowMs: Long
        ): Boolean {
            val dedupeKey = "$notificationKey#$notificationId#" +
                "${sender.orEmpty()}#${content.hashCode()}"
            val lastTime = recentSms[dedupeKey]
            if (lastTime != null && nowMs - lastTime < THROTTLE_WINDOW_MS) {
                return true
            }
            recentSms[dedupeKey] = nowMs
            while (recentSms.size > MAX_ENTRIES) {
                val oldest = recentSms.keys.iterator().next()
                recentSms.remove(oldest)
            }
            return false
        }

        @Synchronized
        fun onNotificationRemoved(notificationKey: String) {
            recentSms.keys.removeAll { it.startsWith("$notificationKey#") }
        }

        @Synchronized
        fun clear() {
            recentSms.clear()
        }
    }

    /** مصدر الإعدادات المحقون — نفس كائن عملية المحرك المُدار من Hilt. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val minIntervalMs = 3000L // الحد الأدنى لإشعارات نفس التطبيق

    // الحد الزمني يُطبَّق لكل حزمة على حدة (بند [10]): إشعاران من تطبيقين
    // مختلفين خلال 3 ثوانٍ لا يُسقط أحدهما الآخر (كان حدّاً عاماً واحداً
    // يُفقد إشعاراً مهماً تصادفَ بعد أي إشعارٍ آخر). متزمِّنة للأمان،
    // LRU (accessOrder=true) لسقفِ الحزم المتتالية.
    private val lastNotifTimes = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>
            ): Boolean = size > MAX_TRACKED_NOTIF_PACKAGES
        }
    )

    /** هل هذا الإشعار (مفتاح هويته) مسقطُ الشفرة (وصل إشعارٌ مماثل
     *  خلال [minIntervalMs])؟ فحصٌ والتحديث معاً تحت نفس القفل —
     *  القابلية للاختبار بحقن خريطة (بند ١٨): المفتاح المُمرَّر هو
     *  هوية المحادثة/الرسالة لا الحزمة كي لا تُسقط رسائل مختلفة. */
    internal fun isNotificationRateLimited(
        key: String,
        now: Long,
        times: MutableMap<String, Long> = lastNotifTimes
    ): Boolean {
        return synchronized(times) {
            val last = times[key]
            if (last == null) {
                // أول إشعار لهذه الهوية: مسموح دوماً ويُسجَّل وقتُه.
                times[key] = now
                false
            } else {
                val limited = (now - last) < minIntervalMs
                if (!limited) times[key] = now
                limited
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        // كل المعالجة (قراءات الإعدادات، استعلام تطبيق الرسائل الافتراضي،
        // إطارات الواجهة Keyguard، فحص الاستثناءات، النطق) تُنفَّذ على
        // appScope (Io) — استدعاء NLS يأتي على خيط الخدمة الرئيسي وكانت
        // عمليات قرص و IPC متزامنة عليه تسبب إسقاط إطارات مع وصول كثيف.
        val scope = (applicationContext as? AnnouncementAppContext)?.appScope
            ?: CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                onNotificationPostedWorker(sbn)
            } catch (t: Throwable) {
                Log.e(TAG, "onNotificationPosted failed", t)
            }
        }
    }

    private fun onNotificationPostedWorker(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return

            // إشعارُ الهاتف يُفحص **قبل كل البوابات** لا بعدها: هو أسرع
            // مصدرٍ للهوية، وملفُّه مستقلٌّ عن قائمة التطبيقات التي اختارها
            // المستخدم — فمنعُ إشعار تطبيق الهاتف ليس رفضاً لإعلان هوية
            // المتصل. فلو فحصناه بعد `isNotificationReadingEnabled`
            // وقائمة التطبيقات لبقي الهاتفُ خارج المسارِ دائماً.
            //
            // **لكن** بوّابة الإذن تبقى للمصادقة: إذنُ الاستماع أذنه
            // المستخدم لمنحِ إشعاراتِ تطبيقاتِ **مختارة**، ولا يُقرأ
            // كلُّ إشعارٍ لمجرّد أنه يُقرأ تطبيقُ الهاتف. فمسارُ هوية
            // المتصل محصورٌ في `CATEGORY_CALL` وحده ولا ينفتح إلا
            // بتفعيل إعلان المتصل (وإلا صار إشعارُ الهاتف قناةً
            // لقراءة إشعارات لم يطلبها).
            val notification = sbn.notification
            if (notification != null &&
                notification.category == Notification.CATEGORY_CALL
            ) {
                if (settingsRepository.isCallerAnnouncementEnabled()) {
                    val extras = notification.extras
                    val rawTitle = extras.getCharSequence(
                        Notification.EXTRA_TITLE
                    )?.toString()?.trim()
                    val rawText = notificationBodyText(extras)
                    val rawSubText = extras.getCharSequence(
                        Notification.EXTRA_SUB_TEXT
                    )?.toString()?.trim()
                    val extraCandidates =
                        collectCallNotificationCandidates(notification)
                    val (callNumber, callName) =
                        RingCallerIdentity.extractFromCallNotification(
                            title = rawTitle,
                            text = rawText,
                            subText = rawSubText,
                            extraCandidates = extraCandidates
                        )
                    Log.w(
                        TAG,
                        "CALL-IDENTITY pkg=$pkg num=${callNumber ?: "?"}" +
                            " name=${callName ?: "?"}"
                    )
                    // **رفعُ عَلَم «انتهى الرنين» قبل كل البوابات**
                    // (بند 5.6 معكوس): إن كان الجوابُ قد وقع فالموضوعُ
                    // ليس إعلاناً جديداً بل **وقفُ تكرارٍ جارٍ** — وهو
                    // يَحتاج أن يُعلَم حتى حين يُكبَح الإعلانُ نفسه أدناه.
                    // والإثباتُ بالإيجاب فقط ([isAnsweredCallPhrase]) فلا
                    // يُمسّ إعلانُ مكالمةٍ ما زالت ترنّ (Meet الوارد مثلاً).
                    if (notificationCallPhrases(notification).any {
                            RingCallerIdentity.isAnsweredCallPhrase(it)
                        }
                    ) {
                        Log.w(
                            TAG,
                            "CALL ANSWERED pkg=$pkg — أُوقف تكرارُ " +
                                "إعلان المتصل"
                        )
                        CallerAnnouncementReceiver.markCallAnswered()
                    }
                    // ===== بند 1 و4: إثباتُ الورود ثم حسمُ المسار =====
                    //
                    // **لا نطقَ إلا بدليلِ ورودٍ إيجابي.** فكان الحارسُ
                    // القديم ينطق ما لم يجد دليلَ صدور، فيُعلَن الصادرةُ
                    // واردةً. والأدلّةُ تُقرأ من النظام مباشرةً: نوعُ
                    // المكالمة، ونافذةُ ملءِ الشاشة، وإجراءُ الردّ، ورنينُ
                    // الخطّ — والاستنتاجُ كلُّه في دالةٍ خالصةٍ مفحوصة.
                    //
                    // **وحالُ الخطّ تُقرأ لا من عَلَمٍ في الذاكرة:** فموتُ
                    // العملية بين `OFFHOOK` والإشعار يُنسي العَلَمَ ولا
                    // يُنسي النظامَ — وهذا ما يجعل حارسَ موتِ العملية
                    // حقيقياً.
                    val callState = telephonyCallState()
                    val positivelyIncoming = isPositivelyIncoming(
                        notification, callState
                    )
                    val isDefaultDialer =
                        CallerAnnouncementReceiver.isDefaultDialerPackage(
                            pkg, defaultDialerPackage()
                        )
                    val route = CallerAnnouncementReceiver
                        .decideCallNotificationRoute(
                            isDefaultDialer = isDefaultDialer,
                            telephonyCallState = callState,
                            positiveIncoming = positivelyIncoming
                        )
                    when (route) {
                        CallerAnnouncementReceiver.CallNotificationRoute
                            .SILENT -> {
                            Log.w(
                                TAG,
                                "CALL no proof of incoming pkg=$pkg" +
                                    " state=$callState dialer=$isDefaultDialer"
                            )
                            return
                        }
                        CallerAnnouncementReceiver.CallNotificationRoute
                            .PUBLISH_IDENTITY_ONLY -> {
                            // **بند 4:** حزمةُ الافتراضي لا تنطق أبداً —
                            // `PHONE_STATE` وحده ناطِقُ مكالمات الشبكة.
                            // وإشعارُها أسرعُ مصدرٍ لاسم المتصل فننشرُه
                            // فقط، فينطقه البثُّ مع الرقم.
                            RingCallerIdentity.publish(callNumber, callName)
                            Log.w(
                                TAG,
                                "CALL identity from dialer pkg=$pkg" +
                                    " num=${callNumber ?: "?"}" +
                                    " name=${callName ?: "?"}"
                            )
                            return
                        }
                        else -> Unit
                    }
                    // **حارس الاتجاه (الاسماء الصادرة والمنتهية):** إشعارُ
                    // `CATEGORY_CALL` وحده لا يميّز الواردةَ من الصادرة
                    // ولا المنتهية — فكان يُنطق «اتصال وارد من فلان» لمن
                    // *هو* المتصل، وأحياناً بعد انتهاء المكالمة. الحارسُ
                    // نقيٌّ في [CallerAnnouncementReceiver] ومفحوصٌ هناك.
                    if (!shouldAnnounceCallNotification(
                            notification = notification,
                            number = callNumber,
                            name = callName
                    )
                    ) {
                        Log.w(
                            TAG,
                            "CALL-IDENTITY skipped pkg=$pkg" +
                                " num=${callNumber ?: "?"}"
                        )
                        return
                    }
                    // **نُطلق ولا ننطق — والنطقُ وحيدٌ في
                    // [CallerAnnouncementReceiver].** مكالماتُ التطبيقات
                    // (واتساب/تلجرام/ميسنجر) VoIPُ بحتٌ فلا يصدر لها بثّ
                    // `PHONE_STATE` أبداً — فلو اكتفينا بنشر الهوية لبقيت
                    // بلا نطقٍ واحد. فنستدعي مسارَ الرنين مباشرةً داخل
                    // العملية (بند 7: لا بثَّ إلى المستقبل المصدَّر)
                    // فينفّذ تكراراتٍ وفواصلَ وحرّاسَ ازدواجٍ بلا مضاعفةٍ
                    // مع مكالمات الشبكة.
                    if (callNumber != null || callName != null) {
                        CallerAnnouncementReceiver.announceNotificationCall(
                            context = applicationContext,
                            settings = settingsRepository,
                            number = callNumber,
                            name = callName,
                            key = notificationCallKey(
                                pkg, callNumber, callName
                            )
                        )
                    }
                }
                return
            }

            val settings = settingsRepository
            // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة (البند 23).
            if (!settings.isAllAnnouncementsEnabled()) return

            // إشعار من تطبيق الرسائل النصية مع قراءة SMS مفعّلة:
            // يُعالج بمسار SMS المستقل حتى لو قراءة الإشعارات
            // العامة مغلقة (البند 23).
            // - إن كان RECEIVE_SMS ممنوحاً فسيَنطقه SmsReadingReceiver مباشرة.
            val defaultSmsApp = runCatching {
                android.provider.Telephony.Sms
                    .getDefaultSmsPackage(applicationContext)
            }.getOrNull()
            val isSmsApp = pkg == defaultSmsApp
            val smsMode = settings.getSmsReadingMode()
            if (isSmsApp && smsMode != SmsReadingReceiver.MODE_OFF) {
                val hasSmsPermission = ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasSmsPermission) return
                handleSmsNotification(sbn, settings, smsMode)
                return
            }

            // بوابة قراءة الإشعارات العامة:
            if (!settings.isNotificationReadingEnabled()) return

            // قراءة التطبيقات المختارة فقط (للمستخدم حرية اختيار قائمتها).
            if (!settings.shouldReadNotificationApp(pkg)) return

            val extras = sbn.notification?.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim()
            val shortcutId = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                sbn.notification?.shortcutId.orEmpty()
            } else ""
            val convKey = "${sbn.packageName}#${sbn.id}#${sbn.tag.orEmpty()}#" +
                (if (shortcutId.isNotEmpty()) shortcutId else title.orEmpty())
            // بند ١٧ و٢٥: نص الرسالة الجديدة من MessagingStyle بنطاق المحادثة
            val text = notificationBodyText(sbn.notification, convKey)

            // بند ١٨: الحدّ لكل محادثة/رسالة لا للحزمة — إشعاران من
            // شخصين مختلفين في نفس التطبيق لا يُسقط أحدهما الآخر.
            val identityKey = notificationIdentityKey(
                pkg = pkg,
                title = title,
                text = text,
                notificationId = sbn.id,
                tag = sbn.tag
            )
            if (isNotificationRateLimited(
                    identityKey, System.currentTimeMillis()
                )
            ) {
                return
            }

            if (title.isNullOrBlank() && text.isNullOrBlank()) return

            val appName = getAppName(pkg)
            // خصوصية قفل الشاشة: عند القفل يُنطق اسم التطبيق فقط دون العنوان
            // والنص (حماية لكلمات تحقق OTP وغيرها من الحساسيات في الإشعارات).
            val privacyEnabled = settings.isLockScreenPrivacyEnabled()
            val privacyLocked =
                privacyEnabled && settings.isDeviceScreenLocked()
            // فلتر OTP الشامل (حماية الخصوصية مفعّلة): كلُّ الإشعارات لا مسار
            // تطبيق الرسائل فقط — واتساب/تلجرام/البنوك تُرسل رموز تحقق تُنطق
            // علناً دون حجب. يُنطق بدل الرمز عبارة آمنة عامة (نمط حماية SMS).
            val isOtp = shouldMaskOtp(
                privacyEnabled, title, text
            )
            // ثابت الأمان: قفل الشاشة يسبق فرع OTP بلا استثناء. قبل هذا
            // الترتيب كان رمز التحقق يُستخرج ويُنطق حرفاً حرفاً والشاشة
            // مقفولة لأن فرع OTP كان يُفحَص أولاً — وهو خرق لخصوصية القفل.
            val speechText = if (privacyLocked) {
                buildSpeechText(appName, title, text, privacyLocked = true)
            } else if (isOtp) {
                val appLang = if (LocaleUtils.containsArabic(appName)) {
                    LanguageCode.AR.tag
                } else {
                    LanguageCode.EN.tag
                }
                // ع٢٩-15: استخراج وقراءة OTP حرفاً حرفاً عند تفعيل الميزة
                val repo = settingsRepository
                val otpEnabled = FeatureFlags.isActive(
                    FeatureFlags.FEATURE_OTP_READING,
                    context = applicationContext,
                    settings = settingsRepository
                )
                val otpText = if (otpEnabled) {
                    val combinedText = "${title.orEmpty()} ${text.orEmpty()}"
                    OtpReader.extractOtp(combinedText)?.let {
                        OtpReader.formatOtpForSpeech(it)
                    } ?: run {
                        val appLang = if (LocaleUtils.containsArabic(appName)) {
                            LanguageCode.AR.tag
                        } else {
                            LanguageCode.EN.tag
                        }
                        LocaleUtils.stringForSpeech(
                            applicationContext, appLang,
                            R.string.notif_otp_safe, R.string.notif_otp_safe
                        ).replace("{app}", appName)
                    }
                } else {
                    val appLang = if (LocaleUtils.containsArabic(appName)) {
                        LanguageCode.AR.tag
                    } else {
                        LanguageCode.EN.tag
                    }
                    LocaleUtils.stringForSpeech(
                        applicationContext, appLang,
                        R.string.notif_otp_safe, R.string.notif_otp_safe
                    ).replace("{app}", appName)
                }
                otpText
            } else {
                buildSpeechText(appName, title, text, privacyLocked)
            }
            val isArabic = LocaleUtils.containsArabic(speechText)
            val locale = if (isArabic) {
                Locale.forLanguageTag(LanguageCode.AR.tag)
            } else {
                Locale.forLanguageTag(LanguageCode.EN.tag)
            }

            // سجلّ مجرّد من مضمون الإشعار (قد يحوي OTP/حساسيات)
            // — الطول والحزمة فقط.
            Log.d(TAG, "Notification from $pkg: ${speechText.length} chars")

            val (senderName, senderKey) = extractNotificationSender(
                sbn.notification
            )
            val resolvedVoice = A11yVoiceResolver.resolve(
                context = applicationContext,
                settings = settings,
                packageName = pkg,
                senderName = senderName,
                conversationKey = senderKey
            )
            val isVoiceLangMatch = isVoiceMatchingLanguage(
                resolvedVoice, locale.language
            )

            val baseRate = settings.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            val basePitch = settings.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            val baseVolume = settings.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )

            val speechRate = resolvedVoice?.speechRate ?: baseRate
            val pitch = resolvedVoice?.pitch ?: basePitch
            val volume = resolvedVoice?.volume ?: baseVolume

            val defaultEngine = settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            val defaultVoice = settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )

            val finalEngine = if (
                isVoiceLangMatch && resolvedVoice?.engine != null
            ) {
                resolvedVoice.engine
            } else {
                defaultEngine
            }
            val finalVoice = if (
                isVoiceLangMatch && resolvedVoice?.voiceId != null
            ) {
                resolvedVoice.voiceId
            } else {
                defaultVoice
            }

            val speaker = AnnouncementSpeaker.getInstance(applicationContext)
            speaker.resetVoice(finalVoice)
            speaker.speak(
                speechText, locale, speechRate, pitch, volume,
                engineOverride = finalEngine,
                category = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            Log.i(
                TAG,
                "Notification speech: pkg=$pkg, sender=$senderName, " +
                    "matched=${resolvedVoice != null}, " +
                    "voice=$finalVoice, engine=$finalEngine"
            )

        } catch (t: Throwable) {
            Log.e(TAG, "onNotificationPosted failed", t)
        }
    }

    internal fun extractNotificationSender(
        notification: Notification?
    ): Pair<String?, String?> {
        if (notification == null) return Pair(null, null)
        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        if (messagingStyle != null) {
            val lastMsg = messagingStyle.messages.lastOrNull()
            if (lastMsg != null) {
                val person = lastMsg.person
                val name = person?.name?.toString()
                    ?: lastMsg.sender?.toString()
                val uri = person?.uri
                val phone = if (uri != null && uri.startsWith("tel:")) {
                    uri.removePrefix("tel:")
                } else {
                    uri
                }
                if (!name.isNullOrBlank() || !phone.isNullOrBlank()) {
                    return Pair(name?.trim(), phone?.trim())
                }
            }
        }

        val extras = notification.extras ?: return Pair(null, null)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()

        var phone: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person = extras.getParcelable<android.app.Person>(
                Notification.EXTRA_MESSAGING_PERSON
            )
            val uri = person?.uri
            if (uri != null && uri.startsWith("tel:")) {
                phone = uri.removePrefix("tel:")
            } else if (uri != null) {
                phone = uri
            }
        }

        return Pair(title, phone)
    }

    private fun isVoiceMatchingLanguage(
        voice: EffectiveVoice?,
        languageTag: String
    ): Boolean {
        if (voice == null) return false
        val segRaw = languageTag.takeWhile { it.isLetter() }
            .lowercase(Locale.ROOT)
        val segLang = when (segRaw) {
            "ara" -> "ar"
            "eng" -> "en"
            else -> segRaw
        }
        val voiceRaw = voice.voiceLanguage?.takeWhile { it.isLetter() }
            ?.lowercase(Locale.ROOT)
            ?: voice.voiceId?.let { id ->
                val sub = id.substringBefore('-')
                    .substringBefore('_')
                    .takeWhile { it.isLetter() }
                    .lowercase(Locale.ROOT)
                if (sub.length in 2..3) sub else null
            }
        val voiceLang = when (voiceRaw) {
            "ara" -> "ar"
            "eng" -> "en"
            else -> voiceRaw
        }
        return voiceLang == null || voiceLang == segLang
    }

    /**
     * هل يستحقّ إشعارُ مكالمةٍ إعلانَ «اتصال وارد»؟ يفوّض القرارَ كلَّه
     * إلى [CallerAnnouncementReceiver.shouldAnnounceCallNotification] ليبقى
     * الحارسُ نقيّاً مختبراً في مكانٍ واحد، ويجلب له الدلائلَ الثلاثة من
     * النظام: جريانُ الإشعار، وحالةُ خطّ الهاتف، وآخرُ مكالمةٍ انتهيناها.
     */
    private fun shouldAnnounceCallNotification(
        notification: Notification,
        number: String?,
        name: String?
    ): Boolean {
        val ongoing = (notification.flags and
            Notification.FLAG_ONGOING_EVENT) != 0
        // **الاتجاهُ من النصّ لا من العَلَم:** جوجل ميت يُعلِّم إشعارَ
        // مكالمته الواردة `ongoing` من لحظة الرنّ، فرفضُ كلِّ `ongoing`
        // (سلوك v1.6.20) أسقط إعلانَه بالكامل. فالدليلُ على الصغرى
        // أن يكون النصُّ «calling…» أو «جاري الاتصال».
        val outgoing = notificationCallPhrases(notification)
            .any { RingCallerIdentity.isOutgoingCallPhrase(it) }
        // حالةُ خطّ الهاتف تُقرأ مباشرةً من النظام لا من عَلَم المستقبل:
        // بثّ `OFFHOOK` يُعالَج في coroutineٍ قد يتأخّر، والإشعارُ يصل
        // بعده مباشرةً — فمن يقرأ العَلَمَ يظنّ المكالمةَ غيرَ قائمة.
        //
        // **و`callState` مُهمَلٌ رسمياً — مع ذلك نقرأه:** بديلُه
        // `TelecomManager.isInCall()` لا يميّز رنيناً من مشغولٍ بل يُرجع
        // `true` لكلِّ مكالمةٍ قائمة — بما فيها مكالمةُ تطبيقٍ مُدارةٍ عبر
        // Telecom، فيُكبِت إعلانُ مكالمةٍ **واردة** زائفةً. فهو أسوأُ من
        // `callState` في هذا الحارس. ولهذا نمرّ على [telephonyCallState]
        // لا على قراءةٍ مباشرة، فيبقى لكلِّ الأدلّة مصدرٌ واحد.
        val callInProgress =
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                telephonyCallState()
            )
        val endedAt = CallerAnnouncementReceiver.lastPhoneIdleAt
        val sameEnded = CallerAnnouncementReceiver.matchesLastNetworkCall(
            lastNumber = CallerAnnouncementReceiver.lastNetworkCallNumber,
            lastName = CallerAnnouncementReceiver.lastNetworkCallName,
            number = number,
            name = name
        )
        return CallerAnnouncementReceiver.shouldAnnounceCallNotification(
            isOngoing = ongoing,
            networkCallInProgress = callInProgress,
            endedCallAt = endedAt,
            sameEndedIdentity = sameEnded,
            now = System.currentTimeMillis(),
            outgoing = outgoing
        )
    }

    /** نصوصُ إشعار المكالمة الثلاثة كما نراها خاماً (العنوان والنصّ
     *  والورقة) — تُقرأ مرةً واحدة هنا وتُستخدم للحارس وللاستخراج. */
    private fun notificationCallPhrases(
        notification: Notification
    ): List<String> {
        val extras = notification.extras
        return listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            notificationBodyText(extras),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        ).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * **هل إشعارُ المكالمة يُثبت ورودَها بالإيجاب؟** — واجهةُ الاستدعاء
     * التي يستدعيها المسارُ الحقيقيُّ بتوقيعها المطلوب.
     *
     * **وهي غلافٌ رقيقٌ يُحيلُ إلى نواةٍ نقيّة:** تستخرج الأدلّةَ من
     * `Notification` وتُمرّرها إلى
     * [CallerAnnouncementReceiver.isPositivelyIncoming] — وهي الدالةُ
     * الخالصةُ التي تحمل العقدَ كلَّه وتُفحَص ببنودها.
     *
     * **ولماذا الغلافُ هنا لا هناك؟** لأنّ استخراج دليلين يحتاج `Context`:
     *  - عنوانُ إجراء الردّ يُقارَن بكلمةِ «ردّ» **المترجَمةِ من مواردنا**،
     *    فالمصدرُ مواردُ لا قائمةُ كلماتٍ ثابتة؛
     *  - تسميةُ اللغة الفعلية عبر [LocaleUtils].
     *
     *فالسياقُ لازمٌ لهذه الغلاف، **وليس للنواة**. ولذلك تبقى النواةُ بلا
     * `Context` قابلةً للاختبار، وتبقى الواجهةُ بتوقيع
     * `isPositivelyIncoming(notification, telephonyCallState)` كما طُلبت
     * بالضبط.
     */
    private fun isPositivelyIncoming(
        notification: Notification,
        telephonyCallState: Int
    ): Boolean = CallerAnnouncementReceiver.isPositivelyIncoming(
        callTypeExtra = callTypeOf(notification),
        hasFullScreenIntent = notification.fullScreenIntent != null,
        hasAnswerAction = hasAnswerAction(notification),
        telephonyCallState = telephonyCallState,
        sdkInt = Build.VERSION.SDK_INT,
        legacyIncomingPhrase = legacyIncomingPhrase(notification)
    )

    // ===== جمعُ أدلّة الورود (بند 1) — impure، والاستنتاجُ نقيٌّ =====

    /**
     * حالةُ خطّ الهاتف **من النظام مباشرةً** لا من عَلَمٍ في الذاكرة.
     *
     * **ولهذا يُقرأ في كلِّ إشعار:** عَلَمُ «مكالمةٌ صادرة» يُضبط عند
     * معالجة بثّ `OFFHOOK` في coroutineٍ قد يتأخّر أو تُفقد بموتِ
     * العملية، فمن يقرأ العَلَمَ يظنّ المكالمةَ غيرَ قائمة بعد موته.
     */
    private fun telephonyCallState(): Int = runCatching {
        @Suppress("DEPRECATION")
        (applicationContext.getSystemService(
            Context.TELEPHONY_SERVICE
        ) as? TelephonyManager)?.callState
            ?: TelephonyManager.CALL_STATE_IDLE
    }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

    /**
     * نوعُ المكالمة الذي أعلنَه التطبيقُ (`EXTRA_CALL_TYPE`)، أو `null`
     * إن لم يذكره.
     *
     * **و`null` يعني «لا معلومة» لا «مجهول»:** فالتطبيقُ القديم لا يضع
     * المفتاحَ أصلاً، فلا يجوز أن يُقرأ صفراً. والمجهولُ صراحةً يبقى
     * صفراً فيقرأه الحارسُ على حقيقته ولا يسودُ على بقية الأدلّة.
     */
    private fun callTypeOf(notification: Notification): Int? {
        val extras = notification.extras ?: return null
        if (!extras.containsKey(Notification.EXTRA_CALL_TYPE)) return null
        return extras.getInt(
            Notification.EXTRA_CALL_TYPE,
            CallerAnnouncementReceiver.CALL_TYPE_UNKNOWN
        )
    }

    /**
     * هل في الإشعار **إجراءُ ردّ**؟ بالدلالي الرسميّ أو بنصٍّ **مُترجَمٍ من
     * الحزمة الناشرِّة** — لا بقائمة عربي/إنجليزي ثابتة.
     *
     * **ومصدرُ النصّ هو موارد التطبيق نفسه:** المنصّة تُصفّ عنوانَ الإجراء
     * في `Notification.Action.title` بعد ترجمته من موارد التطبيق الناشر
     * (لا واصفٌ عامّ في `android.jar` — فتُقرأ الترجمةُ جاهزةً ولا نحتاج
     * `PackageManager`). ثم نُقابلها بكلمةِ «ردّ» **المترجَمةِ من مواردنا**
     * إلى اللغتين المدعومتين، فتأتي المقارنةُ بين نصَّين من مواردَين لا
     * بين نصٍّ وقائمةٍ مكتوبة.
     *
     * **وقائمةُ كلماتٍ ثابتة مستبعدةٌ عمداً:** نصوصُ التطبيقات تختلف
     * باللهجة والإصدار (`رد` / `رد الآن` / `إجابة` / `Answer call`).
     *
     * ولا زرَّ ردٍّ على مكالمةٍ أنتَ من بدأتها — فهذا دليلُ ورودٍ قاطع.
     */
    private fun hasAnswerAction(notification: Notification): Boolean {
        val actions = notification.actions
        if (actions.isNullOrEmpty()) return false
        val words = answerWordsFromOurResources()
        return actions.any { action ->
            action.semanticAction ==
                Notification.Action.SEMANTIC_ACTION_CALL ||
                isAnswerLabel(action.title?.toString(), words)
        }
    }

    /**
     * كلماتُ الردّ من **مواردنا** للغتين المدعومتين — فالمصدرُ مواردٌ لا
     * جدولٌ مكتوبٌ في الكود.
     */
    private fun answerWordsFromOurResources(): List<String> {
        val words = ArrayList<String>(2)
        for (tag in listOf(LanguageCode.AR.tag, LanguageCode.EN.tag)) {
            val normalized = RingCallerIdentity.normalizeCallPhrase(
                LocaleUtils.stringForSpeech(
                    applicationContext, tag,
                    R.string.caller_answer, R.string.caller_answer
                )
            )
            if (normalized.isNotEmpty() && !words.contains(normalized)) {
                words.add(normalized)
            }
        }
        return words
    }

    /**
     * هل عنوانُ الإجراء كلمةُ ردّ؟ بـ`startsWith` بعد التطبيع ليقبل
     * «رد» و«رد الآن» و«Answer call» معاً.
     */
    private fun isAnswerLabel(title: String?, words: List<String>): Boolean {
        if (title.isNullOrBlank()) return false
        val normalized = RingCallerIdentity.normalizeCallPhrase(title)
        if (normalized.isEmpty()) return false
        return words.any { word -> normalized.startsWith(word) }
    }

    /**
     * الطبقةُ النصّيةُ الثانوية: هل في نصوص الإشعار عبارةُ ورودٍ صريحة؟
     *
     * **وتركيبُ حدِّ النظام داخلَ الدالةِ الخالصة** ([isPositivelyIncoming])
     * لا هنا — فمصدرُ الحقيقةِ واحد. فلا تُهمَلُ على كلِّ الحالات،
     * والحاسمةُ هي الدالةُ النقيّة.
     */
    private fun legacyIncomingPhrase(notification: Notification): Boolean =
        notificationCallPhrases(notification)
            .any { RingCallerIdentity.isIncomingCallPhrase(it) }

    /** حزمةُ مُشغِّلِ الهاتف الافتراضي، أو `null` إن لم يُعرف. */
    private fun defaultDialerPackage(): String? = runCatching {
        (applicationContext.getSystemService(
            Context.TELECOM_SERVICE
        ) as? TelecomManager)?.defaultDialerPackage
    }.getOrNull()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        SmsNlsDeduplicator.onNotificationRemoved(sbn.key)
        if (sbn.notification?.category != Notification.CATEGORY_CALL) return
        CallerAnnouncementReceiver.endNotificationCall(
            applicationContext, sbn.packageName
        )
    }

    /**
     * **إعادةُ بناء مفتاح المكالمة الجارية عند ربط المستمع من جديد.**
     *
     * **والعيبُ الذي يسدّده:** مفتاحُ جلسة مكالمة الإشعار في ذاكرة
     * العملية ([CallerAnnouncementReceiver.notificationCallKey]). فتموت
     * العمليةُ في وسط رنينٍ فيُنسى المفتاح، ثم يردُّ التطبيقُ تحديثَ
     * مكالمته فيحسبه [CallerAnnouncementReceiver] إعلاناً جديداً
     * فيُنطق الاسمُ ثانيةً فوق مكالمةٍ جارية — مع جدولِ تكراراته.
     *
     * **والمخرجُ الذي يُبنى منه هو النظامُ نفسُه** — لا ساعةٌ ولا
     * تخزينٌ جديد: `activeNotifications` تُرجع إشعارَ المكالمة التي
     * كانت ترنّ قبل الموت، فنُنبِئُ الحارسَ بها فيرفض تحديثَها.
     *
     * **ولماذا هنا وحده:** الربطُ حدثٌ لا يتكرّر فيُفسد شيئاً. أمّا
     * `onNotificationPosted` فلا يصلح للتبنّي من فيه لأن الإشعار
     * الحالي داخلَ القائمة دائماً فيبدو سابقاً لموتنا — فلا يُميَّز
     * عن حيٍّ. أمّا هنا فالمقروءُ قائمةٌ من لحظتِه لا لحظتِ إشعارٍ
     * قادم.
     *
     * **وتُرتَّب المفاتيحُ بـ`postTime`** فينتقي
     * [CallerAnnouncementReceiver.pickNewestLiveCallKey] أحدثَها: وهي
     * التي رنَّت الآن، والأقدمُ تُركتُ بلا مفتاحٍ فتُعلَن لو وحدَها
     * حدثت.
     *
     * **ويُستثنى مُشغِّلُ الهاتف الافتراضي:** مسارُه
     * `PUBLISH_IDENTITY_ONLY` لا يلمس المفتاح أصلاً (بند 4)، فتبنّيُ
     * مفتاحٍ منه يحجبُ مفتاحَ مكالمةٍ تطبيقٍ حقيقيةٍ أحدثُ منه.
     *
     * **والعملُ كلُّه على `appScope`** كمسار `onNotificationPosted`:
     * `activeNotifications` قراءةُ IPC على خيط الربط، و`postTime`
     * و`extras` قراءاتُ نظامٍ لا يصحّ أن تُحسَب على خيط الربط.
     */
    private var screenReceiver: BroadcastReceiver? = null
    private var protectedAppDetectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        registerScreenStateReceiver()
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        SmsNlsDeduplicator.clear()
        MessagingStyleTracker.clear()
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        screenReceiver = null
        protectedAppDetectionJob?.cancel()
        protectedAppDetectionJob = null
        super.onDestroy()
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val scope =
                    (applicationContext as? AnnouncementAppContext)?.appScope
                        ?: CoroutineScope(Dispatchers.Default)
                scope.launch {
                    ProtectedAppManager.checkAndRestore(
                        context = this@NateqNotificationListener,
                        reason = "screen"
                    )
                    checkStartProtectedAppDetectionLoop()
                }
            }
        }
        screenReceiver = receiver
        runCatching { registerReceiver(receiver, filter) }
    }

    private fun checkStartProtectedAppDetectionLoop() {
        if (!ProtectedAppManager.isSuspended(this)) {
            protectedAppDetectionJob?.cancel()
            protectedAppDetectionJob = null
            return
        }
        if (protectedAppDetectionJob?.isActive == true) return

        val scope = (applicationContext as? AnnouncementAppContext)?.appScope
            ?: CoroutineScope(Dispatchers.Default)
        protectedAppDetectionJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive && ProtectedAppManager.isSuspended(
                    this@NateqNotificationListener
                )
            ) {
                while (pm?.isInteractive == false && isActive) {
                    delay(1000L)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                val pollIntervalMs = if (elapsed < 20_000L) 1500L else 3000L
                delay(pollIntervalMs)

                if (!isActive) break
                val restored = ProtectedAppManager.checkAndRestore(
                    context = this@NateqNotificationListener,
                    reason = "listener"
                )
                if (restored) break
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        isConnected = true
        SmsNlsDeduplicator.clear()
        MessagingStyleTracker.clear()
        val scope = (applicationContext as? AnnouncementAppContext)?.appScope
            ?: CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                adoptLiveCallNotifications()
            } catch (t: Throwable) {
                Log.e(TAG, "adoptLiveCallNotifications failed", t)
            }
            try {
                ProtectedAppManager.checkAndRestore(
                    context = this@NateqNotificationListener,
                    reason = "listener_connected"
                )
                checkStartProtectedAppDetectionLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "checkAndRestore failed", t)
            }
        }
    }

    private fun adoptLiveCallNotifications() {
        val live = runCatching { activeNotifications }
            .getOrNull()
            .orEmpty()
        if (live.isEmpty()) return
        if (!settingsRepository.isCallerAnnouncementEnabled()) return
        val dialer = defaultDialerPackage()
        val candidates = live.mapNotNull { sbn ->
            val notification = sbn.notification ?: return@mapNotNull null
            if (notification.category != Notification.CATEGORY_CALL) {
                return@mapNotNull null
            }
            val pkg = sbn.packageName ?: return@mapNotNull null
            if (CallerAnnouncementReceiver.isDefaultDialerPackage(
                    pkg, dialer
                )
            ) {
                return@mapNotNull null
            }
            val extras = notification.extras
            val extraCandidates =
                collectCallNotificationCandidates(notification)
            val (number, name) = RingCallerIdentity
                .extractFromCallNotification(
                    title = extras.getCharSequence(
                        Notification.EXTRA_TITLE
                    )?.toString()?.trim(),
                    text = notificationBodyText(extras),
                    subText = extras.getCharSequence(
                        Notification.EXTRA_SUB_TEXT
                    )?.toString()?.trim(),
                    extraCandidates = extraCandidates
                )
            if (number == null && name == null) return@mapNotNull null
            sbn.postTime to notificationCallKey(pkg, number, name)
        }
        val adopted = CallerAnnouncementReceiver
            .pickNewestLiveCallKey(candidates)
        if (adopted == null) return
        Log.w(TAG, "CALL rebind saw ${candidates.size} live call(s)")
        CallerAnnouncementReceiver.adoptLiveNotificationCall(adopted)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (activeInstance == this) activeInstance = null
        isConnected = false
        SmsNlsDeduplicator.clear()
        MessagingStyleTracker.clear()
        AnnouncementSpeaker.getInstance(applicationContext).stop()
        Log.w(TAG, "NotificationListener disconnected — محاولة إعادة الربط")
        // إعادة الربط التلقائي بعد فصل النظام (توفير الطاقة/إيقاف مؤقت)
        // حتى لا تتوقف قراءة الإشعارات دون تدخل المستخدم.
        try {
            requestRebind(
                ComponentName(this, NateqNotificationListener::class.java)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "requestRebind failed", t)
        }
    }

    /** قراءة الرسائل النصية الواردة عبر إشعار تطبيق الرسائل
     * (بديل NLS بدل إذن RECEIVE_SMS). */
    private fun handleSmsNotification(
        sbn: StatusBarNotification,
        settings: SettingsRepository,
        smsMode: String
    ) {
        val notification = sbn.notification ?: return
        val extras = notification.extras

        // في إشعارات تطبيقات الرسائل: العنوان يحمل اسم/رقم المرسل
        // عادةً والنص المحتوى.
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim()
        if (sender.isNullOrBlank() && body.isNullOrBlank()) return

        val displayAddress = sender ?: getString(R.string.sms_unknown_sender)
        val voiceId = settings.getSmsReadingVoiceId()
        val speechRate = settings.getSmsReadingRate()
        val volume = settings.getSmsReadingVolume()

        val content = (body ?: "").trim()
            .take(SmsReadingReceiver.MAX_SMS_CONTENT_LENGTH)

        // منع التكرار لمسار SMS عبر NLS (البند 24)
        if (SmsNlsDeduplicator.shouldDrop(
                notificationKey = sbn.key,
                notificationId = sbn.id,
                sender = sender,
                content = content,
                nowMs = System.currentTimeMillis()
            )
        ) {
            return
        }

        // خصوصية قفل الشاشة: عند القفل يُنطق المصدر فقط
        // دون المحتوى (حماية OTP).
        val privacyLocked = settings.isLockScreenPrivacyEnabled()
                && settings.isDeviceScreenLocked()
        val effectiveMode = SmsReadingReceiver.resolveEffectiveMode(
            mode = smsMode,
            privacyLocked = privacyLocked
        )

        val useArabicVoice = SmsReadingReceiver.resolveUseArabicVoice(
            displayAddress = displayAddress,
            content = content
        )

        val template = settings.getSmsAnnouncementTemplate()
        // فلتر رمز التحقق (OTP): لا يُنطق الرمز نفسه في الأماكن العامة.
        val isOtp = LocaleUtils.containsOtp(content)
        val smsFrom = LocaleUtils.stringForSpeech(
            applicationContext,
            if (useArabicVoice) LanguageCode.AR.tag else LanguageCode.EN.tag,
            R.string.sms_from,
            R.string.sms_from
        ).replace("{name}", displayAddress)

        val otpSafeText = LocaleUtils.stringForSpeech(
            applicationContext,
            if (useArabicVoice) LanguageCode.AR.tag else LanguageCode.EN.tag,
            R.string.sms_otp_safe,
            R.string.sms_otp_safe
        ).replace("{name}", displayAddress)

        // توحيد أولويات الخصوصية والقالب بين المسارين (البند 22)
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = privacyLocked,
            isOtp = isOtp,
            smsFrom = smsFrom,
            otpSafeText = otpSafeText,
            template = template,
            content = content,
            displayAddress = displayAddress,
            effectiveMode = effectiveMode
        )

        val isArabic = LocaleUtils.containsArabic(text)
        val locale =
            if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag)
            else Locale.forLanguageTag(LanguageCode.EN.tag)

        Log.d(TAG, "SMS via NLS: ${text.length} chars")

        val (smsSenderName, smsSenderKey) =
            extractNotificationSender(notification)
        val effectiveSender = smsSenderName ?: displayAddress
        val resolvedVoice = A11yVoiceResolver.resolve(
            context = applicationContext,
            settings = settings,
            packageName = sbn.packageName,
            senderName = effectiveSender,
            conversationKey = smsSenderKey ?: displayAddress
        )
        val isVoiceLangMatch = isVoiceMatchingLanguage(
            resolvedVoice, locale.language
        )

        val basePitch = settings.getSmsReadingPitchOrDefault(
            locale.language
        )
        val finalRate = resolvedVoice?.speechRate ?: speechRate
        val finalPitch = resolvedVoice?.pitch ?: basePitch
        val finalVolume = resolvedVoice?.volume ?: volume
        val defaultSmsEngine = settings.getEngineForCategory(
            SettingsRepository.ANNOUNCE_CATEGORY_SMS
        )
        val finalEngine = if (
            isVoiceLangMatch && resolvedVoice?.engine != null
        ) {
            resolvedVoice.engine
        } else {
            defaultSmsEngine
        }
        val finalVoice = if (
            isVoiceLangMatch && resolvedVoice?.voiceId != null
        ) {
            resolvedVoice.voiceId
        } else {
            voiceId
        }

        val speech = AnnouncementSpeaker.getInstance(applicationContext)
        speech.resetVoice(finalVoice)
        speech.speak(
            text, locale, finalRate, finalPitch, finalVolume,
            engineOverride = finalEngine,
            category = SettingsRepository.ANNOUNCE_CATEGORY_SMS
        )
        Log.i(
            TAG,
            "SMS speech via NLS: sender=$effectiveSender, " +
                "matched=${resolvedVoice != null}, " +
                "voice=$finalVoice, engine=$finalEngine"
        )
    }

    private fun buildSpeechText(
        appName: String,
        title: String?,
        text: String?,
        privacyLocked: Boolean
    ): String {
        // لغة النطق من محتوى الإشعار (اسم التطبيق/العنوان/النص)
        // لا من لغة الواجهة
        val dynamicText = "$appName ${title.orEmpty()} ${text.orEmpty()}"
        val isArabic = !dynamicText.any { it.isLetter() } ||
            LocaleUtils.containsArabic(dynamicText)
        val lang = if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag
        // عند قفل الشاشة نكتفي باسم التطبيق دون أي مضمون.
        if (privacyLocked) {
            return LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_new, R.string.notif_new
            ).replace("{app}", appName)
        }
        return when {
            !title.isNullOrBlank() && !text.isNullOrBlank() ->
                LocaleUtils.stringForSpeech(
                    applicationContext, lang,
                    R.string.notif_from_title_text,
                    R.string.notif_from_title_text
                ).replace("{app}", appName)
                .replace("{title}", title)
                .replace("{text}", text)
            !title.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang,
                R.string.notif_from_title,
                R.string.notif_from_title
            ).replace("{app}", appName).replace("{title}", title)
            !text.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang,
                R.string.notif_from_text,
                R.string.notif_from_text
            ).replace("{app}", appName).replace("{text}", text)
            else -> LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_new, R.string.notif_new
            ).replace("{app}", appName)
        }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> getString(R.string.app_whatsapp)
            "com.whatsapp.w4b" -> getString(R.string.app_whatsapp_business)
            "org.telegram.messenger",
            "org.telegram.messenger.web" -> getString(R.string.app_telegram)
            "com.facebook.orca" -> getString(R.string.app_messenger)
            "com.instagram.android" -> getString(R.string.app_instagram)
            else -> packageName.substringAfterLast('.')
        }
    }
}
