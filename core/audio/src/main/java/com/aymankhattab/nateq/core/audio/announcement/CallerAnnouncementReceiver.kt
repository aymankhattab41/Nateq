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
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
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

        /** نافذة جدولة تكرارات نطق المتصل (بعد النطق الأول) — لا يرتبط بها
         *  عمرُ البث إطلاقاً (التكرارات تُجدول في النطاق العام appScope وتستمر
         *  بعد إنهاء الـ goAsync): سقفٌ داخلي لعدد التكرارات المنطقية فقط. */
        private const val BROADCAST_ASYNC_WINDOW_MS = 10_000L

        /** سقف أمان إنهاء بثّ goAsync — أقل من مهلة نظام البث (~10 ثوانٍ)
         *  بهامش واضح: يُنهى البث حتماً قبل حافة المهلة حتى لو علّق المحركُ
         *  صامتاً بلا onDone (السيناريو الذي كان يوصّل دورة النطق للحافة
         *  فيقع ANR «إيقاف مستمر» على الأجهزة الفعلية — كما رُصد على Galaxy
         *  A23 مع محرك TTS معطوب). لا يمسّ التكرارات (النطاق العام)، ولا
         *  النطق السليم (يكتمل قبلها عبر مستمع الاكتمال). */
        private const val BROADCAST_SAFE_CAP_MS = 6_000L

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
        fun finishOnce() {
            if (finishedBroadcast.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }
        // حارس أمان على الخيط الرئيسي: يُنهي البث حتماً قبل حافة مهلة
        // النظام (~10 ثوانٍ) ببُعد [BROADCAST_SAFE_CAP_MS] واضح — مفصولٌ
        // عن كوروتين النطق (الذي قد يعلق على محركٍ صامت بلا onDone) فلا
        // يبلغ الـ goAsync حافتَه قط فيقع ANR. يُزال في finally عند تمام
        // العمل، وإن سبق إنهاؤه فلا يُنهى ثانية (ذرّي).
        val mainHandler = Handler(Looper.getMainLooper())
        val finishFailsafe = Runnable { finishOnce() }
        mainHandler.postDelayed(finishFailsafe, BROADCAST_SAFE_CAP_MS)
        val appScope =
            (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            val state =
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == null) {
                // بلا حالة في البث — لا عمل: يُنهى البث فوراً (بدل تركه
                // معلقاً حتى حارس الأمان) ونخرج بهدوء.
                finishOnce()
                return@launch
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
                    runCatching {
                        AnnouncementSpeaker.getInstance(context).stop()
                    }
                }
                finishOnce()
                return@launch
            }
            // أي رنين جديد يحلّ replace لدورة التكرار السابقة إن بقيت
            // (رنينٌ متكرر لمكالمةٍ نفسها) — لا حلقاتِ نطقٍ متوازية.
            activeCallCycle?.cancel()
            activeCallCycle = coroutineContext.job
            // مستمعُ اكتمالٍ يُسجَّل في try ويُزال في finally (بند [8]) —
            // لا يبقى مسجلاً بعد نافذة البث فلا يُستدعى في دورةٍ لا تخصنا.
            var completionListener: (() -> Unit)? = null
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

                @Suppress("DEPRECATION")
                val incomingNumber = intent.getStringExtra(
                    TelephonyManager.EXTRA_INCOMING_NUMBER
                )

                // الاسم المخصص للمستخدم (خريطة رقم -> اسم) له الأولوية القصوى،
                // ثم البحث في دفتر الاتصالات ثم سجل المكالمات.
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

                // خصوصية قفل الشاشة: عند القفل نكتفي بعبارة عامة
                // «اتصال وارد» دون اسم المتصل أو رقمه — حماية
                // للخصوصية (قد يكون المتصل حسّاساً).
                val privacyLocked = settings.isLockScreenPrivacyEnabled()
                        && settings.isDeviceScreenLocked()

                val text = buildAnnouncementText(
                    context,
                    number = incomingNumber,
                    contactName = contactName,
                    template = settings.getCallerAnnouncementTemplate(),
                    privacyLocked = privacyLocked
                )

                val speechRate = settings.getCallerAnnouncementRate()
                val volume = settings.getCallerAnnouncementVolume()
                val hasArabic = LocaleUtils.containsArabic(text)
                val locale = if (hasArabic) {
                    Locale.forLanguageTag(LanguageCode.AR.tag)
                } else {
                    Locale.forLanguageTag(LanguageCode.EN.tag)
                }

                val speaker = AnnouncementSpeaker.getInstance(context)
                // نعيد ضبط الصوت المفضّل لدورة المتصل قبل كل نطق
                // (عربي/إنجليزي حسب لغة النص الفعلي) حتى لا يبقى
                // عالقاً على صوتٍ من دورة سابقة (إشعار/رسالة...) —
                // نفس النمط المطبّق في SmsReadingReceiver.
                val callerVoice = if (hasArabic) {
                    settings.getCallerAnnouncementArabicVoiceId()
                } else {
                    settings.getCallerAnnouncementEnglishVoiceId()
                }
                speaker.resetVoice(callerVoice)

                // تكرار النطق «repeat» مرات بفاصل «intervalMs»؛ الأول يقع
                // فوراً. التكرارات تُجدول داخل النطاق العام appScope نفسه (لا
                // تُربط بحياة البث): إنهاءُ الـ goAsync مبكراً (اكتمالُ أول
                // جملة فعلياً أو حارسُ الأمان) لا يقطعها — فتبقى تُنطق حتى
                // لو جُمّدت العملية لاحقاً (الخدمة الأمامية التي يضمنها
                // النطق تُبقي العملية أماميةً غالباً).
                val repeat = settings
                    .getCallerAnnouncementRepeat().coerceIn(1, 5)
                val intervalMs = settings.getCallerAnnouncementIntervalSeconds()
                    .coerceIn(1, 10) * 1000L
                speaker.speak(
                    text, locale, speechRate, 1.0f, volume,
                    engineOverride = settings.getEngineForCategory(
                        SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                    )
                )
                // **بند 5.5:** إنهاءٌ مبكر بمستمع الاكتمال: محركٌ سليم يُنهي
                // البث فور اكتمال (onDone) الجملة الأولى فعلياً — بلا حجزٍ
                // أطول من اللازم ولا ذيلِ صوتٍ مبتور (جمدُ العملية بعد
                // finish() كان يقتطع آخر الصوت على أندرويد 14+). يُسجَّل في
                // قائمة مستمعي المتحدث المشترك (بند [8]) فلا يطمس خطاف أداة
                // الساعة أو مستقبلٍ آخر، ويُزال في finally.
                completionListener = { finishOnce() }
                speaker.addCompletionListener(completionListener!!)
                val appCtx = context.applicationContext
                val schedule = repeatSchedule(
                    repeat, intervalMs, BROADCAST_ASYNC_WINDOW_MS
                )
                var lastLaunchMs = 0L
                for (offsetMs in schedule) {
                    delay(offsetMs - lastLaunchMs)
                    lastLaunchMs = offsetMs
                    try {
                        AnnouncementSpeaker.getInstance(appCtx).speak(
                            text, locale, speechRate, 1.0f,
                            volume,
                            engineOverride = settings.getEngineForCategory(
                                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                            )
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "repeat speak failed", t)
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
                // تعويضي: إن انحرف المسار قبل أذرعة الإنهاء أعلاه (استثناء)
                // يُنهى البث هنا — وإن سبق إنهاؤه فلا يُنهى ثانية. ويُزال
                // حارس الأمان — لا يبقى مسجلاً بعد اكتمال الدورة.
                mainHandler.removeCallbacks(finishFailsafe)
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

    /** النص الصادق حسب ما هو متاح فعلاً (لا يدّعي "غير محفوظ" جزافاً). */
    private fun buildAnnouncementText(
        context: Context,
        number: String?,
        contactName: String?,
        template: String?,
        privacyLocked: Boolean
    ): String {
        // عند القفل ننطق العبارة العامة فقط حتى لو ضبط
        // المستخدم قالباً أو اسم من.
        return if (privacyLocked) {
            LocaleUtils.stringForSpeech(
                context,
                LanguageCode.AR.tag,
                R.string.caller_only,
                R.string.caller_only
            )
        } else if (!template.isNullOrBlank()) {
            val filled = template
                .replace("{name}", contactName ?: number.orEmpty())
                .replace("{number}", number.orEmpty())
                .trim()
            if (filled.isBlank()) {
                buildDefaultCallerPhrase(context, number, contactName)
            } else {
                filled
            }
        } else {
            buildDefaultCallerPhrase(context, number, contactName)
        }
    }

    /**
     * عبارة النطق الافتراضية مع قرار اللغة من الاسم/الرقم (عربي أم إنجليزي)
     * وليس من لغة واجهة التطبيق: مرسل عربي يُنطق بالعربية والعكس.
     */
    private fun buildDefaultCallerPhrase(
        context: Context,
        number: String?,
        contactName: String?
    ): String {
        val dynamicText = (contactName ?: number).orEmpty()
        val isArabic = !dynamicText.any { it.isLetter() } ||
            LocaleUtils.containsArabic(dynamicText)
        val lang = if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag
        return when {
            contactName != null -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_from, R.string.caller_from
            ).replace("{name}", contactName)
            !number.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                context,
                lang,
                R.string.caller_from_number,
                R.string.caller_from_number
            )
            else -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_only, R.string.caller_only
            )
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

    /** البحث عن الاسم في سجل المكالمات (CACHED_NAME) — يتطلب READ_CALL_LOG. */
    private fun lookupNameViaCallLog(
        context: Context,
        phoneNumber: String
    ): String? {
        return runCatching {
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(android.provider.CallLog.Calls.CACHED_NAME)
            val selection = "${android.provider.CallLog.Calls.NUMBER} = ?"
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                arrayOf(phoneNumber),
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(
                        android.provider.CallLog.Calls.CACHED_NAME
                    )
                    if (idx >= 0) {
                        val cached = cursor.getString(idx)
                        return cached?.takeIf {
        it.isNotBlank() &&
            !it.equals(phoneNumber, ignoreCase = true)
    }
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
}