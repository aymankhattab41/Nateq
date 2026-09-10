package com.aymankhattab.nateq.core.audio.announcement

import java.util.ArrayDeque

/**
 * بوابة تهيئة المتحدث المشترك لمحرك TTS — تحسمُ بذرّيةٍ تامةٍ مَن يبدأ
 * التهيئة ومَن ينتظر دورته، حتى عبر عدّة محركات متنافسة.
 *
 * سجل التدقيق [5]: قرار القفل المزدوج القديم كان يحسم مصير الطلب لحظةَ
 * وصوله (جاهز/بادئ/منضمّ) دون أخذ محرك التهيئة الجارية في الحسبان،
 * فينضمّ طلبُ محركٍ مختلف إلى تهيئةٍ قائمةٍ لمحركٍ آخر ويُنطق النص
 * بالمحرك الخطأ. البوابة تخزّن «المحرك الجاري» وتصفّي النداءات عند
 * الاكتمال حسب المطابقة، ثم تُسلسل إعادة تهيئةٍ للمحركين المتبقية.
 *
 * وحدة منطقية نقية (لا أندرويد) قابلة للاختبار الآلي.
 */
internal class InitGate {

    /** قرار طلب [enqueue].
     *  - [START]: المتصل يبدأ تهيئةً جديدة بهذا المحرك.
     *  - [JOIN]: الطلب ضمن الطابور — يُصرف عند اكتمال تهيئةٍ مطابقة أو
     *    ينتظر إعادة تهيئةٍ تُسلسل لمحركه بعد لمحركات أسبق منافسة. */
    enum class Decision { START, JOIN }

    private class Entry(
        val engine: String?,
        val callback: (Boolean) -> Unit
    )

    /** حصيلة [complete]: النداءات الجاهزة للصرف فوراً + المحرك المتبقي
     *  الذي يجب أن يبدأ المتصل إعادة تهيئته (رأس الطابور غير المطابق). */
    class Completion(
        val served: List<(Boolean) -> Unit>,
        val nextEngine: String? = null
    )

    private val queue = ArrayDeque<Entry>()
    private var runningEngine: String? = null

    /**
     * طلب نطقٍ عبر محرك [engine]. يعيد START للمتصل إن كان هو من يبدأ
     * التهيئة الحالية (لا تهيئةٌ جارية)، أو JOIN إن انضمّ لطابورٍ قائم.
     * استدعاءات النداء تتم لاحقاً خارج البوابة عبر [complete].
     */
    @Synchronized
    fun enqueue(engine: String?, callback: (Boolean) -> Unit): Decision {
        queue.addLast(Entry(engine, callback))
        if (runningEngine == null) {
            runningEngine = engine
            return Decision.START
        }
        return Decision.JOIN
    }

    /**
     * اكتملت تهيئة المحرك الجاري.
     *
     * عند النجاح: تُصفّى النداءات المطابقة تماماً لمحرك التهيئة الذي
     * انتهت للتوّ؛ ما تبقى ضمن الطابور (محركٌ مختلف) يُحافظ على ترتيبه
     * ويُعاد [nextEngine] ليبدأ المتصل له تهيئةً جديدة.
     *
     * عند الفشل: كل الطابور يُصفّى بنداء failure (لا إعادة استخدامٍ
     * لتهيئةٍ مكسورة) وتَخري البوابة إلى حالة الراحة.
     */
    @Synchronized
    fun complete(success: Boolean): Completion {
        val served = ArrayList<(Boolean) -> Unit>()
        if (!success) {
            while (queue.isNotEmpty()) {
                served.add(queue.removeFirst().callback)
            }
            runningEngine = null
            return Completion(served)
        }
        val completed = runningEngine
        val pending = ArrayList<Entry>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (entry.engine == completed) {
                served.add(entry.callback)
            } else {
                pending.add(entry)
            }
        }
        for (entry in pending) {
            queue.addLast(entry)
        }
        val next = queue.peekFirst()
        runningEngine = next?.engine
        return Completion(served, next?.engine)
    }
}