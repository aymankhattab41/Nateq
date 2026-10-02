package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * مستقبل إعلان اسم المتصل الوارد.
 * يسمع ACTION_PHONE_STATE_CHANGED ويبحث عن اسم المتصل في دفتر الاتصالات
 * ثم ينطقه عبر [AnnouncementSpeaker].
 *
 * ملاحظات واقعية:
 * - يستقبل بث PHONE_STATE المحمي فقط إن مُنح إذن READ_PHONE_STATE وقت
 *   التشغيل (يُطلب عند تفعيل الميزة من الإعدادات).
 * - من أندرويد 12 (API 32) فأعلى، يصل رقم المتصل إلى حامل READ_CALL_LOG
 *   (أو التطبيق الافتراضي للاتصال)؛ والاسم يُبحث عنه في دفتر الاتصالات
 *   (READ_CONTACTS) ثم في سجل المكالمات (READ_CALL_LOG عبر CallerInfo).
 * - إن لم يُمنح الإذنان يُنطق "اتصال وارد" العام.
 */
@AndroidEntryPoint
class CallerAnnouncementReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NATEQ_CALLER"

        // **بند 5.2:** مقبضٌ مشترك لحلقة نطق المتصل النشطة عبر بثوث
        // PHONE_STATE المتعاقبة (كل حالة مصدرُها onReceive مستقل) — به
        // تُلغى حلقة التكرار عند الرد أو الإنهاء فوراً، بدل تركها تعيد
        // نطق الاسم فوق مكالمةٍ نشطة أو بعد انقضائها.
        @Volatile
        private var activeCallCycle: Job? = null

        /** آخر حالة هاتف وردت (بثوث PHONE_STATE المتعاقبة عبر مستقبلات
         *  جديدة) — لتمييز رنين مكالمةٍ ثانية أثناء مكالمة نشطة. */
        @Volatile
        private var lastPhoneState: String? = null

        /** هل المكالمة نشطة حالياً؟ يُسنَّع عند OFFHOOK ويُصفَّر عند IDLE —
         *  دليلٌ ثابتٌ يدوم عبر رنّات الانتظار المتكررة للرنين ذاته. */
        @Volatile
        private var callActive = false

        /** الرقم المحلول في جلسة الرنين الحالية */
        @Volatile
        internal var lastResolvedNumber: String? = null

        /** الاسم المحلول في جلسة الرنين الحالية */
        @Volatile
        internal var lastResolvedName: String? = null

        /** وقت بداية رنين المكالمة الحالية */
        @Volatile
        internal var ringingStartTime: Long = 0L

        /** هل أُعلن عن رنين هذه المكالمة بالفعل؟ */
        @Volatile
        internal var ringingAnnounced = false

        /**
         * الرقم الذي **أُعلن به فعلاً** في جلسة الرنين الحالية — لا
         * «آخر رقم شوهد». الفرقُ حاسم: حين كان يُسجَّل الرقم الوارد
         * في [lastResolvedNumber] **قبل** المقارنة، كانت
         * `rawNumber == lastResolvedNumber` تتحقّق **دائماً** متى وُجد
         * رقم، فالحارسُ لم يكن يفحص شيئاً.
         */
        @Volatile
        internal var announcedNumber: String? = null

        /**
         * الرقم الذي تنتظر دورتُه الجاريةُ حلَّه قبل أول إعلان — يُميَّز
         * به بثُّ `RINGING` المكرّر للمكالمة نفسها عن مكالمةٍ مختلفة.
         *
         * كان انضمامُ البث المكرّر مشروطاً بـ`rawNumber == null` وحدها،
         * فبثٌّ مكرّر **يحمل رقمه** — وهو الأشيع — يقع في فرع استبدال
         * الدورة: يُلغي الدورة الجارية ويفتح أخرى. والإلغاء لا يسري إلا
         * عند نقاط التعليق، فإن كانت الدورة داخل `speak()` آنذاك نطقت
         * الدورةُ الملغاة والجديدة معاً فنسمع «اتصال وارد» زائداً. الآن
         * يُقارَن الرقم فيُنضمّ المكرّر ويُستبدَل الاختلافُ فعلاً.
         */
        @Volatile
        internal var pendingRingNumber: String? = null

        /**
         * هل ينضمّ هذا البثّ إلى دورةٍ حيّة قائمة أم يستبدلها؟
         *
         * الانضمام يصحّ إذا لم يُعلن بعد، والدورة حيّة، والرقمان
         * متوافقان (أحدهما فارغ = لا دليل على اختلاف المكالمة، والفراغ
         * لا يعني مكالمةً أخرى). والاختلاف الصريح لرقمين غير فارغين
         * يعني مكالمةً جديدة فيجب أن تُلغى الدورةُ السابقة.
         */
        internal fun shouldJoinPendingCycle(
            alreadyAnnounced: Boolean,
            cycleActive: Boolean,
            pendingNumber: String?,
            incomingNumber: String?
        ): Boolean {
            if (alreadyAnnounced || !cycleActive) return false
            return pendingNumber.isNullOrBlank() ||
                incomingNumber.isNullOrBlank() ||
                pendingNumber == incomingNumber
        }

        /**
         * هل تُكبَت الرنةُ المتأخّرة بعد أن أُعلن بالفعل؟
         *
         * نعم ما دامت الجلسةُ واحدة، وكل انتقالٍ إلى `IDLE`/`OFFHOOK`
         * يصفّر `announcedNumber` ([resetRingingSession]) فتبتدئ جلسةٌ
         * جديدةٌ تُعلَن بلا كبح.
         *
         * **والحارسُ على حالة الجلسة لا على `activeCallCycle`:** ذلك عمرُ
         * كوروثين ينتهي في `finally` خلال أجزاء الثانية من `speak()` —
         * قبل أن يبدأ الصوت أصلاً — فيموت الحارسُ بعد أول إعلانٍ فلا يبقى
         * أثرٌ له. فالحارسُ هنا على الحالة الدائمة (`ringingAnnounced` +
         * `announcedNumber`).
         *
         * **إعلانٌ سابق بلا رقم** يعني أن الرقم لم يكن متاحاً وقتئذٍ، لا
         * أنه رقمٌ آخر — فبثٌّ لاحق يحمل رقماً لا يثبت أنه مكالمةٌ جديدة.
         * وهذا هو العيب الذي كان يُضاعف النطق: المنصّة ترسل `RINGING`
         * مرّتين للمكالمة الواحدة، الأولى بلا رقم فيبقى `announcedNumber`
         * على `null`، والثانية بعد نحو ستّ ثوانٍ ومعها الرقم. فالمقارنةُ
         * `"01287308580" == null` كانت تُقيَّم خطأً — أي «مكالمةٌ أخرى» —
         * فيُعاد فتح جدول التكرار كاملاً (٥ بلا اسم + ٥ بالاسم = ١٠ عند
         * repeat=٥). وكذلك `incomingNumber == null`: غيابُ الرقم ليس دليلاً
         * على مكالمة جديدة.
         *
         * خالصٌ قابل للاختبار.
         */
        internal fun shouldSuppressDuplicateAnnouncement(
            alreadyAnnounced: Boolean,
            announcedNumber: String?,
            incomingNumber: String?
        ): Boolean {
            if (!alreadyAnnounced) return false
            if (announcedNumber.isNullOrBlank()) return true
            if (incomingNumber == null) return true
            return incomingNumber == announcedNumber
        }

        /**
         * مهلة انتظار وصول رقم المتصل عند وصول بث فارغ.
         *
         * المنصّة تُرسل `PHONE_STATE/RINGING` مرّتين للمكالمة الواحدة:
* الأولى فوريةً بلا رقم، والثانية بعد نحو ستّ ثوانٍ ومعه الرقم
         * (رُصد ٦٫١s مرّتين متتاليتين على جهاز فعلي).
         *
         * **السبع ثوانٍ لم تعُد مقبولةً عند المستخدم** — صمتٌ طويلٌ
         * قبل أوّل كلمةٍ تُسمعه مكالمةً «ماتت». فصار الانتظارُ على
         * **الهوية الفورية** فقط، وهو أمدٌ قصيرٌ يُعطى للمسارات التي
         * تُعيد الاسمَ وقت الرنّة ([RingCallerIdentity]) فتنطق فوراً.
         *
         * وإن لم تصل هويةٌ خلال هذه المهلة، **لا تُنطق عبارةٌ عامةٌ
         * أبداً** — يُشغَّل [CueType.CALL_UNIDENTIFIED] مع اهتزاز
         * (قرار المستخدم: نغمة قصيرة بدل الكلمة المجرّدة)، ويبقى
         * الانتظارُ مفتوحاً على [CALLER_RESOLVE_GRACE_PERIOD_MS] +
         * [CALLER_IDENTITY_LATE_WAIT_MS] ليُعلَن الاسمُ متى وصل.
         */
        internal const val CALLER_RESOLVE_GRACE_PERIOD_MS = 1_500L

        /**
         * مهلة الانتظار **بعد** نفاد مهلة الهوية الفورية: نحتاجها لأن
         * الهوية قد تصل متأخرةً (بثّ الرقم بعد ~٦s كما رُصد، أو تأخّر
         * إشعار الهاتف). بلاها لبخلنا النغمة ثم أبخلنا بالاسم.
         *
         * تلتقط الهويةَ المتأخرةَ لتُعلَن **مرّةً واحدة** — لا تُفتح
         * دورةُ تكرارٍ ثانية (وذلك ما كان يضاعف النطق).
         */
        internal const val CALLER_IDENTITY_LATE_WAIT_MS = 6_500L

        /** فاصل فحص سجل المكالمات أثناء مهلة الانتظار (300 مللي ثانية) */
        internal const val CALLER_LOG_POLL_INTERVAL_MS = 300L

        /**
         * هل تتوفّر هويةٌ صالحة للنطق (اسمٌ أو رقم)؟ إن لم تتوفّر فالإجابة
         * «لا» تُلزم المسارَ بالنغمة والاهتزاز بدل العبارة العامة — وهذا
         * شرطُ المستخدم الحاكم: **لا نطق بلا هوية**.
         */
        internal fun hasSpeakableIdentity(
            number: String?,
            contactName: String?
        ): Boolean =
            !contactName.isNullOrBlank() || !number.isNullOrBlank()

        /**
         * سقف قفل الاستيقاظ لدورة المتصل (20 ثانية).
         *
         * يغطّي مهلةَ انتظار الرقم + نطقَ الجملة الأخيرة. وهو أطول من
         * [BROADCAST_ASYNC_WINDOW_MS] (10 ثوانٍ) لأن ذلك يحدّ *جدولَ
         * التكرار*، أما القفل فيمتدّ على الانتظار قبله — ولم يكن يشمله
         * أصلاً. سقفُ الـ ANR ([BROADCAST_SAFE_CAP_MS]) لا يُرفع: يبقى
         * 9 ثوانٍ لأن إنهاءَ `goAsync` متأخّراً يُجمد العملية، والامتدادُ
         * هنا على قفل الاستيقاظ فقط — فالنطق يبقى داخل `appScope` والخدمة
         * الأمامية تُبقي العمليةَ أماميةً بعد إنهاء البث.
         */
        internal const val CALLER_WAKE_LOCK_CAP_MS = 20_000L

/** تصفير حالة جلسة الرنين عند إنهاء المكالمة أو الرد عليها */
        internal fun resetRingingSession() {
            lastResolvedNumber = null
            lastResolvedName = null
            ringingStartTime = 0L
            ringingAnnounced = false
            announcedNumber = null
            pendingRingNumber = null
            // هوية الجلسة مشتركة مع إشعار الهاتف وخدمة الفرز، فلا
            // تتسرّب هوية مكالمةٍ إلى ما بعدها (بثّ IDLE متأخر).
            RingCallerIdentity.clear()
        }

        /** هل رنينُ الحالة الحالية رنينُ مكالمةٍ واردة أثناء مكالمة نشطة
         *  (مكالمة انتظار)؟ نعم إن كانت الحالة RINGING والمكالمة نشطة —
         *  بالعلم الثابت أو بانتقالٍ مباشر من OFFHOOK. خالصٌ قابل للاختبار. */
        internal fun isWaitingCall(
            previous: String?,
            inCall: Boolean
        ): Boolean =
            inCall || previous == TelephonyManager.EXTRA_STATE_OFFHOOK

        /** نافذة جدولة تكرارات نطق المتصل (بعد النطق الأول) — لا يرتبط بها
         *  عمرُ البث إطلاقاً (التكرارات تُجدول في النطاق العام appScope وتستمر
         *  بعد إنهاء الـ goAsync): سقفٌ داخلي لعدد التكرارات المنطقية فقط. */
        private const val BROADCAST_ASYNC_WINDOW_MS = 10_000L

        /** سقف أمان إنهاء بثّ goAsync — أقل من مهلة نظام البث (~10 ثوانٍ)
         *  بهامش واضح: يُنهى البث حتماً قبل حافة المهلة حتى لو علّق المحركُ
         *  صامتاً بلا onDone (السيناريو الذي كان يوصّل دورة النطق للحافة
         *  فيقع ANR «إيقاف مستمر» على الأجهزة الفعلية — كما رُصد على Galaxy
         *  A23 مع محرك TTS معطوب). **مرفوع من 6 إلى 9 ثوانٍ** بعد رصد
         *  بترِ الإعلانات الطويلة: 6 ثوانٍ كانت تقطع أيّ إعلانٍ يتجاوزها
         *  بتجميد العملية (Process Cgroup Freezer) بلا خطأ.
         *
         *  **ثابتٌ لا يُرفع مهما طالت الميزانيةُ الزمنية:** فتمديد مهلة
         *  انتظار رقم المتصل إلى سبع ثوانٍ ثمّ نطقُ التكرارات يُخرج
         *  النطق عن نافذة البث عمداً — ويُبقى هذا السقف على حاله ليُنهى
         *  البث قبل حافة نظام التشغيل، ويبقى ما بعده في `appScope` خارج
         *  نافذة البث. */
        internal const val BROADCAST_SAFE_CAP_MS = 9_000L

        /** كم عدد الخانات الرقمية الواجب تطابقها في المطابقة الذكية الأخيرة
         *  (المطابقة بآخر 8 خانات). */
        private const val SMART_SUFFIX_DIGITS = 8

        /** عتبةُ كفايةِ الطرفين في المطابقة الذكية قبل احتمالية تقاربٍ في
         *  ذيلِ الأطول عبر [android.telephony.PhoneNumberUtils.compare]:
         *  تنسجم مع أرضيةِ المطابقة الداخلية للـ API (7 خانات) فلا نفتح
         *  نافذةً أعرض بلا داعٍ، مع بقاءِ نافذةِ الذيل الثماني الاسمية
         *  (SMART_SUFFIX_DIGITS) ساريةً في المطابقة الاسمية. */
        private const val MIN_SUFFIX_MATCH_DIGITS = 7

        /** عتبة طول الرقمين (بالخانات) للسماح بمطابقة آخر 8 خانات — تجنباً
         *  للتصادم على الأرقام القصيرة حيث البادئة جزءٌ من الهوية. */
        private const val MIN_SMART_MATCH_DIGITS = 8

        /** جدول إطلاق تكرارات النطق (بعد النطق الأول): كل [intervalMs]
         *  حتى بلوغ [windowMs] — لا يُجدوَل أي تكرارٍ على حافةِ السقف أو
         *  خارجه حتى لا تبلغ عمليةُ البثِ مهلة النظام. خالصٌ قابلٌ للاختبار. */
        internal fun repeatSchedule(
            repeat: Int,
            intervalMs: Long,
            windowMs: Long
        ): List<Long> {
            val count = (repeat - 1).coerceAtLeast(0)
            if (count == 0) return emptyList()
            val launches = mutableListOf<Long>()
            var cursor = 0L
            var index = 0
            while (index < count) {
                index++
                val next = cursor + intervalMs
                if (next >= windowMs) break
                launches += next
                cursor = next
            }
            return launches
        }
    }

    /** مصدر الإعدادات المحقون — كائن واحد مشترك عبر العمليات
     * (keeps تفضيلات المتصل). */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED
        ) {
            return
        }

        // goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        // حارس إنهاء وحيد لدورة البث على مستوى onReceive: أيُّ سابقٍ —
        // اكتمالُ النطق الفعلي (مستمع الاكتمال)، سقفُ الأمان، أو finally
        // التعويضي — يُنهي pendingResult مرةً واحدة (finishٌ مكررٌ يرمي
        // تحذيراً ولا لزوم له). ذرّيٌ ليتحمل وصولَ الإنهاء من خيطي البث
        // والنطق والحارس معاً.
        val finishedBroadcast = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        var finishFailsafe: Runnable? = null
        fun finishOnce() {
            if (finishedBroadcast.compareAndSet(false, true)) {
                finishFailsafe?.let { mainHandler.removeCallbacks(it) }
                pendingResult.finish()
            }
        }
        val failsafe = Runnable { finishOnce() }
        finishFailsafe = failsafe
        mainHandler.postDelayed(failsafe, BROADCAST_SAFE_CAP_MS)
        val appScope =
            (context.applicationContext as? AnnouncementAppContext)?.appScope
                ?: CoroutineScope(Dispatchers.Default)
        appScope.launch {
            var wakeLock: android.os.PowerManager.WakeLock? = null
            val state =
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == null) {
                // بلا حالة في البث — لا عمل: يُنهى البث فوراً (بدل تركه
                // معلقاً حتى حارس الأمان) ونخرج بهدوء.
                finishOnce()
                return@launch
            }
            // سجلّ الحالة قبل أي فرع: القراءة السابقة تخدم تمييز رنين
            // الانتظار، وتحديثُ وسم المكالمة النشطة يبقى متسقاً عبر البثوث.
            val previousState = lastPhoneState
            lastPhoneState = state
            if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                callActive = true
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                callActive = false
            }
            if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                // **بند 5.2:** كل انتقالٍ للحالة — الرد على المكالمة
                // (OFFHOOK) أو إنهاؤها (IDLE) — يُوقف النطق فوراً ويُلغي
                // حلقة التكرار النشطة. قبل هذا كان المستقبل يهملُ غير
                // الرنين: فيبقى كوروتينُ التكرار حياً يعيد نطقَ اسم
                // المتصل فوق المكالمة النشطة/بعد انتهائها. ويُنهى البث
                // فوراً — كان يُترك معلقاً حتى مهلة النظام فيقع ANR.
                if (state == TelephonyManager.EXTRA_STATE_OFFHOOK ||
                    state == TelephonyManager.EXTRA_STATE_IDLE
                ) {
                    val cycle = activeCallCycle
                    activeCallCycle = null
                    cycle?.cancel()
                    resetRingingSession()
                    runCatching {
                        AnnouncementSpeaker.getInstance(context).stop()
                    }
                }
                finishOnce()
                return@launch
            }

            @Suppress("DEPRECATION")
            val rawNumber = intent.getStringExtra(
                TelephonyManager.EXTRA_INCOMING_NUMBER
            )?.trim()?.takeIf { it.isNotBlank() }

            // **تتبّع تشخيصي مؤقّت:** يُظهر تسلسلَ البثوث كاملاً (الحالة
            // السابقة والحالية والرقم) فميّز بثّاً مكرراً لمكالمةٍ واحدة
            // من مكالمةٍ جديدة فعلاً، وميّز إعادةَ الجلسة من IDLE.
            // **المستوى `w` لا `d`:** قواعد ProGuard تحذف `Log.d`
            // بالكامل من نسخة release، فالتتبّع بـ`d` لا يُكتب أصلاً
            // ولا يظهر في تقرير الأخطاء (وهو ما أخفى التشخيص سابقاً).
            Log.w(
                TAG,
                "RX state=$state prev=$previousState" +
                    " num=${rawNumber ?: "?"} announced=$announcedNumber" +
                    " flag=$ringingAnnounced" +
                    " cycle=${activeCallCycle?.isActive}" +
                    " t=${System.currentTimeMillis()}"
            )

if (rawNumber != null) {
                    lastResolvedNumber = rawNumber
                    RingCallerIdentity.publish(rawNumber, null)
                }

            // **حارس منع التكرار — على حالة الجلسة لا على عمر الكوروثين.**
            // كان معلقاً على `activeCallCycle?.isActive` وهو عمرُ
            // كوروثين ينتهي فور `speak()`، فيموت الحارسُ بعد أول إعلان
            // فلا يمنع بثّ `RINGING` الثاني للمكالمة نفسها — وهو ما
            // يجعل بعض الأجهزة تنطق «اتصال وارد» ثلاثاً قبل الاسم.
            // والمقارنةُ صارت على [announcedNumber] (رقمُ ما أُعلن)
            // لا على [lastResolvedNumber] (آخرُ رقم شوهد) الذي كُتب
            // قبلها بسطر، فكانت المقارنةُ تتحقّق دائماً ولا تفحص شيئاً.
            if (shouldSuppressDuplicateAnnouncement(
                    alreadyAnnounced = ringingAnnounced,
                    announcedNumber = announcedNumber,
                    incomingNumber = rawNumber
                )
            ) {
                Log.w(
                    TAG,
                    "suppressed duplicate ring announcement" +
                        " (announced=${announcedNumber ?: "?"}," +
                        " incoming=${rawNumber ?: "?"})"
                )
                finishOnce()
                return@launch
            }

            // بثٌّ مكرّر للمكالمة نفسها: ينضمّ إلى الدورة الحيّة القائمة
            // بدل إلغائها وفتح دورةٍ جديدة — فينتظر مرةً واحدة فقط
            // ويظهر «اتصال وارد» عددَ مرّات الإعداد لا أكثر.
            if (shouldJoinPendingCycle(
                    alreadyAnnounced = ringingAnnounced,
                    cycleActive = activeCallCycle?.isActive == true,
                    pendingNumber = pendingRingNumber,
                    incomingNumber = rawNumber
                )
            ) {
                Log.w(
                    TAG,
                    "joined in-flight cycle (same call," +
                        " pending=${pendingRingNumber ?: "?"})"
                )
                finishOnce()
                return@launch
            }

            // وصول بث برقم أو رنين جديد: استبدال الدورة السابقة وأخذ المقبض
            val previousCycleActive = activeCallCycle?.isActive == true
            activeCallCycle?.cancel()
            activeCallCycle = coroutineContext.job
            // **الرقم الذي تنتظر هذه الدورة حلَّه** — به يتميّز بثُّ
            // RINGING المكرّر للمكالمة نفسها عن مكالمةٍ جديدة فعلاً.
            pendingRingNumber = rawNumber ?: lastResolvedNumber
            if (ringingStartTime == 0L) {
                ringingStartTime = System.currentTimeMillis()
            }
            // **تتبّع تشخيصي:** يكشف أيّ بثّ RINGING يبدأ دورةً جديدة
            // رغم وجود دورةٍ حيّة — وهو ما يعيد نطق «اتصال وارد» رابعاً.
            Log.w(
                TAG,
                "START new cycle (replacingLive=${previousCycleActive}," +
                    " announcedWas=$ringingAnnounced," +
                    " num=${rawNumber ?: "?"})"
            )
            // مستمعُ اكتمالٍ يُسجَّل في try ويُزال في finally (بند [8]) —
            // لا يبقى مسجلاً بعد نافذة البث فلا يُستدعى في دورةٍ لا تخصنا.
            var completionListener: (() -> Unit)? = null
            // عدّاد النطقات الفعليّة في هذه الدورة — للتتبّع فقط.
            var speakCounter = 0
            try {
                // فحص وقائي: وصول بث PHONE_STATE بحد ذاته يتطلب
                // منح READ_PHONE_STATE وقت الإرسال (النظام يفلتر
                // المستقبلين، وليس إعلان الـ Manifest فقط). وعلى
                // أندرويد 12+ يُشرَط READ_CALL_LOG أيضاً— بدونه لا يصل
                // رقم المتصل فيُصمت الإعلان عاماً بلا اسم. سحب النظام
                // التلقائي للأذونات (ابتداءً من أندرويد 11، ويشتد على
                // أندرويد 17) قد يخطف البث قبل وصوله — إن وصلنا هنا
                // رغم فقدانه نتوقف بهدوء بدل نطق نص وسط مكالمة أو رمي
                // SecurityException. المعالجة مجزّأة في
                // [disableAfterPermissionRevoked] قابلةً للاختبار.
                if (!hasCallerPermission(context)) {
                    Log.w(
                        TAG,
                        "READ_PHONE_STATE revoked; caller" +
                        " announcement auto-disabled"
                    )
                    // شفاء ذاتي: إن كان التفعيل قائماً رغم سحب الإذن نطفئه
                    // ونُعيد تقييم الخدمة — بدل تركه «مفعّلاً» صامتاً.
                    disableAfterPermissionRevoked(
                        settingsRepository, context
                    )
                    finishOnce()
                    return@launch
                }

                val state =
                    intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == null) {
                    finishOnce()
                    return@launch
                }
                if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                    finishOnce()
                    return@launch
                }

                val settings = settingsRepository
                if (!settings.isCallerAnnouncementEnabled()) {
                    finishOnce()
                    return@launch
                }
                // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
                if (!settings.isAllAnnouncementsEnabled()) {
                    finishOnce()
                    return@launch
                }

                // ⚠️ تحذير معماري: لا تُضِف أي تدفئة مسبقة (warmEngine) لمحرك
                // إعلان المتصل عند الرنّة. جُرِّب هذا سابقاً بنيّة تسريع أول
                // نطق (كمون التهيئة الباردة 150-800ms)، لكنه تسابق مع مسار
                // النطق الفعلي على نفس مثيل المحرك فعطّل الميزة بالكامل (صمتٌ
                // تام عند بعض/كل المكالمات). إن أردت تسريع أول نطق مستقبلاً،
                // استهدف مساراً مختلفاً لا يشارك نفس مثيل TextToSpeech
                // المستخدَم في مسار النطق الحقيقي — أو أضِف قفلاً صريحاً يمنع
                // تشغيل التدفئة والنطق الفعلي في آنٍ واحد.

                // رنينُ مكالمةٍ ثانية أثناء مكالمة نشطة (مكالمة انتظار):
                // لا يُنطق اسمها إلا إن فعّل المستخدم مربع «نطق اسم المتصل
                // أثناء المكالمة» (غير محدد افتراضياً) — وخارجه يُصمت هنا.
                val waitingCall = isWaitingCall(
                    previousState,
                    callActive
                )
                if (waitingCall &&
                    !settings.isCallerAnnouncementDuringCallEnabled()
                ) {
                    finishOnce()
                    return@launch
                }

                val hasCallLog = hasPermission(
                    context, Manifest.permission.READ_CALL_LOG
                )
                val hasContacts = hasPermission(
                    context, Manifest.permission.READ_CONTACTS
                )

                var incomingNumber = rawNumber ?: lastResolvedNumber
                var contactName = lastResolvedName

                // **ميزانية الاستيقاظ تُحسب هنا لا بعد الانتظار:** قفلُ
                // الاستيقاظ كان يُكتسب بعد حلقة الانتظار، فمع مهلةٍ
                // سبعَ ثوانٍ (=`CALLER_RESOLVE_GRACE_PERIOD_MS`) كان
                // الانتظارُ يجري بلا استيقاظ، و`delay()` على
                // `Dispatchers.IO` والشاشةُ مطفأة يتأخّر فيُفشِل الانتظارُ
                // في مهمّته. فصار القفل يغطّي الانتظارَ والنطقَ معاً.
                val repeat = settings
                    .getCallerAnnouncementRepeat().coerceIn(1, 5)
                val intervalMs = settings.getCallerAnnouncementIntervalSeconds()
                    .coerceIn(1, 10) * 1000L
                val schedule = repeatSchedule(
                    repeat, intervalMs, BROADCAST_ASYNC_WINDOW_MS
                )
                val speechWakeMs = if (schedule.isEmpty()) {
                    TimeAlarmReceiver.SHORT_WAKE_LOCK_MS
                } else {
                    (schedule.lastOrNull() ?: 0L) +
                        TimeAlarmReceiver.SHORT_WAKE_LOCK_MS
                }
                wakeLock = TimeAlarmReceiver.acquireShortWakeLock(
                    context,
                    (CALLER_RESOLVE_GRACE_PERIOD_MS + speechWakeMs)
                        .coerceAtMost(CALLER_WAKE_LOCK_CAP_MS)
                )

                // مهلة سماح عند وصول بث فارغ: ننتظر ونفحص المصادر
                // الثلاثة (الهوية المشتركة، آخر رقمٍ محلول، سجلّ
                // المكالمات) دورياً. **ننتظر الاسمَ لا الرقم:** خدمةُ
                // الفرز وإشعارُ الهاتف قد ينشران الرقمَ أوّلاً ثم الاسمَ
                // بعده بمئات المللي ثانية؛ فإن كسرنا الحلقة على الرقم
                // وحده أعلنّا الرقمَ وضاع الاسمُ الذي وصل بعدها بقليل.
                // فالرقمُ الوحيد لا يُنهي الانتظار — نُنهيه عند توفّر
                // **اسم** أو نفاد المهلة، فنقع في الدورة الواحدة ولا
                // تُفتح دورةٌ ثانيةٌ تضاعف النطق.
                if (contactName == null && !ringingAnnounced) {
                    val elapsed =
                        System.currentTimeMillis() - ringingStartTime
                    val remainingGrace = (CALLER_RESOLVE_GRACE_PERIOD_MS -
                        elapsed).coerceAtLeast(0L)
                    var waited = 0L
                    var triedLocalResolve = false
                    while (waited < remainingGrace) {
                        delay(CALLER_LOG_POLL_INTERVAL_MS)
                        waited += CALLER_LOG_POLL_INTERVAL_MS
                        val fromLog = if (hasCallLog) {
                            resolveLatestCallFromLog(context, hasCallLog)
                        } else {
                            null
                        }
                        val merged = mergeCallerIdentity(
                            number = incomingNumber,
                            name = contactName,
                            shared = RingCallerIdentity.snapshot(),
                            lastResolved = Pair(
                                lastResolvedNumber, lastResolvedName
                            ),
                            fromLog = fromLog
                        )
                        incomingNumber = merged.first
                        contactName = merged.second
                        if (incomingNumber != null) {
                            lastResolvedNumber = incomingNumber
                        }
                        // حلُّ الاسم محلياً مرّةً واحدة (PhoneLookup
                        // فوري) فلا نؤخّر مكالمةَ جهةٍ محفوظة أبداً.
                        if (contactName == null &&
                            incomingNumber != null &&
                            !triedLocalResolve
                        ) {
                            triedLocalResolve = true
                            val custom = resolveCustomName(
                                settings, incomingNumber
                            )
                            contactName = custom ?: resolveContactName(
                                context,
                                number = incomingNumber,
                                hasReadContacts = hasContacts,
                                hasReadCallLog = hasCallLog
                            )
                        }
                        if (identityHasName(contactName)) break
                    }
                }

                // استرداد بديل من سجل المكالمات إن حجب أندرويد 10+ الرقم
                if (incomingNumber == null && hasCallLog) {
                    val fallbackCall = resolveLatestCallFromLog(
                        context, hasCallLog
                    )
                    incomingNumber = fallbackCall?.first
                    if (contactName == null) {
                        contactName = fallbackCall?.second
                    }
                }

                if (incomingNumber != null) {
                    lastResolvedNumber = incomingNumber
                }

                // الاسم المخصص للمستخدم له الأولوية القصوى، ثم دفتر
                // الاتصالات ثم سجل المكالمات.
                if (contactName == null && incomingNumber != null) {
                    val customName = resolveCustomName(
                        settings, incomingNumber
                    )
                    contactName = customName ?: resolveContactName(
                        context,
                        number = incomingNumber,
                        hasReadContacts = hasContacts,
                        hasReadCallLog = hasCallLog
                    )
                }

                if (contactName != null) {
                    lastResolvedName = contactName
                }

                // إعلان اسم المتصل ورقمه ينطق دائماً عند رنين الهاتف حتى لو
                // كانت الشاشة مقفلة (الهدف الأساسي للمكفوفين وسائقي المركبات).
                val privacyLocked = false

                // **لا نطق بلا هوية — القاعدة الحاكمة (طلب المستخدم).**
                // إن لم يتوفّر اسمٌ ولا رقمٌ بعد مهلة الهوية الفورية
                // فلا تُنطق عبارةٌ عامةٌ واحدة: تُشغَّل نغمة
                // [CueType.CALL_UNIDENTIFIED] مع اهتزاز (قرار المستخدم)،
                // ثم ننتظر [CALLER_IDENTITY_LATE_WAIT_MS] لعلّ الهوية
                // تصل متأخرةً (بثّ الرقم بعد ~٦s أو تأخّر إشعار
                // الهاتف) فنُعلِنها **مرّةً واحدة**.
                if (!hasSpeakableIdentity(incomingNumber, contactName)) {
                    Log.w(
                        TAG,
                        "no identity after grace — cue+vibration," +
                            " waiting up to" +
                            " ${CALLER_IDENTITY_LATE_WAIT_MS}ms"
                    )
                    playUnidentifiedCallAlert(context)
                    val late = awaitLateIdentity(context, hasCallLog)
                    if (late == null) {
                        Log.w(
                            TAG,
                            "identity never arrived — silent end" +
                                " (no generic phrase by design)"
                        )
                        finishOnce()
                        return@launch
                    }
                    incomingNumber = late.first ?: incomingNumber
                    contactName = late.second ?: contactName
                    // ثم تُحلّ الهوية بتسلسل الأولوية نفسه (مخصص ثم دفتر).
                    if (contactName == null && incomingNumber != null) {
                        val custom = resolveCustomName(
                            settings, incomingNumber
                        )
                        contactName = custom ?: resolveContactName(
                            context,
                            number = incomingNumber,
                            hasReadContacts = hasContacts,
                            hasReadCallLog = hasCallLog
                        )
                    }
                }

                var text = buildAnnouncementText(
                    context,
                    number = incomingNumber,
                    contactName = contactName,
                    template = settings.getCallerAnnouncementTemplate(),
                    privacyLocked = privacyLocked,
                    numberReadingMode = settings.getNumberReadingMode()
                )

                val speechRate = settings.getCallerAnnouncementRate()
                val volume = settings.getCallerAnnouncementVolume()
                var hasArabic = callerSpeechLanguage(
                    contactName, incomingNumber
                ) == LanguageCode.AR.tag
                var locale = if (hasArabic) {
                    Locale.forLanguageTag(LanguageCode.AR.tag)
                } else {
                    Locale.forLanguageTag(LanguageCode.EN.tag)
                }

                val speaker = AnnouncementSpeaker.getInstance(context)
                // نعيد ضبط الصوت المفضّل لدورة المتصل قبل كل نطق
                // (عربي/إنجليزي حسب لغة النص الفعلي) حتى لا يبقى
                // عالقاً على صوتٍ من دورة سابقة (إشعار/رسالة...) —
                // نفس النمط المطبّق في SmsReadingReceiver.
                var callerVoice = callerVoice(settings, hasArabic)
                speaker.resetVoice(callerVoice)

                // تكرار النطق «repeat» مرات بفاصل «intervalMs»؛ الأول يقع
                // فوراً. التكرارات تُجدول داخل النطاق العام appScope نفسه (لا
                // تُربط بحياة البث): إنهاءُ الـ goAsync مبكراً (اكتمالُ أول
                // جملة فعلياً أو حارسُ الأمان) لا يقطعها — فتبقى تُنطق حتى
                // لو جُمّدت العملية لاحقاً (الخدمة الأمامية التي يضمنها
                // النطق تُبقي العملية أماميةً غالباً).
                // (جدولُ التكرار وقفلُ الاستيقاظ حُسبا قبل حلقة الانتظار.)
                // بند 2.1/2.2: نبرة «نطق المتصل» المستقلة (بديل: نبرةُ نطق
                // اللغة) — نبرةُ الحلقةِ كاملةً.
                var pitch = settings.getCallerAnnouncementPitchOrDefault(
                    locale.language
                )
                if (schedule.isEmpty()) {
                    completionListener = { finishOnce() }
                    speaker.addCompletionListener(completionListener!!)
                }
                // **تتبّع تشخيصي:** يُسجّل النصّ المنطوق فعلياً في كل
                // نطق — يكشف أي إعلانٍ رابع زائد ومن أين جاء.
                speakCounter++
                Log.w(
                    TAG,
                    "SPEAK#$speakCounter text=$text num=" +
                        "${incomingNumber ?: "?"} name=${contactName ?: "?"}"
                )
                speaker.speak(
                    text, locale, speechRate, pitch, volume,
                    engineOverride = callerSpeechEngine(
                        settings, locale.language
                    ),
                    category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                )
                ringingAnnounced = true
                // انتهى الانتظار: لا داعي لتمييز البث المكرّر بعده —
                // الحارس الأعلى (`ringingAnnounced`) يتكفّل به من الآن فصاعداً.
                pendingRingNumber = null
                // **رقمُ ما أُعلن فعلاً** — يُلتقط قبل أي إعادة حلّ
                // في نبضات التكرار، لأن الحارس يقارن به لا بآخر رقم
                // شوهد. يُثبَّت مرّةً واحدة (أول إعلان) فلا تتبعه
                // تغييراتُ حلّ الاسم اللاحقة.
                if (announcedNumber == null) {
                    announcedNumber = lastResolvedNumber ?: rawNumber
                }
                // **بند 5.5:** إنهاءٌ مبكر بمستمع الاكتمال: محركٌ سليم يُنهي
                // البث فور اكتمال (onDone) الجملة الأولى فعلياً — بلا حجزٍ
                // أطول من اللازم ولا ذيلِ صوتٍ مبتور (جمدُ العملية بعد
                // finish() كان يقتطع آخر الصوت على أندرويد 14+). يُسجَّل في
                // قائمة مستمعي المتحدث المشترك (بند [8]) فلا يطمس خطاف أداة
                // الساعة أو مستقبلٍ آخر، ويُزال في finally.
                val appCtx = context.applicationContext
                // تكرارُ الإعلان (وإن كان لرنينِ انتظارٍ أثناء مكالمة) يتبع
                // جدولَ الإعدادات نفسه: عدد مرات وفواصل مضبوطة بحد أقصى نافذة
                // البث — فإعلانُ المتصل أثناء مكالمة نشطة يُكرَّر كباقي الرنّات
                // (لا تخفيفٌ لنطقٍ واحد فوقها بلا سبب).
                if (schedule.isNotEmpty()) {
                    var lastLaunchMs = 0L
                    for ((index, offsetMs) in schedule.withIndex()) {
                        delay(offsetMs - lastLaunchMs)
                        lastLaunchMs = offsetMs
                        if (index == schedule.lastIndex) {
                            completionListener = { finishOnce() }
                            speaker.addCompletionListener(completionListener!!)
                        }
                        // إن كان الاسم مفقوداً في النطق الأول وتحقق
                        // لاحقاً، نحدّث نص النطق واللغة والصوت للتكرارات
                        if (contactName == null) {
                            val num = lastResolvedNumber ?: (if (hasCallLog) {
                                resolveLatestCallFromLog(
                                    context, hasCallLog
                                )?.first
                            } else null)
                            if (num != null) {
                                incomingNumber = num
                                val resolved = lastResolvedName
                                    ?: resolveCustomName(settings, num)
                                    ?: resolveContactName(
                                        context, num, hasContacts, hasCallLog
                                    )
                                if (resolved != null) {
                                    contactName = resolved
                                    lastResolvedName = resolved
                                    text = buildAnnouncementText(
                                        context,
                                        number = incomingNumber,
                                        contactName = contactName,
                                        template = settings
                                            .getCallerAnnouncementTemplate(),
                                        privacyLocked = privacyLocked,
                                        numberReadingMode = settings
                                            .getNumberReadingMode()
                                    )
                                    hasArabic = callerSpeechLanguage(
                                        contactName, incomingNumber
                                    ) == LanguageCode.AR.tag
                                    locale = if (hasArabic) {
                                        Locale.forLanguageTag(
                                            LanguageCode.AR.tag
                                        )
                                    } else {
                                        Locale.forLanguageTag(
                                            LanguageCode.EN.tag
                                        )
                                    }
                                    callerVoice = callerVoice(
                                        settings, hasArabic
                                    )
                                    speaker.resetVoice(callerVoice)
                                    pitch = settings
                                        .getCallerAnnouncementPitchOrDefault(
                                            locale.language
                                        )
                                }
                            }
                        }
                        try {
                            speakCounter++
                            Log.w(
                                TAG,
                                "SPEAK#$speakCounter(repeat+" +
                                    "${offsetMs}ms) text=$text num=" +
                                    "${incomingNumber ?: "?"}" +
                                    " name=${contactName ?: "?"}"
                            )
                            AnnouncementSpeaker.getInstance(appCtx).speak(
                                text, locale, speechRate, pitch,
                                volume,
                                engineOverride = callerSpeechEngine(
                                    settings, locale.language
                                ),
                                category =
                                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                            )
                        } catch (t: Throwable) {
                            Log.e(TAG, "repeat speak failed", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                // الإلغاء (بند 5.2: الرد/الإنهاء) ليس عطلاً — يُنهيه
                // finally أدناه ويُنظّف، وانتظارُ الجدولة يُحرَّر بلا صخب.
                if (t is kotlinx.coroutines.CancellationException) {
                    throw t
                }
                Log.e(TAG, "onReceive failed", t)
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                // تعويضي: إن انحرف المسار قبل أذرعة الإنهاء أعلاه (استثناء)
                // يُنهى البث هنا — وإن سبق إنهاؤه فلا يُنهى ثانية. ويُزال
                // حارس الأمان — لا يبقى مسجلاً بعد اكتمال الدورة.
                finishFailsafe?.let { mainHandler.removeCallbacks(it) }
                completionListener?.let { listener ->
                    // إزالة مستمعنا حتى لا يُستدعى في دورة نطقٍ لاحقة
                    runCatching {
                        AnnouncementSpeaker.getInstance(context)
                            .removeCompletionListener(listener)
                    }
                }
                finishOnce()
            }
        }
    }

    /**
     * معالجة بث المكالمة الواردة ونطق اسم المتصل: مُستخرجة لتيسير
     * الاختبار الآلي المباشر بلا حاجة لتطبيق Hilt كامل، ولاختبار
     * تزامن التدفئة المسبقة والنطق الفعلي.
     */
    internal fun announceIncomingCall(
        context: Context,
        settings: SettingsRepository,
        incomingNumber: String?,
        previousState: String? = null,
        callActive: Boolean = false
    ): Boolean {
        if (!settings.isCallerAnnouncementEnabled()) return false
        if (!settings.isAllAnnouncementsEnabled()) return false

        val waitingCall = isWaitingCall(previousState, callActive)
        if (waitingCall &&
            !settings.isCallerAnnouncementDuringCallEnabled()
        ) {
            return false
        }

        val customName = resolveCustomName(settings, incomingNumber)
        val contactName = customName ?: resolveContactName(
            context,
            number = incomingNumber,
            hasReadContacts = hasPermission(
                context, Manifest.permission.READ_CONTACTS
            ),
            hasReadCallLog = hasPermission(
                context, Manifest.permission.READ_CALL_LOG
            )
        )

        val privacyLocked = settings.isLockScreenPrivacyEnabled() &&
            settings.isDeviceScreenLocked()

        // **بلا هوية: لا نطق.** المسار المُختبَر يُستدعى مباشرةً وقد
        // يُمرَّر له رقمٌ فارغ، فلا يجوز أن ينطق عبارةً عامة. المسار
        // الحقيقي يحرس نفسه قبل الوصول (`playUnidentifiedCallAlert`)،
        // وهذا الحارسُ يجعل العقدَ محفوظاً في المسارِ المُختبَر أيضاً.
        if (!hasSpeakableIdentity(incomingNumber, contactName)) return false

        val text = buildAnnouncementText(
            context,
            number = incomingNumber,
            contactName = contactName,
            template = settings.getCallerAnnouncementTemplate(),
            privacyLocked = privacyLocked,
            numberReadingMode = settings.getNumberReadingMode()
        )

        val speechRate = settings.getCallerAnnouncementRate()
        val volume = settings.getCallerAnnouncementVolume()
        val hasArabic = callerSpeechLanguage(
            contactName, incomingNumber
        ) == LanguageCode.AR.tag
        val locale = if (hasArabic) {
            Locale.forLanguageTag(LanguageCode.AR.tag)
        } else {
            Locale.forLanguageTag(LanguageCode.EN.tag)
        }

        val speaker = AnnouncementSpeaker.getInstance(context)
        val callerVoice = callerVoice(settings, hasArabic)
        speaker.resetVoice(callerVoice)

        val pitch = settings.getCallerAnnouncementPitchOrDefault(
            locale.language
        )
        speaker.speak(
            text, locale, speechRate, pitch, volume,
            engineOverride = callerSpeechEngine(
                settings, locale.language
            ),
            category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER
        )
        return true
    }

    /**
     * انتظارُ الهوية المتأخرة بعد نفاد مهلة الهوية الفورية.
     *
     * يُفحص [RingCallerIdentity] (إشعار الهاتف وخدمة الفرز) أوّلَ كلّ
     * نبضة، ثم آخرُ رقمٍ حُلّ، ثم سجلُّ المكالمات — بترتيب الأولوية:
     * الأسرعُ فالأبطأ.
     *
     * @return زوج (الرقم، الاسم) إن توفّرت هوية، و`null` إن انقضت
     *   المهلةُ بلا هوية — فيُنهي المسارُ صامتاً بلا كلمةٍ عامة.
     */
    private suspend fun awaitLateIdentity(
        context: Context,
        hasCallLog: Boolean
    ): Pair<String?, String?>? {
        var waited = 0L
        while (waited < CALLER_IDENTITY_LATE_WAIT_MS) {
            delay(CALLER_LOG_POLL_INTERVAL_MS)
            waited += CALLER_LOG_POLL_INTERVAL_MS
            val shared = RingCallerIdentity.snapshot()
            if (hasSpeakableIdentity(shared.first, shared.second)) {
                return shared
            }
            if (hasSpeakableIdentity(lastResolvedNumber, lastResolvedName)) {
                return Pair(lastResolvedNumber, lastResolvedName)
            }
            if (hasCallLog) {
                val fromLog = resolveLatestCallFromLog(context, hasCallLog)
                if (hasSpeakableIdentity(fromLog?.first, fromLog?.second)) {
                    return fromLog
                }
            }
        }
        return null
    }

    /**
     * نغمةُ «مكالمة بلا هوية» + اهتزاز مميّز — بديلُ العبارة العامة.
     *
     * تُشغَّل عبر [AudioCuePlayer] فتستعمل نفس محرّك المؤثرات والبنية
     * الصوتية القائمة (لا كودِ صوتيٍّ جديد)، ونمطُ النغمة أطولُ
     * وأحدُّّ من نغمات البطارية ليسهل تمييزُه سمعياً.
     *
     * الاهتزازُ نمطٌ قصيرٌ متكرّر (300ms/150ms × 3) فهو محسوسٌ بلا أن
     * يزعج. كلٌّ منهما في `runCatching` — فشلُ الاهتزاز (لا اهتزاز في
     * الجهاز، إذنٌ مسحوب) يجب ألّا يُسقط النغمةَ ولا يُفقد الحدث.
     */
    private fun playUnidentifiedCallAlert(context: Context) {
        runCatching {
            AudioCuePlayer.getInstance(context).play(
                AudioCue(CueType.CALL_UNIDENTIFIED, volume = 0.7f)
            ) {}
        }.onFailure { Log.w(TAG, "unidentified call cue failed", it) }

        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(
                    android.os.VibratorManager::class.java
                )?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    longArrayOf(0, 300, 150, 300, 150, 300), -1
                )
            }
        }.onFailure { Log.w(TAG, "unidentified call vibration failed", it) }
    }

    private fun callerVoice(
        settings: SettingsRepository,
        hasArabic: Boolean
    ): String? = if (hasArabic) {
        settings.getCallerAnnouncementArabicVoiceId()
    } else {
        settings.getCallerAnnouncementEnglishVoiceId()
    }

    /** النص الصادق حسب ما هو متاح فعلاً (لا يدّعي "غير محفوظ" جزافاً). */
    private fun buildAnnouncementText(
        context: Context,
        number: String?,
        contactName: String?,
        template: String?,
        privacyLocked: Boolean,
        numberReadingMode: Int = 1
    ): String {
        // عند القفل: **صمتٌ تام** لا العبارة العامة. كان يُنطق «اتصال وارد»
        // حفاظاً على الخصوصية، وهو يناقض عقد «لا نطق بلا هوية»
        // (ترويسةٌ بلا هوية = تسميةٌ بلا معلومة) ويكشف وجودَ المكالمة
        // نفسها — وهو ما يريدُ الحاجبُ منعَه. فالخصوصيةُ تقتضي الصمت،
        // والإعلانُ يقتضي الهوية.
        return if (privacyLocked) {
            ""
        } else if (!template.isNullOrBlank()) {
            val filled = template
                .replace("{name}", contactName ?: number.orEmpty())
                .replace("{number}", number.orEmpty())
                .trim()
            if (filled.isBlank()) {
                buildDefaultCallerPhrase(
                    context, number, contactName, numberReadingMode
                )
            } else {
                filled
            }
        } else {
            buildDefaultCallerPhrase(
                context, number, contactName, numberReadingMode
            )
        }
    }

    /**
     * عبارة النطق الافتراضية مع قرار اللغة من الاسم/الرقم (عربي أم إنجليزي)
     * وليس من لغة واجهة التطبيق: مرسل عربي يُنطق بالعربية والعكس.
     */
    private fun buildDefaultCallerPhrase(
        context: Context,
        number: String?,
        contactName: String?,
        numberReadingMode: Int = 1
    ): String {
        val language = callerSpeechLanguage(contactName, number)
        val isArabic = language == LanguageCode.AR.tag
        val lang = language
        return when {
            contactName != null -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_from, R.string.caller_from
            ).replace("{name}", contactName)
            !number.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                context,
                lang,
                R.string.caller_from_number,
                R.string.caller_from_number
            ) + formatCallerNumberForSpeech(
                number, isArabic, numberReadingMode
            )
            // **لا تُنتج هذه الدالة نصاً بلا هوية أبداً** (العقد الحاكم): لا
            // يصل فرعُ الفارغ أبداً لأن الحلقة تُنهي المسارَ بنغمةٍ
            // واهتزاز قبل الوصول هنا، لكن الفرعَ يُبقيها صالحةً ولو
            // أُعيد استعمالها من مسارٍ آخر في المستقبل.
            else -> ""
        }
    }

    /**
     * الاسم المخصص من خريطة المستخدم (رقم -> اسم)، بأفضل مطابقة ممكنة:
     *  1) مطابقة رقمية دقيقة بعد تطبيع الطرفين (السلوك الأصلي المحافظ).
     *  2) مطابقة عبر PhoneNumberUtils (يتفطن لرمز البلد والترقيم).
     *  3) مطابقة آخر 8 خانات رقمية عند اختلاف البادئة فقط (رمز بلد
     *     أُضيف أو حُذف محلياً) — بشرط كفاية كل طرف على 8 خانات على
     *     الأقل حتى لا تتصادم الأرقام القصيرة. تُستبعد القيم الخاصة
     *     غير الحقيقية («-1»/«UNKNOWN») قبل التطبيع (انظر
     *     [normalizeCallerNumber]). مجزّأة إلى [matchCustomName] الخالصة
     *     القابلة للاختبار بلا SettingsRepository.
     */
    internal fun resolveCustomName(
        settings: SettingsRepository,
        number: String?
    ): String? = matchCustomName(settings.getCustomCallerNames(), number)

    /** قلب المطابقة الذكية أعلاه على خريطة أسماء ورقمٍ خام — خالصة
     *  لتُختبَر بلا SettingsRepository. */
    internal fun matchCustomName(
        customNames: Map<String, String>,
        number: String?
    ): String? {
        val normalized = normalizeCallerNumber(number) ?: return null
        customNames.entries.firstOrNull { entry ->
            equivalentByDigits(entry.key, normalized)
        }?.let { return it.value }
        if (normalized.length < MIN_SMART_MATCH_DIGITS) return null
        val suffix = normalized.takeLast(SMART_SUFFIX_DIGITS)
        return customNames.entries.firstOrNull { entry ->
            val keyDigits = entry.key.filter { c -> c.isDigit() }
            keyDigits.length >= MIN_SMART_MATCH_DIGITS &&
                keyDigits.takeLast(SMART_SUFFIX_DIGITS) == suffix
        }?.value
    }

    /** مطابقة رقمية: تتكافأ خانات الطرفين كاملةً، أو بعد تجريد بادئة الوصول
     *  الدولي («00» أو «011» — و«+» حرفٌ لا رقمٌ فتُسقطه الترقيم), أو عبر
     *  [PhoneNumberUtils.compare] لشكلٍ محليٍّ مقابل الدولي حيث الرقم الأقصر
     *  يساوي ذيل الأطول (رمز بلدٍ مُضاف أو محذوف) — يُستدعى الـ API الرسمي
     *  فعلاً كما اعتُمد، مع حارسِ كفايةِ الطرفين على عتبة الـ API نفسها
     *  وحصرِ التقاربِ في «ذيل الأطول» فلا يَقبلَ تبديلَ خانةٍ حاملةٍ محلية
     *  (فئة 050/051 أو 96650/96655) مطابقةً خاطئة. */
    // PhoneNumberUtils.compare مُهملٌ رسمياً — مُطلب بند المطابقة الذكية.
    @Suppress("DEPRECATION")
    private fun equivalentByDigits(a: String, b: String): Boolean {
        val digitsA = a.filter(Char::isDigit)
        val digitsB = b.filter(Char::isDigit)
        if (digitsA == digitsB) return true
        val accessA = stripInternationalAccess(digitsA)
        val accessB = stripInternationalAccess(digitsB)
        if (accessA.isNotEmpty() && accessA == accessB) return true
        if (digitsA.length < MIN_SUFFIX_MATCH_DIGITS ||
            digitsB.length < MIN_SUFFIX_MATCH_DIGITS
        ) {
            return false
        }
        val suffixMatch = digitsB.endsWith(digitsA) ||
            digitsA.endsWith(digitsB)
        return suffixMatch && PhoneNumberUtils.compare(a, b)
    }

    private fun stripInternationalAccess(digits: String): String {
        if (digits.isEmpty()) return digits
        return when {
            digits.startsWith("00") -> digits.substring(2)
            digits.startsWith("011") -> digits.substring(3)
            digits.startsWith("+") -> digits.substring(1)
            else -> digits
        }
    }

    /**
     * تطبيع رقم المتصل للبحث عنه: يُستبعد ختم «لا معرّف/خاص/مجهول» الشائع في
     * EXTRA_INCOMING_NUMBER («-1» و«UNKNOWN» ونظائره) والقيم الخالية أو الخالية
     * بالأرقام، فيُعاد null بلا بحث. خلاف ذلك تُستخرج خاناته الرقمية فقط.
     */
    private fun normalizeCallerNumber(number: String?): String? {
        if (number.isNullOrBlank()) return null
        val trimmed = number.trim()
        if (trimmed == "-1" || trimmed.equals("UNKNOWN", ignoreCase = true) ||
            trimmed.startsWith("unknown", ignoreCase = true) || trimmed == "0"
        ) {
            return null
        }
        val digits = trimmed.filter { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }
    }

    private fun hasReadContacts(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CONTACTS)

    private fun hasPermission(context: Context, permission: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, permission)
        return granted == PackageManager.PERMISSION_GRANTED
    }

    /** هل يحمل المستقبِل الأذونات اللازمة لنطق اسم المتصل؟ READ_PHONE_STATE
     *  بوابة وصول البث (بدونه لا يُسلَّم أصلاً). وعلى أندرويد 12+ (API 31+)
     *  يُشرَط READ_CALL_LOG أيضاً: بدونه لا يصل رقم المتصل في البث — حتى مع
     *  READ_CONTACTS — فيُصمت الإعلان عاماً بلا اسم. */
    internal fun hasCallerPermission(context: Context): Boolean {
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(context, Manifest.permission.READ_CALL_LOG)
        }
        return true
    }

    /** شفاء ذاتي عند سحب أي إذن لازم رغم تفعيل إعلان المتصل (حالة الهاتف؛
     *  وسجل المكالمات أيضاً على أندرويد 12+): يطفئ التفعيل ويُعيد تقييم
     *  الخدمة — بدل تركه «مفعّلاً» صامتاً (يتكرر الوصول الموسوم بلا جدوى
     *  منذرةً بإذن مسحوب). مجزّأة [settings] تمريراً (لا اعتماداً على الحقل
     *  المحقون) لتكون قابلة للاختبار. */
    internal fun disableAfterPermissionRevoked(
        settings: SettingsRepository,
        context: Context
    ) {
        if (!settings.isCallerAnnouncementEnabled()) return
        settings.setCallerAnnouncementEnabled(false)
        try {
            AnnouncementSchedulerService.syncIfRunning(context)
        } catch (t: Throwable) {
            Log.w(TAG, "syncIfRunning after revoke failed", t)
        }
    }

    /**
     * يحلّ اسم المتصل بأفضل ما تسمح به الأذونات:
     * 1) دفتر الاتصالات (READ_CONTACTS)، 2) سجل المكالمات (READ_CALL_LOG)،
     * وإلا يُترك الرقم كما هو أو يُنطق "اتصال وارد" العام.
     */
    private fun resolveContactName(
        context: Context,
        number: String?,
        hasReadContacts: Boolean,
        hasReadCallLog: Boolean
    ): String? {
        if (number.isNullOrBlank()) return null
        // رقم خاص/مجهول («-1»/«UNKNOWN»/…): بلا بحث — قد يطابق سجلّ مكالمة
        // مخزّنٍ سابقاً فيُنطق اسمٌ خاطئ لمكالمةٍ مجهولة.
        if (normalizeCallerNumber(number) == null) return null
        val fromContacts = if (hasReadContacts) {
            lookupContactName(context, number)
        } else {
            null
        }
        if (fromContacts != null) return fromContacts
        if (hasReadCallLog) return lookupNameViaCallLog(context, number)
        return null
    }

    /**
     * البحث عن الاسم في سجل المكالمات (CACHED_NAME) — يتطلب READ_CALL_LOG.
     *
     * **المطابقةُ بالتطبيع الرقمي لا بالنصّ الحرفي:** صيغةُ الرقم في
     * السجل تختلف عن الواردة («+966…» مقابل «0…») فالمقارنةُ النصّية
     * تُفوّت الاسمَ وتُنطق الرقمَ. نفحص أحدث المكالمات ونقارن الخانات
     * المطبّعة (تطابقٌ تام أو ذيلٌ برمز بلدٍ مُضاف/محذوف).
     */
    private fun lookupNameViaCallLog(
        context: Context,
        phoneNumber: String
    ): String? {
        val target = normalizeCallerNumber(phoneNumber) ?: return null
        return runCatching {
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.CACHED_NAME
            )
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            try {
                var scanned = 0
                while (cursor != null &&
                    cursor.moveToNext() &&
                    scanned < CALL_LOG_SCAN_LIMIT
                ) {
                    scanned++
                    val numIdx = cursor.getColumnIndex(
                        android.provider.CallLog.Calls.NUMBER
                    )
                    val nameIdx = cursor.getColumnIndex(
                        android.provider.CallLog.Calls.CACHED_NAME
                    )
                    if (numIdx < 0 || nameIdx < 0) continue
                    val logged = cursor.getString(numIdx)
                    val cached = cursor.getString(nameIdx)
                    if (cached.isNullOrBlank()) continue
                    if (cached.equals(logged, ignoreCase = true)) continue
                    val digits = normalizeCallerNumber(logged) ?: continue
                    if (callerNumbersEquivalent(target, digits)) {
                        return@runCatching cached
                    }
                }
                null
            } finally {
                cursor?.close()
            }
        }.getOrNull()
    }

    /**
     * البحث عن اسم جهة الاتصال من رقم الهاتف باستخدام ContactsContract.
     * يُستدعى فقط بعد التحقق من منح READ_CONTACTS (لا رمي SecurityException).
     * استعلام متزامن (نُستدعى من داخل Coroutine على خيط IO).
     */
    private fun lookupContactName(
        context: Context,
        phoneNumber: String
    ): String? {
        var cursor: Cursor? = null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.PhoneLookup.DISPLAY_NAME
                )
                if (nameIndex >= 0) {
                    val name = cursor.getString(nameIndex)
                    return name.takeIf { it.isNotBlank() }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "فشل البحث عن جهة الاتصال", e)
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * استرداد أحدث مكالمة من سجل المكالمات (CallLog) كبديل إن حجب أندرويد
     * الحديث EXTRA_INCOMING_NUMBER عن بث PHONE_STATE.
     * يعيد Pair(رقم المتصل, الاسم المخزن إن وجد) لمكالمة حديثة.
     */
    internal fun resolveLatestCallFromLog(
        context: Context,
        hasReadCallLog: Boolean
    ): Pair<String?, String?>? {
        if (!hasReadCallLog) return null
        return runCatching {
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.CACHED_NAME,
                android.provider.CallLog.Calls.DATE
            )
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val dateIdx =
                        c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    val callDate =
                        if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val now = System.currentTimeMillis()
                    // نتحقق أن السجل لمكالمة حديثة جداً (خلال آخر 30 ثانية)
                    if (kotlin.math.abs(now - callDate) < 30_000L) {
                        val numIdx = c.getColumnIndex(
                            android.provider.CallLog.Calls.NUMBER
                        )
                        val nameIdx = c.getColumnIndex(
                            android.provider.CallLog.Calls.CACHED_NAME
                        )
                        val num =
                            if (numIdx >= 0) c.getString(numIdx) else null
                        val name =
                            if (nameIdx >= 0) c.getString(nameIdx) else null
                        if (!num.isNullOrBlank() || !name.isNullOrBlank()) {
                            Pair(num, name)
                        } else null
                    } else null
                } else null
            }
        }.getOrNull()
    }
}

/** بند 3.2/3.1: نطق رقم المتصل المجهول وفق طريقة نطق الأرقام
 *  المختارة (مفردة/زوجية/ثلاثية..) — مع الحفاظ على بادئة زائد إن وُجدت. */
internal fun formatCallerNumberForSpeech(
    number: String,
    isArabic: Boolean,
    mode: Int = 1
): String {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    val hasPlus = number.trimStart().startsWith("+")
    val inputStr = if (hasPlus) "+$digits" else digits
    val spoken = NumberSpeech.formatByMode(
        mode = mode.coerceIn(1, 8),
        numberStr = inputStr,
        isEnglish = !isArabic
    )
    return " $spoken"
}

/**
 * قرار لغة نطق اسم المتصل من الاسم/الرقم لا من النص الكامل (فالقالب قد
 * يحوي لغةً ثابتة فتطمس المنطقةَ معه كل القرار): اسمٌ فيه حروف عربية
 * (أو رقم بلا حروف) → عربي، والاسم اللاتيني → إنجليزي. هذا ما يجعل
 * نطق الأسماء الإنجليزية فعلاً بمحركها وصوتها.
 */
internal fun callerSpeechLanguage(
    contactName: String?,
    number: String?
): String {
    val dynamic = (contactName ?: number).orEmpty()
    return if (
        !dynamic.any { it.isLetter() } ||
        LocaleUtils.containsArabic(dynamic)
    ) {
        LanguageCode.AR.tag
    } else {
        LanguageCode.EN.tag
    }
}

/**
 * محرك نطق اسم المتصل حسب لغته من فئتي المحركين المستقلتين (عربي/إنجليزي)
 * — null = محرك تلقائي. بعد ضبط مستخدمٍ محركَ الأسماء الإنجليزية يبدأ
 * النطق الإنجليزي فعلاً عليه (المحرك المشترك الواحد كان يفرض محركاً
 * واحداً للغتين ويظهر أنه لا يعمل أو يعمل على العربية فقط).
 */
internal fun callerSpeechEngine(
    settings: SettingsRepository,
    languageTag: String
): String? = settings.getEngineForCategory(
    if (languageTag == LanguageCode.AR.tag) {
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
    } else {
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
    }
)

/**
 * دمجُ هوية المتصل من المصادر الثلاثة: الحاوية المشتركة
 * ([RingCallerIdentity]) التي يكتب فيها إشعارُ الهاتف وخدمةُ الفرز، وآخر
 * رقمٍ/اسمٍ محلولَين في الجلسة، وسجلّ المكالمات. **الاسمُ لا يُداس بالرقم
 * ولا العكس:** كلُّ مصدرٍ يملأ الفارغَ فقط، فيبقى الاسمُ الصالح مهما تأخّر
 * وصولُه. خالصٌ بلا `Context` فيُختبر مباشرةً.
 */
internal fun mergeCallerIdentity(
    number: String?,
    name: String?,
    shared: Pair<String?, String?>,
    lastResolved: Pair<String?, String?>,
    fromLog: Pair<String?, String?>?
): Pair<String?, String?> {
    var mergedNumber = number
    var mergedName = name
    if (mergedNumber.isNullOrBlank()) mergedNumber = shared.first
    if (mergedName.isNullOrBlank()) mergedName = shared.second
    if (mergedNumber.isNullOrBlank()) mergedNumber = lastResolved.first
    if (mergedName.isNullOrBlank()) mergedName = lastResolved.second
    if (fromLog != null) {
        if (mergedNumber.isNullOrBlank()) mergedNumber = fromLog.first
        if (mergedName.isNullOrBlank()) mergedName = fromLog.second
    }
    return Pair(mergedNumber, mergedName)
}

/**
 * هل الهويةُ مكتملةٌ بما يكفي لإيقاف انتظار الاسم؟ **الاسمُ وحده** هو
 * الشرط: الرقمُ وحده ليس دليلاً على أن الاسمَ لن يصل بعد لحظات — وهذا
 * بالضبط عيبُ نطق الرقم بدل الاسم. خالصةٌ للاختبار.
 */
internal fun identityHasName(name: String?): Boolean =
    !name.isNullOrBlank()

/** حدُّ فحص سجلّ المكالمات عند البحث بالرقم (آخر ٥٠ مكالمة). */
private const val CALL_LOG_SCAN_LIMIT = 50

/** أدنى طولٍ لكفاية الذيل في تكافؤ رقمَي هاتف (يمنع تقارب ذيلٍ قصير). */
private const val MIN_EQUIV_DIGITS = 7

/**
 * تكافؤ رقمَي هاتف **بعد التطبيع**: تطابقٌ تام، أو ذيلٌ لأحدهما (رمزُ
 * بلدٍ مُضاف أو محذوف) بحدٍّ أدنى للطول — فلا تُطابق أرقامٌ قصيرةٌ
 * متباعدة. خالصةٌ للاختبار.
 */
internal fun callerNumbersEquivalent(a: String, b: String): Boolean {
    if (a == b) return true
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length <= b.length) b else a
    return shorter.length >= MIN_EQUIV_DIGITS && longer.endsWith(shorter)
}