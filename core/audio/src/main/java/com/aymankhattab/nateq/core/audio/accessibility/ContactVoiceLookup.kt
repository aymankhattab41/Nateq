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
 * يطابق مفتاح المحادثة أولاً، ثم أرقام الهواتف وصيغها واللواحق الآمنة،
 * ثم الاسم الكامل مع منع الحسم العشوائي للأسماء الملتبسة، ثم الاسم الأول.
 */
object ContactVoiceLookup {

    private const val TAG = "ContactVoiceLookup"

    /**
     * تطبيع اسم جهة الاتصال (يُفوَّض إلى
     * [ContactLookupNormalizer.normalizeName]).
     */
    fun normalizeContactName(name: String): String =
        ContactLookupNormalizer.normalizeName(name)

    fun isPhoneCandidate(s: String): Boolean =
        ContactLookupNormalizer.isPhoneCandidate(s)

    /**
     * البحث الشامل: بمفتاح المحادثة إن وُجد، ثم أرقام الهواتف،
     * ثم الاسم المطبّع في المخزن، ثم الاسم الأول الوحيد، ثم جهات اتصال النظام.
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
                Log.d(TAG, "Matched by conversationKey")
                return it
            }
        }

        if (keyOrName.isBlank()) return null

        // 2. مطابقة أرقام الهواتف إن كان الاستعلام مرشحاً كرقم هاتف (البند 17)
        if (isPhoneCandidate(keyOrName)) {
            val country = Locale.getDefault().country
            val forms = ContactLookupNormalizer.phoneLookupForms(
                keyOrName, country
            )
            if (forms.isNotEmpty()) {
                store.find(forms)?.let {
                    Log.d(TAG, "Matched by phone forms")
                    return it
                }
            }

            val queryDigits = keyOrName.filter { it.isDigit() }
            val significantDigits = queryDigits.trimStart('0')
            if (significantDigits.length >= 7) {
                data class SuffixMatch(
                    val profile: VoiceProfile,
                    val matchLen: Int
                )
                val candidates = mutableListOf<SuffixMatch>()

                for ((storedKey, profile) in store.all) {
                    if (!isPhoneCandidate(storedKey)) continue
                    val storedDigits = storedKey.filter { it.isDigit() }
                    val storedSig = storedDigits.trimStart('0')
                    if (storedSig.length >= 7) {
                        val matches =
                            storedDigits.endsWith(significantDigits) ||
                            queryDigits.endsWith(storedSig)
                        if (matches) {
                            val matchLen = minOf(
                                significantDigits.length,
                                storedSig.length
                            )
                            candidates.add(SuffixMatch(profile, matchLen))
                        }
                    }
                }

                if (candidates.isNotEmpty()) {
                    val maxLen = candidates.maxOf { it.matchLen }
                    val best = candidates.filter { it.matchLen == maxLen }
                    val distinctProfiles = best.map { it.profile }.distinct()
                    if (distinctProfiles.size == 1) {
                        Log.d(TAG, "Matched by significant digits")
                        return distinctProfiles.first()
                    } else {
                        Log.d(
                            TAG,
                            "Ambiguous phone suffix match: " +
                                "${best.size} candidates"
                        )
                        return null
                    }
                }
            }
        }

        // 3. مطابقة الاسم الكامل المطبّع في المخزن (البند 20)
        val normQuery = normalizeContactName(keyOrName)
        val compactQuery = normQuery.replace(" ", "")
        if (compactQuery.isNotEmpty()) {
            val matchingProfiles = mutableListOf<VoiceProfile>()
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
                    matchingProfiles.add(profile)
                }
            }

            val distinctProfiles = matchingProfiles.distinct()
            if (distinctProfiles.size == 1) {
                Log.d(TAG, "Matched by normalized name")
                return distinctProfiles.first()
            } else if (distinctProfiles.size > 1) {
                Log.d(
                    TAG,
                    "Ambiguous full name match: " +
                        "${distinctProfiles.size} candidates"
                )
                return null
            }
        }

        // 4. مطابقة الاسم الأول إن كان وحيداً في المخزن (البند 20)
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
                Log.d(TAG, "Matched by unique first name")
                return single
            } else if (matchingProfiles.size > 1) {
                Log.d(
                    TAG,
                    "Ambiguous first name: " +
                        "${matchingProfiles.size} matches found"
                )
            }
        }

        // 5. استعلام مزود جهات الاتصال بالنظام (يتطلب READ_CONTACTS)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.d(TAG, "No match (READ_CONTACTS not granted)")
            return null
        }

        val sysMatch = querySystemContacts(
            context, store, keyOrName, compactQuery
        )
        if (sysMatch != null) {
            Log.d(TAG, "Matched via system contacts query")
            return sysMatch
        }

        Log.d(TAG, "ContactVoice lookup failed")
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
