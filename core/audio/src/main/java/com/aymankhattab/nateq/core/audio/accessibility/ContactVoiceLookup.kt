package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.data.ContactLookupNormalizer
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.VoiceProfile
import java.util.Locale

/**
 * البحث عن صوتٍ محفوظ لجهة اتصال برقمها الموحّد أو اسمها (ع٤١-ب10.3، ع٤٥).
 * يطابق الرقم الموحّد أولاً، ثم الاسم مع تجاهل المسافات والتشكيل والتطويل
 * والبادئات الشائعة، ثم مطابقة الاسم الأول إن كان وحيداً في المخزن.
 */
object ContactVoiceLookup {

    private const val TAG = "ContactVoiceLookup"

    /**
     * تطبيع اسم جهة الاتصال (يُفوَّض إلى
     * [ContactLookupNormalizer.normalizeName]).
     */
    fun normalizeContactName(name: String): String =
        ContactLookupNormalizer.normalizeName(name)

    /**
     * البحث الشامل: بمفتاح المحادثة إن وُجد، ثم المطابقة المباشرة،
     * ثم الرقم الموحّد، ثم الاسم المطبّع في المخزن، ثم الاسم الأول الوحيد،
     * ثم مزود جهات الاتصال بالنظام (الأمر 3).
     */
    fun find(
        context: Context,
        keyOrName: String,
        conversationKey: String? = null
    ): VoiceProfile? {
        val store = ContactVoiceStore.create(context)

        // 1. مفتاح المحادثة إن وُجد
        if (!conversationKey.isNullOrBlank()) {
            store.get(conversationKey)?.let {
                Log.d(TAG, "Matched by conversationKey: $conversationKey")
                return it
            }
        }

        if (keyOrName.isBlank()) return null

        // 2. مطابقة مباشرة في المخزن
        store.get(keyOrName)?.let {
            Log.d(TAG, "Matched by direct key: $keyOrName")
            return it
        }

        // 3. إن كان النص رقماً موحداً (يحوي أرقاماً): فحص الصيغ
        val country = Locale.getDefault().country
        val forms = ContactLookupNormalizer.phoneLookupForms(
            keyOrName, country
        )
        if (forms.isNotEmpty()) {
            store.find(forms)?.let {
                Log.d(TAG, "Matched by phone forms for: $keyOrName")
                return it
            }
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
                    Log.d(
                        TAG,
                        "Matched by significant digits: $keyOrName ~ $storedKey"
                    )
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
                val normDisp = profile.displayName?.let {
                    normalizeContactName(it)
                }.orEmpty()
                if (normStored == normQuery ||
                    normStored.replace(" ", "") == compactQuery ||
                    normDisp == normQuery ||
                    normDisp.replace(" ", "") == compactQuery
                ) {
                    Log.d(TAG, "Matched by normalized name: $keyOrName")
                    return profile
                }
            }
        }

        // 5. مطابقة الاسم الأول إن كان وحيداً في المخزن (ع٤٥ - الأمر 3 بند 5)
        val queryFirst = ContactLookupNormalizer.firstName(keyOrName)
        if (queryFirst.length >= 2) {
            val matchingProfiles = LinkedHashSet<VoiceProfile>()
            for ((storedKey, profile) in store.all) {
                val storedFirst = ContactLookupNormalizer.firstName(storedKey)
                val dispFirst = profile.displayName?.let {
                    ContactLookupNormalizer.firstName(it)
                }.orEmpty()
                if (storedFirst == queryFirst || dispFirst == queryFirst) {
                    matchingProfiles.add(profile)
                }
            }
            if (matchingProfiles.size == 1) {
                val single = matchingProfiles.first()
                Log.d(TAG, "Matched by unique first name: $queryFirst")
                return single
            } else if (matchingProfiles.size > 1) {
                Log.d(
                    TAG,
                    "Ambiguous first name ($queryFirst): " +
                        "${matchingProfiles.size} matches found"
                )
            }
        }

        // 6. استعلام مزود جهات الاتصال بالنظام (يتطلب READ_CONTACTS)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.d(TAG, "No match for: $keyOrName (READ_CONTACTS not granted)")
            return null
        }

        val sysMatch = querySystemContacts(
            context, store, keyOrName, compactQuery
        )
        if (sysMatch != null) {
            Log.d(TAG, "Matched via system contacts query: $keyOrName")
            return sysMatch
        }

        Log.d(TAG, "ContactVoice lookup failed for: $keyOrName")
        return null
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
                    val forms = ContactLookupNormalizer.phoneLookupForms(
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
                                ContactLookupNormalizer.phoneLookupForms(
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
