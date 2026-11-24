package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Looper
import com.aymankhattab.nateq.core.audio.engine.EnginePerformanceCounters
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
 * مستودع موحد ولقطة غير قابلة للتعديل لأصوات Vocalizer المدمجة.
 *
 * الخصائص:
 * 1. لقطة موحدة آمنة على الخيط الرئيسي (getSnapshot) بلا أي قراءة قرص.
 * 2. عمليات المسح والاكتشاف محصورة كلياً على [AppDispatchers.io].
 * 3. دمج الطلبات المتزامنة (coalescing) عبر Mutex لمنع ازدواج مسح القرص.
 * 4. إدارة مراجعة التثبيت والإبطال الصريح عند تنزيل أو إزالة الأصوات.
 */
object VocalizerVoiceRepository {

    data class VocalizerSnapshot(
        val revision: Long,
        val timestampMs: Long,
        val voiceDirs: List<File>,
        val isConfigured: Boolean,
        val isLoaded: Boolean = revision >= 0L
    )

    private val EMPTY_SNAPSHOT = VocalizerSnapshot(
        revision = -1L,
        timestampMs = 0L,
        voiceDirs = emptyList(),
        isConfigured = false,
        isLoaded = false
    )

    @Volatile
    private var currentSnapshot: VocalizerSnapshot = EMPTY_SNAPSHOT

    private val refreshMutex = Mutex()
    private val localRevisionCounter = AtomicLong(1L)
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.io
    )

    /**
     * قراءة اللقطة الحالية المحفوظة في الذاكرة فوراً ودون مس القرص إطلاقاً.
     * آمنة 100% للاستدعاء من الخيط الرئيسي (Main Thread) ومكونات الواجهة.
     */
    fun getSnapshot(): VocalizerSnapshot = currentSnapshot

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
    ): VocalizerSnapshot = withContext(AppDispatchers.io) {
        val targetRevision = getStorageRevision()
        val cached = currentSnapshot

        if (!forceRefresh &&
            cached.revision == targetRevision &&
            cached.isLoaded
        ) {
            return@withContext cached
        }

        refreshMutex.withLock {
            val innerRevision = getStorageRevision()
            val innerCached = currentSnapshot

            if (!forceRefresh &&
                innerCached.revision == innerRevision &&
                innerCached.isLoaded
            ) {
                return@withLock innerCached
            }

            EnginePerformanceCounters.vocalizer.scans.incrementAndGet()
            val searchDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            )
            val completeDirs = mutableListOf<File>()
            for (dir in searchDirs) {
                if (VocalizerVoiceManifest.hasCompleteVoice(dir)) {
                    completeDirs.add(dir)
                }
            }
            val distinctDirs = completeDirs.distinctBy { it.absolutePath }

            val newSnapshot = VocalizerSnapshot(
                revision = innerRevision,
                timestampMs = System.currentTimeMillis(),
                voiceDirs = distinctDirs,
                isConfigured = distinctDirs.isNotEmpty(),
                isLoaded = true
            )
            currentSnapshot = newSnapshot
            newSnapshot
        }
    }

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
            EmbeddedStorage.baseDir(ctx, EmbeddedStorage.Engine.VOCALIZER)
        }.getOrNull() ?: return null
        return File(base, ".vocalizer_revision")
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

    fun invalidate(context: Context? = null, reason: String = "") {
        localRevisionCounter.incrementAndGet()
        updateRevisionMarker(context)
        currentSnapshot = EMPTY_SNAPSHOT
    }

    fun resetForTests() {
        localRevisionCounter.set(1L)
        currentSnapshot = EMPTY_SNAPSHOT
    }

    fun updateFromInventory(isConfigured: Boolean) {
        currentSnapshot = VocalizerSnapshot(
            revision = getStorageRevision(),
            timestampMs = System.currentTimeMillis(),
            voiceDirs = emptyList(),
            isConfigured = isConfigured,
            isLoaded = true
        )
    }
}
