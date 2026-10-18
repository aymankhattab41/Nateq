package com.aymankhattab.nateq.core.audio.accessibility

/**
 * سياسة التطبيقات لإمكانية الوصول (ع٢٧-9):
 * - قائمة بيضاء افتراضية (واتساب وتلغرام).
 * - حجب تام للتطبيقات الحساسة (الإعدادات، القفل، الدفع).
 * - لا تُنفَّذ أي ميزة خارج قائمة التطبيقات المسموحة.
 */
object AppPolicy {

    /** الحزم المسموحة افتراضياً */
    val DEFAULT_WHITELIST = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger"
    )

    /** حزم تطبيقات الدردشة المدعومة في ملخّص المحادثة (ع٤١-ب5). */
    val CHAT_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "com.facebook.orca",
        "com.android.mms",
        "com.google.android.apps.messaging"
    )

    /** هل الحزمة تطبيق دردشة مدعوم؟ */
    fun isChatPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return CHAT_PACKAGES.contains(packageName.lowercase())
    }

    /** الحزم الحساسة المحجوبة قطعياً */
    val SENSITIVE_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.settings",
        "com.android.systemui",
        "com.android.keyguard",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.gms.pay"
    )

    /** حزم إعدادات النظام المسموحة مؤقتاً أثناء جلسة الأتمتة فقط */
    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.settings"
    )

    /**
     * جلسة أتمتة ضيقة ومؤقتة ومحصورة بشاشة إعدادات النطق (ع٤٥).
     * تنتهي تلقائياً بعد 10 ثوانٍ كحد أقصى لمنع أي تسريب أمني.
     */
    object AutomationSession {
        private const val MAX_DURATION_MS = 10_000L

        @Volatile
        private var sessionToken: String? = null

        @Volatile
        private var sessionExpiresAt: Long = 0L

        val isActive: Boolean
            get() {
                sessionToken ?: return false
                val now = android.os.SystemClock.uptimeMillis()
                if (now >= sessionExpiresAt) {
                    sessionToken = null
                    sessionExpiresAt = 0L
                    return false
                }
                return true
            }

        fun start(token: String): Boolean {
            sessionToken = token
            sessionExpiresAt =
                android.os.SystemClock.uptimeMillis() + MAX_DURATION_MS
            return true
        }

        fun stop(token: String? = null) {
            if (token == null || sessionToken == token) {
                sessionToken = null
                sessionExpiresAt = 0L
            }
        }

        fun currentToken(): String? = if (isActive) sessionToken else null

        /**
         * هل الحزمة مسموحة للأتمتة أثناء الجلسة النشطة فقط؟
         */
        fun isPackageAllowedForAutomation(pkg: String?): Boolean {
            if (!isActive || pkg.isNullOrBlank()) return false
            val lower = pkg.lowercase()
            return SETTINGS_PACKAGES.contains(lower) ||
                lower.endsWith(".settings")
        }
    }

    fun isAutomationActive(): Boolean = AutomationSession.isActive

    /** الكلمات الدلالية لتطبيقات النظام أو الدفع الحساسة */
    private val SENSITIVE_KEYWORDS = listOf(
        "keyguard",
        "lockscreen",
        "password",
        "authenticator",
        "bank",
        "wallet",
        "payment"
    )

    /**
     * هل التطبيق حساس للنظام أو الأمان؟
     */
    fun isSystemSensitive(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val lower = packageName.lowercase()
        if (SENSITIVE_PACKAGES.contains(lower)) return true
        return SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * هل يُسمح بتشغيل ميزات إمكانية الوصول على هذه الحزمة؟
     */
    fun isAllowed(
        packageName: String?,
        userAllowedPackages: Set<String>? = null,
        allowAllApps: Boolean = false
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()

        // التطبيقات الحساسة محجوبة دائماً دون استثناء
        if (isSystemSensitive(lower)) return false

        if (allowAllApps) return true

        val allowedSet = userAllowedPackages ?: DEFAULT_WHITELIST
        return allowedSet.contains(lower)
    }
}
