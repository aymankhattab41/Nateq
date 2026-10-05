package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * مقبضُ اختبارٍ يحصي التحرير ويلتقط الاستثناءات التي نطلبُها.
 *
 * **لماذا لا نختبر `Virtualizer`/`PresetReverb` الحقيقيين؟** لأنهما
 * استدعاءات JNI لا تعمل على JVM، و«اختبارٌ يمرّ لأنّ الاستثناء ابتلعه
 * `runCatching`» لا يحرس شيئاً. فالمقبضُ يحاكي العقد: يُحصي `release()`
 * ويستطيع أن يرمي في `disable()` أو `release()`.
 */
private class FakeEffect(
    private val throwOnDisable: Boolean = false,
    private val throwOnRelease: Boolean = false
) : ReleasableEffect {

    var disableCalls = 0
        private set
    var releaseCalls = 0
        private set

    override fun disable() {
        disableCalls++
        if (throwOnDisable) throw IllegalStateException("disable failed")
    }

    override fun release() {
        releaseCalls++
        if (throwOnRelease) throw IllegalStateException("release failed")
    }
}

/** مصنعُ مؤثرات للاختبار: يُنشئ [FakeEffect] ويحصي مرات الإنشاء. */
private class EffectFactoryStub(
    private val effect: () -> ReleasableEffect
) {
    val created = AtomicInteger(0)

    fun create(sessionId: Int, value: Short): ReleasableEffect? {
        created.incrementAndGet()
        return effect()
    }
}

/**
 * مديرٌ بمصاريد اختبار: أنواعٌ مُحاكاة ومعرّفا نوعين مُحاكيان ومصانعُ
 * تُحصي الإنشاء.
 */
private fun manager(
    types: Set<UUID>,
    virtualizer: EffectFactoryStub? = null,
    reverb: EffectFactoryStub? = null
): AudioEffectManager = AudioEffectManager(
    typesProvider = { types },
    virtualizerTypeId = VIRTUALIZER_TYPE_ID,
    reverbTypeId = REVERB_TYPE_ID,
    virtualizerFactory = { id, value -> virtualizer?.create(id, value) },
    reverbFactory = { id, value -> reverb?.create(id, value) }
)

/**
 * معرّفاتُ المؤثرات نُسخةٌ محليّة من قيم `AudioEffect.EFFECT_TYPE_*`.
 *
 * **لماذا لا نقرأ الثوابت مباشرةً؟** لأنها كائنات `UUID` تُهيَّأ في
 * `<clinit>` لأndroid، فهي `null` على JVM العادي فتصير المقارنات صامتةً
 * خاطئة. وحقل `Descriptor.type` من نوع `UUID` أيضاً، فمقارنةُ النوع
 * خطأٌ مانعٌ للترجمة لا يقبله الـ compiler — وهذا بالضبط ما يحرس
 * الخطأَ الذي وقعنا فيه (مقارنةُ UUID بنص).
 */
private val VIRTUALIZER_TYPE_ID =
    UUID.fromString("1d403060-9a5d-11e0-bd6d-0002a5d5c51b")
private val REVERB_TYPE_ID =
    UUID.fromString("47382d60-ddd8-11db-bf3a-0002a5d5c51b")

class AudioEffectManagerTest {

    private val both = setOf(VIRTUALIZER_TYPE_ID, REVERB_TYPE_ID)

    // ===== V1: التوفّر =====

    @Test
    fun availabilityReportsOnlySupportedEffects() {
        val target = manager(setOf(VIRTUALIZER_TYPE_ID))

        assertTrue(target.isVirtualizerAvailable())
        assertFalse(target.isReverbAvailable())
        assertFalse(target.availability().none)
    }

    @Test
    fun availabilityIsNoneWhenDeviceReportsNothing() {
        val target = manager(emptySet())

        assertTrue(target.availability().none)
    }

    @Test
    fun attachSkipsUnsupportedEffectEvenWhenEnabled() {
        val reverb = EffectFactoryStub { FakeEffect() }
        val target = manager(setOf(VIRTUALIZER_TYPE_ID), reverb = reverb)
        target.setReverbEnabled(true)

        target.attach(7, AudioExpansionLevels.MEDIUM)

        assertEquals(0, reverb.created.get())
        assertFalse(target.isAttached(7))
    }

    // ===== V2: فصل الصدى =====

    @Test
    fun reverbIsOffByDefaultAndVirtualizerAloneAttaches() {
        val virtualizer = EffectFactoryStub { FakeEffect() }
        val reverb = EffectFactoryStub { FakeEffect() }
        val target = manager(both, virtualizer, reverb)

        assertFalse(target.isReverbEnabled())
        target.attach(1, AudioExpansionLevels.LIGHT)

        assertEquals(1, virtualizer.created.get())
        assertEquals(0, reverb.created.get())
        assertTrue(target.isAttached(1))
    }

    @Test
    fun reverbWorksIndependentlyWhileExpansionIsOff() {
        val reverb = EffectFactoryStub { FakeEffect() }
        val target = manager(both, reverb = reverb)
        target.setReverbEnabled(true)

        target.attach(2, AudioExpansionLevels.OFF)

        assertEquals(1, reverb.created.get())
        assertTrue(target.isAttached(2))
    }

    @Test
    fun expansionOffDetachesSessionWhenReverbAlsoOff() {
        val target = manager(both, EffectFactoryStub { FakeEffect() })

        target.attach(3, AudioExpansionLevels.LIGHT)
        target.attach(3, AudioExpansionLevels.OFF)

        assertFalse(target.isAttached(3))
    }

    // ===== V3: كل فئة =====

    @Test
    fun disabledCategoryDropsEffectsButGeneralKeepsThem() {
        val factory = EffectFactoryStub { FakeEffect() }
        val target = manager(both, factory)
        target.setDisabledCategories(
            setOf(SettingsRepository.VOICE_CATEGORY_TIME)
        )

        target.attach(
            4,
            AudioExpansionLevels.MEDIUM,
            SettingsRepository.VOICE_CATEGORY_TIME
        )
        assertFalse(target.isAttached(4))

        target.attach(
            5,
            AudioExpansionLevels.MEDIUM,
            AudioEffectManager.GENERAL_CATEGORY
        )
        assertTrue(target.isAttached(5))
    }

    @Test
    fun generalCategoryCanNeverBeDisabled() {
        val target = manager(both)
        target.setDisabledCategories(
            setOf(AudioEffectManager.GENERAL_CATEGORY)
        )

        assertTrue(
            target.isCategoryEffectsEnabled(AudioEffectManager.GENERAL_CATEGORY)
        )
    }

    @Test
    fun blankCategoryIsTreatedAsGeneral() {
        val target = manager(both)
        target.setDisabledCategories(setOf(""))

        assertTrue(target.isCategoryEffectsEnabled(""))
    }

    // ===== V4: تحريرٌ في كل المسارات + التسريب =====

    @Test
    fun releaseRunsEvenWhenDisableThrows() {
        val fake = FakeEffect(throwOnDisable = true)
        val target = manager(both, EffectFactoryStub { fake })

        target.attach(6, AudioExpansionLevels.LIGHT)
        target.detach(6)

        assertEquals(1, fake.disableCalls)
        assertEquals(1, fake.releaseCalls)
    }

    @Test
    fun releaseAllSurvivesEffectThrowingOnRelease() {
        val bad = FakeEffect(throwOnRelease = true)
        val good = FakeEffect()
        val target = AudioEffectManager(
            typesProvider = { both },
            virtualizerTypeId = VIRTUALIZER_TYPE_ID,
            reverbTypeId = REVERB_TYPE_ID,
            virtualizerFactory = { _, value ->
                if (value == AudioEffectManager.STRENGTH_MEDIUM) bad else good
            },
            reverbFactory = { _, _ -> null }
        )
        target.attach(8, AudioExpansionLevels.MEDIUM)
        target.attach(9, AudioExpansionLevels.LIGHT)

        target.releaseAll()

        assertEquals(0, target.activeSessionCount())
        assertEquals(1, bad.releaseCalls)
        assertEquals(1, good.releaseCalls)
    }

    @Test
    fun factoryExceptionLeavesNoSessionAndDoesNotThrow() {
        val target = AudioEffectManager(
            typesProvider = { both },
            virtualizerTypeId = VIRTUALIZER_TYPE_ID,
            reverbTypeId = REVERB_TYPE_ID,
            virtualizerFactory = { _, _ ->
                throw UnsupportedOperationException("no audio effect service")
            },
            reverbFactory = { _, _ -> null }
        )

        target.attach(10, AudioExpansionLevels.LIGHT)

        assertFalse(target.isAttached(10))
        assertEquals(0, target.activeSessionCount())
    }

    @Test
    fun twoHundredCyclesLeaveNoRetainedSessions() {
        val fake = FakeEffect()
        val target = AudioEffectManager(
            typesProvider = { both },
            virtualizerTypeId = VIRTUALIZER_TYPE_ID,
            reverbTypeId = REVERB_TYPE_ID,
            virtualizerFactory = { _, _ -> fake },
            reverbFactory = { _, _ -> FakeEffect() }
        )

        for (i in 1..200) {
            target.attach(i, AudioExpansionLevels.MEDIUM)
            target.detach(i)
        }

        assertEquals(0, target.activeSessionCount())
        assertEquals(200, fake.releaseCalls)
    }

    @Test
    fun reattachReleasesPreviousEffectInsteadOfLeakingIt() {
        val first = FakeEffect()
        val second = FakeEffect()
        var created = 0
        val target = AudioEffectManager(
            typesProvider = { both },
            virtualizerTypeId = VIRTUALIZER_TYPE_ID,
            reverbTypeId = REVERB_TYPE_ID,
            virtualizerFactory = { _, _ ->
                if (created++ == 0) first else second
            },
            reverbFactory = { _, _ -> null }
        )

        target.attach(11, AudioExpansionLevels.LIGHT)
        target.attach(11, AudioExpansionLevels.MEDIUM)

        assertEquals(1, first.releaseCalls)
        assertEquals(0, second.releaseCalls)
        assertEquals(1, target.activeSessionCount())
    }

    // ===== حراسةُ الواجهة القديمة =====

    @Test
    fun attachWithInvalidSessionIdIsNoOp() {
        val factory = EffectFactoryStub { FakeEffect() }
        val target = manager(both, factory)

        target.attach(0, AudioExpansionLevels.MEDIUM)
        target.attach(-1, AudioExpansionLevels.MEDIUM)

        assertFalse(target.isAttached(0))
        assertFalse(target.isAttached(-1))
        assertEquals(0, target.activeSessionCount())
    }

    @Test
    fun detachOnUnknownSessionIsNoOp() {
        val target = manager(both)

        target.detach(404)

        assertEquals(0, target.activeSessionCount())
    }
}
