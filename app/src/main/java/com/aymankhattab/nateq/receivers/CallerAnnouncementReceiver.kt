package com.aymankhattab.nateq.receivers

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
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.AnnouncementSpeaker
import com.aymankhattab.nateq.util.LocaleUtils
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
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

    /** مصدر الإعدادات المحقون — كائن واحد مشترك عبر العمليات (keeps تفضيلات المتصل). */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope = (context.applicationContext as com.aymankhattab.nateq.NateqApplication).appScope
        appScope.launch {
            try {
                // فحص وقائي: وصول بث PHONE_STATE بحد ذاته يتطلب منح READ_PHONE_STATE
                // وقت الإرسال (النظام يفلتر المستقبلين، وليس إعلان الـ Manifest فقط).
                // لكن سحب النظام التلقائي للإذن (ابتداءً من أندرويد 11، ويشتد على
                // أندرويد 17) قد يخطف البث قبل وصوله — إن وصلنا هنا رغم فقدانه
                // نتوقف بهدوء بدل نطق نص وسط مكالمة أو رمي SecurityException.
                if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
                    Log.w(TAG, "READ_PHONE_STATE revoked; caller announcement silent-skip")
                    return@launch
                }

                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return@launch
                if (state != TelephonyManager.EXTRA_STATE_RINGING) return@launch

                val settings = settingsRepository
                if (!settings.isCallerAnnouncementEnabled()) return@launch
                // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
                if (!settings.isAllAnnouncementsEnabled()) return@launch

                @Suppress("DEPRECATION")
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                // الاسم المخصص للمستخدم (خريطة رقم -> اسم) له الأولوية القصوى،
                // ثم البحث في دفتر الاتصالات ثم سجل المكالمات.
                val customName = resolveCustomName(settings, incomingNumber)
                val contactName = customName ?: resolveContactName(
                    context,
                    number = incomingNumber,
                    hasReadContacts = hasPermission(context, Manifest.permission.READ_CONTACTS),
                    hasReadCallLog = hasPermission(context, Manifest.permission.READ_CALL_LOG)
                )

                // خصوصية قفل الشاشة: عند القفل نكتفي بعبارة عامة «اتصال وارد» دون
                // اسم المتصل أو رقمه — حماية للخصوصية (قد يكون المتصل حسّاساً).
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
                val locale = if (hasArabic) Locale.forLanguageTag("ar") else Locale.forLanguageTag("en")

                val speaker = AnnouncementSpeaker.getInstance(context)
                // نعيد ضبط الصوت المفضّل لدورة المتصل قبل كل نطق (عربي/إنجليزي
                // حسب لغة النص الفعلي) حتى لا يبقى عالقاً على صوتٍ من دورة سابقة
                // (إشعار/رسالة...) — نفس النمط المطبّق في SmsReadingReceiver.
                val callerVoice = if (hasArabic) {
                    settings.getCallerAnnouncementArabicVoiceId()
                } else {
                    settings.getCallerAnnouncementEnglishVoiceId()
                }
                speaker.resetVoice(callerVoice)

                // تكرار النطق «repeat» مرات مع فاصل «intervalMs» بين كل مرة نطق
                // وليس نطقاً واحداً يجمع العبارة بفواصل — فيُسمع المتصل بوضوح
                // مع توقف حقيقي بين التكرارات.
                val repeat = settings.getCallerAnnouncementRepeat().coerceIn(1, 5)
                val intervalMs = settings.getCallerAnnouncementIntervalSeconds()
                    .coerceIn(1, 10) * 1000L
                for (i in 0 until repeat) {
                    speaker.speak(text, locale, speechRate, 1.0f, volume)
                    if (i < repeat - 1) delay(intervalMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onReceive failed", t)
            } finally {
                // يبقى المستقبَل حياً حتى يُنهي الكوروتين عمله (فحوص الأذونات،
                // البحث عن اسم المتصل، إطلاق النطق) ثم يُطلق finish() — لا
                // finish() مبكراً قبل بدء التنفيذ الذي كان يتيح للنظام قتل العملية
                // أثناء رنين الهاتف قبل نطق الاسم. بعد الإطلاق يكمل النطق عبر
                // الخدمة الأمامية التي يبدأها المتحدث (نفس نمط SmsReadingReceiver).
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
        // عند القفل ننطق العبارة العامة فقط حتى لو ضبط المستخدم قالباً أو اسم من.
        return if (privacyLocked) {
            LocaleUtils.stringForSpeech(
                context, "ar", R.string.caller_only, R.string.caller_only
            )
        } else if (!template.isNullOrBlank()) {
            val filled = template
                .replace("{name}", contactName ?: number.orEmpty())
                .replace("{number}", number.orEmpty())
                .trim()
            if (filled.isBlank()) buildDefaultCallerPhrase(context, number, contactName) else filled
        } else {
            buildDefaultCallerPhrase(context, number, contactName)
        }
    }

    /**
     * عبارة النطق الافتراضية مع قرار اللغة من الاسم/الرقم (عربي أم إنجليزي)
     * وليس من لغة واجهة التطبيق: مرسل عربي يُنطق بالعربية والعكس.
     */
    private fun buildDefaultCallerPhrase(context: Context, number: String?, contactName: String?): String {
        val dynamicText = (contactName ?: number).orEmpty()
        val isArabic = !dynamicText.any { it.isLetter() } || LocaleUtils.containsArabic(dynamicText)
        val lang = if (isArabic) "ar" else "en"
        return when {
            contactName != null -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_from, R.string.caller_from
            ).replace("{name}", contactName)
            !number.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_from_number, R.string.caller_from_number
            )
            else -> LocaleUtils.stringForSpeech(
                context, lang, R.string.caller_only, R.string.caller_only
            )
        }
    }

    /**
     * الاسم المخصص من خريطة المستخدم (رقم -> اسم). تُطابق الأرقام بحذف كل
     * ما ليس رقماً (أرقام "063...", "+63...", " 06 3..." كلها متطابقة).
     */
    private fun resolveCustomName(settings: SettingsRepository, number: String?): String? {
        if (number.isNullOrBlank()) return null
        val norm = number.filter { it.isDigit() }
        if (norm.isEmpty()) return null
        return settings.getCustomCallerNames()
            .entries.firstOrNull { it.key.filter { c -> c.isDigit() } == norm }
            ?.value
    }

    private fun hasReadContacts(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CONTACTS)

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
        val fromContacts = if (hasReadContacts) lookupContactName(context, number) else null
        if (fromContacts != null) return fromContacts
        if (hasReadCallLog) return lookupNameViaCallLog(context, number)
        return null
    }

    /** البحث عن الاسم في سجل المكالمات (CACHED_NAME) — يتطلب READ_CALL_LOG. */
    private fun lookupNameViaCallLog(context: Context, phoneNumber: String): String? {
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
                    val idx = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    if (idx >= 0) {
                        val cached = cursor.getString(idx)
                        return cached?.takeIf {
                            it.isNotBlank() && !it.equals(phoneNumber, ignoreCase = true)
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
    private fun lookupContactName(context: Context, phoneNumber: String): String? {
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
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
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