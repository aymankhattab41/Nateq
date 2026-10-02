package com.aymankhattab.nateq.core.audio.announcement

/**
 * حسابُ مستوى الرنين بعد الخفض — خالصٌ بلا نظام صوتٍ فيُختبر وحده.
 *
 * منفصلٌ عن [AnnouncementSpeaker] عمداً: الحسابُ قرارٌ (كم يبقى من
 * الرنّة؟)، وتنفيذُه `AudioManager` عملٌ جانبيٌّ لا يُختبر على JVM.
 */
internal object RingtoneDuckMath {

/**
     * المستوى الجديد = نسبة [percent] من [current]، مقرّبةً **للأسفل**
     * وبحدٍّ أدنى مستقرّ، ولا تتجاوز الأصل أبداً.
     *
     * **القرضُ للأهل لا للأعلى** هو العقد: شريطٌ مكتوب فيه «40%» يعني
     * لا تُخفض أكثر من 40% من مستوى الرنين. فالتقريبُ للأعلى يجعل
     * الخفضَ 43% بلا أن يُقال للمستخدم (7 عند 40% = 2.8 ⇒ 3 لا 2)،
     * وهو عكسُ ما يَعِد به الشريط. والأدنى 1 لأن 0 يُطفئ الرنين فيبدو
     * الهاتفُ صامتاً لحظةَ إعلانه.
     */
    fun duckedLevel(current: Int, max: Int, percent: Int): Int {
        if (max <= 0 || current <= 0) return 0
        val ratio = percent.coerceIn(0, 100) / 100.0
        val target = Math.floor(current * ratio).toInt().coerceIn(1, max)
        return target.coerceAtMost(current)
    }
}