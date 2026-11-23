package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Looper
import com.aymankhattab.nateq.core.audio.engine.AcapelaPerformanceCounters
import com.aymankhattab.nateq.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * مستودع موحد ولقطة غير قابلة للتعديل لأصوات Acapela واكتشافها والتحقق منها.
 *
 * الخصائص:
 * 1. لقطة موحدة آمنة على الخيط الرئيسي (getSnapshot) بلا أي قراءة قرص.
 * 2. عمليات القراءة والاكتشاف وحساب البصمات محصورة كلياً على
 *    [AppDispatchers.io].
 * 3. دمج الطلبات المتزامنة (coalescing) عبر Mutex لمنع ازدواج مسح القرص.
 * 4. ربط الذاكرة المؤقتة برقم مراجعة التثبيت ومزامنتها عبر العمليات
 *    (عبر ملف علامة المراجعة دون مسح شجري متكرر).
 * 5. إبطال صريح وشامل عند أي تعديل أو استيراد أو حذف للأصوات.
 */
object AcapelaVoiceRepository {

    data class AcapelaSnapshot(
        val revision: Long,
        val timestampMs: Long,
        val voices: List<AcapelaVoiceManifest.VoiceInfo>,
        val searchDirs: List<File>,
        val isConfigured: Boolean,
        val isLoaded: Boolean = revision >= 0L
    )

    private val EMPTY_SNAPSHOT = AcapelaSnapshot(
        revision = -1L,
        timestampMs = 0L,
        voices = emptyList(),
        searchDirs = emptyList(),
        isConfigured = false,
        isLoaded = false
    )

    @Volatile
    private var currentSnapshot: AcapelaSnapshot = EMPTY_SNAPSHOT

    private val refreshMutex = Mutex()
    private val localRevisionCounter = AtomicLong(1L)
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.io
    )

    /**
     * قراءة اللقطة الحالية المحفوظة في الذاكرة فوراً ودون مس القرص إطلاقاً.
     * آمنة 100% للاستدعاء من الخيط الرئيسي (Main Thread) ومكونات الواجهة.
     */
    fun getSnapshot(): AcapelaSnapshot = currentSnapshot

    /**
     * جدولة تحديث خلفي للقطة على [AppDispatchers.io] دون حظر الخيط المستدعي.
     */
    fun triggerBackgroundRefresh(context: Context) {
        if (currentSnapshot.isLoaded) return
        repositoryScope.launch {
            getOrRefreshSnapshot(context)
        }
    }

    /**
     * الحصول على لقطة حديثة أو تحديثها على [AppDispatchers.io] عند الحاجة.
     * يدمج الاستدعاءات المتزامنة بحيث يُنفَّذ المسح مرة واحدة فقط.
     */
    suspend fun getOrRefreshSnapshot(
        context: Context,
        forceRefresh: Boolean = false
    ): AcapelaSnapshot = withContext(AppDispatchers.io) {
        val targetRevision = getStorageRevision(context)
        val cached = currentSnapshot

        if (!forceRefresh &&
            cached.revision == targetRevision &&
            cached.isLoaded
        ) {
            return@withContext cached
        }

        refreshMutex.withLock {
            val innerRevision = getStorageRevision(context)
            val innerCached = currentSnapshot

            if (!forceRefresh &&
                innerCached.revision == innerRevision &&
                innerCached.isLoaded
            ) {
                return@withLock innerCached
            }

            AcapelaPerformanceCounters.recordScan("AcapelaVoiceRepository")
            val searchDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            val allVoices = mutableListOf<AcapelaVoiceManifest.VoiceInfo>()
            for (dir in searchDirs) {
                val voices = AcapelaVoiceManifest.cachedVoices(dir)
                allVoices.addAll(voices)
            }
            val distinctVoices = allVoices.distinctBy { it.id }

            val newSnapshot = AcapelaSnapshot(
                revision = innerRevision,
                timestampMs = System.currentTimeMillis(),
                voices = distinctVoices,
                searchDirs = searchDirs,
                isConfigured = distinctVoices.isNotEmpty(),
                isLoaded = true
            )
            currentSnapshot = newSnapshot
            newSnapshot
        }
    }

    /**
     * الحصول على قائمة الأصوات من اللقطة، وتحديثها خلفياً إذا لزم الأمر.
     */
    suspend fun getVoices(
        context: Context
    ): List<AcapelaVoiceManifest.VoiceInfo> {
        return getOrRefreshSnapshot(context).voices
    }

    /**
     * مراجعة التثبيت الحالية: تعتمد على أعلى قيمة بين:
     * 1. زمن آخر تعديل مسجل محلياً في [EmbeddedStorage.lastBumpTime].
     * 2. زمن تعديل ملف علامة المراجعة المشترك عبر العمليات.
     * 3. عداد المراجعات المحلي لضمان تصاعد القيمة عند الإبطال.
     *
     * يتم الفحص بـ stat فردي (O(1)) دون مسح شجري أو حساب SHA-256.
     */
    fun getStorageRevision(context: Context? = null): Long {
        val bumpTime = EmbeddedStorage.lastBumpTime
        val localRev = localRevisionCounter.get()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return maxOf(bumpTime, localRev)
        }
        val markerMod = getRevisionMarkerTime(context)
        return maxOf(bumpTime, maxOf(markerMod, localRev))
    }

    private fun getRevisionMarkerFile(context: Context?): File? {
        val ctx = context ?: return null
        val base = runCatching {
            EmbeddedStorage.baseDir(ctx, EmbeddedStorage.Engine.ACAPELA)
        }.getOrNull() ?: return null
        return File(base, ".acapela_revision")
    }

    private fun getRevisionMarkerTime(context: Context?): Long {
        val marker = getRevisionMarkerFile(context) ?: return 0L
        return if (marker.exists()) marker.lastModified() else 0L
    }

    private fun updateRevisionMarker(context: Context?) {
        val marker = getRevisionMarkerFile(context) ?: return
        runCatching {
            val parent = marker.parentFile
            if (parent != null && parent.exists()) {
                if (!marker.exists()) {
                    marker.createNewFile()
                } else {
                    marker.setLastModified(System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * إبطال شامل لكافة كاشات Acapela وتحديث علامة المراجعة.
     */
    fun invalidate(context: Context? = null, reason: String = "explicit") {
        AcapelaPerformanceCounters.recordInvalidation(reason)
        val nextRev = maxOf(
            localRevisionCounter.get() + 1L,
            System.currentTimeMillis()
        )
        localRevisionCounter.set(nextRev)
        updateRevisionMarker(context)
        currentSnapshot = EMPTY_SNAPSHOT
        AcapelaPackageResolver.invalidateCache()
        AcapelaVoiceManifest.invalidateVoicesCache()
        AcapelaVoiceDiagnostics.invalidateVerificationCache()
        AcapelaVoiceManifest.clearDiagnosticLogs()
    }

    /**
     * تصفير كامل للاختبارات فقط.
     */
    internal fun resetForTests() {
        currentSnapshot = EMPTY_SNAPSHOT
        localRevisionCounter.set(0L)
        AcapelaPackageResolver.invalidateCache()
        AcapelaVoiceManifest.invalidateVoicesCache()
        AcapelaVoiceDiagnostics.invalidateVerificationCache()
        AcapelaVoiceManifest.clearDiagnosticLogs()
    }
}
