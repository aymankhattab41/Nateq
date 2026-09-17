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
    private val delegate: SharedPreferences,
    private val onChanged: () -> Unit = {}
) : SharedPreferences {

    /** اللقطة الحالية — تُستبدل ذرياً عبر [replaceSnapshot] أو بعد كل كتابة. */
    @Volatile
    private var snapshot: Map<String, Any?> = delegate.all

    /** يستبدل اللقطة التي تُقرأ منها القيم دون أي مساس بالقرص — جوهر
     *  الرحلة النظيفة في [SettingsRepository.reload]. */
    fun replaceSnapshot(values: Map<String, Any?>) {
        snapshot = values
    }

    /** لقطة القراءة الحالية (لأغراض الفحص والمقارنة في reload). */
    fun currentSnapshot(): Map<String, Any?> = snapshot

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
        return value as MutableSet<String>
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

    /** محرر يجمّع تعديلاته ثم يطبقها على [delegate] و[snapshot] معاً. */
    private inner class SnapshotEditor : SharedPreferences.Editor {
        private var clearFirst = false
        private val removals = LinkedHashSet<String>()
        private val puts = LinkedHashMap<String, Any?>()

        override fun clear(): SharedPreferences.Editor =
            apply { clearFirst = true }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals.add(key) }

        override fun putString(key: String, value: String?) =
            apply { puts[key] = value }

        override fun putStringSet(key: String, values: Set<String>?) =
            apply { puts[key] = values }

        override fun putInt(key: String, value: Int) =
            apply { puts[key] = value }

        override fun putLong(key: String, value: Long) =
            apply { puts[key] = value }

        override fun putFloat(key: String, value: Float) =
            apply { puts[key] = value }

        override fun putBoolean(key: String, value: Boolean) =
            apply { puts[key] = value }

        override fun commit(): Boolean {
            val ok = delegate.edit().also { applyOps(it) }.commit()
            if (ok) {
                snapshot = delegate.all
                onChanged()
            }
            return ok
        }

        override fun apply() {
            delegate.edit().also { applyOps(it) }.apply()
            snapshot = delegate.all
            onChanged()
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