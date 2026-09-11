package com.aymankhattab.nateq.core.audio.engine

/**
 * مسبح صفائف بايت قابل لإعادة الاستخدام (بند تسريع النطق). بيانات الصوت
 * أسرع مصادر تشكيل المصفوفات في مسار التخليق (تُقرأ للملف ثم تُعاد معاينتها
 * ثم تُبث)، وإعادة إنشائها في كل مقطع تُرهق المُجمّع وتُؤجج GC. نستعيد
 * الصفائف المستهلكة (بعد أن ينتهي المتلقي من قراءتها عبر وحدة البث) ونعيد
 * استخدامها للطلب التالي بدل إنشاء جديد.
 *
 * ## إعادة استخدام غير حرفية (مهم)
 * نقبل عند الاسترجاع أي صفيف حجمه **أكبر من أو يساوي** [minRetainedSize]
 * وليس المطابقة الحرفية فحسب. البيانات الصوتية متغيرة الحجم بين مقطع وآخر،
 * والمطابقة الحرفية كانت تُفشل إعادة الاستخدام فتُنشأ مصفوفة جديدة في كل
 * مرة — فيرتفع ضغط الـ GC. الطول الصالح يُمرَّر صراحةً من المتصل
 * (`validLength`/written)
 * `written`) فلا تُبثّ القمامة الزاوية وتُستهلك بيانات البيانات الفعلية فقط.
 */
class BytePool(
    private val minRetainedSize: Int = DEFAULT_MIN_SIZE,
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY
) {

    /** رامي/مستقبل أحادي — FIFO بسيط كافٍ للخيط الواحد. */
    private val available = ArrayDeque<ByteArray>()
    private val lock = Any()

    /**
     * يُرجع مخزّناً بحجم [targetSize] أو أكبر (لا إنشاء إن أمكن) للاستهلاك
     * المتغير. عند طلبٍ أصغر من حد الاحتفاظ يُنشأ مباشرة ولا يُخزَّن لاحقاً
     * (الصفائف الصغيرة أرخص في الإنشاء والنسخ).
     */
    fun acquire(targetSize: Int): ByteArray {
        if (targetSize < minRetainedSize) return ByteArray(targetSize)
        synchronized(lock) {
            var best: ByteArray? = null
            val it = available.iterator()
            while (it.hasNext()) {
                val candidate = it.next()
                if (candidate.size >= targetSize) {
                    // نفضّل الأقرب استهلاكاً للحجم لتقليل الهدر؛
                    // أي مرشح يغادر القائمة.
                    if (best == null || candidate.size < best.size) {
                        best = candidate
                    }
                }
            }
            if (best != null) {
                available.remove(best)
                return best
            }
        }
        return ByteArray(targetSize)
    }

    /**
     * يُخزّن صفيفاً لإعادة الاستخدام (يحتفظ به كما هو، وتُبثّ الأطوال الصالحة
     * صراحةً من المتصل). يُرفض الأصغر من حدّ الاحتفاظ ويكتمل عند بلوغ السعة.
     */
    fun release(array: ByteArray): Boolean {
        if (array.size < minRetainedSize) return false
        synchronized(lock) {
            if (available.size >= maxCapacity) return false
            available.addLast(array)
        }
        return true
    }

    /** يفرّغ كل الصفائف المخزّنة (يُستدعى عند الإغلاق النهائي للخدمة). */
    fun clear() {
        synchronized(lock) {
            available.clear()
        }
    }

    companion object {
        /** الحجم الأدنى المخزَّن في المسبح (بايت). */
        private const val DEFAULT_MIN_SIZE = 4096

        /**
         * أقصى سعة للعناصر. حدٌّ صغير يمنع تسرّب الذاكرة عندما تُترك النصوص
         * الطويلة صفائفَ ضخمة خاملةً في القائمة؛ السعة 12 تستوعب تداخل النطق
         * وتتابع الإعلانات الشائعة (ساعة/إشعار+رسائل) مع النطق اللغوي المتعدد
         * للإيموجي (كل إيموجي قد يتوزع على مقاطع متعددة فيُستخرج له مقطع خاص).
         */
        private const val DEFAULT_MAX_CAPACITY = 12
    }
}