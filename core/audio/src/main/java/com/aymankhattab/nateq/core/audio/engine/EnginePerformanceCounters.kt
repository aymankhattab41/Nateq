package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * عدادات دقيقة وموضوعية لقياس أداء كافة المحركات المدمجة
 * (Acapela، Vocalizer، Microsoft) ومنع تكرار مسح القرص أو إعادة
 * تهيئة المحرك في المسار الدافئ.
 */
object EnginePerformanceCounters {

    val acapela: AcapelaPerformanceCounters get() = AcapelaPerformanceCounters

    object vocalizer {
        val scans = AtomicLong(0L)
        val loadVoiceHits = AtomicLong(0L)
        val loadVoiceMisses = AtomicLong(0L)
        val engineInitCount = AtomicLong(0L)
        val serviceCloseCount = AtomicLong(0L)
        val queueWaitMs = AtomicLong(0L)

        fun reset() {
            scans.set(0L)
            loadVoiceHits.set(0L)
            loadVoiceMisses.set(0L)
            engineInitCount.set(0L)
            serviceCloseCount.set(0L)
            queueWaitMs.set(0L)
        }
    }

    object microsoft {
        val scans = AtomicLong(0L)
        val synthWarmHits = AtomicLong(0L)
        val synthWarmMisses = AtomicLong(0L)
        val licenseCacheHits = AtomicLong(0L)
        val licenseCacheMisses = AtomicLong(0L)
        val modelNameCacheHits = AtomicLong(0L)
        val modelNameCacheMisses = AtomicLong(0L)
        val engineInitCount = AtomicLong(0L)
        val serviceCloseCount = AtomicLong(0L)
        val queueWaitMs = AtomicLong(0L)

        fun reset() {
            scans.set(0L)
            synthWarmHits.set(0L)
            synthWarmMisses.set(0L)
            licenseCacheHits.set(0L)
            licenseCacheMisses.set(0L)
            modelNameCacheHits.set(0L)
            modelNameCacheMisses.set(0L)
            engineInitCount.set(0L)
            serviceCloseCount.set(0L)
            queueWaitMs.set(0L)
        }
    }

    fun resetAllForTest() {
        acapela.resetForTest()
        vocalizer.reset()
        microsoft.reset()
    }
}
