package com.aymankhattab.nateq.settings.banking

/**
 * خطوات معالج «اعزل السبب» — تُنفَّذ بالترتيب المُعلن للمستخدم، ونتيجة
 * كل خطوة أن «فُتح التطبيق» (true) أم «لم يُفتح» (false).
 *
 * **التسلسل مبنيٌّ على إثباتٍ مُشاهَدٍ من المستخدم لا على التخمين:**
 *  - قارئ شاشةٍ ثالث مُفعَّل وحده → إنستاباي **يعمل**؛ فتشخيص «أي خدمة
 *    إمكانية وصول تمنع المصرفية» ساقط، والمشكلة ليست في كون خدمةٍ تعمل.
 *  - تفعيل خيارات المطوّر وتصحيح USB واللاسلكي → إنستاباي **يعمل**؛
 *    فتصحيح الأجهزة (ADB) ليس سبباً، فلا يُختبر في المعالج ولا يُصنَّف
 *    خطراً في شاشة الفحص.
 *  - تفعيل ناطق وحده → إنستاباي **يتوقف**؛ فالسبب في قدرةٍ خاصةً بناطق،
 *    وهذه خطوات المعالج تُفصلها واحداً واحداً بترتيب المشتبه به.
 */
enum class IsolateStep {
    /** خطوة 1: فتح التطبيق كما هو الآن (كل قدرات ناطق مفعّلة). */
    BASELINE,

    /**
     * خطوة 2: سحب إذن «الاستماع للإشعارات» عن ناطق ثم فتح التطبيق.
     * **أعلى المشتبه**: قارئ الشاشة المُفعَّل (الذي يعمل وحده) ليس مُستمِعاً
     * للإشعارات، فاستثناؤه يزيح احتمال خدمة إمكانية الوصول ويُبقي هذا.
     */
    DISABLE_NOTIFICATION_LISTENER,

    /** خطوة 3: إيقاف خدمة إمكانية الوصول في ناطق ثم فتح التطبيق. */
    DISABLE_ACCESSIBILITY,

    /** خطوة 4: إبطال «العرض فوق التطبيقات» عن ناطق ثم فتح التطبيق. */
    REVOKE_OVERLAY,

    /**
     * خطوة 5: إعادة تفعيل كل ما سُحِب (لا تُختبر بها فتحة — استعادة فقط).
     * بعد إتمامها يجب أن يعود التطبيق إلى حالته قبل المعالج.
     */
    RESTORE_SERVICES
}

/** نتيجة خطوة واحدة: هل فتح التطبيق بعد تنفيذها؟ */
data class IsolateResult(
    val step: IsolateStep,
    val opened: Boolean
)

/** السبب الأرجح الذي تُخرج به نتائج المعالج. */
enum class IsolateCause {
    /** فتح التطبيق مع كل القدرات مفعّلة → لا تعارض أصلاً. */
    NONE,

    /** إذن «الاستماع للإشعارات» هو السبب. */
    NOTIFICATION_LISTENER,

    /** خدمة إمكانية الوصول في ناطق هي السبب. */
    ACCESSIBILITY,

    /** «العرض فوق التطبيقات» هو السبب. */
    OVERLAY,

    /** لم يفتح التطبيق بعد كل الخطوات → السبب خارج ناطق. */
    OTHER_APP
}

/** خلاصة المعالج: السبب الأرجح مع الخطوة التي أثبتته ونتائج كلها. */
data class IsolateSummary(
    val cause: IsolateCause,
    val causeStep: IsolateStep?,
    val results: List<IsolateResult>
)

/**
 * معالج «اعزل السبب» — منطق نقيٌّ بلا اعتماد على أندرويد، كي يُختبر آلياً
 * ويُبنى فوقه أي واجهة.
 *
 * **القاعدة الحاكمة**: لا نُدَّعي سبباً إلا بمقارنةٍ قبلية/بعدية على
 * الخطوة نفسها: الخطوة 1 تُثبت أن المشكلة موجودة أصلاً، وكل خطوةٍ بعدها
 * تُغيّر متغيّراً واحداً فقط؛ فأي «فُتح» يسبقه «لم يُفتح» يُحيل السببَ
 * إلى المتغيِّر الخاص بتلك الخطوة. وقفزُ نتيجة الخطوة من «لم يُفتح» إلى
 * «فُتح» هو العلامة الوحيدة التي تُسمح بالاستنتاج — لا رأيٌ ولا تخمين.
 *
 * **خطوة الاستعادة (5) لا تُختم بها استنتاج**: لم يُغيَّر فيها متغيّر
 * بل أُعيد وضع الأمور كما كانت، فلا تصلح دليلاً على سببٍ ولا تنقضٍ له.
 */
object IsolateCauseWizard {

    /** اسم ملف نتائج الخطوات — يُكتب داخل `filesDir` ويُقرأ في الخلاصة. */
    const val RESULTS_FILE_NAME = "banking_isolate_results.txt"

    /** الخطوات بالترتيب المطلوب تنفيذه. */
    val steps: List<IsolateStep> = IsolateStep.values().toList()

    /** عدد الخطوات = عدد نتائج المعالج لازم لاحتساب الخلاصة. */
    fun stepCount(): Int = steps.size

    /** الخطوات التي تُختم فيها كلُّ نتائجَها خلاصةً سببٍ (لا 5: استعادة). */
    val causeSteps: List<IsolateStep> = listOf(
        IsolateStep.BASELINE,
        IsolateStep.DISABLE_NOTIFICATION_LISTENER,
        IsolateStep.DISABLE_ACCESSIBILITY,
        IsolateStep.REVOKE_OVERLAY
    )

    /** تحويل النتيجة إلى سطر ملف (صيغة `STEP=boolean`). */
    fun formatResult(step: IsolateStep, opened: Boolean): String =
        "${step.name}=$opened"

    /** قراءة سطر واحد — ترجع null عند أي سطر تالف أو مجهول. */
    fun parseResultLine(line: String): IsolateResult? {
        val trimmed = line.trim()
        val separator = trimmed.indexOf('=')
        if (separator <= 0) return null
        val name = trimmed.substring(0, separator)
        val value = trimmed.substring(separator + 1)
        val step = steps.firstOrNull { it.name == name } ?: return null
        val opened = when (value) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return IsolateResult(step, opened)
    }

    /**
     * قراءة ملف النتائج كاملاً — تتجاهل الأسطر التالفة وتُبقي الترتيب
     * **بترتيب التنفيذ الفعلي** (الخطوة 1 قبل 2 ...) حتى لا تعتمد الخلاصة
     * على ترتيب الملف الذي قد يُعدَّل من المستخدم.
     */
    fun parseResults(text: String): List<IsolateResult> =
        text.lineSequence()
            .mapNotNull { parseResultLine(it) }
            .sortedBy { steps.indexOf(it.step) }
            .toList()

    /**
     * احتساب الخلاصة: قارن كل خطوةٍ بخطوة الأساس (1) — فالفتحُ بعد سحب
     * قدرةٍ يُحيل السببَ إلى تلك القدرة. وينتُج السببُ من أول خطوةٍ
     * تُقلب النتيجة من «لم يُفتح» إلى «فُتح».
     */
    fun summarize(results: List<IsolateResult>): IsolateSummary {
        val byStep = results.associateBy { it.step }
        val baseline = byStep[IsolateStep.BASELINE]
            ?: return IsolateSummary(
                cause = IsolateCause.OTHER_APP,
                causeStep = null,
                results = results
            )

        // الخطوة 1: يفتح التطبيق مع كل القدرات مفعّلة → لا تعارض.
        if (baseline.opened) {
            return IsolateSummary(
                cause = IsolateCause.NONE,
                causeStep = IsolateStep.BASELINE,
                results = results
            )
        }

        // الخطوات 2/3/4: يُحسم بقفزة «لم يُفتح ← فُتح» مقابل الأساس.
        val flips: List<Pair<IsolateStep, IsolateCause>> = listOf(
            IsolateStep.DISABLE_NOTIFICATION_LISTENER to
                IsolateCause.NOTIFICATION_LISTENER,
            IsolateStep.DISABLE_ACCESSIBILITY to
                IsolateCause.ACCESSIBILITY,
            IsolateStep.REVOKE_OVERLAY to
                IsolateCause.OVERLAY
        )
        for ((step, cause) in flips) {
            if (byStep[step]?.opened == true) {
                return IsolateSummary(
                    cause = cause,
                    causeStep = step,
                    results = results
                )
            }
        }

        // لم يُفتح بعد كل الخطوات → السبب خارج ناطق.
        return IsolateSummary(
            cause = IsolateCause.OTHER_APP,
            causeStep = null,
            results = results
        )
    }

    /** هل اكتملت كل الخطوات التي تُختم بها خلاصة سببٍ؟ */
    fun hasCauseEvidence(results: List<IsolateResult>): Boolean {
        val done = results.map { it.step }.toSet()
        return done.containsAll(causeSteps)
    }
}
