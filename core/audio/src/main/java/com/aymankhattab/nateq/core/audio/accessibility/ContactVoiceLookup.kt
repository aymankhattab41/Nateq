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
 * البحث عن صوتٍ محفوظ لجهة اتصال برقمها الموحّد أو اسمها (ع٤١-ب10.3، ع٤٢-11).
 * يطابق الرقم الموحّد أولاً، ثم الاسم مع تجاهل المسافات والتشكيل والتطويل
 * والبادئات الشائعة (د.، دكتور، م.، مهندس، أ.، أستاذ، الشيخ، السيد).
 */
object ContactVoiceLookup {

    private val PREFIXES = listOf(
        "دكتور ", "دكتورة ", "د.", "د/", "د ",
        "مهندس ", "مهندسة ", "م.", "م/", "م ",
        "أستاذ ", "أستاذة ", "استاذ ", "استاذه ",
        "أ.", "ا.", "أ/", "ا/",
        "الشيخ ", "شيخ ",
        "السيد ", "السيدة ", "سيد ", "سيدة ",
        "dr. ", "dr.", "dr ",
        "eng. ", "eng.", "eng ",
        "prof. ", "prof.", "prof ",
        "mr. ", "mr.", "mr ",
        "mrs. ", "mrs.", "mrs ",
        "ms. ", "ms.", "ms "
    )

    /**
     * تطبيع اسم جهة الاتصال (نقي قابل للاختبار):
     * تجريد التشكيل والتطويل، توحيد الألف والياء والتاء المربوطة،
     * حذف البادئات والألقاب الشائعة، وتجريد علامات الترقيم.
     */
    fun normalizeContactName(name: String): String {
        if (name.isBlank()) return ""
        var text = name.trim().lowercase(Locale.ROOT)

        // إزالة التشكيل (الحركات) والتطويل
        text = text.replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")

        // توحيد الألف والياء والتاء المربوطة
        text = text.replace(Regex("[إأآٱ]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')

        // حذف البادئات والألقاب من البداية
        var changed = true
        while (changed) {
            changed = false
            for (p in PREFIXES) {
                if (text.startsWith(p)) {
                    text = text.substring(p.length).trim()
                    changed = true
                    break
                }
            }
        }

        // تجريد علامات الترقيم
        text = text.replace(Regex("[.,/#!$%^&*;:{}=\\-_`~()?\\[\\]]"), "")
        return text.trim()
    }

    /**
     * البحث الشامل: بمفتاح المحادثة إن وُجد، ثم المطابقة المباشرة،
     * ثم الرقم الموحّد، ثم الاسم المطبّع في المخزن، ثم مزود جهات الاتصال.
     */
    fun find(
        context: Context,
        keyOrName: String,
        conversationKey: String? = null
    ): VoiceProfile? {
        val store = ContactVoiceStore.create(context)

        // 1. مفتاح المحادثة إن وُجد
        if (!conversationKey.isNullOrBlank()) {
            store.get(conversationKey)?.let { return it }
        }

        if (keyOrName.isBlank()) return null

        // 2. مطابقة مباشرة في المخزن
        store.get(keyOrName)?.let { return it }

        // 3. إن كان النص رقماً موحداً (يحوي أرقاماً): فحص الصيغ
        val country = Locale.getDefault().country
        val forms = CallerAnnouncementReceiver.callerLookupForms(
            keyOrName, country
        )
        if (forms.isNotEmpty()) {
            store.find(forms)?.let { return it }
        }

        val queryDigits = keyOrName.filter { it.isDigit() }
        val significantDigits = queryDigits.trimStart('0')
        if (significantDigits.length >= 7) {
            for ((storedKey, profile) in store.all) {
                val storedDigits = storedKey.filter { it.isDigit() }
                val storedSig = storedDigits.trimStart('0')
                if (storedSig.isNotEmpty() &&
                    (storedDigits.endsWith(significantDigits) ||
                        queryDigits.endsWith(storedSig))
                ) {
                    return profile
                }
            }
        }

        // 4. مطابقة الاسم المطبّع في المخزن
        val normQuery = normalizeContactName(keyOrName)
        val compactQuery = normQuery.replace(" ", "")
        if (compactQuery.isNotEmpty()) {
            for ((storedKey, profile) in store.all) {
                val normStored = normalizeContactName(storedKey)
                if (normStored == normQuery ||
                    normStored.replace(" ", "") == compactQuery
                ) {
                    return profile
                }
            }
        }

        // 5. استعلام مزود جهات الاتصال بالنظام (يتطلب READ_CONTACTS)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return querySystemContacts(context, store, keyOrName, compactQuery)
    }

    /** توافقية مع الاستدعاءات القديمة. */
    fun byName(context: Context, name: String): VoiceProfile? =
        find(context, name)

    private fun querySystemContacts(
        context: Context,
        store: ContactVoiceStore,
        rawName: String,
        compactQuery: String
    ): VoiceProfile? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val country = Locale.getDefault().country

        // محاولة بالاسم الدقيق أولاً
        runCatching {
            val selection =
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf(rawName),
                null
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numIdx) ?: continue
                    val forms = CallerAnnouncementReceiver.callerLookupForms(
                        number, country
                    )
                    store.find(forms)?.let { return it }
                }
            }
        }

        // مسح جهات الاتصال لمطابقة الاسم المطبّع
        if (compactQuery.isNotEmpty()) {
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val numIdx = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameIdx) ?: continue
                        val norm = normalizeContactName(displayName)
                            .replace(" ", "")
                        if (norm == compactQuery) {
                            val number = cursor.getString(numIdx) ?: continue
                            val forms =
                                CallerAnnouncementReceiver.callerLookupForms(
                                    number, country
                                )
                            store.find(forms)?.let { return it }
                            store.get(displayName)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }
}
