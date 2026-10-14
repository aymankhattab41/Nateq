package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import com.aymankhattab.nateq.core.audio.accessibility.KeyShortcutRouter
import com.aymankhattab.nateq.core.audio.accessibility.ShortcutAction
import com.aymankhattab.nateq.core.audio.accessibility.ShortcutBindingTable
import com.aymankhattab.nateq.core.audio.accessibility.ShortcutGesture
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * حوار ربط إيماءات مفاتيح الصوت بالأفعال (ع٤١-ب1.6).
 * - قائمة الإيماءات مع الفعل الحالي لكلٍّ منها.
 * - اختيار فعلٍ لكل إيماءة، وزر «اختبار» يُرسل الإيماءة للخدمة لتنفيذها.
 */
class ShortcutBindingsDialog(
    private val context: Context,
    private val settings: SettingsRepository
) {

    fun show() {
        val table = ShortcutBindingTable.decode(
            settings.getA11yShortcutBindings()
        )
        val gestures = ShortcutGesture.entries.toTypedArray()
        val labels = gestures.map {
            gestureLabel(it, table.actionFor(it))
        }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_shortcut_dialog_title)
            .setItems(labels) { _, which ->
                chooseActionFor(gestures[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseActionFor(gesture: ShortcutGesture) {
        val table = ShortcutBindingTable.decode(
            settings.getA11yShortcutBindings()
        )
        val actions = ShortcutAction.entries.toTypedArray()
        val current = actions.indexOf(table.actionFor(gesture))
        var selected = if (current >= 0) current else actions.size - 1
        val labels = actions.map { actionLabel(it) }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_shortcut_choose_action)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val action = actions[selected]
                settings.setA11yShortcutBindings(
                    table.with(gesture, action).encode()
                )
                show()
            }
            .setNeutralButton(R.string.a11y_shortcut_test) { _, _ ->
                testGesture(gesture)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** يطلب من خدمة إمكانية الوصول تنفيذ الإيماءة كاختبارٍ فوري. */
    private fun testGesture(gesture: ShortcutGesture) {
        val intent = Intent(KeyShortcutRouter.ACTION_TEST_SHORTCUT)
            .setPackage(context.packageName)
            .putExtra(KeyShortcutRouter.EXTRA_GESTURE, gesture.name)
        context.sendBroadcast(intent)
    }

    private fun gestureLabel(
        gesture: ShortcutGesture,
        action: ShortcutAction
    ): String {
        val name = context.getString(gestureNameRes(gesture))
        val act = context.getString(actionLabelRes(action))
        return "$name: $act"
    }

    private fun actionLabel(action: ShortcutAction): String =
        context.getString(actionLabelRes(action))

    private fun gestureNameRes(gesture: ShortcutGesture): Int = when (gesture) {
        ShortcutGesture.VOL_UP_LONG -> R.string.a11y_gesture_vol_up_long
        ShortcutGesture.VOL_DOWN_LONG -> R.string.a11y_gesture_vol_down_long
        ShortcutGesture.BOTH_HOLD -> R.string.a11y_gesture_both_hold
        ShortcutGesture.VOL_UP_DOUBLE -> R.string.a11y_gesture_vol_up_double
        ShortcutGesture.VOL_DOWN_DOUBLE ->
            R.string.a11y_gesture_vol_down_double
        ShortcutGesture.VOL_UP_TRIPLE -> R.string.a11y_gesture_vol_up_triple
        ShortcutGesture.VOL_DOWN_TRIPLE ->
            R.string.a11y_gesture_vol_down_triple
    }

    private fun actionLabelRes(action: ShortcutAction): Int = when (action) {
        ShortcutAction.NONE -> R.string.a11y_action_none
        ShortcutAction.READ_SCREEN -> R.string.a11y_action_read_screen
        ShortcutAction.READ_CLIPBOARD -> R.string.a11y_action_read_clipboard
        ShortcutAction.REPEAT_LAST -> R.string.a11y_action_repeat_last
        ShortcutAction.SPELL_LAST -> R.string.a11y_action_spell_last
        ShortcutAction.WHERE_AM_I -> R.string.a11y_action_where_am_i
        ShortcutAction.TOGGLE_PAUSE -> R.string.a11y_action_toggle_pause
        ShortcutAction.TOGGLE_CONTINUOUS ->
            R.string.a11y_action_toggle_continuous
        ShortcutAction.OCR_FOCUS -> R.string.a11y_action_ocr_focus
        ShortcutAction.READ_FULL_URL -> R.string.a11y_action_read_full_url
        ShortcutAction.REPEAT_PREVIOUS ->
            R.string.a11y_action_repeat_previous
        ShortcutAction.SPEECH_HISTORY ->
            R.string.a11y_action_speech_history
        ShortcutAction.SHOW_QUICK_MENU ->
            R.string.a11y_action_show_quick_menu
    }
}
