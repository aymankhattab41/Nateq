package com.aymankhattab.nateq.core.audio.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * مدير التطبيقات المحمية (إنستاباي والتطبيقات المصرفية) — الجولة 48 (الأمر 7).
 */
object ProtectedAppManager {

    private const val TAG = "ProtectedAppManager"
    const val NOTIFICATION_ID_REENABLE = 7001
    const val CHANNEL_ID_PROTECTED = "protected_app_channel_v2"

    /**
     * بثٌّداخليٌّ (من حزمتنا فقط) يطلب من الخدمة تعطيل نفسها — يُستخدم
     * حين لا يكون مثيل الخدمة معنا، كمسار «تشغيل عبر لورد».
     */
    const val ACTION_DISABLE_SELF =
        "com.aymankhattab.nateq.action.DISABLE_A11Y_SELF"

    /**
     * طلب تعطيل خدمة إمكانية الوصول من خارج الخدمة نفسها.
     */
    fun requestSelfDisable(context: Context) {
        val intent = Intent(ACTION_DISABLE_SELF)
            .setPackage(context.packageName)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "requestSelfDisable failed", it) }
    }

    /**
     * نافذة اعتماد علامة التعطيل: أحداثُ نافذةٍ أوائلية بعد disableSelf
     * تصل قبل فك الارتباط، فلا يُعاد التعطيل ولا يُعاد إطلاق التطبيق.
     */
    private const val FRESH_SUSPENSION_WINDOW_MS = 5_000L

    @Volatile
    var isPartiallySuspended: Boolean = false
        private set

    @Volatile
    var partiallySuspendedPkg: String? = null
        private set

    /**
     * حزمة مشغّل الشاشة الرئيسية الافتراضية إن وُجدت.
     */
    fun homeLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
    }

    /**
     * هل الحزمة تنتمي لمشغل الشاشة الرئيسية (Launcher / Home)؟
     */
    fun isHomeLauncher(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        if (pkg == homeLauncherPackage(context)) return true

        val allHomes = runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        if (allHomes.contains(pkg)) return true

        val lower = pkg.lowercase()
        return lower.contains("launcher") || lower.contains("home")
    }

    /**
     * اسم التطبيق كما يحمله حدث النقرة في المشغّل: نصُّ العقدة أو
     * وصفُها. لاحظ أن [AccessibilityEvent.getText] يعود بقائمةٍ فارغة
     * لا بـ null حين لا نصَّ لها، فلا يصحّ الاعتماد على الـ `?:` وحده
     * (وإلا ضاع وصفُ الأيقونة الذي هو المصدر الحقيقي للاسم). وإن كانت
     * العقدة نفسها فارغة (صفُّ نتيجة البحث في مشغّل سامسونج) فاسمتها
     * في العقدة المصدر أو أحد أبنائها — وهو موضعُ «Insta‌Pay» الفعلية.
     */
    fun launcherCandidateLabel(event: AccessibilityEvent): String? {
        val fromText = event.text?.joinToString(" ")?.trim().orEmpty()
        if (fromText.isNotBlank()) return fromText
        val fromDesc = event.contentDescription?.toString()?.trim()
        if (!fromDesc.isNullOrBlank()) return fromDesc
        val source = runCatching { event.source }.getOrNull()
            ?: return null
        // لا نستدعي recycle(): منتهية منذ API 33 ودالة فارغة، والعقدة
        // المستخرجة من الحدث تُجمع على وجهها.
        return nodeLabel(source, depth = 0)
    }

    /** أقصى عمق نزول في شجرة العقدة بحثاً عن اسم التطبيق. */
    private const val MAX_LABEL_NODE_DEPTH = 3

    /**
     * أول نص (أو وصف) غير فارغ في العقدة أو أبنائها — يمشي عمقاً
     * محدوداً حتى لا يُهدر الحدث على شجرةٍ ضخمة. internal للاختبار
     * المباشر لأن setSource(AccessibilityNodeInfo) مُزيل من SDK 37
     * فلا يمكن تركيب حدثٍ بمصدرٍ في اختبار الوحدة.
     */
    internal fun nodeLabel(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): String? {
        if (node == null || depth > MAX_LABEL_NODE_DEPTH) return null
        val ownText = node.text?.toString()?.trim().orEmpty()
        if (ownText.isNotBlank()) return ownText
        val ownDesc =
            node.contentDescription?.toString()?.trim().orEmpty()
        if (ownDesc.isNotBlank()) return ownDesc
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
            val label = nodeLabel(child, depth + 1)
            if (!label.isNullOrBlank()) return label
        }
        return null
    }

    /**
     * الحزم التي يجب أن تصل أحداثها إلى الخدمة كي يعمل تعطيل
     * التطبيقات المحمية: القائمة المسموحة + مشغّل الشاشة (نقرة
     * الأيقونة) + التطبيقات المحمية نفسها (نافذتها). قصيرةُ الأسماء
     * تُسقط أحداثها من الأساس فلا تعطيل ولا إعادة تفعيل.
     */
    fun effectiveEventPackages(
        allowed: Set<String>?,
        protectedApps: List<ProtectedApp>,
        homeLauncher: String?
    ): Set<String> {
        val base = (allowed ?: emptySet()).toMutableSet()
        if (protectedApps.isEmpty()) return base
        if (!homeLauncher.isNullOrBlank()) base += homeLauncher
        base += protectedApps.map { it.packageName }
        return base
    }

    /**
     * مطابقة نص أو وصف العنصر المنقور في المشغّل مع أي تطبيق محمي.
     */
    fun matchProtectedApp(context: Context, text: String?): ProtectedApp? {
        if (text.isNullOrBlank()) return false.let { null }
        val settings = SettingsRepository.create(context)
        val protectedApps = settings.getProtectedApps()
        if (protectedApps.isEmpty()) return null

        val normText = TtsSettingsAutomation.normalizeForMatch(text)
        for (app in protectedApps) {
            val normLabel = TtsSettingsAutomation.normalizeForMatch(app.label)
            val normPkg =
                TtsSettingsAutomation.normalizeForMatch(app.packageName)
            if (normText == normLabel ||
                normText == normPkg ||
                normText.contains(normLabel) ||
                normLabel.contains(normText)
            ) {
                return app
            }
        }
        return null
    }

    /**
     * اختيار التطبيق المحمي الذي صار أماماً من قائمة حزم أحداث
     * `ACTIVITY_RESUMED` بترتيب وصولها. **آخرُ حدثٍ هو الأمامُ الآن**،
     * فلا يكفي أن يظهر محميٌّ سابق بين الأحداث (وإلا عطّلنا التطبيق
     * بعد أن غادر المستخدم إليه). دالةٌ نقيّة ليمكن اختبارُ منطق
     * المراقبة الاستباقية دون نظامٍ حقيقي.
     *
     * @param resumedPackages حزم أحداث ACTIVITY_RESUMED بترتيب وصولها
     * @return التطبيق المحمي الأمامي أو null
     */
    fun pickForegroundProtectedApp(
        resumedPackages: List<String>,
        protectedApps: List<ProtectedApp>,
        ownPackage: String?
    ): ProtectedApp? {
        val foreground = resumedPackages.lastOrNull { it.isNotBlank() }
            ?: return null
        if (ownPackage != null && foreground == ownPackage) return null
        return protectedApps.firstOrNull {
            it.packageName == foreground
        }
    }

    /**
     * معالجة دخول التطبيق المحمي (تعليق كامل أو جزئي).
     *
     * @param relaunchIfForeground صحيح عند مجيء الحدث من نافذة التطبيق
     * نفسه (أي أنه بدأ بالفعل): إيقافُ الخدمة يقع بعد انطلاق شاشة البداية،
     * فتُعاد محاولة فتح التطبيق بعد التعطيل حتى لا يتحطّم في أول مرة.
     */
    fun handleAppEntry(
        context: Context,
        service: NateqAccessibilityService?,
        app: ProtectedApp,
        relaunchIfForeground: Boolean = false
    ) {
        val settings = SettingsRepository.create(context)
        Log.i(
            TAG,
            "handleAppEntry: pkg=${app.packageName}, mode=${app.mode}"
        )

        // حارس التكرار: حدثُ نافذةٍ ثانية قبل فك الارتباط لا يعيد
        // التعطيل ولا يعيد إطلاق التطبيق (حلقة لا نهائية). وما مضى
        // من زمنٍ بعد التعطيل مع بقاء الخدمة حيّة علامةٌ قديمة تعني
        // أن التعطيل لم يكتمل، فتُمحى ولا تمنع معالجةً جديدة.
        val suspendedAt = settings.getSuspendedProtectedAppTimestamp()
        val isFreshSuspension =
            settings.getSuspendedProtectedApp() == app.packageName &&
                System.currentTimeMillis() - suspendedAt <
                FRESH_SUSPENSION_WINDOW_MS
        if (isFreshSuspension) {
            Log.i(TAG, "Already suspended for ${app.packageName}; ignored.")
            return
        }

        if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
            isPartiallySuspended = true
            partiallySuspendedPkg = app.packageName
            service?.applyServiceInfo()
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعليق جزئي لإمكانية الوصول من أجل ${app.label}",
                isInterruptAllowed = true
            )
            return
        }

        // إيقاف كامل: disableSelf() وإعادة التشغيل عند الخروج
        settings.setSuspendedProtectedApp(
            app.packageName,
            System.currentTimeMillis()
        )

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "تم تعطيل إمكانية الوصول لفتح ${app.label}",
            isInterruptAllowed = true
        )

        // تشغيل مراقب الخروج قبل التعطيل
        ProtectedAppWatcherService.startForApp(
            context = context,
            pkg = app.packageName,
            label = app.label
        )

        // إيقاف خدمة إمكانية الوصول
        // (١) تعديلُ الإعداد مباشرةً هو الفعّال في السباق: يُزيل اسمَنا
        // من القائمة فيُفكُّ الارتباط فوراً قبل بدء التطبيق، بخلاف
        // disableSelf التي تُنفَّذ متأخرة. ولا يفشل إن قلّ الإذن.
        val disabledDirectly =
            disableAccessibilityServiceDirectly(context)
        if (!disabledDirectly) {
            // (٢) احتياط عند تعذّر الكتابة: الطلب المعتاد للنظام.
            if (service != null) {
                service.disableSelf()
            } else {
                requestSelfDisable(context)
            }
        }

        // إن كان التطبيق قد بدأ أصلاً (وليس عبر أيقونة المشغّل) فلا
        // يزال سينهار في شاشة البداية — نفتحه من جديد والخدمة معطّلة.
        if (relaunchIfForeground) {
            relaunchProtectedApp(context, app)
        }
    }

    /**
     * إعادة فتح التطبيق المحمي بنشاط مشغّله — تُستدعى بعد التعطيل
     * فقط، فلا يبقى خدمةٌ تعيد اكتشافه وتُدخلنا في حلقة.
     */
    private fun relaunchProtectedApp(
        context: Context,
        app: ProtectedApp
    ) {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(
                app.packageName
            )
        }.getOrNull()
        if (launch == null) {
            Log.w(TAG, "No launch intent for ${app.packageName}")
            return
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        runCatching { context.startActivity(launch) }
            .onFailure {
                Log.w(TAG, "Relaunch failed for ${app.packageName}", it)
            }
    }

    /**
     * معالجة الخروج من التطبيق المحمي واستعادة الخدمة.
     */
    fun handleAppExit(context: Context, pkg: String, label: String) {
        val settings = SettingsRepository.create(context)
        Log.i(TAG, "handleAppExit: pkg=$pkg, label=$label")

        if (isPartiallySuspended && partiallySuspendedPkg == pkg) {
            isPartiallySuspended = false
            partiallySuspendedPkg = null
            return
        }

        ProtectedAppWatcherService.stopService(context)

        val hasWriteSecure = SystemTtsEngineController.hasPermission(context)
        if (hasWriteSecure) {
            val ok = enableAccessibilityServiceDirectly(context)
            if (ok) {
                A11ySpeechCoordinator.speakAnnouncement(
                    context = context,
                    text = "عادت إمكانية الوصول",
                    isInterruptAllowed = true
                )
                settings.setSuspendedProtectedApp(null)
                return
            }
        }

        // عند عدم توفر الإذن: إشعار عالي الأولوية واهتزاز وإعلان صوتي
        postReenableNotification(context, label)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "انتهيت من $label، اضغط زرّي الصوت لإعادة تشغيل Lord",
            isInterruptAllowed = true
        )
        settings.setSuspendedProtectedApp(null)
    }

    /**
     * إعادة تفعيل خدمة إمكانية الوصول عبر Settings.Secure
     * مع الحفاظ على الخدمات الأخرى دون حذف (الأمر 7).
     */
    fun enableAccessibilityServiceDirectly(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val serviceName = OUR_SERVICE_NAME
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current.split(":")
                .filter { it.isNotBlank() }
                .toMutableList()
            if (services.none { isOurService(it) }) {
                services.add(serviceName)
            }
            val newString = services.joinToString(":")
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newString
            )
            Settings.Secure.putInt(
                cr,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            val verified = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            verified.split(":").any { isOurService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityServiceDirectly failed", e)
            false
        }
    }

    /** اسم خدمتنا المكتمل كما يُكتب في enabled_accessibility_services. */
    private const val OUR_SERVICE_NAME =
        "com.aymankhattab.nateq/.core.audio.accessibility" +
            ".NateqAccessibilityService"

    /**
     * هل يمثّل مدخلُ القائمة خدمتَنا؟ النظام يقبل صيغتين:
     * مختصرةً بالنقطة (`pkg/.Class`) وكاملةً (`pkg/full.Class`)،
     * ولا بدّ من مساواتهما وإلا بقي اسمُنا بعد التعطيل فلم يُفكَّ
     * الارتباط (وهو ما يُبقي انهيار إنستاباي).
     */
    private fun isOurService(entry: String): Boolean {
        val norm = entry.replace('/', '.').trim()
        val ours = OUR_SERVICE_NAME.replace('/', '.')
        return norm == ours ||
            norm == "com.aymankhattab.nateq" +
            ".com.aymankhattab.nateq.core.audio.accessibility" +
            ".NateqAccessibilityService"
    }

    /**
     * **تعطيلٌ فوريٌّ مباشر** لخدمتنا عبر تعديل
     * `enabled_accessibility_services` في `Settings.Secure`.
     *
     * لِمَ لا يكفي [`AccessibilityService.disableSelf`]؟ لأنها غير
     * متزامنة: تُرسل طلباً للنظام فيُنفّذه في دورة لاحقة عبر IPC،
     * بينما نقرة أيقونة المشغّل تُطلق التطبيقَ المحمي في نفس الحدث،
     * فيبدأ `onCreate` ويسقط قبل أن يكتمل فكُّ الارتباط. أمّا الكتابةُ
     * في الإعدادات فتُزيل اسمَنا من القائمة فوراً، فيُفكُّ الارتباط في
     * دورةِ النظام التالية مباشرةً بلا سباق.
     *
     * تُبقي خدماتِ إمكانية الوصول الأخرى (TalkBack وغيره) على حالها،
     * وتتطلّب `WRITE_SECURE_SETTINGS`.
     */
    fun disableAccessibilityServiceDirectly(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current.split(":")
                .filter { it.isNotBlank() && !isOurService(it) }
            val newString = services.joinToString(":")
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newString
            )
            val verified = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            verified.split(":").none { isOurService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "disableAccessibilityServiceDirectly failed", e)
            false
        }
    }

    private fun postReenableNotification(context: Context, appLabel: String) {
        val nm = context.getSystemService(
            NotificationManager::class.java
        ) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel("protected_app_channel")
            val channel = NotificationChannel(
                CHANNEL_ID_PROTECTED,
                "إعادة تشغيل إمكانية الوصول",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "تنبيه لإعادة تشغيل الخدمة بعد الخروج من التطبيق المحمي"
                enableVibration(false)
                vibrationPattern = null
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_REENABLE,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            context,
            CHANNEL_ID_PROTECTED
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("انتهيت من $appLabel")
            .setContentText(
                "اضغط زرّي الصوت لإعادة تشغيل Lord أو اضغط لفتح الإعدادات"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(null)
            .setDefaults(0)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "فتح الإعدادات",
                pendingIntent
            )

        nm.notify(NOTIFICATION_ID_REENABLE, builder.build())
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isPartiallySuspended = false
        partiallySuspendedPkg = null
    }
}
