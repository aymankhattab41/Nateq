package com.aymankhattab.nateq.core.audio.providers

/**
 * سجلّ محركات TTS: مركز القرار النقي لاختيار المحرك الأفضل المثبّت،
 * والاحتياط بعد الفشل، وسلسلة المحركات القادرة على نطق لغةٍ معيّنة.
 *
 * كل دوال هذا الكائن نقية (بلا Context) وقابلة للاختبار الآلي مباشرةً؛
 * الاستعلامات المعتمدة على النظام (المثبّتة في الجهاز) تبقى في
 * [EnginePicker] الذي يفوض قرارات الاختيار إلى هنا ليتّحد منطق القرار
 * في مكان واحد عبر التطبيق كله (المتحدث العام ومديري الإعلانات).
 */
object EngineRegistry {

    /** محركات النطق الحقيقية التي نمنحها الأولوية عند اختيار تلقائي، لأنها
     *  مضمونةً تُنتج صوتاً قياسياً (على عكس قارئات الشاشة). الترتيب يفضّل
     *  MultiTTS (صوت قياسي مرن) ثم محرك النظام الرسمي
     *  (جوجل فسامسونج فـ AOSP). */
    private val preferredEngines = listOf(
        "org.nobody.multitts",
        "com.google.android.tts",
        "com.samsung.SMT",
        "com.svox.pico"
    )

    /** قارئات الشاشة التي تُستثنى من الاختيار التلقائي: لا تُنتج صوتاً عبر
     *  TextToSpeech.synthesize القياسي فتجعل المستخدم بلا صوت. تبقى ظاهرة
     *  في واجهة المحركات للاختيار اليدوي الصريح (بعض المستخدمين يفضّلها).
     *  Talkman/Jieshuo (com.nirenr.talkman) قارئ ومحرك معاً: يسجّل نفسه
     *  TTS عبر eSpeak ويردّ بـ getVoices لغاتٍ نظرية (af/am/…) بلا بيانات
     *  مثبتة فعلياً على الجهاز — يستثنى من المساهمة باللغات المكتشفة ويبقى
     *  قابلاً للاختيار اليدوي كأي قارئ آخر. */
    private val screenReaderPackages = setOf(
        "com.google.android.marvin.talkback",   // TalkBack جوجل
        "com.samsung.accessibility",            // TalkBack سامسونج
        // Jieshuo/Talkman (قارئ + محرك eSpeak)
        "com.nirenr.talkman"
    )

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return packageName in screenReaderPackages
    }

    /**
     * يختار المحرك المفضّل من قائمة الحزم المثبتة وفق ترتيب [preferredEngines]،
     * ثم أي محرك مثبّت ليس قارئ شاشة كمسار احتياطي آمن (بدل العودة null).
     * منطق نقي قابل للاختبار دون Context.
     */
    fun pickPreferredEngineFrom(installed: Collection<String>): String? {
        preferredEngines.forEach { pkg ->
            if (installed.contains(pkg)) return pkg
        }
        // المسار الاحتياطي: أي محرك حقيقي (غير قارئ شاشة) بدل null.
        return installed.firstOrNull { !isScreenReader(it) }
    }

    /**
     * يختار محرك الاحتياط بعد فشل محرك أو أكثر في النطق: يستبعد **كل** المحركات
     * الفاشلة سابقاً (سجل [failedEngines]) من القائمة ثم يعتمد على
     * [pickPreferredEngineFrom] على المتبقّي — فيُفضَّل جوجل (وإن لم يوجد، أي
     * محرك حقيقي آخر بالترتيب: MultiTTS/سامسونج/…). يدعم الأسواق التي لا تصلها
     * خدمة جوجل (الصين مثلاً). منطق نقي قابل للاختبار دون Context.
     *
     * بالاستبعاد التراكمي يُمنع «تأرجح التراجع» (ping-pong): لو فشل المحركان
     * A ثم B معاً فلن يُعاد A (المفضّل الأول) لأن الاثنين مستبعدان من القائمة
     * — فكان الاستبعادُ السابق للمحرك الأخير الفاشل فقط يُعيد الأعلى أولويةً
     * وتترنّح المحاولة بين المحركين حتى استنفاد الذاكرة.
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
     * سلسلة المحركات القادرة على نطق لغةٍ معيّنة، مرتبةً ترتيباً احتياطياً
     * (أول عنصر = المحرك المعتمد إن نجح، فالباقي مسارُ تراجعٍ طبيعي):
     * 1) المحرك الذي اختاره المستخدم لهذه اللغة صراحةً [preferredForLanguage]
     *    إن كان مثبّتاً وقادراً — ويُشرف حتى لو كان قارئ شاشة (اختيار صريح
     *    يُحترم)، ما لم يكن من المحركات الفاشلة [excludeFailed].
     * 2) ثم محركات النطق الحقيقية بالترتيب المفضَّل.
     * 3) ثم أي محرك حقيقي متبقٍّ مرتباً أبجدياً (استقرار كامل).
     * تُستبعد قارئات الشاشة من المسارات 2/3 فلا يُعتمد عليها تلقائياً،
     * وتُستبعد [excludeFailed] من كل المسارات ليمنع «تأرجح التراجع».
     * الدالة نقية: المتصل يمرّر القادرين للغة (`discovery[lang]`) فقط.
     */
    fun capableEnginesForLanguage(
        capable: Collection<String>,
        preferredForLanguage: String? = null,
        excludeFailed: Set<String> = emptySet()
    ): List<String> {
        val available = capable.filter { it !in excludeFailed }
        val order = LinkedHashSet<String>()
        preferredForLanguage
            ?.takeIf { it in available }
            ?.let { order.add(it) }
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