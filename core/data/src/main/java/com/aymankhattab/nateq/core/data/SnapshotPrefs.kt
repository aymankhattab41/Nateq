package com.aymankhattab.nateq.core.data

import android.content.SharedPreferences

/**
 * غلاف [SharedPreferences] يقرأ كل مفاتيحه من لقطة ذاكرة قابلة للاستبدال،
 * ويعيد توجيه الكتابة إلى المخزن الحقيقي على القرص، وينبّه [onChanged]
 * بعد كل التزام.
 *
 * تخدم هذه الطبقة المرحلة 6 (تحديث الإعدادات بين العمليتين بلا كتابةٍ
 * جرّاء القراءة): [SettingsRepository.reload] يستبدل اللقطة التي تُقرأ
 * منها القيم من ملخص قرصِ العملية الأخرى دون لمس المخزن — فلم يعد
 * كل تحديثٍ يعيد كتابة الملف ويرسل الاهتزاز للأطراف (ونقض السباق بين
 * العملية الرئيسية و :tts).
 *
 * [onChanged] يُستدعى بعد كل [apply]/[commit] ناجح ليتولى المتصل إبلاغ
 * العملية الأخرى عبر [SettingsChangeProvider] بلا علاقةٍ هنا بآلية البث.
 */
internal class SnapshotPrefs(
    @Volatile private var delegate: SharedPreferences,
    private val context: android.content.Context? = null,
    private val onChanged: () -> Unit = {},
    private val stampProvider: () -> Pair<Long, Long> = { 0L to 0L }
) : SharedPreferences {

    companion object {
        private val writeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "nateq-prefs-writer").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }

        fun awaitPendingWritesForTesting(timeoutMs: Long = 5000) {
            val latch = java.util.concurrent.CountDownLatch(1)
            writeExecutor.execute { latch.countDown() }
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /**
     * رقم المراجعة المونوتوني للقطة الحالية.
     */
    @Volatile
    private var revision: Long = 0L

    fun getRevision(): Long = revision

    /**
     * كتابةٌ محلية أُطلقت بـ[apply] ولم يُعرف بعد أنها بلغت القرص، مع بصمةٍ
     * للملف لحظة الكتابة. تكشف [replaceSnapshot] بها أن قراءة القرص أقدم من
     * ذاكرة العملية فلا تُسقط تعديلاً لم يُفرَّغ بعد.
     */
    private data class PendingLocalWrite(
        val value: Any?,
        val stamp: Pair<Long, Long>
    )

    private val pendingLocalWrites =
        java.util.concurrent.ConcurrentHashMap<String, PendingLocalWrite>()

    @Volatile
    private var snapshot: Map<String, Any?> = delegate.all

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = delegate.all
        }

    init {
        delegate.registerOnSharedPreferenceChangeListener(
            preferenceChangeListener
        )
    }

    /** يستبدل اللقطة التي تُقرأ منها القيم دون أي مساس بالقرص — جوهر
     *  الرحلة النظيفة في [SettingsRepository.reload].
     *
     * كتابةُ [apply] المحلية تظهر في ذاكرة العملية فوراً وتُفرَّغ على القرص
     * لاحقاً؛ فإن جاءت قراءةُ القرص (من إشعار المراقب) قبل التفريغ حملت
     * حالةً أقدم، وكان استبدالُ اللقطة بها يمحو التعديلَ من القراءة التالية
     * (سباقٌ ظهر في تسجيل تطبيق محمي ثم محوه فوراً). لذلك تُحفظ الكتابات
     * المحلية المعلّقة ما دامت بصمة الملف لم تتغيّر عن لحظة كتابتها: فحين
     * تتغيّر البصمة صار القرص أحدث (تفريغٌ أو كتابةُ عمليةٍ أخرى) فيُؤخذ.
     */
    fun replaceSnapshot(
        values: Map<String, Any?>,
        newRevision: Long = 0L
    ) {
        if (newRevision > 0 && newRevision < revision) {
            return
        }
        if (newRevision > 0) {
            revision = newRevision
        }
        if (pendingLocalWrites.isEmpty()) {
            snapshot = values
            return
        }
        val currentStamp = stampProvider()
        val merged = HashMap<String, Any?>(values)
        pendingLocalWrites.forEach { (key, pending) ->
            when {
                pending.stamp != currentStamp ->
                    pendingLocalWrites.remove(key, pending)
                values[key] == pending.value ->
                    pendingLocalWrites.remove(key, pending)
                else -> merged[key] = pending.value
            }
        }
        snapshot = merged
    }

    /**
     * يستبدل المخزن الحقيقي وينقل المراقبة إليه بعد فتح القفل (الأمر 6).
     */
    fun updateDelegate(newDelegate: SharedPreferences) {
        runCatching {
            delegate.unregisterOnSharedPreferenceChangeListener(
                preferenceChangeListener
            )
        }
        delegate = newDelegate
        runCatching {
            newDelegate.registerOnSharedPreferenceChangeListener(
                preferenceChangeListener
            )
        }
        pendingLocalWrites.clear()
        snapshot = newDelegate.all
    }

    override fun getAll(): MutableMap<String, *> = HashMap(snapshot)

    override fun getString(key: String, defValue: String?): String? {
        val value = snapshot[key] ?: return defValue
        return if (value is String) value else defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        val value = snapshot[key] ?: return defValues
        if (value !is Set<*>) return defValues
        @Suppress("UNCHECKED_CAST")
        return (value as Set<String>).toMutableSet()
    }

    override fun getInt(key: String, defValue: Int): Int {
        val value = snapshot[key] ?: return defValue
        return if (value is Int) value else defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        val value = snapshot[key] ?: return defValue
        return if (value is Long) value else defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val value = snapshot[key] ?: return defValue
        return if (value is Float) value else defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val value = snapshot[key] ?: return defValue
        return if (value is Boolean) value else defValue
    }

    override fun contains(key: String): Boolean = snapshot.containsKey(key)

    override fun edit(): SharedPreferences.Editor = SnapshotEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = delegate.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = delegate.unregisterOnSharedPreferenceChangeListener(listener)

    /** محرر يجمّع تعديلاته ثم يطبقها معاملة موحدة. */
    private inner class SnapshotEditor : SharedPreferences.Editor {
        private var clearFirst = false
        private val removals = LinkedHashSet<String>()
        private val puts = LinkedHashMap<String, Any?>()

        override fun clear(): SharedPreferences.Editor =
            apply {
                clearFirst = true
                removals.clear()
                puts.clear()
            }

        override fun remove(key: String): SharedPreferences.Editor =
            apply {
                removals.add(key)
                puts.remove(key)
            }

        override fun putString(key: String, value: String?) =
            apply {
                if (value == null) {
                    removals.add(key)
                    puts.remove(key)
                } else {
                    removals.remove(key)
                    puts[key] = value
                }
            }

        override fun putStringSet(key: String, values: Set<String>?) =
            apply {
                if (values == null) {
                    removals.add(key)
                    puts.remove(key)
                } else {
                    removals.remove(key)
                    puts[key] = values
                }
            }

        override fun putInt(key: String, value: Int) =
            apply { puts[key] = value }

        override fun putLong(key: String, value: Long) =
            apply { puts[key] = value }

        override fun putFloat(key: String, value: Float) =
            apply { puts[key] = value }

        override fun putBoolean(key: String, value: Boolean) =
            apply { puts[key] = value }

        override fun commit(): Boolean {
            val tx = SettingsTransaction(
                clearFirst = clearFirst,
                removals = removals.toList(),
                puts = HashMap(puts)
            )
            val ctx = context
            val isChildProcess = ctx != null &&
                (!com.aymankhattab.nateq.util.ProcessUtils.isMainProcess(ctx) ||
                    SettingsAuthority.forceIpcForTesting)
            if (ctx != null && isChildProcess) {
                val result = SettingsAuthority.commitTransaction(ctx, tx)
                if (result.success) {
                    synchronized(this@SnapshotPrefs) {
                        if (result.revision >= revision) {
                            revision = result.revision
                            snapshot = result.snapshot
                            pendingLocalWrites.clear()
                        }
                    }
                    onChanged()
                    return true
                }
                return false
            } else {
                val ok = delegate.edit().also { applyOps(it) }.commit()
                if (ok) {
                    synchronized(this@SnapshotPrefs) {
                        pendingLocalWrites.clear()
                        snapshot = delegate.all
                    }
                    onChanged()
                }
                return ok
            }
        }

        override fun apply() {
            val tx = SettingsTransaction(
                clearFirst = clearFirst,
                removals = removals.toList(),
                puts = HashMap(puts)
            )
            val ctx = context
            val isChildProcess = ctx != null &&
                (!com.aymankhattab.nateq.util.ProcessUtils.isMainProcess(ctx) ||
                    SettingsAuthority.forceIpcForTesting)
            if (ctx != null && isChildProcess) {
                val stampAtWrite = stampProvider()
                val optimistic = HashMap(snapshot)
                if (clearFirst) optimistic.clear()
                removals.forEach { optimistic.remove(it) }
                for ((key, value) in puts) {
                    if (value == null) {
                        optimistic.remove(key)
                    } else {
                        optimistic[key] = value
                    }
                }
                snapshot = optimistic
                writeExecutor.execute {
                    val result = SettingsAuthority.commitTransaction(ctx, tx)
                    if (result.success) {
                        synchronized(this@SnapshotPrefs) {
                            if (result.revision >= revision) {
                                revision = result.revision
                                snapshot = result.snapshot
                                pendingLocalWrites.clear()
                            }
                        }
                        onChanged()
                    }
                }
            } else {
                val stampAtWrite = stampProvider()
                val keysBeforeClear = if (clearFirst) {
                    snapshot.keys.toList()
                } else {
                    emptyList()
                }
                delegate.edit().also { applyOps(it) }.apply()
                keysBeforeClear.forEach { key ->
                    pendingLocalWrites[key] =
                        PendingLocalWrite(null, stampAtWrite)
                }
                removals.forEach { key ->
                    pendingLocalWrites[key] =
                        PendingLocalWrite(null, stampAtWrite)
                }
                puts.forEach { (key, value) ->
                    pendingLocalWrites[key] =
                        PendingLocalWrite(value, stampAtWrite)
                }
                snapshot = delegate.all
                onChanged()
            }
        }

        private fun applyOps(editor: SharedPreferences.Editor) {
            if (clearFirst) editor.clear()
            removals.forEach { editor.remove(it) }
            for ((key, value) in puts) {
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                }
            }
        }
    }
}