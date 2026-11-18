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
            SpeechRole.DIALOGUE -> runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_DIALOGUE
                )
            }.getOrNull()
            SpeechRole.NARRATION -> runCatching {
                settings?.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_NARRATION
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
            SpeechRole.DIALOGUE -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_DIALOGUE
                )
            }.getOrNull()
            SpeechRole.NARRATION -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NARRATION
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
            VoiceResolutionPolicy.SOURCE_CATEGORY_DIALOGUE -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_DIALOGUE
                )
            }.getOrNull() ?: defaultEngine
            VoiceResolutionPolicy.SOURCE_CATEGORY_NARRATION -> runCatching {
                settings?.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NARRATION
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

        val categoryForSource = when (resolved.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI ->
                SettingsRepository.VOICE_CATEGORY_EMOJI
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER_PARAMS ->
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            VoiceResolutionPolicy.SOURCE_CATEGORY_DIALOGUE ->
                SettingsRepository.VOICE_CATEGORY_DIALOGUE
            VoiceResolutionPolicy.SOURCE_CATEGORY_NARRATION ->
                SettingsRepository.VOICE_CATEGORY_NARRATION
            else -> null
        }

        val rate = if (categoryForSource != null) {
            if (settings?.hasExplicitSpeechRateForCategory(
                    categoryForSource
                ) == true
            ) {
                settings?.getSpeechRateForCategory(categoryForSource)
                    ?: defaultRate
            } else {
                defaultRate
            }
        } else {
            val langRate = settings?.getSpeechRateOrNull(languageTag)
                ?: settings?.getSpeechRateOrNull(canonicalTag)
            langRate ?: defaultRate
        }

        val pitch = if (categoryForSource != null) {
            if (settings?.hasExplicitPitchForCategory(
                    categoryForSource
                ) == true
            ) {
                settings?.getPitchForCategory(categoryForSource)
                    ?: defaultPitch
            } else {
                defaultPitch
            }
        } else {
            val langPitch = settings?.getPitchOrNull(languageTag)
                ?: settings?.getPitchOrNull(canonicalTag)
            langPitch ?: defaultPitch
        }

        val volume = if (categoryForSource != null) {
            if (settings?.hasExplicitVolumeForCategory(
                    categoryForSource
                ) == true
            ) {
                settings?.getVolumeForCategory(categoryForSource)
                    ?: defaultVolume
            } else {
                defaultVolume
            }
        } else {
            val langVol = settings?.getVolumeOrNull(languageTag)
                ?: settings?.getVolumeOrNull(canonicalTag)
            langVol ?: defaultVolume
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
        val multiVoiceEnabled = runCatching {
            settings?.isMultiVoiceReaderEnabled() == true
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
                    val cleanSegText = seg.text
                        .replace(EngineMarkers.NUMBER_MARK_START, "")
                        .replace(EngineMarkers.NUMBER_MARK_END, "")
                    if (isNumCandidate) {
                        val numRes = resolveVoiceForSegment(
                            languageTag = seg.languageTag,
                            role = SpeechRole.NUMBER,
                            settings = settings,
                            defaultEngine = defaultEngine,
                            defaultVoiceId = defaultVoiceId,
                            defaultRate = defaultRate,
                            defaultPitch = defaultPitch,
                            defaultVolume = defaultVolume
                        )
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
                        val role = if (numRes != textRes) {
                            SpeechRole.NUMBER
                        } else {
                            SpeechRole.TEXT
                        }
                        val resolved = if (role == SpeechRole.NUMBER) {
                            numRes
                        } else {
                            textRes
                        }
                        Log.i(
                            TAG,
                            "[SpeechRouting] تخطيط المقطع: النص='$cleanSegText', " +
                                "اللغة='${seg.languageTag}', " +
                                "المحرك='${resolved.engine ?: "default"}', " +
                                "الصوت='${resolved.voiceId ?: "default"}'"
                        )
                        items.add(
                            SpeechPlanItem(
                                text = cleanSegText,
                                languageTag = seg.languageTag,
                                role = role,
                                engine = resolved.engine,
                                voiceId = resolved.voiceId,
                                rate = resolved.rate,
                                pitch = resolved.pitch,
                                volume = resolved.volume
                            )
                        )
                    } else if (multiVoiceEnabled) {
                        val roleChunks = splitDialogueNarration(cleanSegText)
                        for ((chunkRole, chunkText) in roleChunks) {
                            if (chunkText.isBlank()) continue
                            val resolved = resolveVoiceForSegment(
                                languageTag = seg.languageTag,
                                role = chunkRole,
                                settings = settings,
                                defaultEngine = defaultEngine,
                                defaultVoiceId = defaultVoiceId,
                                defaultRate = defaultRate,
                                defaultPitch = defaultPitch,
                                defaultVolume = defaultVolume
                            )
                            Log.i(
                                TAG,
                                "[SpeechRouting] تخطيط المقطع: النص='$chunkText', " +
                                    "اللغة='${seg.languageTag}', " +
                                    "الدور='$chunkRole', " +
                                    "المحرك='${resolved.engine ?: "default"}', " +
                                    "الصوت='${resolved.voiceId ?: "default"}'"
                            )
                            items.add(
                                SpeechPlanItem(
                                    text = chunkText,
                                    languageTag = seg.languageTag,
                                    role = chunkRole,
                                    engine = resolved.engine,
                                    voiceId = resolved.voiceId,
                                    rate = resolved.rate,
                                    pitch = resolved.pitch,
                                    volume = resolved.volume
                                )
                            )
                        }
                    } else {
                        val resolved = resolveVoiceForSegment(
                            languageTag = seg.languageTag,
                            role = SpeechRole.TEXT,
                            settings = settings,
                            defaultEngine = defaultEngine,
                            defaultVoiceId = defaultVoiceId,
                            defaultRate = defaultRate,
                            defaultPitch = defaultPitch,
                            defaultVolume = defaultVolume
                        )
                        Log.i(
                            TAG,
                            "[SpeechRouting] تخطيط المقطع: النص='$cleanSegText', " +
                                "اللغة='${seg.languageTag}', " +
                                "المحرك='${resolved.engine ?: "default"}', " +
                                "الصوت='${resolved.voiceId ?: "default"}'"
                        )
                        items.add(
                            SpeechPlanItem(
                                text = cleanSegText,
                                languageTag = seg.languageTag,
                                role = SpeechRole.TEXT,
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
        }

        // دمج المقاطع المتجاورة التي تتطابق كل إعداداتها (الجولة 50 - الأمر 3)
        // مع عدم دمج أدوار الحوار/السرد المختلفة في القارئ متعدد الأصوات.
        val mergedItems = ArrayList<SpeechPlanItem>()
        for (item in items) {
            val last = mergedItems.lastOrNull()
            val rolesCompatible = last?.role == item.role ||
                (last?.role != SpeechRole.EMOJI &&
                    item.role != SpeechRole.EMOJI &&
                    last?.role != SpeechRole.DIALOGUE &&
                    item.role != SpeechRole.DIALOGUE &&
                    last?.role != SpeechRole.NARRATION &&
                    item.role != SpeechRole.NARRATION)
            if (last != null &&
                rolesCompatible &&
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
        val phoneSplitItems = ArrayList<SpeechPlanItem>()
        for (item in mergedItems) {
            val chunks = splitPhoneNumberWords(item.text)
            if (chunks.size > 1) {
                for (chunk in chunks) {
                    phoneSplitItems.add(item.copy(text = chunk))
                }
            } else {
                phoneSplitItems.add(item)
            }
        }

        // تقسيم المقاطع الطويلة (> 220 حرفاً) عند الحدود اللغوية
        // أو الفواصل لتسريع زمن أول شريحة صوتية وتفادي تأخير وبطء الجمل الطويلة
        val splitItems = ArrayList<SpeechPlanItem>()
        for (item in phoneSplitItems) {
            if (item.text.length > 220) {
                val sentenceChunks = splitLongSentenceChunks(item.text, 220)
                if (sentenceChunks.size > 1) {
                    for (chunk in sentenceChunks) {
                        splitItems.add(item.copy(text = chunk))
                    }
                } else {
                    splitItems.add(item)
                }
            } else {
                splitItems.add(item)
            }
        }

        val dynamicSpeedEnabled = runCatching {
            settings?.isDynamicSpeedEnabled() == true
        }.getOrDefault(false)
        val finalItems = if (dynamicSpeedEnabled) {
            val adjusted = ArrayList<SpeechPlanItem>(splitItems.size)
            for (item in splitItems) {
                val factor = dynamicSpeedFactor(item)
                adjusted.add(item.copy(rate = item.rate * factor))
            }
            adjusted
        } else {
            splitItems
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
    private val WHITESPACE_REGEX = Regex("\\s+")

    private fun isDigitWord(word: String): Boolean {
        val clean = word.trim().lowercase().removePrefix("+")
        return clean.all { it.isDigit() || it in '\u0660'..'\u0669' } ||
            clean in DIGIT_WORDS
    }

    internal fun splitPhoneNumberWords(text: String): List<String> {
        if (text.length <= 40) return listOf(text)
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
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

    /** تقسيم تقريبي إلى حوار/سرد حسب علامات الاقتباس والشرطات (إرشادي). */
    internal fun splitDialogueNarration(
        text: String
    ): List<Pair<SpeechRole, String>> {
        if (text.isBlank()) return listOf(SpeechRole.NARRATION to text)
        val result = ArrayList<Pair<SpeechRole, String>>()
        val sb = StringBuilder()
        var currentRole = SpeechRole.NARRATION
        var inQuote = false
        var quoteChar: Char? = null
        var i = 0
        fun flush() {
            if (sb.isNotEmpty()) {
                val chunk = sb.toString()
                if (chunk.isNotBlank()) {
                    result.add(currentRole to chunk.trim())
                }
                sb.setLength(0)
            }
        }

        // سطر يبدأ بشرطة حوار يُعامل كحوار كامل (إرشادي)
        val trimmedStart = text.trimStart()
        if (trimmedStart.isNotEmpty() &&
            isDialogueDash(trimmedStart.first())
        ) {
            return listOf(SpeechRole.DIALOGUE to text.trim())
        }

        while (i < text.length) {
            val c = text[i]
            when {
                isOpeningQuote(c) -> {
                    if (!inQuote) {
                        flush()
                        inQuote = true
                        quoteChar = matchingCloseQuote(c)
                        currentRole = SpeechRole.DIALOGUE
                        sb.append(c)
                    } else {
                        sb.append(c)
                    }
                }
                inQuote && c == quoteChar -> {
                    sb.append(c)
                    flush()
                    inQuote = false
                    quoteChar = null
                    currentRole = SpeechRole.NARRATION
                }
                inQuote && isAnyClosingQuote(c) && c != quoteChar -> {
                    // إغلاق غير متوقع، أغلِق الحوار
                    sb.append(c)
                    flush()
                    inQuote = false
                    quoteChar = null
                    currentRole = SpeechRole.NARRATION
                }
                c == '\n' -> {
                    // شرطة حوار بعد سطر جديد
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace() && text[j] != '\n') j++
                    if (j < text.length && isDialogueDash(text[j])) {
                        flush()
                        inQuote = false
                        quoteChar = null
                        currentRole = SpeechRole.DIALOGUE
                        sb.append(c)
                    } else {
                        if (inQuote) {
                            sb.append(c)
                        } else {
                            sb.append(c)
                        }
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        flush()
        if (result.isEmpty()) {
            return listOf(SpeechRole.NARRATION to text.trim())
        }
        return result
    }

    private fun isOpeningQuote(c: Char): Boolean =
        c == '«' || c == '"' || c == '\u201C' || c == '\u201E' ||
            c == '‹' || c == '\u300C'

    private fun matchingCloseQuote(c: Char): Char = when (c) {
        '«' -> '»'
        '‹' -> '›'
        '\u300C' -> '\u300D'
        '"' -> '"'
        '\u201C', '\u201E' -> '\u201D'
        else -> c
    }

    private fun isAnyClosingQuote(c: Char): Boolean =
        c == '»' || c == '"' || c == '\u201D' || c == '›' || c == '\u300D'

    private fun isDialogueDash(c: Char): Boolean =
        c == '—' || c == '–' || c == 'ـ' || c == '-'

    internal fun splitLongSentenceChunks(
        text: String,
        maxChars: Int = 220
    ): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = ArrayList<String>()
        val delimiters = setOf('.', '!', '?', '…', '؛', '؟', '\n', '\r')
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= maxChars) {
                val lastPart = text.substring(start).trim()
                if (lastPart.isNotEmpty()) result.add(lastPart)
                break
            }
            var cut = -1
            val searchLimit = (start + maxChars).coerceAtMost(text.length)
            for (i in (start + 60) until searchLimit) {
                if (text[i] in delimiters) {
                    cut = i + 1
                }
            }
            if (cut == -1) {
                val lastSpace = text.lastIndexOf(' ', searchLimit - 1)
                if (lastSpace > start + 60) {
                    cut = lastSpace + 1
                } else {
                    cut = searchLimit
                }
            }
            val chunk = text.substring(start, cut).trim()
            if (chunk.isNotEmpty()) {
                result.add(chunk)
            }
            start = cut
            while (start < text.length && text[start] == ' ') {
                start++
            }
        }
        return if (result.isEmpty()) listOf(text) else result
    }

    /**
     * معامل سرعة ديناميكي لكل مقطع (قصير/أرقام أبطأ، طويل أسرع) بحدود آمنة.
     * يُطبَّق فقط عند تفعيل السرعة الديناميكية ويتجاوز تسريع النص الطويل.
     */
    internal fun dynamicSpeedFactor(item: SpeechPlanItem): Float {
        if (item.role == SpeechRole.NUMBER) return 0.9f
        val text = item.text
        val len = text.length
        if (len == 0) return 1.0f
        var letters = 0
        var digits = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                Character.isLetter(cp) -> letters++
                Character.isDigit(cp) -> digits++
            }
            i += Character.charCount(cp)
        }
        val total = letters + digits
        val digitRatio = if (total > 0) digits.toFloat() / total else 0f
        if (digitRatio >= 0.5f) return 0.9f
        val factor = when {
            len <= 25 -> 0.92f
            len <= 60 -> 0.97f
            len <= 120 -> 1.0f
            len <= 220 -> 1.05f
            len <= 400 -> 1.10f
            else -> 1.15f
        }
        return factor.coerceIn(0.85f, 1.20f)
    }
}
