package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.announcement.CallerAnnouncementReceiver
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.VoiceProfile
import java.util.Locale

/**
 * البحث عن صوتٍ محفوظ لجهة اتصال باسمها (ع٤١-ب10.3).
 * يُستعمل في مسار الدردشة الحية حيث تتوفر هوية المرسِل باسمٍ لا رقم.
 */
object ContactVoiceLookup {

    /**
     * @return صوت جهة الاتصال إن وُجد لها صوتٌ محفوظ وتطابق أحدُ
     *   أرقامها، وإلا null. يتطلّب إذن `READ_CONTACTS`.
     */
    fun byName(context: Context, name: String): VoiceProfile? {
        if (name.isBlank()) return null
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val store = ContactVoiceStore.create(context)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf(name),
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (cursor.moveToNext()) {
                    val number = cursor.getString(idx) ?: continue
                    val forms = CallerAnnouncementReceiver
                        .callerLookupForms(number, Locale.getDefault().country)
                    store.find(forms)?.let { return it }
                }
            }
        }
        return null
    }
}
