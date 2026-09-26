package com.aymankhattab.nateq.core.audio.providers

/**
 * سجلّ محركات TTS: مركز القرار النقي لاختيار المحرك الأفضل المثبّت،
 * والاحتياط بعد الفشل، وسلسلة المحركات القادرة على نطق لغةٍ معيّنة.
 *
 * كل دوال هذا الكائن نقية (بلا Context) وقابلة للاختبار الآلي مباشرةً؛
 * الاستعلامات المعتمدة على النظام (المثبّتة في الجهاز) تبقى في
 * [EnginePicker] الذي يفوض قرارات الاختيار إلى هنا ليتّحد منطق القرار
 * في مكان واحد عبر التطبيق كله.
 *
 * **اختيارٌ ذكي صامت:** حين لا يختار المستخدم محركاً صراحةً لفئةٍ أو لغة،
 * يُعتمد هذا الاختيارُ التلقائي داخلياً وكأنّ المستخدم اختاره — بلا أي
 * إشعار صوتي/مرئي/تشخيصي (متطلب صمت الاختيار التلقائي).
 */
object EngineRegistry {

    /** محركات النطق الحقيقية التي نمنحها الأولوية عند اختيار تلقائي، لأنها
     *  مضمونةً تُنتج صوتاً قياسياً (على عكس قارئات الشاشة). السلسلة مرنة:
     *  الطرفية القادرة على تخليقٍ قياسي أولاً (MultiTTS ثم eSpeak)، ثم
     *  النظامية الأصلية (AOSP، Huawei Celia)، ثم محركات المصنّعين المغلقة،
     *  وجوجل ملاذٌ أخير — يُصل إليه التطبيق بعد فشل البقية. */
    private val preferredEngines = listOf(
        "org.nobody.multitts",
        "com.reecenetworks.espeak",
        "com.svox.pico",
        "com.huawei.tts",
        "com.samsung.SMT",
        "com.google.android.tts"
    )

    /** قارئات الشاشة التي تُستثنى من الاختيار التلقائي: لا تُنتج صوتاً عبر
     *  TextToSpeech.synthesize القياسي فتجعل المستخدم بلا صوت. تبقى ظاهرة
     *  في واجهة المحركات للاختيار اليدوي الصريح. Talkman/Jieshuo قارئ
     *  ومحرك معاً — يُستثنى من المساهمة باللغات المكتشفة ويبقى قابلاً
     *  للاختيار اليدوي كأي قارئ آخر. */
    private val screenReaderPackages = setOf(
        "com.google.android.marvin.talkback",
        "com.samsung.accessibility",
        "com.nirenr.talkman"
    )

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return packageName in screenReaderPackages
    }

    /**
     * يختار المحرك المفضّل من قائمة الحزم المثبتة وفق ترتيب
     * [preferredEngines]، ثم أي محرك مثبّت ليس قارئ شاشة كمسار احتياطي
     * آمن (بدل العودة null). منطق نقي قابل للاختبار دون Context.
     */
    fun pickPreferredEngineFrom(installed: Collection<String>): String? {
        preferredEngines.forEach { pkg ->
            if (installed.contains(pkg)) return pkg
        }
        return installed.firstOrNull { !isScreenReader(it) }
    }

    /**
     * يختار محرك الاحتياط بعد فشل محرك أو أكثر: يستبعد **كل** المحركات
     * الفاشلة سابقاً (سجل [failedEngines]) ثم يعتمد على
     * [pickPreferredEngineFrom] على المتبقّي. بالاستبعاد التراكمي يُمنع
     * «تأرجح التراجع» (ping-pong) بين محركين فاشلين. منطق نقي.
     */
    fun pickFallbackEngineFrom(
        installed: Collection<String>,
        failedEngines: Set<String>
    ): String? {
        return pickPreferredEngineFrom(
            installed.filter { it !in failedEngines }
        )
    }

    /**
     * سلسلة المحركات القادرة على نطق لغةٍ معيّنة، مرتبةً ترتيباً
     * احتياطياً (أول عنصر = المحرك المعتمد إن نجح، فالباقي مسار تراجع):
     * 1) المحرك الذي اختاره المستخدم لهذه اللغة صراحةً
     *    [preferredForLanguage] إن كان مثبّتاً وقادراً — ويُشرّف حتى لو كان
     *    قارئ شاشة (اختيار صريح يُحترم)، ما لم يكن من الفاشلين
     *    [excludeFailed].
     * 2) ثم محركات النطق الحقيقية بالترتيب المفضَّل.
     * 3) ثم أي محرك حقيقي متبقٍّ مرتباً أبجدياً (استقرار كامل).
     * تُستبعد قارئات الشاشة من المسارات 2/3، وتُستبعد [excludeFailed]
     * من الكل ليمنع «تأرجح التراجع». الدالة نقية: المتصل يمرّر القادرين
     * للغة (`discovery[lang]`) فقط.
     */
    fun capableEnginesForLanguage(
        capable: Collection<String>,
        preferredForLanguage: String? = null,
        excludeFailed: Set<String> = emptySet()
    ): List<String> {
        val available = capable.filter { it !in excludeFailed }
        val order = LinkedHashSet<String>()
        preferredForLanguage?.takeIf { it in available }?.let { order.add(it) }
        preferredEngines.forEach { pkg ->
            if (pkg in available && !isScreenReader(pkg)) {
                order.add(pkg)
            }
        }
        available.sorted().forEach { pkg ->
            if (!isScreenReader(pkg)) {
                order.add(pkg)
            }
        }
        return order.toList()
    }
}