@file:Suppress("DEPRECATION")

package com.aymankhattab.nateq.core.audio.engine

import android.media.audiofx.AudioEffect
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * مقبضُ مؤثرٍ واحد قابل للتحرير.
 *
 * **لماذا لا نستخدم [AudioEffect] مباشرةً؟** لفصل المدير عن أصناف النظام،
 * فيحقن الاختبارُ مقابضَ تُحصي مرات التحرير وتُلقي استثناءات — وهو ما
 * يجعل حارسَ التسريب (بند V4) قابلاً للإثبات بدل الاعتماد على افتراض
 * أن `release()` نُفّذ.
 */
internal interface ReleasableEffect {
    /** تعطيل المؤثر قبل التحرير (فصلُ `disable` عن `release`). */
    fun disable()

    /** تحرير الموارد الأصلية. */
    fun release()
}

/**
 * [بند V1] أنواعُ المؤثرات التي يدعمها هذا الجهاز فعلاً.
 *
 * @property virtualizer هل يوجد مؤثر اتساع مكاني (Virtualizer).
 * @property reverb هل يوجد مؤثر صدى (PresetReverb).
 */
data class AudioEffectAvailability(
    val virtualizer: Boolean,
    val reverb: Boolean
) {
    /** لا يدعم الجهاز أيَّ مؤثر — فتُخفى كل خيارات الواجهة. */
    val none: Boolean get() = !virtualizer && !reverb
}

/**
 * أنواعُ المؤثرات (بمعرّفات UUID) كما يبلّغ عنها النظام في استعلامٍ واحد.
 *
 * **انتبه:** `AudioEffect.EFFECT_TYPE_*` معرّفاتٌ من نوع `UUID` لا نصوص،
 * وحقل `Descriptor.type` من نوع `UUID` أيضاً — فالمقارنةُ بالنص تُفشِل
 * الاكتشافَ دائماً وتُخفي الخيارات على أجهزةٍ تدعمها فعلاً.
 */
private fun systemEffectTypes(): Set<UUID> =
    runCatching { AudioEffect.queryEffects() }
        .getOrNull()
        ?.mapNotNullTo(mutableSetOf()) { it.type }
        ?: emptySet()

/** يغلّف [AudioEffect] بفئةَّ [ReleasableEffect] الموحّدة. */
private class SystemEffect(private val effect: AudioEffect) :
    ReleasableEffect {
    override fun disable() {
        effect.enabled = false
    }

    override fun release() {
        effect.release()
    }
}

/** إنشاء Virtualizer وضبط قوّته (سطحُ النظام). */
private fun createVirtualizer(
    sessionId: Int,
    strength: Short
): ReleasableEffect? {
    val virt = Virtualizer(0, sessionId)
    if (virt.strengthSupported) {
        virt.setStrength(strength)
    }
    virt.enabled = true
    return SystemEffect(virt)
}

/** إنشاء PresetReverb وضبط رنته (سطحُ النظام). */
private fun createReverb(
    sessionId: Int,
    preset: Short
): ReleasableEffect? {
    val reverb = PresetReverb(0, sessionId)
    reverb.preset = preset
    reverb.enabled = true
    return SystemEffect(reverb)
}

/**
 * مديرُ مؤثرات الصوت المدمجة في أندرويد (Virtualizer + PresetReverb).
 *
 * يعمل على `AudioTrack.audioSessionId` عبر واجهات النظام الرسمية
 * `android.media.audiofx` بلا أي مكتبات DSP خارجية تثقل الـ APK، فيمنح
 * إحساساً باتساعٍ مكاني خفيف مريح في سماعات الأذن.
 *
 * **بند V1 — التوفّر:** لا يُنشأ مؤثرٌ إلا بعد أن يؤكّد
 * [AudioEffect.queryEffects] وجود نوعه على هذا الجهاز، وInterface تُقرأ
 * التوفّرُ مرّةً واحدة (لا يتغيّر أثناء عمر العملية).
 *
 * **بند V2 — فصل الصدى:** الاتساعُ (Virtualizer) يبقى مرتبطاً بالمستوى
 * [`AudioExpansionLevels`]، والصدى (PresetReverb) مفتاحٌ مستقلّ
 * **افتراضاً مُعطَّل** — فالتشغيلُ لا يفعّلهما معاً.
 *
 * **بند V3 — لكل فئة:** يمكن تعطيل التأثير في فئات الإعلانات
 * (الساعة/البطارية/المتصل/الرسائل/الإشعارات) مع بقائه لقراءة النص
 * الطويل (الفئة العامة) التي لا يمكن تعطيلها.
 *
 * **بند V4 — RESOURCES:** كلُّ تحريرٍ في مسارٍ مستقلّ عن التعطيل، فلا
 * يُمنع `release()` استثناءٌ في `disable()`، وكلُّ استثناءٍ من مصنع
 * المؤثرات يُسجَّل ولا يقطع النطق. وتضمن [activeSessionCount] حارسَ
 * التسريب في الاختبار.
 *
 * واجهة [Virtualizer] مُهجَّرة في جرة API 37 مع بقاء سلوكها الوظيفي بلا
 * بديل مباشر، فنُجتبي تحذيرَ التهجير باحتوائه واقعياً في هذا الملف.
 */
class AudioEffectManager internal constructor(
    private val typesProvider: () -> Set<UUID> = ::systemEffectTypes,
    private val virtualizerTypeId: UUID = AudioEffect.EFFECT_TYPE_VIRTUALIZER,
    private val reverbTypeId: UUID = AudioEffect.EFFECT_TYPE_PRESET_REVERB,
    private val virtualizerFactory: (Int, Short) -> ReleasableEffect? =
        ::createVirtualizer,
    private val reverbFactory: (Int, Short) -> ReleasableEffect? =
        ::createReverb
) {

    /**
     * المنشئُ العام — بمصاريد النظام الحقيقية. أما مخارجُ الاختبار
     * ([typesProvider] ومعرّفا النوعين والمصانع) فداخليةٌ لهذه الوحدة،
     * فلا تتسرّب أنواعٌ internal إلى واجهةٍ عامة.
     */
    constructor() : this(
        ::systemEffectTypes,
        AudioEffect.EFFECT_TYPE_VIRTUALIZER,
        AudioEffect.EFFECT_TYPE_PRESET_REVERB,
        ::createVirtualizer,
        ::createReverb
    )

    companion object {
        private const val TAG = "NATEQ_AUDIO_FX"

        /** فئةُ النطق العام (قراءةُ النص الطويل) — لا تعطيلَ خاصاً بها. */
        const val GENERAL_CATEGORY = "general"

        /** مفتاحُ تمرير فئة النطق في حزمة طلب محرك النطق. */
        const val PARAM_CATEGORY = "nateq_effect_category"

        /** قوة الاتساع للمستوى الخفيف (من أصل 1000). */
        const val STRENGTH_LIGHT: Short = 500

        /** قوة الاتساع للمستوى المتوسط (من أصل 1000). */
        const val STRENGTH_MEDIUM: Short = 900
    }

    private data class SessionEffects(
        val virtualizer: ReleasableEffect?,
        val reverb: ReleasableEffect?
    )

    private val sessions = ConcurrentHashMap<Int, SessionEffects>()

    /** [V1] استعلامٌ واحد فقط: التوفّرُ لا يتغيّر أثناء عمر العملية. */
    private val availability: AudioEffectAvailability by lazy {
        val types = runCatching { typesProvider() }
            .getOrElse { error ->
                Log.w(TAG, "audio effect query failed", error)
                emptyList()
            }
        AudioEffectAvailability(
            virtualizer = virtualizerTypeId in types,
            reverb = reverbTypeId in types
        )
    }

    /** [V2] الصدى مفتاحٌ مستقل، **افتراضياً مُعطَّل**. */
    @Volatile
    private var reverbEnabled = false

    /** [V3] فئاتُ الإعلانات التي عُطّل فيها التأثير. */
    private val disabledCategories =
        ConcurrentHashMap.newKeySet<String>()

    /** [V1] توفّر المؤثرات على هذا الجهاز. */
    fun availability(): AudioEffectAvailability = availability

    /** [V1] هل يدعم الجهاز اتساعاً مكانياً؟ */
    fun isVirtualizerAvailable(): Boolean = availability.virtualizer

    /** [V1] هل يدعم الجهاز صدى؟ */
    fun isReverbAvailable(): Boolean = availability.reverb

    /** [V2] ضبطُ تفعيل الصدى. */
    fun setReverbEnabled(enabled: Boolean) {
        reverbEnabled = enabled
    }

    /** [V2] حالةُ تفعيل الصدى. */
    fun isReverbEnabled(): Boolean = reverbEnabled

    /** [V3] استبدالُ قائمة الفئات المعطّلة دفعةً واحدة. */
    fun setDisabledCategories(categories: Set<String>) {
        disabledCategories.clear()
        disabledCategories.addAll(categories)
    }

    /** [V3] الفئةُ العامة والفئةُ الفارغة مفعّلتان دائماً؛ وسائرُ الفئات
     *  مفعّلة ما لم تكن في [disabledCategories]. */
    fun isCategoryEffectsEnabled(category: String): Boolean {
        if (category.isBlank() || category == GENERAL_CATEGORY) return true
        return category !in disabledCategories
    }

    /**
     * ربطُ المؤثرات على جلسة صوت.
     *
     * @param audioSessionId معرّفُ جلسة `AudioTrack`.
     * @param expansionLevel مستوى الاتساع — يتحكّم بـ Virtualizer وحده.
     * @param category فئةُ النطق — الفئةُ المعطّلة تُسقط المؤثرين معاً.
     */
    fun attach(
        audioSessionId: Int,
        expansionLevel: Int,
        category: String = GENERAL_CATEGORY
    ) {
        if (audioSessionId <= 0) {
            detach(audioSessionId)
            return
        }

        val categoryOn = isCategoryEffectsEnabled(category)
        val wantVirtualizer = categoryOn &&
            expansionLevel > AudioExpansionLevels.OFF &&
            availability.virtualizer
        val wantReverb = categoryOn &&
            reverbEnabled &&
            availability.reverb

        if (!wantVirtualizer && !wantReverb) {
            detach(audioSessionId)
            return
        }

        // تفريغ أي مؤثرات قديمة للجلسة قبل إنشاء الجديدة
        detach(audioSessionId)

        val strength =
            if (expansionLevel == AudioExpansionLevels.LIGHT) {
                STRENGTH_LIGHT
            } else {
                STRENGTH_MEDIUM
            }
        val preset =
            if (expansionLevel == AudioExpansionLevels.LIGHT) {
                PresetReverb.PRESET_SMALLROOM
            } else {
                PresetReverb.PRESET_MEDIUMROOM
            }

        // [V4] استثناءُ المصنع يُسجَّل ولا يقطع النطق: يعود الصوتُ بلا
        // مؤثرٍ كما لو لم يكن مدعوماً.
        val virt = if (wantVirtualizer) {
            runCatching { virtualizerFactory(audioSessionId, strength) }
                .onFailure { Log.w(TAG, "Virtualizer failed", it) }
                .getOrNull()
        } else {
            null
        }
        val rev = if (wantReverb) {
            runCatching { reverbFactory(audioSessionId, preset) }
                .onFailure { Log.w(TAG, "PresetReverb failed", it) }
                .getOrNull()
        } else {
            null
        }

        if (virt == null && rev == null) return
        sessions[audioSessionId] = SessionEffects(virt, rev)
    }

    /**
     * إيقافُ المؤثرات وتحريرُها لجلسة — [V4]: التعطيلُ والتحريرُ في
     * مسارين مستقلّين، فاستثناءُ `disable` لا يُفوّت `release`.
     */
    fun detach(audioSessionId: Int) {
        val effects = sessions.remove(audioSessionId) ?: return
        releaseQuietly(effects.virtualizer)
        releaseQuietly(effects.reverb)
    }

    /** تحريرُ كل المؤثرات النشطة (تدمير الخدمة أو إعادة التشغيل). */
    fun releaseAll() {
        for (sessionId in sessions.keys().toList()) {
            runCatching { detach(sessionId) }
        }
    }

    /** هل المؤثراتُ نشطةٌ لجلسةٍ ما؟ */
    fun isAttached(audioSessionId: Int): Boolean =
        sessions.containsKey(audioSessionId)

    /** عددُ الجلسات المحتجزة — حارسُ التسريب في الاختبار (بند V4). */
    internal fun activeSessionCount(): Int = sessions.size

    private fun releaseQuietly(effect: ReleasableEffect?) {
        if (effect == null) return
        runCatching { effect.disable() }
        runCatching { effect.release() }
    }
}
