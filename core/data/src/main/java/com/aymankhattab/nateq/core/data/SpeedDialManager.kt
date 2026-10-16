package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * مدير الذاكرة للاتصال السريع (1 - 10000 موقع).
 * يحفظ لكل موقع اسماً ورقم هاتف واحداً فقط.
 */
object SpeedDialManager {

    private const val PREFS_NAME = "lord_speed_dial_prefs"
    private const val KEY_ASSIGNED_SLOTS = "assigned_slots"
    private const val PREFIX_NAME = "slot_name_"
    private const val PREFIX_PHONE = "slot_phone_"

    const val MIN_SLOT = 1
    const val MAX_SLOT = 10000

    data class SpeedDialEntry(
        val slot: Int,
        val name: String,
        val phoneNumber: String
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isValidSlot(slot: Int): Boolean = slot in MIN_SLOT..MAX_SLOT

    fun getSlot(context: Context, slot: Int): SpeedDialEntry? {
        if (!isValidSlot(slot)) return null
        val prefs = getPrefs(context)
        val name = prefs.getString("$PREFIX_NAME$slot", null) ?: return null
        val phone = prefs.getString("$PREFIX_PHONE$slot", null) ?: return null
        return SpeedDialEntry(slot, name, phone)
    }

    fun saveSlot(
        context: Context,
        slot: Int,
        name: String,
        phoneNumber: String
    ): Boolean {
        if (!isValidSlot(slot)) return false
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ASSIGNED_SLOTS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        currentSet.add(slot.toString())

        prefs.edit()
            .putString("$PREFIX_NAME$slot", name.trim())
            .putString("$PREFIX_PHONE$slot", phoneNumber.trim())
            .putStringSet(KEY_ASSIGNED_SLOTS, currentSet)
            .commit()
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
            val name = prefs.getString("$PREFIX_NAME$slot", null) ?: continue
            val phone = prefs.getString("$PREFIX_PHONE$slot", null) ?: continue
            list.add(SpeedDialEntry(slot, name, phone))
        }
        return list.sortedBy { it.slot }
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().commit()
    }
}
