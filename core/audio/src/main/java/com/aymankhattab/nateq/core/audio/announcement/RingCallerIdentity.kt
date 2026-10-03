package com.aymankhattab.nateq.core.audio.announcement

/**
 * هوية المتصل للجلسة الرنينية الواحدة — حاوية مشتركة بين مسارات
 * الوصول الثلاثة التي تتنافس على تحميلها:
 *
 *  1. **بث حالة الهاتف** ([CallerAnnouncementReceiver]) — يحمل الرقم
 *     لكن لا يضمنه: توثيق أندرويد ينص على أن الإرسال يتم مرتين، إحداهما
 *     بلا رقم، ولا يجوز افتراض الترتيب.
 *  2. **إشعار المكالمة** ([NateqNotificationListener]) — يحمل **الاسم**
 *     محلولاً محلياً وقت الرنة، وهو ما تعتمده TalkBack وGoogle Phone.
 *  3. **خدمة فرز المكالمات** ([CallerScreeningService]) — الرقم قبل
 *     الرنة حين يحمل التطبيق دور الفرز.
 *
 * النتيجة: **أي مسار يعطي هوية أولاً هو الفائز**، ولا ينتظر أحدهم
 * أحداً.
 *
 * **لماذا حاوية مشتركة لا استدعاء مباشر؟** لأن المسارات الثلاثة تصل على
 * خيوط مختلفة (بث على الرئيسية، ومستمع إشعارات على خيط الخدمة، وخدمة
 * على خيط الربط)، ولو نطق كل منها مباشرة لتضاعف النطق — وهو بالضبط
 * العيب الذي أصلحناه في `v1.6.8`. هنا **يكتب الجميع**، والحلقة الواحدة
 * في [CallerAnnouncementReceiver] وحدها **تقرأ وتُنطق**، فيبقى «إعلان
 * واحد لكل مكالمة» عقداً بنيوياً لا شرطاً متفقاً عليه.
 *
 * الحالة متزامنة بـ`synchronized` لأن الكتابة من خيط البث والقراءة من
 * خيط `appScope` فعلياً؛ و`@Volatile` على الحقول يتيح قراءة سريعة في
 * الحلقة دون قفل.
 *
 * تُصفَّر بـ[clear] عند انتهاء المكالمة (انظر [resetRingingSession]) فلا
 * تتسرب هوية مكالمة إلى ما بعدها.
 */
internal object RingCallerIdentity {

    private val lock = Any()

    @Volatile
    private var number: String? = null

    @Volatile
    private var name: String? = null

    /**
     * نشر هوية: يملأ ما هو فارغ فقط فلا تُداس هوية أعلى منه. الاسم
     * مفضّل على الرقم (أدق للمستخدم)، والاسم الفارغ لا يحذف اسماً وصل
     * قبلياً.
     */
    fun publish(callerNumber: String?, callerName: String?) {
        val n = callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        val m = callerName?.trim()?.takeIf { it.isNotEmpty() }
        if (n == null && m == null) return
        synchronized(lock) {
            if (number == null && n != null) number = n
            if (name == null && m != null) name = m
        }
    }

    /** لقطة للحالة: زوج (الرقم، الاسم)، وكلاهما قد يكون فارغاً. */
    fun snapshot(): Pair<String?, String?> = Pair(number, name)

    /** هل توفرت هوية صالحة للنطق (اسم أو رقم)؟ */
    fun hasIdentity(): Boolean =
        !name.isNullOrEmpty() || !number.isNullOrEmpty()

    /** تصفير الجلسة — عند انتهاء المكالمة. */
    fun clear() {
        synchronized(lock) {
            number = null
            name = null
        }
    }

    /**
     * استخراج هوية المتصل من إشعار مكالمة.
     *
     * يُبحث في حقل العنوان أولاً (تطبيقات الهاتف تضع الاسم أو الرقم
     * فيه)، ثم في `EXTRA_TEXT` ثم `EXTRA_SUB_TEXT`. يُستبعد النص العام
     * لأنه **ليس هوية** — وعودتنا للمستخدم ألا ننطق بلا اسم أو رقم.
     *
     * خالصة بلا `Context` فهي قابلة للاختبار وحدها.
     */
    fun extractFromCallNotification(
        title: String?,
        text: String?,
        subText: String?
    ): Pair<String?, String?> {
        val candidates = listOfNotNull(title, text, subText)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isGenericCallPhrase(it) }
        if (candidates.isEmpty()) return Pair(null, null)
        // أول مرشح يبدو رقماً فهو رقم بلا اسم؛ وإلا فهو اسم محلول.
        val first = candidates.first()
        return if (looksLikePhoneNumber(first)) {
            Pair(first, null)
        } else {
            Pair(null, first)
        }
    }

    /**
     * هل النص عبارة عامة عن مكالمة بلا هوية؟ تُستبعد حتى لا تُنطق
     * بوصفها اسماً — بالعربية والإنجليزية معاً.
     */
    internal fun isGenericCallPhrase(text: String): Boolean {
        val t = text.trim().lowercase()
        val markers = listOf(
            "وارد", "واردة", "مكالمة واردة", "مكالمة", "اتصال",
            "مكالمة جارية",
            "رقم محجوب", "رقم غير معروف", "غير معروف", "محجوب",
            "incoming", "incoming call", "call", "blocked",
            "blocked number", "withheld", "private", "unknown"
        )
        if (markers.none { t == it }) return false
        // نص يطابق عبارة عامة فلا هوية فيه. نص طويل (مثل «مكالمة واردة
        // من أحمد») يحمل الاسم فلا يُستبعد.
        return !t.any { it.isDigit() }
    }

    /**
     * هل النصّ يصفّ مكالمةً **صادرة** (المستخدمُ هو المتصل)؟
     *
* **لماذا وُجد (انحدارُ جوجل ميت):** كان حارسُ الاتجاه يرفض كلَّ
     * إشعارٍ مُعلَّم `ongoing`، فسقط إعلانُ Meet بصمت. وجوجل ميت
     * يُعلِّم إشعارَ مكالمته الواردة `ongoing` **من لحظة الرنّ** (لأنه
     * واجهةُ مكالمةٍ حيّة لا إشعارُ حدثٍ عابر)، فسقط كلُّ إعلانٍ لجوجل
     * ميت بصمت. ولم يكن في الحارس ما يميّز «واردةً مُعلَّمة ongoing»
     * من «صادرةً مُعلَّمة ongoing» — بل كان يرفض الاثنين معاً.
     *
     * فالحارسُ صار يرفض `ongoing` إذا بدا نصُّه صادراً، ويقبله وإلا.
     * وهذه هي العلاماتُ التي تنشرُها التطبيقات عند إجراء المستخدم
     * المكالمة (عربياً وإنجليزياً).
     */
    internal fun isOutgoingCallPhrase(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        val markers = listOf(
            "calling", "calling...", "calling…",
            "outgoing call", "outgoing", "placing call", "dialing",
            "ringing...", "ringing…", "ringing",
            "جاري الاتصال", "جاري الإتصال", "يتصل",
            "مكالمة صادرة", "اتصال صادر", "صادرة"
        )
        return markers.any { t == it || t.startsWith("$it ") }
    }

    /** أرقام فقط (مع رموز الاتصال المسموحة) فهو هوية رقمية. */
    internal fun looksLikePhoneNumber(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val digits = t.count { it.isDigit() }
        if (digits < MIN_PHONE_DIGITS) return false
        val foreign = t.count { !it.isDigit() && it !in ALLOWED_PHONE_CHARS }
        return foreign == 0
    }

    /** أقل عدد خانات يعد رقماً (لتجاهل كلمة قصيرة عادية). */
    private const val MIN_PHONE_DIGITS = 3

    /** رموز مسموحة ضمن رقم الهاتف. */
    private val ALLOWED_PHONE_CHARS = setOf(
        '+', '#', '*', '-', '(', ')', '.', ' ', ' ', '‎', '‏'
    )
}