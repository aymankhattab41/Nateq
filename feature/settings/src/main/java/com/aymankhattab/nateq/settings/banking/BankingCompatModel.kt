package com.aymankhattab.nateq.settings.banking

/**
 * تصنيف بند فحص توافق التطبيقات المصرفية.
 *
 * التطبيقات المصرفية (مثل إنستاباي) ترفض التشغيل على الأجهزة التي تكتشف
 * فيها علاماتٍ أمنية معيّنة. التصنيف هنا نقيٌّ (بلا أندرويد) حتى يُختبر
 * آلياً.
 *
 * **التصنيف مُصحَّحٌ بمُشاهدةٍ حقيقية من المستخدم** — ولا يُخمَّن:
 *  - قارئ شاشةٍ مُفعَّل وحده (أداة، `isAccessibilityTool == true`) →
 *    إنستاباي **يعمل**؛ فالخدمة ذاتها لا تمنعه، تُصنَّف LOW.
 *  - خيارات المطوّر وتصحيح USB واللاسلكي مُفعّلة → إنستاباي **يعمل**؛
 *    فلا يُصنَّف ADB خطراً أبداً.
 *  - ناطق مُفعَّل وحده → إنستاباي **يتوقف**؛ فالمستشهَد به هنا هو
 *    «الاستماع للإشعارات» (قدرةٌ لا يملكها قارئ الشاشة) و«العرض فوق
 *    التطبيقات»، وكلاهما HIGH.
 *
 * **لا يُعامَل أي حزمٍ خاصٌّ بشكلٍ استثنائي** — لا قارئ شاشةٍ معيّن
 * ولا محركٌ معيّن؛ كل بندٍ يُصنَّف بمحتواه فقط.
 */
enum class BankingRisk {
    /** قد يمنع التطبيقات المصرفية. */
    HIGH,

    /** غالباً غير مؤثر على التطبيقات المصرفية. */
    LOW
}

/** بند فحص واحد كما يُعرض في الشاشة (العنوان/الحالة/التصنيف). */
data class BankingCheckItem(
    val id: String,
    val label: String,
    val detail: String,
    val risk: BankingRisk
)

/** معرّفات البنود الثابتة — تُقرأ عبر [BankingCompatClassifier.riskFor]. */
object BankingCheckIds {
    const val ACCESSIBILITY_SERVICE = "a11y_service"
    const val ADB_ENABLED = "adb_enabled"
    const val ADB_WIFI = "adb_wifi"
    const val OVERLAY = "overlay"
    const val NOTIFICATION_LISTENER = "notif_listener"
    const val DEVICE_ADMIN = "device_admin"
    const val DIALER_ROLE = "dialer_role"
    const val VPN = "vpn"
    const val SU_BINARY = "su_binary"
    const val SHIZUKU = "shizuku"
    const val INSTALL_SOURCE = "install_source"
}

/** صنف حزمة ناطق (التطبيق) كما تُعلنه حزمةُ التثبيت نفسها. */
enum class InstallSourceClass {
    /** ثُبِّت من متجر تطبيقات موثوق. */
    PLAY_STORE,

    /** ثُبِّت من خارج المتجر (يدوي/مصدر غير موثوق). */
    SIDE_LOADED,

    /** التصنيف غير معروف — يُعامَل كثُبِّت من خارج المتجر احتياطاً. */
    UNKNOWN
}

/**
 * منطق التصنيف النقي لفحص توافق التطبيقات المصرفية — بلا أي اعتماد على
 * أندرويد كي يُختبر آلياً مباشرة (وحتى لا تُختبر واجهةٌ لا نتحقق منها).
 */
object BankingCompatClassifier {

    /** حزم متجر Google Play — وأيٌّ غيرها يُعتبر تثبيتاً من خارج المتجر. */
    private val STORE_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.feedback"
    )

    /**
     * تصنيف بند ثابت غير مرتبط بخدمة إمكانية الوصول.
     *
     * ملاحظة مهمة: ADB (اللاسلكي/السلكي) **ليس** خطراً هنا — رُصد أن
     * إنستاباي يعمل مع تفعيل خيارات المطوّر وتصحيح USB واللاسلكي،
     * فتصنيفه خطراً كان سيُضلِّل المستخدم في عزل السبب.
     */
    fun riskFor(id: String): BankingRisk = when (id) {
        BankingCheckIds.NOTIFICATION_LISTENER,
        BankingCheckIds.OVERLAY,
        BankingCheckIds.VPN,
        BankingCheckIds.SU_BINARY,
        BankingCheckIds.SHIZUKU -> BankingRisk.HIGH
        else -> BankingRisk.LOW
    }

    /**
     * تصنيف خدمة إمكانية وصول: قارئ الشاشة (`isAccessibilityTool == true`)
     * أداةٌ معترف بها فغالباً غير مؤثرة؛ أما غيرها فخدماتٌ تقرأ محتوى
     * النوافذ وتُعدّ علامةً أمنية يرفض التطبيقات المصرفية وجودها.
     */
    fun accessibilityServiceRisk(
        isAccessibilityTool: Boolean
    ): BankingRisk = if (isAccessibilityTool) {
        BankingRisk.LOW
    } else {
        BankingRisk.HIGH
    }

    /**
     * تصنيف مصدر تثبيت الناطق. الحزمة الفارغة/الغائبة تعني تثبيتاً من
     * خارج المتجر (احتياط أمني: لا نفترض المتجر بلا دليل).
     */
    fun classifyInstallSource(
        installingPackageName: String?
    ): InstallSourceClass = when {
        installingPackageName.isNullOrBlank() ->
            InstallSourceClass.SIDE_LOADED
        STORE_PACKAGES.contains(installingPackageName) ->
            InstallSourceClass.PLAY_STORE
        else -> InstallSourceClass.SIDE_LOADED
    }
}
