package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * عدادات دقيقة وموضوعية لقياس أداء Acapela ومنع تكرار مسح القرص أو إعادة
 * تهيئة المحرك في المسار الدافئ.
 *
 * البنود المقاسة:
 *  - [scans]: عدد عمليات مسح المجلدات على القرص.
 *  - [configReads]: عدد مرات قراءة ملفات التهيئة (YAML / .inf / .ini).
 *  - [hashBytes]: حجم البايتات المحسوب لها بصمة تجزئة SHA-256.
 *  - [verificationCacheHits]: عدد مرات استرجاع نتيجة التحقق من الكاش.
 *  - [verificationCacheMisses]: عدد مرات حساب التحقق من القرص.
 *  - [engineInitCount]: عدد مرات استدعاء تهيئة المحرك الأصلي (nInit).
 *  - [serviceCloseCount]: عدد مرات إغلاق أو تحرير الخدمة.
 *  - [queueWaitMs]: مجموع زمن انتظار الأقفال والطوابير بالمللي ثانية.
 */
object AcapelaPerformanceCounters {

    val scans = AtomicLong(0L)
    val configReads = AtomicLong(0L)
    val hashBytes = AtomicLong(0L)
    val verificationCacheHits = AtomicLong(0L)
    val verificationCacheMisses = AtomicLong(0L)
    val engineInitCount = AtomicLong(0L)
    val serviceCloseCount = AtomicLong(0L)
    val queueWaitMs = AtomicLong(0L)

    private val _invalidationReasons = CopyOnWriteArrayList<String>()
    val invalidationReasons: List<String> get() = _invalidationReasons

    fun recordScan(caller: String = "") {
        scans.incrementAndGet()
    }

    fun recordConfigRead(caller: String = "") {
        configReads.incrementAndGet()
    }

    fun recordHashBytes(bytes: Long) {
        if (bytes > 0L) {
            hashBytes.addAndGet(bytes)
        }
    }

    fun recordVerificationHit() {
        verificationCacheHits.incrementAndGet()
    }

    fun recordVerificationMiss() {
        verificationCacheMisses.incrementAndGet()
    }

    fun recordEngineInit() {
        engineInitCount.incrementAndGet()
    }

    fun recordServiceClose() {
        serviceCloseCount.incrementAndGet()
    }

    fun recordQueueWait(ms: Long) {
        if (ms > 0L) {
            queueWaitMs.addAndGet(ms)
        }
    }

    fun recordInvalidation(reason: String) {
        val entry = "${System.currentTimeMillis()}: $reason"
        _invalidationReasons.add(entry)
    }

    data class Snapshot(
        val scans: Long,
        val configReads: Long,
        val hashBytes: Long,
        val verificationCacheHits: Long,
        val verificationCacheMisses: Long,
        val engineInitCount: Long,
        val serviceCloseCount: Long,
        val queueWaitMs: Long,
        val invalidationCount: Int
    )

    fun snapshot(): Snapshot = Snapshot(
        scans = scans.get(),
        configReads = configReads.get(),
        hashBytes = hashBytes.get(),
        verificationCacheHits = verificationCacheHits.get(),
        verificationCacheMisses = verificationCacheMisses.get(),
        engineInitCount = engineInitCount.get(),
        serviceCloseCount = serviceCloseCount.get(),
        queueWaitMs = queueWaitMs.get(),
        invalidationCount = _invalidationReasons.size
    )

    fun resetForTest() {
        scans.set(0L)
        configReads.set(0L)
        hashBytes.set(0L)
        verificationCacheHits.set(0L)
        verificationCacheMisses.set(0L)
        engineInitCount.set(0L)
        serviceCloseCount.set(0L)
        queueWaitMs.set(0L)
        _invalidationReasons.clear()
    }
}
