package com.aymankhattab.nateq.core.audio.engine

import android.util.Log
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.util.LanguageCode

/**
 * دور المقطع في خطة النطق الموحدة:
 * نص عادي، أو رقم، أو اسم إيموجي (بند ع٤٢-9).
 */
enum class SpeechRole {
    TEXT,
    NUMBER,
    EMOJI
}

/**
 * بند نطق فردي داخل خطة النطق الموحدة:
 * يحمل النص، والوسم اللغوي، والدور، والصوت/المحرك ومعاملات النطق المحلولة.
 */
data class SpeechPlanItem(
    val text: String,
    val languageTag: String,
    val role: SpeechRole,
    val engine: String?,
    val voiceId: String?,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f
)

/**
 * نتيجة حل الصوت والمحرك والأشرطة لمقطع محدد.
 */
data class VoiceResolution(
    val engine: String?,
    val voiceId: String?,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f
)

/**
 * مخطط النطق الموحّد المشترك بين مسار الخدمة ومسار الإعلانات (ع٤٢-8 و ع٤٢-9).
 * يضمن اتساق الأصوات والأدوار وتطبيق القاموس أولاً وحل الإيموجي والأرقام.
 */
object SpeechPlanner {
    private const val TAG = "SpeechPlanner"

    /**
     * حلّ الصوت في دالة واحدة مشتركة لكل المسارات (الخدمة والإعلانات).
     * يُعاد بها نفس المحرك والصوت لكل مقاطع العائلة الواحدة (ع٤٢-8).
     */
    fun resolveVoiceForSegment(
        languageTag: String,
        role: SpeechRole,
        settings: VoicePrefsProvider?,
        defaultEngine: String? = null,
        defaultVoiceId: String? = null,
        defaultRate: Float = 1.0f,
        defaultPitch: Float = 1.0f,
        defaultVolume: Float = 1.0f
    ): VoiceResolution {
        val canonicalTag = when {
            LanguageCode.isArabic(languageTag) -> LanguageCode.AR.tag
            LanguageCode.isEnglish(languageTag) -> LanguageCode.EN.tag
            else -> languageTag.substringBefore('-')
                .substringBefore('_').trim().lowercase()
        }

        // 1. دور الإيموجي: فئة الإيموجي أولاً، فإن لم تُضبط فبصوت النص
        if (role == SpeechRole.EMOJI) {
            val emojiVoice = runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull()
            if (emojiVoice != null) {
                val emojiEngine = runCatching {
                    settings?.getEngineForCategory(
                        SettingsRepository.VOICE_CATEGORY_EMOJI
                    )
                }.getOrNull()
                val rate = runCatching {
                    settings?.getSpeechRateForCategory(
                        SettingsRepository.VOICE_CATEGORY_EMOJI
                    )
                }.getOrNull() ?: defaultRate
                val pitch = runCatching {
                    settings?.getPitchForCategory(
                        SettingsRepository.VOICE_CATEGORY_EMOJI
                    )
                }.getOrNull() ?: defaultPitch
                val vol = runCatching {
                    settings?.getVolumeForCategory(
                        SettingsRepository.VOICE_CATEGORY_EMOJI
                    )
                }.getOrNull() ?: defaultVolume
                return VoiceResolution(
                    engine = emojiEngine ?: defaultEngine,
                    voiceId = emojiVoice,
                    rate = rate,
                    pitch = pitch,
                    volume = vol
                )
            }
        }

        // 2. دور الأرقام: فئة الأرقام أولاً، فإن لم تُضبط فبصوت النص
        if (role == SpeechRole.NUMBER) {
            val numVoice = runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull()
            if (numVoice != null) {
                val numEngine = runCatching {
                    settings?.getEngineForCategory(
                        SettingsRepository.VOICE_CATEGORY_NUMBERS
                    )
                }.getOrNull()
                val rate = runCatching {
                    settings?.getSpeechRateForCategory(
                        SettingsRepository.VOICE_CATEGORY_NUMBERS
                    )
                }.getOrNull() ?: defaultRate
                val pitch = runCatching {
                    settings?.getPitchForCategory(
                        SettingsRepository.VOICE_CATEGORY_NUMBERS
                    )
                }.getOrNull() ?: defaultPitch
                val vol = runCatching {
                    settings?.getVolumeForCategory(
                        SettingsRepository.VOICE_CATEGORY_NUMBERS
                    )
                }.getOrNull() ?: defaultVolume
                return VoiceResolution(
                    engine = numEngine ?: defaultEngine,
                    voiceId = numVoice,
                    rate = rate,
                    pitch = pitch,
                    volume = vol
                )
            }
        }

        // 3. النص العادي (أو سقوط الإيموجي/الأرقام): صوت اللغة الصريح
        // مع التوحيد على مستوى العائلة اللغوية (ar/en) لمنع تشتت الأصوات
        val langEngine = runCatching {
            settings?.getEngineForLanguage(languageTag)
                ?: settings?.getEngineForLanguage(canonicalTag)
        }.getOrNull()

        val langVoice = runCatching {
            settings?.getVoiceForLanguage(languageTag)
                ?: settings?.getVoiceForLanguage(canonicalTag)
        }.getOrNull()

        return VoiceResolution(
            engine = defaultEngine ?: langEngine,
            voiceId = defaultVoiceId ?: langVoice,
            rate = defaultRate,
            pitch = defaultPitch,
            volume = defaultVolume
        )
    }

    /**
     * يخطط النطق بالكامل:
     * 1. تطبيق القاموس أولاً على النص الخام (لحماية مخصصات الإيموجي).
     * 2. تقسيم الإيموجي إن كان مفعلاً.
     * 3. تقسيم المقاطع اللغوية والأرقام عبر LanguageSegmenter.
     * 4. حل الأصوات والمحركات موحداً.
     * 5. تسجيل debug تفصيلي لكل مقطع.
     */
    fun plan(
        rawText: String,
        fallbackLanguage: String = LanguageCode.AR.tag,
        secondaryLanguage: String = LanguageCode.EN.tag,
        numberLanguage: String = LanguageCode.AR.tag,
        emojiPronunciationEnabled: Boolean = false,
        dictionary: PronunciationDictionary? = null,
        settings: VoicePrefsProvider? = null,
        defaultEngine: String? = null,
        defaultVoiceId: String? = null,
        defaultRate: Float = 1.0f,
        defaultPitch: Float = 1.0f,
        defaultVolume: Float = 1.0f,
        segmenter: LanguageSegmenter? = null
    ): List<SpeechPlanItem> {
        if (rawText.isEmpty()) return emptyList()

        // خطوة 1: القاموس يطبَّق أولاً على النص الخام قبل توسيع الإيموجي
        val dictText = if (dictionary != null) {
            runCatching {
                dictionary.apply(rawText, fallbackLanguage)
            }.getOrDefault(rawText)
        } else {
            rawText
        }

        val isArabicContext = LanguageCode.isArabic(fallbackLanguage)

        // خطوة 2: تقسيم الإيموجي إن كان مفعلاً
        val parts = if (emojiPronunciationEnabled) {
            runCatching {
                EmojiSpeech.split(dictText, isArabicContext)
            }.getOrDefault(listOf(SpeechPart(dictText, false)))
        } else {
            listOf(SpeechPart(dictText, false))
        }

        val activeSegmenter = segmenter ?: LanguageSegmenter { numberLanguage }
        val items = ArrayList<SpeechPlanItem>()

        for (part in parts) {
            if (part.isEmojiName) {
                val emojiLang = if (isArabicContext) {
                    LanguageCode.AR.tag
                } else {
                    LanguageCode.EN.tag
                }
                val resolved = resolveVoiceForSegment(
                    languageTag = emojiLang,
                    role = SpeechRole.EMOJI,
                    settings = settings,
                    defaultEngine = defaultEngine,
                    defaultVoiceId = defaultVoiceId,
                    defaultRate = defaultRate,
                    defaultPitch = defaultPitch,
                    defaultVolume = defaultVolume
                )
                items.add(
                    SpeechPlanItem(
                        text = part.text,
                        languageTag = emojiLang,
                        role = SpeechRole.EMOJI,
                        engine = resolved.engine,
                        voiceId = resolved.voiceId,
                        rate = resolved.rate,
                        pitch = resolved.pitch,
                        volume = resolved.volume
                    )
                )
            } else {
                val segs = activeSegmenter.segment(
                    text = part.text,
                    fallbackLanguage = fallbackLanguage,
                    secondaryLanguage = secondaryLanguage,
                    numberLanguage = numberLanguage
                )
                for (seg in segs) {
                    val role = if (seg.isNumericOnly()) {
                        SpeechRole.NUMBER
                    } else {
                        SpeechRole.TEXT
                    }
                    val resolved = resolveVoiceForSegment(
                        languageTag = seg.languageTag,
                        role = role,
                        settings = settings,
                        defaultEngine = defaultEngine,
                        defaultVoiceId = defaultVoiceId,
                        defaultRate = defaultRate,
                        defaultPitch = defaultPitch,
                        defaultVolume = defaultVolume
                    )
                    items.add(
                        SpeechPlanItem(
                            text = seg.text,
                            languageTag = seg.languageTag,
                            role = role,
                            engine = resolved.engine,
                            voiceId = resolved.voiceId,
                            rate = resolved.rate,
                            pitch = resolved.pitch,
                            volume = resolved.volume
                        )
                    )
                }
            }
        }

        // خطوة 5: تسجيل debug تفصيلي لكل مقطع (ع٤٢-8)
        for (item in items) {
            val preview = if (item.text.length > 25) {
                item.text.take(25) + "…"
            } else {
                item.text
            }
            Log.d(
                TAG,
                "[SpeechPlanner] seg: text='$preview', " +
                    "tag='${item.languageTag}', role=${item.role}, " +
                    "engine='${item.engine}', voice='${item.voiceId}'"
            )
        }

        return items
    }
}
