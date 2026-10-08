package com.aymankhattab.nateq.core.audio.enginehost;

import android.os.ParcelFileDescriptor;

/**
 * ع٢٩-5: عقد العملية المعزولة :engine_host.
 *
 * كل المكتبات الأصلية (Vocalizer / Acapela / Sherpa-ONNX) تُحمَّل
 * وتُستدعى هناك وحدها؛ أي انهيار أصلي (segfault / exit() / JNI
 * abort) يقتل :engine_host فقط ويبقى التطبيق وخدمة النطق حيّين.
 *
 * كل الدوال تعيد سلسلة نصية مرمَّزة عبر [com.aymankhattab
 * .nateq.core.audio.enginehost.HostProtocol] (OK / ERR ...) حتى لا
 * تعتمد الواجهة على JSON ضمن إطار اختبار واحد.
 */
interface IEmbeddedEngineHost {

    /**
     * تهيئة محرك ببياناته؛ علامة الانهيار تُكتب قبل النداء الأصلي.
     * [force] = تجاوز علامة «انهار في آخر تشغيل» (زر إعادة المحاولة).
     */
    String init(String engineId, String dataDir, boolean force);

    /** قائمة أصوات المحرك مرمَّزة (يحتاجها Vocalizer من المكتبة). */
    String listVoices(String engineId, String langTag);

    /**
     * توليد نصّ كامل إلى الأنبوب [pcmFd] (نهاية الكتابة للمضيف):
     * إطارات الصيغة ثم الـPCM ثم إطار النهاية بالحالة والرمز.
     * تعيد معرّف الطلب لـ[cancel] فوراً (النطق يجري خلفياً).
     */
    String synthesize(
        String engineId,
        String voiceId,
        String text,
        float rate,
        float pitch,
        float volume,
        in ParcelFileDescriptor pcmFd
    );

    /** إيقاف طلب جارٍ (يوقف المحرّك الأصلي عند أول فرصة). */
    void cancel(long requestId);

    /** إغلاق محرك وتحرير موارده في المضيف. */
    String shutdown(String engineId);
}
