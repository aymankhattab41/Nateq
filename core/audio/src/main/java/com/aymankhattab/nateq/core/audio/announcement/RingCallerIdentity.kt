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

    /**
     * **بند 3 — سقفُ عمرِ الهوية: خمسَ عشرةَ ثانية.**
     *
     * **جذرُه:** كانت الهويةُ بلا طابعٍ زمنيٍّ أبداً، فتبقى بصفتِها
     * «متاحة» ما لم يمرَّ عليها [clear]. و[clear] يُستدعى من
     * [resetRingingSession] عند انتهاء المكالمة، **فأيُّ مسارٍ لا يمرّ
     * به — موتُ العملية، أو بثٌّ لم يُعتمد، أو استثناءٌ في فرعٍ ما —
     * يُبقي هويةَ مكالمةٍ سابقةٍ تُنطق في رنينٍ لاحقٍ** بلا دليلٍ على
     * أنّها لهذه المكالمة.
     *
     * **ولماذا 15 ثانية؟** المسارُ الحيُّ من النشر إلى النطق لا يتجاوز
     * `CALLER_RESOLVE_GRACE_PERIOD_MS` (1.5s) زائداً
     * `CALLER_IDENTITY_LATE_WAIT_MS` (6.5s) — أي أقلَّ من ثماني ثوانٍ —
     * فالسقفُ يترك هامشاً سخياً ويبقى أقصرَ ما يمكن على رنينٍ لاحق.
     *
     * **وعند كلِّ نشرٍ يُجدَّد** (لا عند أوله): المساراتُ الثلاثة تكتب
     * على دفعات — الفرزُ ينشر الرقمَ قبل الرنّة ثم يُنشر الاسمُ من
     * الإشعار أثناءها — فحسابُ العمرِ من أول نشرٍ يُبطل اسمَ وصل
     * متأخّراً رغم حداثته.
     */
    internal const val IDENTITY_TTL_MS = 15_000L

    @Volatile
    private var number: String? = null

    @Volatile
    private var name: String? = null

    /** زمنُ آخر نشرٍ **بالمللي ثانية**، وصفرٌ يعني «لا هويةَ منشورة». */
    @Volatile
    private var publishedAtMs: Long = 0L

    /**
     * نشر هوية: يملأ ما هو فارغ فقط فلا تُداس هوية أعلى منه. الاسم
     * مفضّل على الرقم (أدق للمستخدم)، والاسم الفارغ لا يحذف اسماً وصل
     * قبلياً.
     *
     * **ويُجدد الطابعَ الزمنيَّ (بند 3)** في كلِّ نشرٍ **غير فارغ** —
     * فالاسمُ أو الرقمُ الذي وصل متأخّراً يُبقي الهويةَ حيّةً ولو كان
     * أولُ نشرٍ قديماً. والنشرُ الفارغ `(null, null)` يُبطل عملَ الدالّة
     * فلا يُنشئ هويةً ولا يُجدد طابعاً: تجديدُه بكتابةٍ فارغة كان
     * سيمدّ عمرَ اسمٍ منتهيٍ ويبقيه يُنطق بعد مكالمةٍ انتهت.
     *
     * @param now زمنُ النشر بالمللي ثانية، **معاملٌ صريحٌ لأجل
     *   الاختبار** (الساعةُ تُمرَّر ولا تُقرأ من داخل الدالّة فيبقى
     *   العقدُ قابلاً للاختبار بمرور وقتٍ محاكى).
     */
    fun publish(
        callerNumber: String?,
        callerName: String?,
        now: Long = System.currentTimeMillis()
    ) {
        val n = callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        val m = callerName?.trim()?.takeIf { it.isNotEmpty() }
        if (n == null && m == null) return
        synchronized(lock) {
            if (number == null && n != null) number = n
            if (name == null && m != null) name = m
            publishedAtMs = now
        }
    }

    /**
     * لقطةٌ للحالة: زوج (الرقم، الاسم) **إن كانت ضمن صلاحيتها**،
     * وإلا زوجٌ فارغ.
     *
     * **والانتهاءُ هو السلوكُ المطلوب (بند 3):** هويةٌ تجاوزت
     * [IDENTITY_TTL_MS] من آخر نشرٍ تُقرأ كأنها غيرُ موجودة، فلا
     * تتسرّب هويةُ مكالمةٍ سابقةٍ إلى رنينٍ لاحق.
     */
    fun snapshot(
        now: Long = System.currentTimeMillis()
    ): Pair<String?, String?> =
        if (isIdentityFresh(now)) {
            Pair(number, name)
        } else {
            Pair(null, null)
        }

    /**
     * هل توفرت هويةٌ صالحةٌ للنطق (اسمٌ أو رقم) **ضمن عمرها**؟
     *
     * فالهويةُ المنتهيةُ كالغائبةِ تماماً — وهي التي كان مسحُ التكرار
     * يجدُها في لقطةٍ فلا ينطق اسماً لمكالمةٍ قديمة.
     */
    fun hasIdentity(now: Long = System.currentTimeMillis()): Boolean =
        isIdentityFresh(now) &&
            (!name.isNullOrEmpty() || !number.isNullOrEmpty())

    /**
     * هل الطابعُ الزمنيُّ ضمن [IDENTITY_TTL_MS]؟
     *
     * **خالصةٌ بلا حالةٍ داخلية** فتحسب عقدَ العمر وحدَه. والسالبُ
     * (`now` قبل الطابع — تقديماً يدوياً للساعة أو قياسٌ في عمليةٍ
     * أخرى) **لا يُحسب انتهاءً**: النتيجةُ «نُشر حديثاً» وهي آمنة،
     * لأن الأسوأَ تأخيرُ الانتهاء لا نطقَ هويةٍ منتهية.
     *
     * والصفرُ لا هويةَ معه: قبل أوّل نشرٍ لا طابع، فلا تُقرأ هوية.
     */
    private fun isIdentityFresh(now: Long): Boolean {
        val published = publishedAtMs
        if (published == 0L) return false
        val age = now - published
        return age < IDENTITY_TTL_MS
    }

    /** تصفيرُ الجلسة — عند انتهاء المكالمة. */
    fun clear() {
        synchronized(lock) {
            number = null
            name = null
            publishedAtMs = 0L
        }
    }

    /**
     * استخراج هوية المتصل من إشعار مكالمة.
     *
     * يُبحث في حقل العنوان أولاً (تطبيقات الهاتف تضع الاسم أو الرقم
     * فيه)، ثم في `EXTRA_TEXT` ثم `EXTRA_SUB_TEXT`. يُستبعد النص العام
     * لأنه **ليس هوية** — وعودتنا للمستخدم ألا ننطق بلا اسم أو رقم.
     *
     * **الرقمُ والاسمُ يُستخرجان معاً من كل المرشحين لا من أولّهم فقط**
     * (بند 5.6): كان يُؤخذ المرشُّ الأول فقط، فإن وضع تطبيقُ الهاتف
     * **الرقمَ في العنوان والاسمَ في النصّ** طُرح الاسمُ كلياً — وهو
     * بالضبط عيبُ «المُسجَّل يُنطق رقمَه لا اسمَه»، إذ ينشر
     * [publish] ما يملأ الفراغَ فقط فلا يعود الاسمُ من هذا المصدر.
     * فصار لكل حقلٍ دورُه: أوّلُ ما يبدو رقماً رقمٌ، وأوّلُ ما يبدو
     * اسماً اسمٌ، ويُعطى كلٌّ منهما أوّلُ مرشّحٍ له.
     *
     * خالصة بلا `Context` فهي قابلة للاختبار وحدها.
     */
    fun extractFromCallNotification(
        title: String?,
        text: String?,
        subText: String?
    ): Pair<String?, String?> {
        val candidates = listOfNotNull(title, text, subText)
            .map { stripBidiControls(it).trim() }
            .filter { it.isNotEmpty() && !isGenericCallPhrase(it) }
        if (candidates.isEmpty()) return Pair(null, null)
        val number = candidates.firstOrNull { looksLikePhoneNumber(it) }
        val name = candidates.firstOrNull { !looksLikePhoneNumber(it) }
        return Pair(number, name)
    }

    /**
     * حذفُ محارف الضبط الاتجاهي (bidi) من نصٍّ مستخرج: `LRM` و`RLM`
     * و`ALM` والفراغِ الصفرِي والـ`BOM`. تُغشّي تطبيقاتُ الهاتف الرقمَ
     * والاسمَ بها في الواجهة العربية (وهي مقبولةٌ في
     * [looksLikePhoneNumber] عمداً)، فبدون الحذف تخرج في الهوية
     * المنطوقة وفي مفتاح منع التكرار فتبخَق المقارنات.
     *
     * خالصةٌ قابلةٌ للاختبار.
     */
    internal fun stripBidiControls(text: String): String =
        text.filterNot { it in BIDI_CONTROL_CHARS }

    /**
     * محارفُ الضبط الاتجاهي — لا تُنطق ولا تُقارَن. تُكتبُ بترميزٍ
     * صريحٍ (`\u200E`) لا حرفياً: فهي غيرُ مرئيةٍ في المحرّر، والكتابةُ
     * الحرفيةُ تتلاشى عند الحفظ فتُنتج محرفاً فارغاً في compilation.
     */
    private val BIDI_CONTROL_CHARS = setOf(
        '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D',
        '\u202E', '\u2066', '\u2067', '\u2068', '\u2069', '\u061C',
        '\u200B', '\uFEFF'
    )

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
    *
    * **بند 5.6 — لا تُطابَق كتابةُ مُشغِّلِ الهاتف حرفياً:** كان الشرطُ
    * تساوياً تامّاً (`t == it || t.startsWith("$it ")`) على قائمةٍ صغيرة،
    * فتنكسر المطابقةُ عند أوّل اختلافٍ طباعيٍّ يكتبه التطبيقُ فعلياً:
    * «**جارٍ** الاتصال» بتشكيلٍ لا «جاري»، و«**جارى**» بألفٍ مقصورة
    * لا بياء، و«مكالم**ة** صادرة جاري**ة**» جملةً لا عبارتين. فنُطبَّع
    * النصّ أولاً ([normalizeCallPhrase]: تشكيلٌ محذوف، همزاتٌ موحَّدة،
    * تاءُ مربوطةٌ كـ«ه»، وعلاماتُ وقفٍ منقوصةٌ محذوفة) ويُبحث
    * بـ**تضمينٍ** لا بتساوٍ، فصار النصُّ الحقيقيُّ يُلتقَط. والبحثُ
    * بالتضمين لا يُخلط: لا اسمَ عربيٌّ ولا عبارةٌ واردةٌ تحوي أيَّ
    * واحدةٍ من هذه العلامات.
    */
    internal fun isOutgoingCallPhrase(text: String): Boolean {
        val t = normalizeCallPhrase(text)
        if (t.isEmpty()) return false
        return normalizedOutgoingMarkers.any { marker -> t.contains(marker) }
    }

    /**
     * تطبيعُ نصّ عبارةِ مكالمة قبل مطابقتها: حروفٌ صغيرة، تشكيلٌ
     * (علاماتُ الوقف والهمزات والتطويل) محذوف، همزاتُ الألف
     * (أ إ آ ٱ) موحَّدةً على «ا»، وألفُ مقصورة «ى» على «ي»،
     * وتاءُ مربوطة «ة» على «ه»، وعلاماتُ الوقف اللاتينية (`.` `…` `!`)
     * محذوفةٌ أيضاً فلا تُبقي «calling…» خارجَ قائمة «calling».
     *
     * خالصةٌ وقابلةٌ للاختبار.
     */
    internal fun normalizeCallPhrase(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.trim().lowercase()) {
            when {
                ch in TASHKEEL_RANGE -> Unit
                ch in QURANIC_MARKS_RANGE -> Unit
                ch in ALEF_VARIANTS -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch in STOP_MARKS -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /** مدى علامات التشكيل والوقف العربي (تُحذف كلّها). */
    private val TASHKEEL_RANGE = 'ً'..'ٰ'

    /** مدى علامات المصحف الصغيرة (حركاتٌ وعلاماتُ وقفٍ — تُحذف). */
    private val QURANIC_MARKS_RANGE = 'ۖ'..'ۭ'

    /** صيغُ همزات الألف الأربع التي تُوحَّد على «ا». */
    private val ALEF_VARIANTS = setOf('أ', 'إ', 'آ', 'ٱ')

    /** علاماتُ الوقف التي تُحذف فلا تُبقي «calling…» خارجَ «calling». */
    private val STOP_MARKS = setOf('.', '…', '!', '؟', '،')

    /** علاماتُ نصِّ صادر — تُكتب خاماً ثم تُطبَّع مرّةً واحدة عند أول
     *  استعمال، فتكفي صيغةٌ واحدة مهما اختلف هجاءُ العربية.
     *
     *  «جار الاتصال» و«جاري الاتصال» صيغتان شائعتان لنفس العبارة
     * («جارٍ» بلا ياء أصلاً فيُكتب كذلك)، وكلتاهما مشمولةٌ بعد التطبيع.
     *
     *  **و«ringing» محذوفةٌ منها — وهو تصحيحُ خطأٍ حقيقي:** كان سطرُها
     *  فحُسبت دليلاً على الصدور، و Meet يكتب «Ringing tone…» على
     *  مكالمتِه **الواردة**. فكان الحارسُ في المسارِ الحقيقي يكبِتُ
     *  إعلانَ Meet الوارد (`ongoing` مع `outgoing`) — أي انحدارُ
     *  v1.6.20 الذي ظُنّ مُصلَحاً. واختبارُ v1.6.20 كان يمرّر
     *  `outgoing = false` مكتوبةً بيده فلا يمسّ الدالةَ أصلاً، فحُرِس
     *  الحالةُ باطل. والكلمةُ ملتبسةٌ بطبيعتها
     *  (الواردُ يرنّ والمُرسِلُ يَرِنّ) فلا يجوز أن تكون رفضاً بلا سند؛
     *  وإثباتُ الصادرة صار بالغياب: من لا دليلَ له على الورود لا يُعلَن
     *  ([isPositivelyIncoming]). */
    private val OUTGOING_PHRASE_MARKERS = listOf(
        "calling", "outgoing call", "outgoing", "placing call",
        "placing", "dialing call", "dialing",
        "جاري الاتصال", "جار الاتصال", "يتصل",
        "مكالمة صادرة", "اتصال صادر", "صادرة", "صادر"
    )

    /** العلاماتُ بعد التطبيع — فلا يُقارَن نصٌّ مُطبَّعٌ بعلامةٍ خام. */
    private val normalizedOutgoingMarkers: List<String> by lazy {
        OUTGOING_PHRASE_MARKERS.map { normalizeCallPhrase(it) }
    }

    /**
     * هل النصّ يصفّ مكالمةً **واردة** بدليلٍ إيجابي؟
     *
     * **علاماتُ هذه الدالة تعمل على `API < 31` وحدَها** — انظر
     * [isPositivelyIncoming] وما يقابلها من معامل. فمن 31 فصاعداً توفّر
     * المنصّةُ نفسُها نوعَ المكالمةَ والإجراءَ الدلاليَّ، ويصير نصُّ
     * تطبيقٍ مُترجَمٌ تخميناً بلا سندٍ منطقي — فيعود سببُ الشكوى الأصلي:
     * نطقُ الصادرةِ واردةً. **فهي ممنوعةٌ فوق 31.**
     *
     * **ولماذا لا «ringing»؟** لأنها في قائمة الصادرة: Meet يكتب
     * «Ringing tone…» على مكالمتِه **الواردة**، فلو أُدرجت هنا لتناقضت
     * القائمتان فيُرفض إعلانُ Meet الوارد — وهو الانحدارُ الذي حدث فعلاً
     * في v1.6.20.
     *
     * خالصةٌ قابلةٌ للاختبار مباشرةً.
     */
    internal fun isIncomingCallPhrase(text: String): Boolean {
        val t = normalizeCallPhrase(text)
        if (t.isEmpty()) return false
        return normalizedIncomingMarkers.any { marker -> t == marker }
    }

    /**
     * علاماتُ الوارد — تُكتب خاماً فتُطبَّع عند أول استعمال.
     *
     * **والتساويُ لا التضمين، وذلك مقصود:** النصوصُ المفحوصة هي عنوانُ
     * الإشعار ونصُّه، واسمُ المتصل يقيمُ في النصِّ نفسِه («مكالمة واردة
     * من أحمد»). فبالتضمين يصير الاسمُ وحدَه دليلَ اتجاهٍ فيُعلَن كلُّ
     * متصلٍ في كلِّ مكالمة. أمّا التساويُ فلا يُدخل إلا العبارةَ التي هي
     * نفسُها الاتجاه.
     */
    private val INCOMING_PHRASE_MARKERS = listOf(
        "incoming call", "incoming", "مكالمة واردة", "مكالمه وارده"
    )

    /** العلاماتُ بعد التطبيع — فلا يُقارَن نصٌّ مُطبَّعٌ بعلامةٍ خام. */
    private val normalizedIncomingMarkers: List<String> by lazy {
        INCOMING_PHRASE_MARKERS.map { normalizeCallPhrase(it) }
    }

    /**
     * هل انتهى رنينُ المكالمة — أي صارت **مُجابةً/نشطة** لا رنّةً؟
     *
     * **جذرُ «نطق المتصل بعد فتح المكالمة»:** حلقةُ التكرار كانت تنفّذ
     * `delay` ثم تنطق بلا أي فحص، فلا يتوقفُ النطقُ إلا بإلغاءٍ من
     * `OFFHOOK`. ومكالماتُ التطبيقات (VoIP) **لا يُبَثّ لها
     * `PHONE_STATE` إطلاقاً**، فالإلغاءُ الوحيد فيها عند *حذف* إشعار
     * المكالمة — وردُّ المستخدم يحوّل الإشعارَ إلى «جارية» ولا يحذفه،
     * فاستمرّ الاسمُ يُنطق فوق المكالمةِ الجارية حتى آخر تكرار.
     *
     * **والإثباتُ بالإيجاب لا بغياب الدليل:** لا يكفي أن ينقص
     * «جارٍ الاتصال» من النصّ، فقد يغيّر التطبيقُ صياغتَه فلا يبقى ما
     * يُطابَق. فلا تُحسب إلا عبارةٌ **صريحةٌ بالجوارحة**، بالعربية
     * والإنجليزية — فهذه القاعدة لا تصيب إعلانَ Meet الوارد
     * («Ringing tone…») وهو إصلاحٌ سابق (بند 5.6).
     *
     * خالصةٌ قابلةٌ للاختبار بلا `Context`.
     */
    internal fun isAnsweredCallPhrase(text: String): Boolean {
        val t = normalizeCallPhrase(text)
        if (t.isEmpty()) return false
        return normalizedAnsweredMarkers.any { marker -> t.contains(marker) }
    }

    /** عباراتُ الجوارحة الصريحة — تُكتب خاماً فتُطبَّع عند أول استعمال. */
    private val ANSWERED_PHRASE_MARKERS = listOf(
        "ongoing call", "ongoing", "active call", "on call",
        "in call", "connected call", "call in progress",
        "مكالمة جارية", "مكالمة جاريه", "مكالمة نشطة", "مكالمة نشطه",
        "مكالمة متصلة", "مكالمة متصله", "جاريه", "جار مكالمه"
    )

    /** العباراتُ بعد التطبيع — فلا يُقارَن نصٌّ مُطبَّعٌ بعلامةٍ خام. */
    private val normalizedAnsweredMarkers: List<String> by lazy {
        ANSWERED_PHRASE_MARKERS.map { normalizeCallPhrase(it) }
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
