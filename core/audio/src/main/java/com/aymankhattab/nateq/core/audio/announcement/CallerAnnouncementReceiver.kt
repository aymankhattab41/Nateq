package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import android.os.Handler
import android.os.Looper
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
        val appScope =
            (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            try {
                // فحص وقائي: وصول بث PHONE_STATE بحد ذاته يتطلب
                // منح READ_PHONE_STATE وقت الإرسال (النظام يفلتر
                // المستقبلين، وليس إعلان الـ Manifest فقط). لكن سحب
                // النظام التلقائي للإذن (ابتداءً من أندرويد 11، ويشتد
                // على أندرويد 17) قد يخطف البث قبل وصوله — إن وصلنا
                // هنا رغم فقدانه نتوقف بهدوء بدل نطق نص وسط مكالمة
                // أو رمي SecurityException. المعالجة مجزّأة في
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
                    return@launch
                }

                val state =
                    intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        ?: return@launch
                if (state != TelephonyManager.EXTRA_STATE_RINGING) return@launch

                val settings = settingsRepository
                if (!settings.isCallerAnnouncementEnabled()) return@launch
                // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
                if (!settings.isAllAnnouncementsEnabled()) return@launch

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

                // تكرار النطق «repeat» مرات مع فاصل «intervalMs» بين كل مرة.
                // الأول يقع فوراً ثم يُحرَّر pendingResult؛ الخدمة الأمامية
                // التي يبدأها المتحدث تُبقي العملية حيّة. التكرارات المتبقية
                // تُجدَّل عبر Handler على MainLooper مستقلة عن حياة البث —
                // لا نقاءً بمهلة goAsync.
                val repeat = settings
                    .getCallerAnnouncementRepeat().coerceIn(1, 5)
                val intervalMs = settings.getCallerAnnouncementIntervalSeconds()
                    .coerceIn(1, 10) * 1000L
                speaker.speak(text, locale, speechRate, 1.0f, volume)
                if (repeat > 1) {
                    val appCtx = context.applicationContext
                    val handler = Handler(Looper.getMainLooper())
                    for (i in 1 until repeat) {
                        handler.postDelayed({
                            try {
                                AnnouncementSpeaker.getInstance(appCtx)
                                    .speak(
                                        text, locale, speechRate, 1.0f, volume
                                    )
                            } catch (t: Throwable) {
                                Log.e(TAG, "repeat speak failed", t)
                            }
                        }, intervalMs * i.toLong())
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onReceive failed", t)
            } finally {
                // نُطلق finish() بعد النطق الأول مباشرة؛ التكرارات المجدولة عبر
                // Handler لا تتعلّق بحياة البث (الخدمة الأمامية التي يبدأها
                // المتحدث تُبقي العملية حيّة). لا finish مبكر جداً قبل إطلاق
                // نطق الاسم كما كان يسمح للنظام بقتل العملية أثناء الرنين.
                pendingResult.finish()
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
     * الاسم المخصص من خريطة المستخدم (رقم -> اسم). تُطابق الأرقام بحذف كل
     * ما ليس رقماً (أرقام "063...", "+63...", " 06 3..." كلها متطابقة).
     * تُستبعد القيم الخاصة غير الحقيقية («-1» للمجهول/الخاص و«UNKNOWN»)
     * قبل التطبيع حتى لا ينطق التطبيق اسم جهة اتصالٍ تتصادف أرقامها مع «1»
     * (كملحق رموز الولايات المتحدة) لمكالمةٍ مجهولةٍ فعلياً.
     */
    private fun resolveCustomName(
        settings: SettingsRepository,
        number: String?
    ): String? {
        val normalized = normalizeCallerNumber(number) ?: return null
        return settings.getCustomCallerNames()
            .entries.firstOrNull { entry ->
                entry.key.filter { c -> c.isDigit() } == normalized
            }
            ?.value
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

    /** هل يحمل [CallerAnnouncementReceiver] إذن قراءة حالة الهاتف اللازم؟
     *  (READ_PHONE_STATE) — بدونه لا يسلّم النظام بث PHONE_STATE أصلاً، أو
     *  سُحب بعد تفعيل الميزة فأوقفنا التفعيل ذاتياً. */
    internal fun hasCallerPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_PHONE_STATE)

    /** شفاء ذاتي عند سحب READ_PHONE_STATE رغم تفعيل إعلان المتصل: يطفئ
     *  التفعيل ويُعيد تقييم الخدمة — بدل تركه «مفعّلاً» صامتاً (يتكرر
     *  الوصول الموسوم بلا جدوى منذرةً بإذن مسحوب). مجزّأة [settings] تمريراً
     *  (لا اعتماداً على الحقل المحقون) لتكون قابلة للاختبار. */
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