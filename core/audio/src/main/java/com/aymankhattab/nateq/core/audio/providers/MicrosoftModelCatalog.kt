package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * كتالوج أصوات مايكروسوفت العصبية المدمجة (Microsoft Neural Embedded Voices).
 * يقرأ قائمة الأصوات من ملف JSON المرفق بالأصول ويتيح البحث والتحقق من التثبيت.
 */
object MicrosoftModelCatalog {

    private const val TAG = "MsModelCatalog"
    private const val ASSET_PATH = "microsoft/microsoft-voices.json"

    @Volatile
    private var cachedItems: List<MicrosoftCatalogItem>? = null

    private val AR_COUNTRIES = mapOf(
        "EG" to "مصر",
        "SA" to "السعودية",
        "AE" to "الإمارات",
        "DZ" to "الجزائر",
        "IQ" to "العراق",
        "JO" to "الأردن",
        "KW" to "الكويت",
        "LB" to "لبنان",
        "LY" to "ليبيا",
        "MA" to "المغرب",
        "OM" to "عمان",
        "QA" to "قطر",
        "SY" to "سوريا",
        "TN" to "تونس",
        "YE" to "اليمن",
        "BH" to "البحرين"
    )

    private val AR_NAMES = mapOf(
        "Salma" to "سلمى",
        "Shakir" to "شاكر",
        "Hamed" to "حامد",
        "Zariyah" to "زارية",
        "Fatima" to "فاطمة",
        "Hamdan" to "حمدان",
        "Amina" to "أمينة",
        "Ismael" to "إسماعيل",
        "Bassel" to "باسل",
        "Rana" to "رنا",
        "Sana" to "سناء",
        "Taim" to "تيم",
        "Fahed" to "فهد",
        "Noura" to "نورة",
        "Layla" to "ليلى",
        "Rami" to "رامي",
        "Iman" to "إيمان",
        "Omar" to "عمر",
        "Jamal" to "جمال",
        "Mouna" to "منى",
        "Abdullah" to "عبد الله",
        "Aysha" to "عائشة",
        "Amal" to "أمل",
        "Moaz" to "معاذ",
        "Amany" to "أماني",
        "Laith" to "ليث",
        "Hedi" to "الهادي",
        "Reem" to "ريم",
        "Maryam" to "مريم",
        "Saleh" to "صالح",
        "Ali" to "علي",
        "Laila" to "ليلى"
    )

    fun getItems(context: Context): List<MicrosoftCatalogItem> {
        cachedItems?.let { return it }
        synchronized(this) {
            cachedItems?.let { return it }
            val loaded = loadFromAssets(context)
            cachedItems = loaded
            return loaded
        }
    }

    private fun loadFromAssets(context: Context): List<MicrosoftCatalogItem> {
        return try {
            val stream: InputStream = runCatching {
                context.assets.open(ASSET_PATH)
            }.getOrNull() ?: javaClass.classLoader
                ?.getResourceAsStream("assets/$ASSET_PATH")
                ?: return emptyList()

            val jsonText = stream.use {
                String(it.readBytes(), StandardCharsets.UTF_8)
            }
            parseJson(jsonText)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load Microsoft voices catalog", t)
            emptyList()
        }
    }

    fun parseJson(jsonText: String): List<MicrosoftCatalogItem> {
        val array = JSONArray(jsonText)
        val list = ArrayList<MicrosoftCatalogItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val item = parseItem(obj)
            if (item != null) list.add(item)
        }
        return list
    }

    private fun parseItem(obj: JSONObject): MicrosoftCatalogItem? {
        val id = obj.optString("id", "")
        if (id.isBlank()) return null
        val name = obj.optString("name", "")
        val langTag = obj.optString("language", "")
        val locale = Locale.forLanguageTag(langTag)
        val langCategory = locale.language.lowercase()
        val gender = obj.optString("gender", "Female")
        val modelName = obj.optString("modelName", "")
        val voiceUrl = obj.optString("voiceUrl", "")
        val sampleUrl = obj.optString("sampleUrl", "").takeIf {
            it.isNotBlank()
        }
        val sizeBytes = obj.optLong("archiveSize", 0L)
        val sha256 = obj.optString("archiveSha256", "")

        val filesArr = obj.optJSONArray("files")
        val firstFile = filesArr?.optJSONObject(0)?.optString("path", "")
        val topDir = firstFile?.split('/')?.firstOrNull()
            ?.takeIf { it.isNotBlank() } ?: name

        val country = locale.country.uppercase()
        val arCountry = AR_COUNTRIES[country] ?: locale.displayCountry
        val arName = AR_NAMES[name] ?: name

        val displayNameAr = "$arName ($arCountry)"
        val countryLabel = locale.displayCountry.ifBlank { country }
        val displayNameEn = "$name ($countryLabel)"

        return MicrosoftCatalogItem(
            id = id,
            voiceName = name,
            displayNameAr = displayNameAr,
            displayNameEn = displayNameEn,
            locale = locale,
            languageCategory = langCategory,
            gender = gender,
            modelName = modelName,
            downloadUrl = voiceUrl,
            sampleUrl = sampleUrl,
            sizeBytes = sizeBytes,
            archiveSha256 = sha256,
            topDirName = topDir,
            quality = "Neural"
        )
    }

    fun isVoiceInstalled(
        baseDir: File,
        item: MicrosoftCatalogItem
    ): Boolean = findInstalledVoiceDir(baseDir, item) != null

    fun findInstalledVoiceDir(
        baseDir: File,
        item: MicrosoftCatalogItem
    ): File? {
        if (!baseDir.exists() || !baseDir.isDirectory) return null
        val dir = File(baseDir, item.topDirName)
        if (File(dir, item.indicatorFileName).exists()) return dir
        val altDir = File(baseDir, item.id)
        if (File(altDir, item.indicatorFileName).exists()) return altDir
        val nameDir = File(baseDir, item.voiceName)
        if (File(nameDir, item.indicatorFileName).exists()) return nameDir

        val allDirs = MicrosoftVoiceManifest.completeVoiceDirs(baseDir)
        return allDirs.firstOrNull { d ->
            d.name.equals(item.topDirName, ignoreCase = true) ||
                d.name.equals(item.id, ignoreCase = true) ||
                d.name.equals(item.voiceName, ignoreCase = true) ||
                d.name.contains(item.topDirName, ignoreCase = true) ||
                d.name.startsWith(
                    "${item.voiceName}-",
                    ignoreCase = true
                )
        }
    }

    fun findItemByNameOrModel(
        context: Context,
        query: String
    ): MicrosoftCatalogItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val items = getItems(context)
        return items.firstOrNull {
            it.voiceName.equals(q, ignoreCase = true) ||
                it.displayNameAr.equals(q, ignoreCase = true) ||
                it.displayNameEn.equals(q, ignoreCase = true) ||
                it.id.equals(q, ignoreCase = true) ||
                it.modelName.equals(q, ignoreCase = true) ||
                it.topDirName.equals(q, ignoreCase = true) ||
                q.contains(it.voiceName, ignoreCase = true) ||
                q.contains(it.displayNameAr, ignoreCase = true) ||
                q.contains(it.displayNameEn, ignoreCase = true) ||
                q.contains(it.topDirName, ignoreCase = true)
        }
    }
}
