package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * مدير الذاكرة للاتصال السريع (1 - 10000 موقع).
 * يحفظ لكل موقع اسماً ورقم هاتف وهوية جهة
 * الاتصال (CONTACT_ID وLOOKUP_KEY و_ID).
 */
object SpeedDialManager {

    private const val PREFS_NAME = "lord_speed_dial_prefs"
    private const val KEY_ASSIGNED_SLOTS = "assigned_slots"
    private const val PREFIX_NAME = "slot_name_"
    private const val PREFIX_PHONE = "slot_phone_"
    private const val PREFIX_CONTACT_ID = "slot_contact_id_"
    private const val PREFIX_LOOKUP_KEY = "slot_lookup_key_"
    private const val PREFIX_PHONE_ROW_ID = "slot_phone_row_id_"

    const val MIN_SLOT = 1
    const val MAX_SLOT = 10000

    data class SpeedDialEntry(
        val slot: Int,
        val name: String,
        val phoneNumber: String,
        val contactId: Long? = null,
        val lookupKey: String? = null,
        val phoneRowId: Long? = null
    )

    data class SystemContactMatch(
        val contactId: Long,
        val lookupKey: String,
        val phoneRowId: Long,
        val name: String,
        val number: String
    )

    sealed class MigrationResult {
        data class Migrated(val entry: SpeedDialEntry) : MigrationResult()
        object NotNeeded : MigrationResult()
        object Ambiguous : MigrationResult()
        object NotFound : MigrationResult()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isValidSlot(slot: Int): Boolean = slot in MIN_SLOT..MAX_SLOT

    fun getSlot(context: Context, slot: Int): SpeedDialEntry? {
        if (!isValidSlot(slot)) return null
        val prefs = getPrefs(context)
        val name = prefs.getString("$PREFIX_NAME$slot", null) ?: return null
        val phone = prefs.getString("$PREFIX_PHONE$slot", null) ?: return null

        val contactId = if (prefs.contains("$PREFIX_CONTACT_ID$slot")) {
            val id = prefs.getLong("$PREFIX_CONTACT_ID$slot", 0L)
            if (id > 0L) id else null
        } else null

        val lookupKey = prefs.getString("$PREFIX_LOOKUP_KEY$slot", null)

        val phoneRowId = if (prefs.contains("$PREFIX_PHONE_ROW_ID$slot")) {
            val rid = prefs.getLong("$PREFIX_PHONE_ROW_ID$slot", 0L)
            if (rid > 0L) rid else null
        } else null

        return SpeedDialEntry(
            slot = slot,
            name = name,
            phoneNumber = phone,
            contactId = contactId,
            lookupKey = lookupKey,
            phoneRowId = phoneRowId
        )
    }

    fun saveSlot(
        context: Context,
        slot: Int,
        name: String,
        phoneNumber: String,
        contactId: Long? = null,
        lookupKey: String? = null,
        phoneRowId: Long? = null
    ): Boolean {
        if (!isValidSlot(slot)) return false
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ASSIGNED_SLOTS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        currentSet.add(slot.toString())

        val editor = prefs.edit()
            .putString("$PREFIX_NAME$slot", name.trim())
            .putString("$PREFIX_PHONE$slot", phoneNumber.trim())
            .putStringSet(KEY_ASSIGNED_SLOTS, currentSet)

        if (contactId != null && contactId > 0L) {
            editor.putLong("$PREFIX_CONTACT_ID$slot", contactId)
        } else {
            editor.remove("$PREFIX_CONTACT_ID$slot")
        }

        if (!lookupKey.isNullOrBlank()) {
            editor.putString("$PREFIX_LOOKUP_KEY$slot", lookupKey.trim())
        } else {
            editor.remove("$PREFIX_LOOKUP_KEY$slot")
        }

        if (phoneRowId != null && phoneRowId > 0L) {
            editor.putLong("$PREFIX_PHONE_ROW_ID$slot", phoneRowId)
        } else {
            editor.remove("$PREFIX_PHONE_ROW_ID$slot")
        }

        editor.commit()
        return true
    }

    fun deleteSlot(context: Context, slot: Int): Boolean {
        if (!isValidSlot(slot)) return false
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ASSIGNED_SLOTS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(slot.toString())

        prefs.edit()
            .remove("$PREFIX_NAME$slot")
            .remove("$PREFIX_PHONE$slot")
            .remove("$PREFIX_CONTACT_ID$slot")
            .remove("$PREFIX_LOOKUP_KEY$slot")
            .remove("$PREFIX_PHONE_ROW_ID$slot")
            .putStringSet(KEY_ASSIGNED_SLOTS, currentSet)
            .commit()
        return true
    }

    fun getAllAssignedSlots(context: Context): List<SpeedDialEntry> {
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ASSIGNED_SLOTS, emptySet())
            ?: emptySet()
        val list = mutableListOf<SpeedDialEntry>()
        for (slotStr in currentSet) {
            val slot = slotStr.toIntOrNull() ?: continue
            if (!isValidSlot(slot)) continue
            val entry = getSlot(context, slot) ?: continue
            list.add(entry)
        }
        return list.sortedBy { it.slot }
    }

    fun migrateSlotIfPossible(
        context: Context,
        slot: Int,
        matches: List<SystemContactMatch>
    ): MigrationResult {
        val entry = getSlot(context, slot) ?: return MigrationResult.NotFound
        if (entry.contactId != null && entry.contactId > 0L) {
            return MigrationResult.NotNeeded
        }
        return if (matches.size == 1) {
            val match = matches[0]
            saveSlot(
                context = context,
                slot = slot,
                name = match.name,
                phoneNumber = match.number,
                contactId = match.contactId,
                lookupKey = match.lookupKey,
                phoneRowId = match.phoneRowId
            )
            val updated = getSlot(context, slot) ?: entry
            MigrationResult.Migrated(updated)
        } else if (matches.size > 1) {
            MigrationResult.Ambiguous
        } else {
            MigrationResult.NotFound
        }
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().commit()
    }
}
