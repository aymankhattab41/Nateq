package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import java.util.concurrent.atomic.AtomicLong

/**
 * معقدة المعاملات الموحدة للإعدادات.
 */
data class SettingsTransaction(
    val clearFirst: Boolean = false,
    val removals: List<String> = emptyList(),
    val puts: Map<String, Any?> = emptyMap()
)

/**
 * نتيجة تنفيذ معاملة الإعدادات بعد الحفظ.
 */
data class TransactionResult(
    val success: Boolean,
    val revision: Long,
    val snapshot: Map<String, Any?>
)

/**
 * سلطة الحفظ والتسلسل الموحدة للإعدادات عبر كافة عمليات التطبيق.
 * تضمن كتابة ذرية واحدة على أحدث حالة مع تجنب مسارات الكتابة
 * القديمة المسببة لتعارضات التعديل.
 */
object SettingsAuthority {

    const val METHOD_APPLY_CHANGES = "apply_changes"
    const val METHOD_GET_SNAPSHOT = "get_snapshot"

    const val EXTRA_CLEAR = "extra_clear"
    const val EXTRA_REMOVALS = "extra_removals"
    const val EXTRA_PUTS = "extra_puts"
    const val EXTRA_STRING_SET_KEYS = "extra_string_set_keys"
    const val EXTRA_SUCCESS = "extra_success"
    const val EXTRA_REVISION = "extra_revision"
    const val EXTRA_SNAPSHOT = "extra_snapshot"

    private val writeLock = Any()
    private val globalRevision = AtomicLong(1L)

    @androidx.annotation.VisibleForTesting
    @Volatile
    var forceIpcForTesting: Boolean = false

    /**
     * تنفيذ معاملة كتابة من خلال ContentProvider أو
     * محلياً عند غيابه.
     */
    fun commitTransaction(
        context: Context,
        tx: SettingsTransaction
    ): TransactionResult {
        val appCtx = context.applicationContext ?: context
        val bundle = transactionToBundle(tx)

        var resultBundle: Bundle? = null
        try {
            resultBundle = appCtx.contentResolver.call(
                SettingsChangeProvider.uri(),
                METHOD_APPLY_CHANGES,
                null,
                bundle
            )
        } catch (_: Exception) {
            // محاولة تعافٍ سريعة عند إعادة تشغيل العملية
            // المالكة لمزود الإعدادات
            try {
                resultBundle = appCtx.contentResolver.call(
                    SettingsChangeProvider.uri(),
                    METHOD_APPLY_CHANGES,
                    null,
                    bundle
                )
            } catch (_: Exception) {
                resultBundle = null
            }
        }

        if (resultBundle != null &&
            resultBundle.containsKey(EXTRA_SUCCESS)
        ) {
            val success = resultBundle.getBoolean(EXTRA_SUCCESS, false)
            val (rev, snap) = bundleToSnapshot(resultBundle)
            return TransactionResult(
                success = success,
                revision = rev,
                snapshot = snap
            )
        }

        // مسار بديل محلي عند تعذر IPC أو في الاختبارات
        return applyDirectly(appCtx, tx)
    }

    /**
     * استرجاع أحدث لقطة ورقم مراجعة من السلطة الموثوقة.
     */
    fun getSnapshot(
        context: Context
    ): Pair<Long, Map<String, Any?>> {
        val appCtx = context.applicationContext ?: context
        var resultBundle: Bundle? = null
        try {
            resultBundle = appCtx.contentResolver.call(
                SettingsChangeProvider.uri(),
                METHOD_GET_SNAPSHOT,
                null,
                null
            )
        } catch (_: Exception) {
            resultBundle = null
        }

        if (resultBundle != null &&
            resultBundle.containsKey(EXTRA_REVISION)
        ) {
            return bundleToSnapshot(resultBundle)
        }

        val prefs = getAuthoritativePrefs(appCtx)
        return Pair(globalRevision.get(), HashMap(prefs.all))
    }

    /**
     * معالجة نداء IPC من مزود الإعدادات.
     */
    fun applyFromIpc(
        context: Context,
        extras: Bundle
    ): Bundle {
        val tx = bundleToTransaction(extras)
        val result = applyDirectly(context, tx)
        return resultToBundle(result)
    }

    /**
     * تجهيز حزمة اللقطة الحالية لمزود الإعدادات.
     */
    fun getSnapshotBundle(context: Context): Bundle {
        val prefs = getAuthoritativePrefs(context)
        val result = TransactionResult(
            success = true,
            revision = globalRevision.get(),
            snapshot = HashMap(prefs.all)
        )
        return resultToBundle(result)
    }

    /**
     * تطبيق المعاملة مباشرة على المخزن الموثوق مع
     * قفل تزامني.
     */
    fun applyDirectly(
        context: Context,
        tx: SettingsTransaction
    ): TransactionResult = synchronized(writeLock) {
        val appCtx = context.applicationContext ?: context
        val prefs = getAuthoritativePrefs(appCtx)
        val currentAll = HashMap(prefs.all)

        val editor = prefs.edit()
        if (tx.clearFirst) {
            editor.clear()
            currentAll.clear()
        }
        for (key in tx.removals) {
            editor.remove(key)
            currentAll.remove(key)
        }
        for ((key, value) in tx.puts) {
            when (value) {
                null -> {
                    editor.remove(key)
                    currentAll.remove(key)
                }
                is String -> {
                    editor.putString(key, value)
                    currentAll[key] = value
                }
                is Int -> {
                    editor.putInt(key, value)
                    currentAll[key] = value
                }
                is Long -> {
                    editor.putLong(key, value)
                    currentAll[key] = value
                }
                is Float -> {
                    editor.putFloat(key, value)
                    currentAll[key] = value
                }
                is Boolean -> {
                    editor.putBoolean(key, value)
                    currentAll[key] = value
                }
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val set = (value as Set<String>).toSet()
                    editor.putStringSet(key, set)
                    currentAll[key] = set
                }
            }
        }

        val ok = editor.commit()
        val rev = if (ok) {
            globalRevision.incrementAndGet()
        } else {
            globalRevision.get()
        }

        if (ok) {
            runCatching {
                appCtx.contentResolver.notifyChange(
                    SettingsChangeProvider.uri(),
                    null
                )
            }
        }

        TransactionResult(
            success = ok,
            revision = rev,
            snapshot = currentAll
        )
    }

    fun getAuthoritativePrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !isUserUnlocked(appCtx)
        ) {
            val deviceContext = appCtx.createDeviceProtectedStorageContext()
            return deviceContext.getSharedPreferences(
                SettingsRepository.BOOT_MIRROR_PREFS,
                Context.MODE_PRIVATE
            )
        }
        return appCtx.getSharedPreferences(
            SettingsRepository.NEW_PREFS,
            Context.MODE_PRIVATE
        )
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(UserManager::class.java)
            return um?.isUserUnlocked ?: true
        }
        return true
    }

    fun transactionToBundle(tx: SettingsTransaction): Bundle {
        val b = Bundle()
        b.putBoolean(EXTRA_CLEAR, tx.clearFirst)
        b.putStringArrayList(EXTRA_REMOVALS, ArrayList(tx.removals))
        val putsBundle = Bundle()
        val stringSetKeys = ArrayList<String>()

        for ((key, value) in tx.puts) {
            when (value) {
                is String -> putsBundle.putString(key, value)
                is Int -> putsBundle.putInt(key, value)
                is Long -> putsBundle.putLong(key, value)
                is Float -> putsBundle.putFloat(key, value)
                is Boolean -> putsBundle.putBoolean(key, value)
                is Set<*> -> {
                    stringSetKeys.add(key)
                    @Suppress("UNCHECKED_CAST")
                    val list = ArrayList(value as Set<String>)
                    putsBundle.putStringArrayList(key, list)
                }
            }
        }
        b.putBundle(EXTRA_PUTS, putsBundle)
        b.putStringArrayList(EXTRA_STRING_SET_KEYS, stringSetKeys)
        return b
    }

    fun bundleToTransaction(b: Bundle): SettingsTransaction {
        val clearFirst = b.getBoolean(EXTRA_CLEAR, false)
        val removals = b.getStringArrayList(EXTRA_REMOVALS) ?: emptyList()
        val putsBundle = b.getBundle(EXTRA_PUTS)
        val stringSetKeys = (b.getStringArrayList(
            EXTRA_STRING_SET_KEYS
        ) ?: emptyList()).toSet()
        val puts = LinkedHashMap<String, Any?>()

        if (putsBundle != null) {
            for (key in putsBundle.keySet()) {
                if (key in stringSetKeys) {
                    val list = putsBundle.getStringArrayList(key)
                    puts[key] = list?.toSet() ?: emptySet<String>()
                } else {
                    puts[key] = putsBundle.get(key)
                }
            }
        }
        return SettingsTransaction(clearFirst, removals, puts)
    }

    fun resultToBundle(result: TransactionResult): Bundle {
        val b = Bundle()
        b.putBoolean(EXTRA_SUCCESS, result.success)
        b.putLong(EXTRA_REVISION, result.revision)
        val snapBundle = Bundle()
        val stringSetKeys = ArrayList<String>()

        for ((key, value) in result.snapshot) {
            when (value) {
                is String -> snapBundle.putString(key, value)
                is Int -> snapBundle.putInt(key, value)
                is Long -> snapBundle.putLong(key, value)
                is Float -> snapBundle.putFloat(key, value)
                is Boolean -> snapBundle.putBoolean(key, value)
                is Set<*> -> {
                    stringSetKeys.add(key)
                    @Suppress("UNCHECKED_CAST")
                    val list = ArrayList(value as Set<String>)
                    snapBundle.putStringArrayList(key, list)
                }
            }
        }
        b.putBundle(EXTRA_SNAPSHOT, snapBundle)
        b.putStringArrayList(EXTRA_STRING_SET_KEYS, stringSetKeys)
        return b
    }

    fun bundleToSnapshot(b: Bundle): Pair<Long, Map<String, Any?>> {
        val rev = b.getLong(EXTRA_REVISION, 0L)
        val snapBundle = b.getBundle(EXTRA_SNAPSHOT)
        val stringSetKeys = (b.getStringArrayList(
            EXTRA_STRING_SET_KEYS
        ) ?: emptyList()).toSet()
        val map = LinkedHashMap<String, Any?>()

        if (snapBundle != null) {
            for (key in snapBundle.keySet()) {
                if (key in stringSetKeys) {
                    val list = snapBundle.getStringArrayList(key)
                    map[key] = list?.toSet() ?: emptySet<String>()
                } else {
                    map[key] = snapBundle.get(key)
                }
            }
        }
        return Pair(rev, map)
    }
}
