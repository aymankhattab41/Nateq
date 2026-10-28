package com.aymankhattab.nateq.core.audio.engine

import android.util.Log
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.util.LanguageCode


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

        val categoryVoice = when (role) {
            SpeechRole.EMOJI -> runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull()
            SpeechRole.NUMBER -> runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull()
            SpeechRole.TEXT -> null
        }

        val langVoice = runCatching {
            settings?.getVoiceForLanguage(languageTag)
                ?: settings?.getVoiceForLanguage(canonicalTag)
        }.getOrNull()

        val categoryEngine = when (role) {
            SpeechRole.EMOJI -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull()
            SpeechRole.NUMBER -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull()
            SpeechRole.TEXT -> null
        }

        val resolved = VoiceResolutionPolicy.resolve(
            role = role,
            languageTag = languageTag,
            categoryVoice = categoryVoice,
            appVoice = defaultVoiceId,
            contactVoice = null,
            languageDefaultVoice = langVoice,
            categoryEngine = categoryEngine
        )

        val targetEngine = when (resolved.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull() ?: defaultEngine
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull() ?: defaultEngine
            else -> {
                val langEngine = runCatching {
                    settings?.getEngineForLanguage(languageTag)
                        ?: settings?.getEngineForLanguage(canonicalTag)
                }.getOrNull()
                defaultEngine ?: langEngine
            }
        }

        val rate = when (resolved.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI -> runCatching {
                settings?.getSpeechRateForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull() ?: defaultRate
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER_PARAMS -> runCatching {
                settings?.getSpeechRateForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull() ?: defaultRate
            else -> defaultRate
        }

        val pitch = when (resolved.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI -> runCatching {
                settings?.getPitchForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull() ?: defaultPitch
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER_PARAMS -> runCatching {
                settings?.getPitchForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull() ?: defaultPitch
            else -> defaultPitch
        }

        val volume = when (resolved.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI -> runCatching {
                settings?.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            }.getOrNull() ?: defaultVolume
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER_PARAMS -> runCatching {
                settings?.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            }.getOrNull() ?: defaultVolume
            else -> defaultVolume
        }

        return VoiceResolution(
            engine = targetEngine,
            voiceId = resolved.voiceId,
            rate = rate,
            pitch = pitch,
            volume = volume
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

        @Suppress("UNUSED_VARIABLE")
        val unusedDict = dictionary

        val emojiSetting = runCatching {
            settings?.getEmojiPronunciationLanguage()
        }.getOrNull()
        val emojiVoice = runCatching {
            settings?.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrNull()
        val resolvedEmojiLang = EmojiLanguageResolver.resolve(
            emojiSetting = emojiSetting,
            contextLanguage = fallbackLanguage,
            emojiVoiceId = emojiVoice,
            fallbackLanguage = fallbackLanguage
        )
        val isArabicEmoji = LanguageCode.isArabic(resolvedEmojiLang)

        // خطوة 2: تقسيم الإيموجي إن كان مفعلاً
        val parts = if (emojiPronunciationEnabled) {
            runCatching {
                EmojiSpeech.split(rawText, isArabicEmoji)
            }.getOrDefault(listOf(SpeechPart(rawText, false)))
        } else {
            listOf(SpeechPart(rawText, false))
        }

        val activeSegmenter = segmenter ?: LanguageSegmenter { numberLanguage }
        val diagnostics = runCatching {
            settings?.isLanguageDiagnosticsEnabled() ?: false
        }.getOrDefault(false)
        LanguageSegmenter.diagnosticsEnabled = diagnostics
        LatinLanguageDetector.diagnosticsEnabled = diagnostics

        val items = ArrayList<SpeechPlanItem>()

        for (part in parts) {
            if (part.isEmojiName) {
                val emojiLang = resolvedEmojiLang
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
                val cleanEmoji = part.text
                    .replace(EngineMarkers.NUMBER_MARK_START, "")
                    .replace(EngineMarkers.NUMBER_MARK_END, "")
                items.add(
                    SpeechPlanItem(
                        text = cleanEmoji,
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
                    val isNumCandidate = seg.role == SpeechRole.NUMBER ||
                        seg.isNumericOnly()
                    val textRes = resolveVoiceForSegment(
                        languageTag = seg.languageTag,
                        role = SpeechRole.TEXT,
                        settings = settings,
                        defaultEngine = defaultEngine,
                        defaultVoiceId = defaultVoiceId,
                        defaultRate = defaultRate,
                        defaultPitch = defaultPitch,
                        defaultVolume = defaultVolume
                    )
                    val numRes = if (isNumCandidate) {
                        resolveVoiceForSegment(
                            languageTag = seg.languageTag,
                            role = SpeechRole.NUMBER,
                            settings = settings,
                            defaultEngine = defaultEngine,
                            defaultVoiceId = defaultVoiceId,
                            defaultRate = defaultRate,
                            defaultPitch = defaultPitch,
                            defaultVolume = defaultVolume
                        )
                    } else {
                        textRes
                    }

                    val role = if (isNumCandidate && numRes != textRes) {
                        SpeechRole.NUMBER
                    } else {
                        SpeechRole.TEXT
                    }
                    val resolved = if (role == SpeechRole.NUMBER) {
                        numRes
                    } else {
                        textRes
                    }

                    val cleanText = seg.text
                        .replace(EngineMarkers.NUMBER_MARK_START, "")
                        .replace(EngineMarkers.NUMBER_MARK_END, "")
                    val isDynamicRate = (settings as? SettingsRepository)
                        ?.isExtraEnabled("extra_dynamic_rate") == true
                    val effectiveRate = if (isDynamicRate) {
                        DynamicRatePolicy.apply(
                            baseRate = resolved.rate,
                            textLength = cleanText.length,
                            role = role,
                            isEnabled = true
                        )
                    } else {
                        resolved.rate
                    }
                    items.add(
                        SpeechPlanItem(
                            text = cleanText,
                            languageTag = seg.languageTag,
                            role = role,
                            engine = resolved.engine,
                            voiceId = resolved.voiceId,
                            rate = effectiveRate,
                            pitch = resolved.pitch,
                            volume = resolved.volume
                        )
                    )
                }
            }
        }

        // دمج المقاطع المتجاورة التي تتطابق كل إعداداتها (الجولة 50 - الأمر 3)
        val mergedItems = ArrayList<SpeechPlanItem>()
        for (item in items) {
            val last = mergedItems.lastOrNull()
            if (last != null &&
                last.role == item.role &&
                last.engine == item.engine &&
                last.voiceId == item.voiceId &&
                last.languageTag == item.languageTag &&
                last.rate == item.rate &&
                last.pitch == item.pitch &&
                last.volume == item.volume
            ) {
                val mergedText = when {
                    last.text.isEmpty() -> item.text
                    item.text.isEmpty() -> last.text
                    else -> "${last.text} ${item.text}"
                }
                mergedItems[mergedItems.size - 1] = last.copy(
                    text = mergedText
                )
            } else {
                mergedItems.add(item)
            }
        }

        // تقسيم أرقام الهاتف الطويلة (> 40 حرفاً بعد تحويلها إلى كلمات)
        // إلى مجموعات من 3 إلى 4 أرقام لتخرج بالترتيب فور جاهزيتها
        // (الجولة 50 - الأمر 3 البند 5)
        val finalItems = ArrayList<SpeechPlanItem>()
        for (item in mergedItems) {
            val chunks = splitPhoneNumberWords(item.text)
            if (chunks.size > 1) {
                for (chunk in chunks) {
                    finalItems.add(item.copy(text = chunk))
                }
            } else {
                finalItems.add(item)
            }
        }

        // خطوة 5: تسجيل debug تفصيلي لكل مقطع (ع٤٢-8)
        if (diagnostics) {
            for (item in finalItems) {
                val preview = if (item.text.length > 25) {
                    item.text.take(25) + "…"
                } else {
                    item.text
                }
                Log.d(
                    "NateqLang",
                    "[SpeechPlanner] seg: text='$preview', " +
                        "tag='${item.languageTag}', role=${item.role}, " +
                        "engine='${item.engine}', voice='${item.voiceId}'"
                )
            }
        }

        return finalItems
    }

    private val DIGIT_WORDS = setOf(
        "صفر", "واحد", "اثنان", "اثنين", "ثلاثة", "أربعة", "خمسة",
        "ستة", "سبعة", "ثمانية", "تسعة", "عشرة",
        "zero", "one", "two", "three", "four", "five",
        "six", "seven", "eight", "nine", "ten", "plus", "موجب"
    )

    private fun isDigitWord(word: String): Boolean {
        val clean = word.trim().lowercase().removePrefix("+")
        return clean.all { it.isDigit() || it in '\u0660'..'\u0669' } ||
            clean in DIGIT_WORDS
    }

    internal fun splitPhoneNumberWords(text: String): List<String> {
        if (text.length <= 40) return listOf(text)
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size < 6) return listOf(text)
        val digitWordCount = words.count { isDigitWord(it) }
        if (digitWordCount < words.size * 0.7) return listOf(text)

        val result = ArrayList<String>()
        val chunkSize = if (words.size % 4 == 0) 4 else 3
        for (group in words.chunked(chunkSize)) {
            result.add(group.joinToString(" "))
        }
        return result
    }
}
