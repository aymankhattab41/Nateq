package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs

/**
 * [LanguagePrefs] — نطاق لغة التطبيق ولغة نطق الإعلانات (البند 4).
 * ينفّذها SettingsRepository.
 */
interface LanguagePrefs {

    /** لغة واجهة التطبيق (رمز مثل "ar" أو "en")، null = لغة النظام. */
    fun getAppLanguage(): String?

    /** يعيّن لغة واجهة التطبيق (null = لغة النظام). */
    fun setAppLanguage(language: String?)

    /** لغة نطق الإعلانات الصوتية، null = لغة التطبيق. */
    fun getAnnouncementSpeechLanguage(): String?

    /** يعيّن لغة نطق الإعلانات (null = لغة التطبيق). */
    fun setAnnouncementSpeechLanguage(language: String?)
}

/**
 * [SynthesisPrefs] — نطاق النطق والترجيع: السرعة/النبرة/مستوى الصوت
 * والأصوات المفضّلة لكل لغة، مع الافتراضيات. يتضمن [SynthesisConfig]
 * و[VoicePrefsProvider] ليكسو حاجات خط الإنتاج ومزوّدي الأصوات بنطاق واحد
 * (البند 4). ينفّذها SettingsRepository.
 */
interface SynthesisPrefs : SynthesisConfig, VoicePrefsProvider {

    /** الصوت المفضّل للغة معيّنة (معرّف صوت داخل المحرك). */
    fun getPreferredVoiceId(languageTag: String): String?

    /** يعيّن الصوت المفضّل للغة معيّنة. */
    fun setPreferredVoiceId(languageTag: String, voiceId: String)

    /** معدل الكلام للغة معيّنة (نسبة من الطبيعي). */
    fun getSpeechRate(languageTag: String): Float

    /** يعيّن معدل الكلام للغة معيّنة. */
    fun setSpeechRate(languageTag: String, rate: Float)

    /** درجة الصوت للغة معيّنة (نصف نغمة). */
    fun getPitch(languageTag: String): Float

    /** يعيّن درجة الصوت للغة معيّنة. */
    fun setPitch(languageTag: String, pitch: Float)

    /** مستوى الصوت للغة معيّنة (0..1). */
    fun getVolume(languageTag: String): Float

    /** يعيّن مستوى الصوت للغة معيّنة. */
    fun setVolume(languageTag: String, volume: Float)

    /** معدل الكلام للغة معيّنة أو null إن لم يُضبط صراحةً. */
    fun getSpeechRateOrNull(languageTag: String): Float?

    /** درجة الصوت للغة معيّنة أو null إن لم تُضبط صراحةً. */
    fun getPitchOrNull(languageTag: String): Float?

    /** مستوى الصوت للغة معيّنة أو null إن لم يُضبط صراحةً. */
    fun getVolumeOrNull(languageTag: String): Float?

    /** معدل الكلام الافتراضي (للغات بلا تفضيل صريح). */
    fun getDefaultSpeechRate(): Float

    /** يعيّن معدل الكلام الافتراضي. */
    fun setDefaultSpeechRate(rate: Float)

    /** درجة الصوت الافتراضية (للغات بلا تفضيل صريح). */
    fun getDefaultPitch(): Float

    /** يعيّن درجة الصوت الافتراضية. */
    fun setDefaultPitch(pitch: Float)

    /** مستوى الصوت الافتراضي (للغات بلا تفضيل صريح). */
    fun getDefaultVolume(): Float

    /** يعيّن مستوى الصوت الافتراضي. */
    fun setDefaultVolume(volume: Float)

    /** تفضيل محركك/صوتك/أشرطتك للغة في خارطة التحويل الديناميكية. */
    fun getEnginePreferenceForLanguage(languageTag: String): LanguageSpeechPrefs

    /** يعيّن تفضيل المحرك/الصوت/الأشرطة للغة في الخارطة الديناميكية
     *  (مدخل مساوٍ للافتراضيات يُحذف). */
    fun setEnginePreferenceForLanguage(
        languageTag: String,
        engine: String?,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        volume: Float
    )

    /** كل تفضيلات اللغات في خارطة التحويل الديناميكية. */
    fun allConvertLanguagePreferences(): Map<String, LanguageSpeechPrefs>
}

/**
 * [CategoryVoicePrefs] — نطاق الأصوات/المحركات/الأشرطة لكل مجموعة صوتية
 * (الزمن، الأرقام، الإشعارات، الافتراضي، الإيموجي، المتصل...) (البند 4).
 * ينفّذها SettingsRepository.
 */
interface CategoryVoicePrefs {

    /** الصوت المفضّل لمجموعة صوتية معيّنة. */
    fun getPreferredVoiceIdForCategory(category: String): String?

    /** يعيّن الصوت المفضّل لمجموعة صوتية معيّنة. */
    fun setPreferredVoiceIdForCategory(category: String, voiceId: String)

    /** محرك TTS لمجموعة صوتية معيّنة (null = محرك النظام). */
    fun getEngineForCategory(category: String): String?

    /** يعيّن محرك TTS لمجموعة صوتية معيّنة. */
    fun setEngineForCategory(category: String, engine: String?)

    /** معدل الكلام لمجموعة صوتية معيّنة. */
    fun getSpeechRateForCategory(category: String): Float

    /** يعيّن معدل الكلام لمجموعة صوتية معيّنة. */
    fun setSpeechRateForCategory(category: String, rate: Float)

    /** درجة الصوت لمجموعة صوتية معيّنة. */
    fun getPitchForCategory(category: String): Float

    /** يعيّن درجة الصوت لمجموعة صوتية معيّنة. */
    fun setPitchForCategory(category: String, pitch: Float)

    /** مستوى الصوت لمجموعة صوتية معيّنة. */
    fun getVolumeForCategory(category: String): Float

    /** يعيّن مستوى الصوت لمجموعة صوتية معيّنة. */
    fun setVolumeForCategory(category: String, volume: Float)
}

/**
 * [ReadingPrefs] — نطاق قراءة النص ونطق الرموز: وضع الأرقام، مستوى
 * علامات الترقيم، التهجئة الذكية، نطق الإيموجي، ومستشعرات الإيقاف (البند 4).
 * ينفّذها SettingsRepository.
 */
interface ReadingPrefs {

    /** وضع قراءة الأرقام: 0 أرقام، 1 قيم الأرقام، 2 قيم الأرقام، 3 تهجئة. */
    fun getNumberReadingMode(): Int

    /** يعيّن وضع قراءة الأرقام. */
    fun setNumberReadingMode(mode: Int)

    /** مستوى نطق علامات الترقيم: 0 لا شيء، 1 البعض، 2 الكل. */
    fun getPunctuationLevel(): Int

    /** يعيّن مستوى نطق علامات الترقيم. */
    fun setPunctuationLevel(level: Int)

    /** تفعيل التهجئة الذكية ونطق التشكيل عند التنقل الحرفي. */
    fun isSmartSpellingEnabled(): Boolean

    /** يعيّن تفعيل التهجئة الذكية. */
    fun setSmartSpellingEnabled(enabled: Boolean)

    /** تفعيل نطق أسماء الإيموجي قبل إزالتها من النص. */
    fun isEmojiPronunciationEnabled(): Boolean

    /** يعيّن تفعيل نطق أسماء الإيموجي. */
    fun setEmojiPronunciationEnabled(enabled: Boolean)

    /** تفعيل الإيقاف الفوري بالهزاز أثناء النطق. */
    fun isShakeToStopEnabled(): Boolean

    /** يعيّن تفعيل الإيقاف بالهزاز. */
    fun setShakeToStopEnabled(enabled: Boolean)

    /** تفعيل الإسكات الفوري بمستشعر التقارب أثناء النطق. */
    fun isProximitySilenceEnabled(): Boolean

    /** يعيّن تفعيل الإسكات بمستشعر التقارب. */
    fun setProximitySilenceEnabled(enabled: Boolean)
}

/**
 * [AnnouncementPrefs] — نطاق الإعلانات الصوتية: الزمن، البطارية، المتصل،
 * الرسائل النصية، الصيغة/الرنة/الفاصل/الأوقات الهادئة والودجت (البند 4).
 * ينفّذها SettingsRepository.
 */
interface AnnouncementPrefs {

    /** تفعيل جملة الإعلانات الصوتية دفعةً واحدة. */
    fun isAllAnnouncementsEnabled(): Boolean

    /** يعيّن تفعيل الوضع الكلي للإعلانات. */
    fun setAllAnnouncementsEnabled(enabled: Boolean)

    /** تفعيل إعلان الزمن. */
    fun isTimeAnnouncementEnabled(): Boolean

    /** يعيّن تفعيل إعلان الزمن. */
    fun setTimeAnnouncementEnabled(enabled: Boolean)

    /** الفاصل الزمني لإعلان الزمن (بالدقائق). */
    fun getTimeAnnouncementInterval(): Int

    /** يعيّن الفاصل الزمني لإعلان الزمن. */
    fun setTimeAnnouncementInterval(interval: Int)

    /** بداية وقت الهدوء ليوم (0..23)، -1 = بلا. */
    fun getQuietStartForDay(day: Int): Int

    /** نهاية وقت الهدوء ليوم (0..23)، -1 = بلا. */
    fun getQuietEndForDay(day: Int): Int

    /** يعيّن بداية وقت الهدوء ليوم. */
    fun setQuietStartForDay(day: Int, hour: Int)

    /** يعيّن نهاية وقت الهدوء ليوم. */
    fun setQuietEndForDay(day: Int, hour: Int)

    /** تفعيل يومٍ بعينه ضمن الأوقات الهادئة. */
    fun isDayQuietEnabled(day: Int): Boolean

    /** يعيّن تفعيل يومٍ بعينه ضمن الأوقات الهادئة. */
    fun setDayQuietEnabled(day: Int, enabled: Boolean)

    /** صيغة إعلان الزمن (مثل "h" أو "hm"). */
    fun getTimeAnnouncementFormat(): String

    /** يعيّن صيغة إعلان الزمن. */
    fun setTimeAnnouncementFormat(format: String)

    /** تفعيل عرض الساعة بنظام 24. */
    fun isTime24Hour(): Boolean

    /** يعيّن نظام الساعة 24. */
    fun setTime24Hour(enabled: Boolean)

    /** تفعيل التحويل إلى التقويم الهجري في التواريخ. */
    fun isHijriDateEnabled(): Boolean

    /** يعيّن تفعيل التقويم الهجري. */
    fun setHijriDateEnabled(enabled: Boolean)

    /** تفعيل ودجت الساعة الناطقة. */
    fun isClockWidgetEnabled(): Boolean

    /** يعيّن تفعيل ودجت الساعة الناطقة. */
    fun setClockWidgetEnabled(enabled: Boolean)

    /** تفعيل رنة الزمن. */
    fun isTimeChimeEnabled(): Boolean

    /** يعيّن تفعيل رنة الزمن. */
    fun setTimeChimeEnabled(enabled: Boolean)

    /** صوت رنة الزمن (اسم أصل الصوت). */
    fun getTimeChimeSound(): String

    /** يعيّن صوت رنة الزمن. */
    fun setTimeChimeSound(sound: String)

    /** مستوى صوت الرنة (0..1). */
    fun getTimeChimeVolume(): Float

    /** يعيّن مستوى صوت الرنة. */
    fun setTimeChimeVolume(volume: Float)

    /** تفعيل إعلان البطارية. */
    fun isBatteryAnnouncementEnabled(): Boolean

    /** يعيّن تفعيل إعلان البطارية. */
    fun setBatteryAnnouncementEnabled(enabled: Boolean)

    /** مستويات البطارية التي تُعلن عند بلوغها. */
    fun getBatteryAnnouncementLevels(): Set<String>

    /** يعيّن مستويات البطارية المُعلنة. */
    fun setBatteryAnnouncementLevels(levels: Set<String>)

    /** الصوت المفضّل لإعلان البطارية. */
    fun getBatteryAnnouncementVoiceId(): String?

    /** يعيّن صوت إعلان البطارية. */
    fun setBatteryAnnouncementVoiceId(voiceId: String?)

    /** معدل كلام إعلان البطارية. */
    fun getBatteryAnnouncementRate(): Float

    /** يعيّن معدل كلام إعلان البطارية. */
    fun setBatteryAnnouncementRate(rate: Float)

    /** مستوى صوت إعلان البطارية. */
    fun getBatteryAnnouncementVolume(): Float

    /** يعيّن مستوى صوت إعلان البطارية. */
    fun setBatteryAnnouncementVolume(volume: Float)

    /** مستوى صوت التلميح الصوتي للبطارية. */
    fun getBatteryCueVolume(): Float

    /** يعيّن مستوى صوت تلميح البطارية. */
    fun setBatteryCueVolume(volume: Float)

    /** وضع التلميح الصوتي للبطارية (0..2). */
    fun getBatterySoundCueMode(): Int

    /** يعيّن وضع التلميح الصوتي للبطارية. */
    fun setBatterySoundCueMode(mode: Int)

    /** تفعيل إعلان اكتمال الشحن. */
    fun isChargingCompleteAnnouncementEnabled(): Boolean

    /** يعيّن تفعيل إعلان اكتمال الشحن. */
    fun setChargingCompleteAnnouncementEnabled(enabled: Boolean)

    /** تفعيل إعلان فصل الشاحن. */
    fun isChargingDisconnectAnnouncementEnabled(): Boolean

    /** يعيّن تفعيل إعلان فصل الشاحن. */
    fun setChargingDisconnectAnnouncementEnabled(enabled: Boolean)

    /** تفعيل إعلان المتصل. */
    fun isCallerAnnouncementEnabled(): Boolean

    /** يعيّن تفعيل إعلان المتصل. */
    fun setCallerAnnouncementEnabled(enabled: Boolean)

    /** عدد مرات تكرار إعلان المتصل. */
    fun getCallerAnnouncementRepeat(): Int

    /** يعيّن عدد مرات تكرار إعلان المتصل. */
    fun setCallerAnnouncementRepeat(repeat: Int)

    /** الفاصل الزمني بين تكرارات إعلان المتصل (بالثواني). */
    fun getCallerAnnouncementIntervalSeconds(): Int

    /** يعيّن الفاصل بين تكرارات إعلان المتصل. */
    fun setCallerAnnouncementIntervalSeconds(seconds: Int)

    /** صوت إعلان المتصل للغة العربية. */
    fun getCallerAnnouncementArabicVoiceId(): String?

    /** يعيّن صوت إعلان المتصل للغة العربية. */
    fun setCallerAnnouncementArabicVoiceId(voiceId: String?)

    /** صوت إعلان المتصل للغة الإنجليزية. */
    fun getCallerAnnouncementEnglishVoiceId(): String?

    /** يعيّن صوت إعلان المتصل للغة الإنجليزية. */
    fun setCallerAnnouncementEnglishVoiceId(voiceId: String?)

    /** معدل كلام إعلان المتصل. */
    fun getCallerAnnouncementRate(): Float

    /** يعيّن معدل كلام إعلان المتصل. */
    fun setCallerAnnouncementRate(rate: Float)

    /** مستوى صوت إعلان المتصل. */
    fun getCallerAnnouncementVolume(): Float

    /** يعيّن مستوى صوت إعلان المتصل. */
    fun setCallerAnnouncementVolume(volume: Float)

    /** قالب نص إعلان المتصل (مع سلسلة الاستبدال). */
    fun getCallerAnnouncementTemplate(): String

    /** يعيّن قالب نص إعلان المتصل. */
    fun setCallerAnnouncementTemplate(template: String?)

    /** قالب نص إعلان الرسائل (مع سلسلة الاستبدال). */
    fun getSmsAnnouncementTemplate(): String

    /** يعيّن قالب نص إعلان الرسائل. */
    fun setSmsAnnouncementTemplate(template: String?)

    /** وضع قراءة الرسائل النصية (مثل "caller" أو "full"). */
    fun getSmsReadingMode(): String

    /** يعيّن وضع قراءة الرسائل النصية. */
    fun setSmsReadingMode(mode: String)

    /** صوت قراءة الرسائل النصية. */
    fun getSmsReadingVoiceId(): String?

    /** يعيّن صوت قراءة الرسائل النصية. */
    fun setSmsReadingVoiceId(voiceId: String?)

    /** معدل كلام قراءة الرسائل النصية. */
    fun getSmsReadingRate(): Float

    /** يعيّن معدل كلام قراءة الرسائل النصية. */
    fun setSmsReadingRate(rate: Float)

    /** مستوى صوت قراءة الرسائل النصية. */
    fun getSmsReadingVolume(): Float

    /** يعيّن مستوى صوت قراءة الرسائل النصية. */
    fun setSmsReadingVolume(volume: Float)
}

/**
 * [DevicePrefs] — نطاق الجهاز والخصوصية والطاقة: قفل الشاشة، توفير الطاقة،
 * صحة الجهاز، تطبيقات الإشعارات المقروءة، والأول مرة (البند 4).
 * ينفّذها SettingsRepository.
 */
interface DevicePrefs {

    /** تقييد قراءة الإعلانات عند قفل الشاشة. */
    fun isLockScreenPrivacyEnabled(): Boolean

    /** يعيّن تقييد قراءة الإعلانات عند قفل الشاشة. */
    fun setLockScreenPrivacyEnabled(enabled: Boolean)

    /** هل شاشة الجهاز مقفلة فعلياً الآن؟ */
    fun isDeviceScreenLocked(): Boolean

    /** تفعيل وضع توفير الطاقة (خفض الإعلانات). */
    fun isPowerSaverModeEnabled(): Boolean

    /** يعيّن تفعيل وضع توفير الطاقة. */
    fun setPowerSaverModeEnabled(enabled: Boolean)

    /** حد بطارية تفعيل توفير الطاقة (بالنسبة). */
    fun getPowerSaverBatteryThreshold(): Int

    /** يعيّن حد بطارية توفير الطاقة. */
    fun setPowerSaverBatteryThreshold(threshold: Int)

    /** بنود صحة الجهاز المُنادى بها. */
    fun getDeviceHealthItems(): Set<String>

    /** يعيّن بنود صحة الجهاز. */
    fun setDeviceHealthItems(items: Set<String>)

    /** تفعيل قراءة إشعارات التطبيقات المختارة. */
    fun isNotificationReadingEnabled(): Boolean

    /** يعيّن تفعيل قراءة الإشعارات. */
    fun setNotificationReadingEnabled(enabled: Boolean)

    /** حزم التطبيقات المختارة لقراءة إشعاراتها. */
    fun getNotificationAppsSelection(): Set<String>

    /** يعيّن حزم التطبيقات المختارة لقراءة إشعاراتها. */
    fun setNotificationAppsSelection(pkgs: Set<String>)

    /** هل يجب قراءة إشعار تطبيقٍ بعينه؟ */
    fun shouldReadNotificationApp(pkg: String): Boolean

    /** هل اكتمل إعداد التشغيل الأول؟ */
    fun isFirstRunSetupCompleted(): Boolean

    /** يعيّن اكتمال تشغيل الإعداد الأول. */
    fun setFirstRunSetupCompleted(completed: Boolean)

    /** هل تلميح تثبيت اللغة (المُساعد) مفعّل؟ */
    fun isLanguageInstallHintEnabled(): Boolean

    /** يعيّن تلميح تثبيت اللغة. */
    fun setLanguageInstallHintEnabled(enabled: Boolean)
}

/**
 * [ConvertPrefs] — نطاق التحويل التلقائي: تفعيله وسلوتا اللغة/المحرك
 * وصيحاتهما (البند 4). ينفّذها SettingsRepository.
 */
interface ConvertPrefs {

    /** تفعيل التحويل التلقائي إلى لغات أخرى أثناء النطق. */
    fun isAutoConvertEnabled(): Boolean

    /** يعيّن تفعيل التحويل التلقائي. */
    fun setAutoConvertEnabled(enabled: Boolean)

    /** محرك السلوت الأول للتحويل التلقائي. */
    fun getConvertEngine1(): String?

    /** يعيّن محرك السلوت الأول. */
    fun setConvertEngine1(pkg: String?)

    /** لغة السلوت الأول. */
    fun getConvertLanguageTag1(): String?

    /** يعيّن لغة السلوت الأول. */
    fun setConvertLanguageTag1(langTag: String?)

    /** صوت السلوت الأول. */
    fun getConvertVoice1(): String?

    /** يعيّن صوت السلوت الأول. */
    fun setConvertVoice1(voiceId: String?)

    /** مستوى صوت السلوت الأول. */
    fun getConvertVolume1(): Float

    /** يعيّن مستوى صوت السلوت الأول. */
    fun setConvertVolume1(volume: Float)

    /** نبرة السلوت الأول. */
    fun getConvertPitch1(): Float

    /** يعيّن نبرة السلوت الأول. */
    fun setConvertPitch1(pitch: Float)

    /** معدل السلوت الأول. */
    fun getConvertRate1(): Float

    /** يعيّن معدل السلوت الأول. */
    fun setConvertRate1(rate: Float)

    /** محرك السلوت الثاني للتحويل التلقائي. */
    fun getConvertEngine2(): String?

    /** يعيّن محرك السلوت الثاني. */
    fun setConvertEngine2(pkg: String?)

    /** لغة السلوت الثاني. */
    fun getConvertLanguageTag2(): String?

    /** يعيّن لغة السلوت الثاني. */
    fun setConvertLanguageTag2(langTag: String?)

    /** صوت السلوت الثاني. */
    fun getConvertVoice2(): String?

    /** يعيّن صوت السلوت الثاني. */
    fun setConvertVoice2(voiceId: String?)

    /** مستوى صوت السلوت الثاني. */
    fun getConvertVolume2(): Float

    /** يعيّن مستوى صوت السلوت الثاني. */
    fun setConvertVolume2(volume: Float)

    /** نبرة السلوت الثاني. */
    fun getConvertPitch2(): Float

    /** يعيّن نبرة السلوت الثاني. */
    fun setConvertPitch2(pitch: Float)

    /** معدل السلوت الثاني. */
    fun getConvertRate2(): Float

    /** يعيّن معدل السلوت الثاني. */
    fun setConvertRate2(rate: Float)
}

/**
 * [CallerNamesStore] — نطاق أسماء المتصلين المخصّص (PII) في المخزن المشفر
 * — عزلٌ منطقي للبيانات الحساسة عن باقي الإعدادات (البند 4).
 * ينفّذها SettingsRepository.
 */
interface CallerNamesStore {

    /** خارطة أسماء المتصلين المخصّص (رقم -> الاسم). */
    fun getCustomCallerNames(): Map<String, String>

    /** يستبدل خارطة أسماء المتصلين المخصّص بالكامل. */
    fun setCustomCallerNames(names: Map<String, String>)
}