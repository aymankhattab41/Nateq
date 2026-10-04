package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.core.audio.engine.SpeechChunker
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.Segment
import com.aymankhattab.nateq.core.audio.engine.isNumericOnly
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.SpeechLock
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * إعدادات نطق أسماء الإيموجي (فئة «نطق الإيموجي») — تُقرأ من الإعدادات مرة
 * واحدة لكل دورة نطق وتُطبق على مقاطع أسماء الإيموجي فقط.
 */
internal data class EmojiSpeechConfig(
    val voiceId: String?,
    val arabic: Boolean,
    val rate: Float,
    val pitch: Float,
    val volume: Float
)

/** إعدادات نطق فئة الأرقام للمقاطع الرقمية البحتة داخل الإعلانات —
 *  تُقرأ من الإعدادات مرة واحدة وتُطبق على الرقم فقط (نفس استعلامات فئة
 *  الأرقام في [TimeAnnouncementManager]). */
internal data class NumbersCategorySpeech(
    val voiceId: String,
    val rate: Float,
    val pitch: Float,
    val volume: Float
)

/**
 * متحدث مستقل يستخدمه التطبيق للإعلانات الصوتية التلقائية
 * (مستوى البطارية، اسم المتصل، الرسائل الواردة) دون المرور عبر خدمة النظام.
 * يربط مباشرةً بمحرك TTS المحدد (محرك اللغة المضبوط أو المحفوظ في
 * الإعدادات، وإلا محرك النظام الافتراضي)، وينطق عبر `speak()` ليعمل في
 * الخلفية حتى لو لم يُظهر النظام شاشة تخليق (على عكس
 * TextToSpeechService الذي يقود النظام).
 *
 * ليتفادى حلقة ربط النظام TextToSpeech → خدمة LORD نفسها (التي قد تُسقط
 * الصوت)، يستبعد دائماً حزمة التطبيق نفسه عند اختيار المحرك فيفوض النطق
 * لمحركٍ مثبّت خارجي (منهج MultiTTS).
 */

/**
 * **أيُّ قناةٍ يعبر عليها النطق فعلاً.**
 *
 * **ولماذا لا تكفي الفئة وحدها؟** لأن `isCallerCategory`
 * تخلط حالتين: رنينُ متصلٍ بلا مكالمة، ومكالمةُ انتظار ثمة
 * مكالمةٌ جارية. الأولى تُراد فوق الرنين فقناةُ الإشعار
 * مختارةٌ لها (النظام يخفض `STREAM_MUSIC` أثناء الرنين).
 * والثانية تُراد في جوف مكالمة المستخدم، فقناةُ الإشعار فيها
 * غلطان: ليست على مسار المكالمة — فمعالجةُ المسار الهاتفي
 * لا تسري عليها فيلتقطها الميكروفون — ويرفعها
 * [boostStreamVolume] إلى القمّة بلا منحنى صوت يخفضها.
 */
internal enum class SpeechRoute {
    /** فوق مكالمة جارية. */
    CALL,

    /** رنينُ متصلٍ بلا مكالمة. */
    NOTIFICATION,

    /** ما عداه. */
    MEDIA
}

/**
 * بُعدُ النعمة التي تبقى فيها قناةُ المكالمة مقفولةً بعد
 * انتهاء المكالمة.
 *
 * **لماذا نعمة؟** لأن `mode` قد يرجع إلى `MODE_NORMAL`
 * قبل إرسال آخر جزء، فنطبّق المسار الجديد في وسط الكلمة
 * فيسمع المستخدم نقلةَ قناة. فنعمةٌ قصيرة تكمل الذيل على
 * قناةٍ واحدة.
 */
internal const val CALL_ROUTE_GRACE_MS = 600L

/**
 * قفلُ قناةِ المكالمة: يعاين الحالة ويعيدُ ما إذا كانت
 * القناة مقفلةً بالإغلاق الطازج أو بميعاد النعمة.
 *
 * ابتدائيٌّ مفتوح — قفلٌ لم يرَ مكالمةً لا يدّعي أنه مقفل.
 */
internal class CallRouteLatch {
    private var latchedUntil = Long.MIN_VALUE

    fun observe(inCall: Boolean, nowMs: Long): Boolean {
        if (inCall) {
            latchedUntil = nowMs + CALL_ROUTE_GRACE_MS
        }
        return nowMs < latchedUntil
    }
}

/**
 * قرارُ المسار — خالصٌ بلا `Context` فيفحصُه اختبارُ الوحدة.
 *
 * **وفئاتُ غير المتصل لا تصل قناةَ المكالمة أبداً**
 * (بطارية/وقت/رسائل): خطفُ مسار مكالمة المستخدم لها أسوأُ
 * من التسريب نفسِه.
 */
class AnnouncementSpeaker(
    context: Context,
    private var voiceId: String? = null
) {

    companion object {
        private const val TAG = "NATEQ_TTS"

        // أدنى معامل للسرعة/النبرة مقبول لدى محركات TTS؛ دونها تتوقف بعض
        // المحركات عن الاستجابة (صمت تام). يُطبَّق هنا على كل مسار، حتى مع
        // قيم قديمة/خاطئة مخزنة من قبل.
        private const val MIN_RATE_OR_PITCH = 0.25f

        // سقف آمن أعلى لسرعة النطق: بعض محركات سامسونج/Vocalizer فوق 2.5
        // تصمت بلا onDone ولا onError (بند 2.4). الواجهة تقتصِر أصلاً على
        // 2.0، ويبقى السقف هنا وقائياً على حدود المحرك مهما كان مصدر القيمة.
        private const val MAX_SPEECH_RATE = 2.5f

        /** تثبيت سرعة النطق ضمن المدى الآمن للمحرك وقائياً (بند 2.4):
         *  حدٌّ أدنى فلا يتعطل المحرك، وحدٌّ أعلى فلا يعلّق صامتاً. */
        internal fun clampedSpeechRate(rate: Float): Float =
            rate.coerceIn(MIN_RATE_OR_PITCH, MAX_SPEECH_RATE)

        /** منفّذ تسلسلي لمعالجة نصوص الإعلانات الثقيلة خارج خيط الواجهة؛
         *  خيطه وصّي (daemon) فلا يحجب عمليات الإنصات، ويمنع تزاحمَ
         *  معالجات الإعلانات المتقاربة على نفسه (وقوف في الصف). */
        private val textProcessorExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "nateq-announce-process").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }

        // **قفل النطق العابر:** مهلة قصوى لانتظار هدوء تخليق قارئ الشاشة
        // (رفعَهُ محركُ :tts عبر content://…/speaking) قبل نطق الإعلانات
        // المؤجلة — بعدها يُنطق الإعلان على أي حال (لا ضياع).
        private const val SPEAKING_WAIT_TIMEOUT_MS = 5000L

        // إعادة جدولة طلب التركيز المرفوض (AUDIOFOCUS_REQUEST_FAILED):
        // محاولات بفاصل وجيز، ثم إسقاطٌ صامت صريح (لا حلقة لا نهائية فوق
        // مشغّلٍ محجوز للمكالمة/الوسائط).
        private const val FOCUS_RETRY_DELAY_MS = 500L
        private const val MAX_FOCUS_RETRIES = 3
        val EVENT_CATEGORIES: Set<String> = setOf(
            SettingsRepository.VOICE_CATEGORY_TIME,
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN,
            SettingsRepository.ANNOUNCE_CATEGORY_SMS,
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )

        fun isEventCategory(category: String?): Boolean =
            category != null && category in EVENT_CATEGORIES

        fun isResumableCategory(category: String?): Boolean =
            category == SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS ||
                category == SettingsRepository.ANNOUNCE_CATEGORY_SMS

        /** فئاتُ إعلان المتصل (ثلاثها: افتراضية/عربية/إنجليزية) — وهي
         *  الفئةُ الوحيدة التي تُنطق **فوق مكالمةٍ جارية**، فتخضع
         *  لتخفيض النظام للوسائط أثناء المكالمة (Voice-call Ducking)،
         *  وهو ما جعل صوتها منخفضاً على أجهزةٍ مثل Pixel (أندرويد 17)
         *  بينما يعلو نطقُ البطارية والساعة اللذين لا يُخفضهما النظام.
         *  لذلك يُنطق المتصل على مسار الإشعار بدل مسار الوسائط —
         *  انظر [speechAudioAttributes]. */
        fun isCallerCategory(category: String?): Boolean =
            category == SettingsRepository.ANNOUNCE_CATEGORY_CALLER ||
                category == SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR ||
                category == SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
        /**
         * قرارُ المسار — خالصٌ بلا `Context` فيفحصُه اختبارُ الوحدة.
         *
         * **وفئاتُ غير المتصل لا تصل قناةَ المكالمة أبداً**
         * (بطارية/وقت/رسائل): خطفُ مسار مكالمة المستخدم لها أسوأُ
         * من التسريب نفسِه.
         */
        internal fun speechRouteFor(
            category: String?,
            inCall: Boolean
        ): SpeechRoute = when {
            !isCallerCategory(category) -> SpeechRoute.MEDIA
            inCall -> SpeechRoute.CALL
            else -> SpeechRoute.NOTIFICATION
        }

        /**
         * قناةُ كلِّ مسار.
         *
         * **ومرادُها التامُّ لـ[speechAudioAttributes] شرطٌ لا يحتمل
         * كسرَه:** انظر تحذير `doSpeak` — فإن اختلفت، تجاهل
         * المحرّكُ السمةَ ورجع إلى `STREAM_MUSIC` فعاد العلة.
         */
        internal fun streamForRoute(route: SpeechRoute): Int = when (route) {
            SpeechRoute.CALL -> AudioManager.STREAM_VOICE_CALL
            SpeechRoute.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            SpeechRoute.MEDIA -> AudioManager.STREAM_MUSIC
        }

        /** تجزئة نصوص الإشعارات والرسائل الطويلة إلى جمل طبيعية مستقلة
         *  لتمكين مقاطعتها بحدث آني واستئناف ما تبقى منها بسلاسة. */
        internal fun splitIntoSentences(text: String): List<String> {
            if (text.length <= 60) return listOf(text)
            val result = mutableListOf<String>()
            val current = StringBuilder()
            val delimiters = charArrayOf(
                '.', '!', '?', '؟', '،', '؛', '…', '\n', ':'
            )
            for (i in text.indices) {
                val c = text[i]
                current.append(c)
                if (c in delimiters && current.length >= 25) {
                    val s = current.toString().trim()
                    if (s.isNotBlank()) result.add(s)
                    current.setLength(0)
                }
            }
            val remaining = current.toString().trim()
            if (remaining.isNotBlank()) {
                result.add(remaining)
            }
            return if (result.isEmpty()) listOf(text) else result
        }

        /** حلّ صوت وحدةٍ لغوية من صوت المحرك: يفضّل المعرّف الصريح
         *  ([partVoice] كاسم صوت مخصص في إعدادات اللغة)؛ وإلا أفضلَ صوتٍ
         *  لسانُه لسانُ الوحدة — تُرجَّح مطابقةُ رمز البلد، ثم أيُّ صوتٍ
         *  باللغة — بدل الاعتماد على `setLanguage` وحده الذي قد لا يبدّل
         *  لغةَ نطق المحرك فعلياً (بند 18). ترجيحُ البلد يمنح تغييرَ
         *  اللغة/المحرك أثراً حقيقياً بدل «أول صوتٍ» ثابت بلغة النظام
         *  (كان تبديل لغة النطق لا يغيّر الصوتَ على بعض المحركات).
         *  null إن لم يوجد صوت ملائم — يبقى `setLanguage` سقوطاً. */
        internal fun voiceFor(
            voices: Collection<Voice>?,
            partVoice: String?,
            locale: Locale
        ): Voice? {
            if (voices.isNullOrEmpty()) return null
            partVoice?.let { vid ->
                voices.firstOrNull { it.name == vid }?.let { return it }
            }
            val targetLang = LocaleUtils.normalizeLanguageCode(locale.language)
            val sameLanguage = voices.filter {
                LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                    targetLang
            }
            if (sameLanguage.isEmpty()) return null
            val country = LocaleUtils.normalizeCountryCode(locale.country)
            if (!country.isNullOrEmpty()) {
                sameLanguage
                    .firstOrNull {
                        val c = LocaleUtils.normalizeCountryCode(
                            it.locale?.country
                        )
                        c?.equals(country, ignoreCase = true) == true
                    }
                    ?.let { return it }
            }
            return sameLanguage.first()
        }

        /** صوتٌ عربي/نفسُ اللسان بديل عن صوتٍ مرفوض: بعض المحركات (ظاهرة
         *  Vocalizer) تعرض أصواتاً برموز مختلفة عن المعرّف المُخصَّص،
         *  فتنجح `setVoice` بغير الصوت المُفضَّل. يُستبعد المعرّضُ الفاشل
         *  ويُرجَّح صوتُ البلد المطابق ثم أيُّ صوتٍ باللغة (بلا صوتٌ
         *  أجنبيٍّ للوحدة)، وإلا null للرضى بالـ setLanguage. */
        internal fun fallbackVoiceFor(
            voices: Collection<Voice>?,
            locale: Locale,
            excludedName: String?
        ): Voice? {
            if (voices.isNullOrEmpty()) return null
            val targetLang = LocaleUtils.normalizeLanguageCode(locale.language)
            val eligible = voices.filter {
                LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                    targetLang && it.name != excludedName
            }
            if (eligible.isEmpty()) return null
            val country = LocaleUtils.normalizeCountryCode(locale.country)
            return if (!country.isNullOrEmpty()) {
                eligible
                    .firstOrNull {
                        val c = LocaleUtils.normalizeCountryCode(
                            it.locale?.country
                        )
                        c?.equals(country, ignoreCase = true) == true
                    }
                    ?: eligible.first()
            } else {
                eligible.first()
            }
        }

        /** حل لسان بديل مدعوم بإقليم عند رفض المحرك اللسان المجرد (مثل
         *  رفض Vocalizer لـ Locale("ar") بدون بلد): يفضل إقليم أول صوت
         *  مطابق في المحرك، وإلا إقليماً قياسياً شهيراً باللسان ذاته. */
        internal fun resolveFallbackLocale(
            locale: Locale,
            voices: Collection<Voice>?
        ): Locale? {
            val targetLang = LocaleUtils.normalizeLanguageCode(locale.language)
            val voiceMatch = voices?.firstOrNull {
                LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                    targetLang
            }
            if (voiceMatch?.locale != null) {
                return voiceMatch.locale
            }
            return when (targetLang) {
                LanguageCode.AR.tag -> Locale.forLanguageTag("ar-SA")
                LanguageCode.EN.tag -> Locale.US
                "fr" -> Locale.FRANCE
                else -> null
            }
        }

        // نطاق الإيموجي الشائع (بلوكات Unicode): رموز التباين (2600-27BF)،
        // الأسهم/الرموز الإضافية (2B00-2BFF) والبلوكات التكميلية الخاصة
        // بالإيموجي (1F000-1FBFF: الفقر والرموز والإيموجي والرموز
        // التصويرية)، إضافةً لمتغير التباين FE0F والرابط الصفري ZWJ (200D).
        // تنظيف الإيموجي برمجيٌّ بنقاط الكود (لا Regex فئات surrogate التي
        // تتذرّع في النمط العربي من البيئة) فيستبعد حروفاً ليست إيموجي مثل
        // امتداد CJK-B (U+20000) وOld Italic (U+10300) وامتداد الرموز
        // المكملة (U+2A6D6) — كانت تُبتلع سابقاً. يُستخدم لتنظيف النصوص
        // الخارجية (SMS/إشعارات/اسم المتصل) قبل النطق عبر المحرك الخارجي
        // حتى لا يُقرأ الإيموجي باسمه الإنجليزي (مثل بعض المحركات).
        internal fun stripEmojis(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            var emojiRun = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val isEmoji = cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                    cp in 0x1F000..0x1FBFF || cp == 0xFE0F || cp == 0x200D
                if (isEmoji) {
                    if (!emojiRun) {
                        emojiRun = true
                        sb.append(' ')
                    }
                    // يتم ابتلاع الركض المتواصل (مشكلة إيموجي متتابع) بمسافة
                    // واحدة، كخطوة التنظيف القديمة عبر Regex+.
                    i += Character.charCount(cp)
                } else {
                    emojiRun = false
                    sb.appendCodePoint(cp)
                    i += Character.charCount(cp)
                }
            }
            // ركض إيموجي في مستهل النص لا يترك مسافة افتتاحية (نفس ما كان
            // يفعله النمط القديم، فالركض يُستبدل بمسافة واحدة تبقى في
            // المقدمة). يُزيلها التنظيف شرطَ ألا يفقد كل المحتوى.
            val cleaned = sb.toString()
            return if (cleaned.startsWith(" ") && cleaned.length > 1) {
                cleaned.substring(1)
            } else {
                cleaned
            }
        }

        // مثيل واحد مشترك لكل عملية. تعدد المتحدثات (مثيل لكل مستقبِل) كان
        // يفتح محرك TTS منفصلاً في كل مرة فيتقاطع صوتان ويستنزف الذاكرة.
        @Volatile
        private var shared: AnnouncementSpeaker? = null

        /** الحصول على المتحدث المشترك الوحيد (محمي بالإنشاء المزدوج).
         *  يُحمَّل مسار المعالجة مسبقاً وقت الإنشاء على خيط خلفية
         *  [prewarm] (بند الأوامر د.1) — فعّالٌ مرةً واحدة لكل عملية. */
        @JvmStatic
        fun getInstance(context: Context): AnnouncementSpeaker {
            return shared ?: synchronized(this) {
                shared ?: AnnouncementSpeaker(context.applicationContext)
                    .also { shared = it }
                    .also { it.prewarm() }
            }
        }

        @VisibleForTesting
        internal fun resetSharedForTesting() {
            synchronized(this) {
                shared?.shutdown()
                shared = null
            }
        }

/** هل معرّف الصوت إنجليزي؟ يقبل الصيغ القديمة
         *  (nateq-en…/en-local/ar-local) والموحّدة (en-US) و
         *  صوتَ محركٍ مكتشف (com.google.android.tts:eng-usa) عبر
         *  القارئ الموحّد [AnnouncementLanguageResolver]. */
        private fun isEnglishVoiceName(voiceId: String?): Boolean =
            AnnouncementLanguageResolver.languageOfVoiceId(voiceId) ==
                AnnouncementLanguageResolver.ENGLISH

        // عدّادٌ ذرّي لمعرّفات النطق — الزمن وحده كان يتكرر بين جزأين في
        // نفس المللي ثانية فيصدر onDone مبكراً ويفلتر أجزاء (بند [3]).
        private val utteranceCounter = AtomicLong(0L)

        /** معرّف نطق فريد لكل جزء يُرسَل إلى المحرك — الزمن + عدّادٍ ذرّي
         *  يضمنان التفرد حتى داخل نفس المللي ثانية. قابلةٌ للاختبار. */
        internal fun nextUtteranceId(): String =
            "nateq_announce_${System.currentTimeMillis()}_" +
                utteranceCounter.incrementAndGet()
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(
        Context.AUDIO_SERVICE
    ) as AudioManager
    private val mainHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastFocusGain: Int? = null
    private var lastAudioAttributes: AudioAttributes? = null
    private var hasAudioFocus = false
    private var skippedFocusForMedia = false
    internal var speechDispatchedCount: Long = 0L
        private set

    // **رفع حجم القناة الصوتية مؤقتاً لفئات النطق التي يشكو الصوتُ عليها
    // (نطق المتصل ونطق الساعة):** يُحفظ مستوى حجم القناة التي سيُنطق عليها
    // الإعلان (MUSIC أو ACCESSIBILITY) ثم تُرفع إلى قمتها طوال النطق
    // وتُستعاد بعده؛ فيصدر الإعلان بأقصى صوتٍ فعلي حتى لو كان مستوى تلك
    // القناة منخفضاً على الجهاز. يعمل أيضاً في الوضع الصامت لأن مفتاح
    // الصمت يُسكت قناة الرنين/الإشعارات لا قناتَي الوسائط أو الإتاحة
    // (بند رفع حجم نطق المتصل والساعة). -1 تعني عدم وجود رفعٍ قائم حالياً.
    private var boostedStream: Int = -1
    private var savedStreamVolume: Int = -1

    /**
     * قفلُ قناةِ المكالمة — يقرأ `mode` فيغلقُه، ويبقى مقفلاً بميعاد
     * [CALL_ROUTE_GRACE_MS] بعد انتهاء المكالمة.
     *
     * **و[nowMs] محقونٌ ليكون قابلاً للفحص:** ساعةٌ لا تتأثر
     * بتغيّر وقت الجهاز (فلو كان الحسابُ بتوقيت الجدار لقَصَرَت
     * النعمةُ أو طالت).
     */
    @VisibleForTesting
    internal fun speechRouteForNow(
        category: String?,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): SpeechRoute {
        val inCall = runCatching {
            val mode = audioManager.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)
        return speechRouteFor(
            category,
            callRouteLatch.observe(inCall, nowMs)
        )
    }

    private val callRouteLatch = CallRouteLatch()

    // **خفضُ نغمة الرنين أثناء إعلان المتصل (اختياريّ):** رفعُ قناة
    // الموسيقى لا يمسّ قناة الرنين في AOSP (وهي ليست ضمن الـducking)،
    // فيختفي اسمُ المتصل تحت الرنّة. فنخفضها استثناءً بالمقدار الذي
    // يختاره المستخدم ونعيدها بعد النطق — وهو [boostStreamVolume] في
    // الاتجاه المعاكس. -1 تعني «لا خفضٌ قائم» فلا تكرارَ ولا استرجاعَ
    // بلا سبب.
    private var duckedRingStream: Int = -1
    private var savedRingVolume: Int = -1

    @Volatile
    private var resumableAnnouncement: ResumableAnnouncement? = null
    private val pendingUnitsForResume =
        CopyOnWriteArrayList<Pair<String, SpeakUnit>>()

    // آخر سمات طُبّقت على المحرك: نتغير فقط عند الاختلاف الفعلي فلا نُعيد
    // setAudioAttributes بلا داعٍ (تبقى أغلى قليلاً من الفحص البسيط).
    private var lastAppliedAudioAttributes: AudioAttributes? = null

    // النطق المنتظر لحين وصول Audio Focus المؤجل (DELAYED): يُخزَّن الإجراء
    // ويُطلق فور استلام AUDIOFOCUS_GAIN، مع مؤقّت أمان يمنع ضياع الإعلان
    // إن لم يتحرر التركيز أبداً.
    private var pendingFocusAction: (() -> Unit)? = null
    private var pendingFocusTimer: Runnable? = null

    // **قفل النطق العابر (بند التنسيق مع قارئ الشاشة):** طابور الإعلانات
    // المؤجلة خلف قراءة نشطة لمحرك :tts (TalkBack وغيرها). تُسجَّل هنا
    // متى كان علم «نطق جارٍ» مرفوعاً (content://…/speaking)، وتُحرَّر كلها
    // عند هبوط العلم فوراً (ContentObserver) أو انقضاء مهلة الأمان القصوى
    // [SPEAKING_WAIT_TIMEOUT_MS] — فتتسلسل الإعلانات بعد القراءة بدل
    // تراكبها فوقها.
    private val deferredWhileSpeaking = ConcurrentLinkedQueue<() -> Unit>()
    private var speakingLockObserver: ContentObserver? = null
    private var speakingLockTimeout: Runnable? = null

    // عدّاد جيل النطق: يزداد في كل دورة speak وينفي مسارات مؤجلة
    // من دورات سابقة — يمنع النطق القديم بعد stop()/speak جديد.
    private val speechGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // عدّاد دورات النطق الفعلية: يزداد عند إرسال أول جزءٍ من دورةٍ جديدة
    // فعلياً (لا عند طلبها — التهيئة/التركيز قد يؤجلانها). يخدم
    // [currentSpeechCycle] ليُميّز صاحبُ الخطاف (ويدجت الساعة) اكتمالَ
    // دورته عن اكتمالِ دورةٍ سابقة (بند 5.1).
    private val speechCycle = AtomicLong(0L)

    private var tts: TextToSpeech? = null
    private var nowSpeaking = false
    @Volatile
    private var currentCategory: String? = null

    // مؤقّت أمان على Main (المحور السادس): إن علّق المحرك بلا onDone/onError
    // يُحرَّر التركيز الصوتي ويُرفع رصد الإسكات — فلا يبقى النظام محجوزاً
    // صامتاً إلى إشعارٍ لن يأتي. يُعاد فتحه/إلغاؤه مع كل دورة نطق.
    private var speechWatchdog: Runnable? = null

    /** المحرك المرتبط حالياً بالمتحدث — يُقارن قبل النطق بأي محركٍ صريح
     *  لفئةٍ معيّنة فيُعاد الربط عند الاختلاف (تبديل حي بين فئات الوظائف). */
    private var boundEngine: String? = null

    /** مقسم النصوص المختلطة الكتابات داخل إعلانات
     * التطبيق (منطق نقي بلا حالة). */
    private val languageSegmenter = LanguageSegmenter()

    private val settings: SettingsRepository?
        get() = runCatching {
            (appContext as? AnnouncementAppContext)?.settingsRepository
                ?: SettingsRepository.create(appContext)
        }.getOrNull()

    // **توحيد مسار الإعلانات مع مسار القراءة (بند الأوامر 1):** تمرُّ نصوص
    // الإعلانات (الإشعارات/الرسائل/البطارية/المتصل) عبر TextProcessor نفسه
    // الذي يُعالج نص القارئ — أرقام/أوقات/عملات/روابط/رموز تُحول لصيغة
    // نطق طبيعية قبل إرسالها للمحرك. الإعدادات تُحقَن عبر
    // AnnouncementAppContext إن وُجدت (Hilt) وإلا تُبنى محلياً —
    // قراءة لحظية للتشكيل/التهجئة/الإيموجي.
    //
    // **والقاموسُ محقونٌ حصراً بالمثّل المشترك** — وهذا كان عطل
    // «القاموس لا يعمل» بأكمله: بلا حقنٍ كان كلُّ نداءٍ يبني
    // `PronunciationDictionary(context)` جديدةً **فارغة**، فلا يرى نطقُ
    // الإعلانات ما كتبه المستخدمُ في الإعدادات إلا عبر استطلاعٍ للقرص
    // ينهار صامتاً (Keystore معطوب، أو طابعٌ لم يتغيّر، أو نافذةُ خنق).
    // فالمثّلُ الواحد يُسقطُ المزامنةَ من العملية الواحدة كلها.
    private val textProcessor: TextProcessor by lazy {
        TextProcessor(
            appContext,
            settings,
            PronunciationDictionary.shared(appContext)
        )
    }

    // **التحميل المسبق لمسار المعالجة (بند الأوامر د.1):** أول استخدامٍ فعلي
    // لـ [textProcessor] الكسول يبني PronunciationDictionary (قراءة قرص +
    // تفكيك JSON) وقد يقع عند استدعاء نطقٍ على الخيط الرئيسي (النطق التجريبي
    // من شاشة الإعدادات) فيجمّد الواجهة لحظياً. يُحمَّل مسبقاً على خيط خلفية
    // وقت إنشاء المتحدث ([getInstance]) ويبقى `by lazy` حارسَ أمانٍ لمن يسبق
    // التحميل (نادر) — فلا تُجمَّد الواجهة مطلقاً.
    private val prewarmScope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var prewarmLaunched = false

    /** يبني [textProcessor] (يلمس الخاصية الكسولة) على خيط خلفية —
     *  يُستدعى مرة واحدة من [getInstance] عند إنشاء المتحدث. */
    fun prewarm() {
        if (prewarmLaunched) return
        prewarmLaunched = true
        prewarmScope.launch {
            runCatching { warmTextProcessor() }
        }
    }

    /** يلمس [textProcessor] ليفرض التهيئة الفعلية لمسار المعالجة ويجيب عن
     *  جاهزيته — يُستخدم من [prewarm] ومن اختبارات التحميل المسبق. */
    @VisibleForTesting
    internal fun warmTextProcessor(): Boolean = runCatching {
        textProcessor
    }.isSuccess

    /** هل بُدئ التحميل المسبق فعلاً (بند الأوامر د.1)؟ تعرضها الاختبارات. */
    @VisibleForTesting
    internal fun isPrewarmStarted(): Boolean = prewarmLaunched

    // قائمة مستمعي اكتمال دورة النطق (آخر جملة تُتم أو تُخطئ). بدل خانة
    // الخطاف الوحيدة التي كانت تُطمس خطافات أدوات/مستقبلات أخرى (بند [8])
    // — كل مسجّل (أداة الساعة، مستقبل المتصل، مستقبل المنبه) يُستدعى عند
    // اكتمال آخر جملة دون أن يمسّ الآخرين. CopyOnWrite لتتحمل الاستدعاء
    // المتزامن مع الإضافة/الإزالة من خيوط النطق والبث معاً.
    private val completionListeners = CopyOnWriteArrayList<() -> Unit>()

    /** تسجيل خطاف يُستدعى عند اكتمال آخر جملة في دورة النطق الحالية
     *  (onDone/onError للـ lastQueuedUtteranceId فقط). تستخدمه أداة الساعة
     *  ومستقبِلات المتصل/المنبه لتحرير goAsync() وWakeLock المؤقت عقب
     *  اكتمال النطق فعلياً بدل التحرير المبكر (جمد العملية بعد onReceive
     *  على Android 14+ يقتطع ذيل الصوت). */
    fun addCompletionListener(listener: () -> Unit) {
        completionListeners.add(listener)
    }

    /** إلغاء تسجيل خطاف (غالباً في finally — فلا يُستدعى في دورة نطقٍ
     *  لاحقة لا تخص صاحبه). لا يؤثر إلغاء أحدهم على الآخرين. */
    fun removeCompletionListener(listener: () -> Unit) {
        completionListeners.remove(listener)
    }

    /** رقم دورة النطق الجارية (يزداد عند إرسال أول جزءٍ من دورةٍ جديدة
     *  فعلياً). يُلتقط قبل طلب النطق ثم يُقارن في خطاف الاكتمال: اكتمالٌ
     *  رقمُه ≤ الرقم الملتقط يخص دورةً سابقة ولا يُحسب لطلبنا — يمنع
     *  تحرير goAsync/WakeLock المبكر في ويدجت الساعة (بند 5.1). */
    fun currentSpeechCycle(): Long = speechCycle.get()

    /** استدعاء كل مستمعي الاكتمال (كلٌّ بمعزلٍ عن أخطاء غيره). */
    private fun notifySpeechComplete() {
        val listeners = ArrayList(completionListeners)
        listeners.forEach { cb ->
            runCatching { cb() }
        }
    }

    /**
     * معرّف آخر جزء أُرسل إلى المحرك في دورات النطق الحالية. يُقارن به عند
     * استقبال onDone/onError لنحرر التركيز الصوتي فقط عند اكتمال الجزء الأخير،
     * لا بعد أول جزء — فالإعلان متعدد المقاطع (نص + أسماء إيموجي متتابعة) يبقى
     * محمياً من تشويش التطبيقات الأخرى حتى ينتهي
     * كل النطق. Volatile لأن الكتابة
     * قد تأتي من خيط إرسال (Main أو IO) والقراءة من مستمع المحرك على Main.
     */
    @Volatile
    private var lastQueuedUtteranceId: String? = null

    // بند 1.1: السجلّ الصريح لمعرّفات الأجزاء المرسلة فعلياً إلى المحرك
    // وغيرِ معادٍ إشعارُها. غايةُ التتبع أن تُبطَل دفعةً واحدة في كل دورة
    // نطقٍ جديدة (FLUSH) وعند الإيقاف، فمستمعٌ قادمٌ متأخراً لمعرّفٍ قديم
    // يجد نفسه خارج السجل لا «معلّقاً» بلا هدف. لا يمسُّ مقارنةَ isFinal
    // (تبقى على lastQueuedUtteranceId كما هي إطلاقاً).
    private val activeUtteranceIds =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** تسجيل معرّفٍ وُضع فعلياً في طابور المحرك (بند 1.1). */
    internal fun trackUtterance(utteranceId: String) {
        activeUtteranceIds.add(utteranceId)
    }

    /** ما يزال المعرّف نشطاً (يُنتظَر إشعارُه)؟ تعرضها الاختبارات لإثبات أن
     *  FLUSHَ والإيقافَ يُبطلان الصراحةَ كل المعرّفات القديمة. */
    internal fun isActiveUtterance(utteranceId: String?): Boolean {
        return utteranceId != null && activeUtteranceIds.contains(utteranceId)
    }

    /** إبطال صريح لكل المعرّفات النشطة (دورة FLUSH جديدة أو إيقاف/إغلاق). */
    internal fun invalidateActiveUtterances() {
        activeUtteranceIds.clear()
    }

    /** تغيير الصوت المفضّل لدورات النطق القادمة دون هدم اتصال المحرك */
    fun resetVoice(newVoiceId: String?) {
        if (newVoiceId == voiceId) return
        voiceId = newVoiceId
    }

    /** رصد الإسكات الفوري (هز/تقارب) أثناء النطق الجاري — معطّل افتراضياً
     *  حتى يفعّل المستخدم أحد المفتاحين في الإعدادات. */
    private var interruptionSensors: InterruptionSensors? = null

    /** بدء رصد الهز/التقارب قبل إرسال أول جزء في دورة النطق — يقرأ مفاتيح
     *  الإسكات الفوري من الإعدادات لحظياً، ولا يسجّل شيئاً إلا إذا فُعّل
     *  أحدهما وجهاز المستخدم يملك مستشعره (آمن للتكرار/Failed هذا لا شيء). */
    private fun startInterruptionMonitoring() {
        if (interruptionSensors != null) return
        val settings = runCatching {
            (appContext as? AnnouncementAppContext)?.settingsRepository
                ?: SettingsRepository.create(appContext)
        }.getOrNull() ?: return
        val shake = runCatching { settings.isShakeToStopEnabled() }
            .getOrDefault(false)
        val proximity = runCatching { settings.isProximitySilenceEnabled() }
            .getOrDefault(false)
        if (!shake && !proximity) return
        val sensors = InterruptionSensors(
            shakeEnabled = { shake },
            proximityEnabled = { proximity },
            onInterrupt = { stop() }
        )
        interruptionSensors = sensors
        sensors.start(appContext)
    }

    /** إيقاف رصد الهز/التقارب — يستدعى عند اكتمال/فشل النطق أو إيقافه. */
    private fun stopInterruptionMonitoring() {
        interruptionSensors?.stop()
        interruptionSensors = null
    }

    /** إلغاء مؤقّت حارس النطق (اكتمال/فشل/إيقاف أو دورة جديدة تستبدله). */
    private fun cancelSpeechWatchdog() {
        speechWatchdog?.let { mainHandler.removeCallbacks(it) }
        speechWatchdog = null
    }

/** فتح حارس انتهاء النطق بميزانيةٍ مشتقّة من وحدات النطق نفسها
     *  ([SynthesisBudget.unitsTimeoutMs]): إن لم يصل onDone/onError
     *  لدورة النطق الجارية خلالها — محرّكٌ علّق صامتاً — يُحرَّر
     *  التركيز ويُرفع رصد الإسكات (المحور السادس: لا «تركيز مكتوم»
     *  بلا مخرج أبداً). كان السقفُ ثابتاً (سابقاً 120 ثانية) بينما
     *  مجموعُ ميزانيات وحدات إعلانٍ طويل يتجاوزه، فيُقطع الإعلان
     *  في منتصفه — الانقطاعُ الجذري نفسه في مسار الإعلانات. وعند
     *  الاطلاق المتأخر للحدث النهائي يبقى المسار الطبيعي سالماً:
     *  [releaseAudioFocus] معفاةُ التكرار ومستعدّو الاكتمال
     *  يُستدعون من onDone فقط. */
    private fun armSpeechWatchdog(units: List<SpeakUnit>) {
        cancelSpeechWatchdog()
        val timeoutMs =
            SynthesisBudget.unitsTimeoutMs(units.map { it.text.length })
        val seconds = timeoutMs / 1000L
        val timer = Runnable {
            speechWatchdog = null
            Log.w(TAG,
                "[Watchdog] انقضت $seconds ث بلا onDone/onError" +
                " — تصفير الحالة وتحرير التركيز وهدم المحرك المعلّق")
            stopInterruptionMonitoring()
            releaseAudioFocus()
            nowSpeaking = false
            currentCategory = null
            notifySpeechComplete()
            invalidateActiveUtterances()
            shutdownSafely()
        }
        speechWatchdog = timer
        mainHandler.postDelayed(timer, timeoutMs)
    }

    /**
     * يهيّئ المحرك مرة واحدة؛ يعيد true عند الجاهزية.
     * يمنع سباق التهيئة المزدوج (Single-flight): الاستدعاءات المتزامنة أثناء
     * التهيئة تصطّف جميعها وتُستدعى بنتيجة واحدة عند اكتمال onInit.
     *
     * [requestedEngine] محرك صريح لفئةٍ معيّنة (متصل/بطارية/وقت): يُفضَّل إن
     * كان مثبّتاً، ويُعاد ربط المتحدث إن كان مربوطاً بمحركٍ مختلف (تبديل
     * حي بين الفئات)؛ null → محرك اللغة المضبوط أو محرك النظام الافتراضي.
     */
    private fun ensureInit(
        onReady: (Boolean) -> Unit,
        requestedEngine: String? = null
    ) {
        val requested = requestedEngine
            ?.takeIf {
                it in EnginePicker.installedEnginePackages(appContext)
            }
        // مسارٌ جاهز: المثيل الحالي مرتبط فعلاً بنفس المحرك المطلوب —
        // نداء فوري بلا بوابة (لا تهيئة جديدة ولا انتظار دورة).
        if (tts != null && boundEngine == requested) {
            onReady(true)
            return
        }
        // خلاف ذلك يُحسم القرار عبر البوابة (بند [5]): إن كانت تهيئةٌ
        // ما قائمة يُصرف الخطاف عند اكتمال محركٍ مطابق داخل تلك الدورة،
        // وإن كان المحرك مختلفاً ينتظر إعادة تهيئةٍ تُسلسل بعدها — فلا
        // يُنطق النص أبداً بمحركٍ حُسم لاحقاً عن التهيئة الجارية.
        when (initGate.enqueue(requested, onReady)) {
            InitGate.Decision.JOIN -> return
            InitGate.Decision.START -> startInit(requested)
        }
    }

    /** يبدأ تهيئة TextToSpeech لمحركٍ محسوم؛ عند الاكتمال تُصفّى البوابة
     *  خارجها (تخدم النداءات المطابقة وتُسلسل إعادة تهيئةٍ للمحرك
     *  المتبقي بمحركٍ مختلف). */
    private fun startInit(finalEngine: String?) {
        // محرك مختلف للفئة القادمة (أو تهيئة أولى): أُغلق الربط القديم
        // كاملاً ثم أُهيّئ الجديد (لا تبقى مثيلات معلقة على محرك آخر).
        if (tts != null) {
            shutdownSafely()
        }
        val engine = safeEngineForAnnouncement(appContext, finalEngine)
        boundEngine = engine
        var newTts: TextToSpeech? = null
        val timeoutRunnable = Runnable {
            Log.w(TAG, "[Speaker] Engine $engine init timed out")
            val completion = initGate.complete(false)
            completion.served.forEach { cb -> cb(false) }
            if (completion.hasNext) {
                startInit(completion.nextEngine)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 5_000L)
        // **المنشئ الثلاثي الصريح** TextToSpeech(context, listener, engine):
        // الربط المباشر بحزمة المحرك المحسومة صراحة يمنع الحلقات الذاتية.
        newTts = TextToSpeech(appContext, { status ->
            mainHandler.removeCallbacks(timeoutRunnable)
            val success = status == TextToSpeech.SUCCESS
            if (success) {
                // لا تُخزَّن إلا المثيلات الناجحة؛ المثيل الفاشل يُهمَل ولا
                // يظل "جاهزاً" للدورات اللاحقة (كان يفسد المتحدث مسبقاً).
                tts = newTts
            } else {
                tts = null
            }
            // تصفية البوابة خارجها: تُصرف النداءات المطابقة لمحرك هذه
            // التهيئة فقط، وما بقي بمحركٍ مختلف يُعاد تهيئته بعدها.
            val completion = initGate.complete(success)
            completion.served.forEach { cb -> cb(success) }
            if (completion.hasNext) {
                startInit(completion.nextEngine)
            }
        }, engine)
        newTts.apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        nowSpeaking = true
                    }

                    @Deprecated("Java Override")
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) {
                            activeUtteranceIds.remove(utteranceId)
                            pendingUnitsForResume.removeAll {
                                it.first == utteranceId
                            }
                            if (pendingUnitsForResume.isEmpty() &&
                                isResumableCategory(currentCategory)
                            ) {
                                resumableAnnouncement = null
                            }
                        }
                        val isFinal = (utteranceId != null &&
                            utteranceId == lastQueuedUtteranceId) ||
                            activeUtteranceIds.isEmpty()
                        try {
                            if (isFinal) {
                                cancelSpeechWatchdog()
                                stopInterruptionMonitoring()
                            }
                        } finally {
                            if (isFinal) releaseAudioFocus()
                        }
                        if (isFinal) {
                            notifySpeechComplete()
                            nowSpeaking = false
                            currentCategory = null
                            checkAndResumeInterruptedSpeech()
                        }
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int
                    ) {
                        handleFailure(utteranceId)
                    }

                    override fun onStop(
                        utteranceId: String?,
                        interrupted: Boolean
                    ) {
                        handleFailure(utteranceId)
                    }

                    @Deprecated("Java Override")
                    override fun onError(utteranceId: String?) {
                        handleFailure(utteranceId)
                    }

                    private fun handleFailure(utteranceId: String?) {
                        if (utteranceId != null) {
                            activeUtteranceIds.remove(utteranceId)
                        }
                        val shouldClean = (utteranceId != null &&
                            utteranceId == lastQueuedUtteranceId) ||
                            activeUtteranceIds.isEmpty()
                        try {
                            if (shouldClean) {
                                cancelSpeechWatchdog()
                                stopInterruptionMonitoring()
                            }
                        } finally {
                            if (shouldClean) releaseAudioFocus()
                        }
                        if (shouldClean) {
                            notifySpeechComplete()
                            nowSpeaking = false
                            currentCategory = null
                        }
                    }
                })

            // سمات نطق الإعلانات: مسار الوسائط افتراضياً (لا مسار إتاحة ولا
            // تبعية لحالة قارئ الشاشة بعد قفل النطق العابر) — تُطبَّق عند
            // الربط وتُعاد كل دورة فقط إذا اختلفت فعلياً. ولا فئةَ حالية
            // هنا، فيُمنح المسارُ الافتراضي ثم يُصحَّح في
            // [applySpeechAudioAttributes] عند أوّل نطقٍ فعلي.
            lastAppliedAudioAttributes = null
            applySpeechAudioAttributes(null)
            // يُربط المحرك مباشرةً عبر المنشئ الثلاثي أعلاه — لا داعٍ
            // لـ setEngineByPackageName (مُهملٍ ويُعاد ربطه بالكائن قسراً).
        }
    }

    /**
     * ينطق نصاً (يدفع طابور نطق جديد).
     * @param text النص المراد نطقه
     * @param locale لغة النص لتحديد صوت المحرك المناسب
     * @param speechRate سرعة النطق (1.0 = طبيعي)
     * @param pitch النبرة (1.0 = طبيعي)
     * @param volume مستوى الصوت (0.0..1.0) — يُطبّق عبر معامل الصوت إن أمكن
     *
     * عند تفعيل «نطق الإيموجي» يُقسَّم النص تلقائياً إلى مقاطع، ويُنطق كل اسم
     * إيموجي بإعدادات فئة «نطق الإيموجي» المستقلة (صوت/سرعة/نبرة/مستوى صوت)
     * عبر جملة متتابعة بعده — فلا تُقرأ أسماء الإيموجي بالصوت الافتراضي.
     */
    fun speak(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        engineOverride: String? = null,
        cue: AudioCue? = null,
        category: String? = null
    ) {
        // إعدادات نطق الإيموجي تُحسم قبل طلب التركيز حتى تكون المقاطع جاهزة
        // للدورة (بلا قراءة متكررة للإعدادات عند كل عودة تركيز).
        val emojiCfg = resolveEmojiConfig(locale)
        val parts = if (emojiCfg != null) {
            EmojiSpeech.split(text, emojiCfg.arabic)
        } else {
            null
        }
        // محركُ الدورة المحسوم: الفئة الصريحة أو محركُ اللغة المضبوط —
        // يُربط المتحدثُ به في كل الفئات (تغطية شكوى «أصوات محددة لا تتغير»).
        val resolvedEngine = resolveAnnouncementEngine(
            engineOverride,
            runCatching {
                settings?.getEngineForLanguage(locale.language)
            }.getOrNull()
        )

        // نُفوض النطق دائماً لمحركٍ مثبّت (منهج MultiTTS):
        // يستبعد اختيار المحرك
        // حزمة LORD نفسها، فيمرّ `tts.speak()` عبر محركٍ خارجي مستقر بدل حلقة
        // ربط النظام TextToSpeech → خدمة LORD التي قد تُسقط الصوت على Samsung.

        // أندرويد 15+ يقيد صوت الخلفية: النطق من مستقبلات
        // المتصل/الرسائل/الإشعارات لا يُضمن دون خدمة أمامية.
        // نشغّل خدمة الإعلانات (specialUse) إن لم تكن قائمة
        // حتى تُحتسب العملية "أمامية" وتسمح للـ TTS الخارجي
        // بالنطق.
        try {
            if (!AnnouncementSchedulerService.isRunning) {
                AnnouncementSchedulerService.startIfNeeded(appContext)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scheduler service start failed", t)
        }

        if (shouldPreemptCurrentSpeech(category)) {
            val remaining = pendingUnitsForResume.map { it.second }
            if (remaining.isNotEmpty()) {
                resumableAnnouncement = ResumableAnnouncement(
                    units = remaining,
                    engineOverride = boundEngine,
                    category = currentCategory,
                    voiceId = voiceId
                )
            }
        } else if (!isEventCategory(category) &&
            !isResumableCategory(category)
        ) {
            resumableAnnouncement = null
        }

        val gen = speechGeneration.incrementAndGet()
        currentCategory = category
        val isEvent = isEventCategory(category)
        val speakAction = {
            launchWithCue(
                gen, text, locale, speechRate, pitch, volume,
                emojiCfg, parts, resolvedEngine, cue,
                immediate = isEvent
            )
        }
        // **قفل النطق العابر:** إن كان محرك التخليق (:tts) ينطق حالياً
        // (قراءة قارئ الشاشة فوق content://…/speaking) فلا ننطق فوقه —
        // نؤجل الإعلان حتى يهدأ القفل أو تنقضي مهلة الأمان. فئات الأحداث
        // (الساعة، البطارية، المتصل) تتجاوز القفل؛ والجميع يمر عبر
        // speakWithFocus احتراماً لنتائج التركيز (Delayed/Failed/Granted).
        if (!isEvent && SpeechLock.isSpeaking(appContext)) {
            enqueueWhileSpeaking(gen, speakAction)
            return
        }
        speakWithFocus(gen, speakAction)
    }

    /** يطلب Audio Focus ويتصرف حسب النتيجة (Delayed/Failed/Granted).
     *  [focusRetries] ما تبقى من محاولات إعادة الجدولة بعد رفضٍ للتركيز
     *  (لا ننطق فوق مشغّلٍ محجوز — المكالمة/الوسائط)؛ نفادُها إسقاطٌ صامت. */
    private fun speakWithFocus(
        gen: Long,
        speakAction: () -> Unit,
        focusRetries: Int = MAX_FOCUS_RETRIES
    ) {
        speechDispatchedCount++
        // نتيجة منح التركيز تُحترم: على أندرويد 17 قد يُنبّه النظام بطلبٍ
        // مؤجل (DELAYED) أو مرفوض (FAILED) بدل المنح الفوري.
        when (requestAudioFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // التركيز سيُسلَّم لاحقاً عبر onAudioFocusChange؛ ننتظر وصول
                // AUDIOFOCUS_GAIN ثم ننطق. مؤقّت الأمان يحرّر الإعلان ما دام
                // التركيز قد تحرّر فعلاً (لا نطق أبداً والتركيز
                // ما يزال محجوزاً
                // لمشغّلٍ آخر — المكالمة الهاتفية أشهره — فيتداخل معه الصوت).
                pendingFocusAction = speakAction
                val timer = Runnable {
                    val action = pendingFocusAction
                    pendingFocusAction = null
                    pendingFocusTimer = null
                    // حارس المسار المؤجل: ننطق عند انقضاء المهلة فقط إن بلغنا
                    // التركيز فعلاً؛ وإلا إلغاءٌ صامت (بدل إجبار النطق فوق
                    // مكالمةٍ أو وسائطَ صارمةٍ حجزت التركيز، كما كان يحدث).
                    if (hasAudioFocus) {
                        action?.invoke()
                    } else if (isEventCategory(currentCategory) &&
                        audioManager.mode != AudioManager.MODE_IN_CALL &&
                        audioManager.mode !=
                            AudioManager.MODE_IN_COMMUNICATION
                    ) {
                        Log.w(
                            TAG,
                            "[Focus] DELAYED انقضت المهلة —" +
                            " نطق أفضل جهد"
                        )
                        releaseAudioFocus()
                        action?.invoke()
                    } else {
                        Log.w(
                            TAG,
                            "[Focus] DELAYED أُلغيت الصامتة:" +
                            " التركيز لم يُسلَّم"
                        )
                        // **تحرير التركيز عند المهلة:** طلبُنا المؤجل ما زال
                        // مسجلاً بالنظام؛ حين يحرر
                        // المشغّل الآخرُ الصوتَ لاحقاً
                        // سيُسلَّم drift إلينا فنحتجزه للأبد ونخفض موسيقاه
                        // (Ducking دائم). الإلغاء هنا يُخلّي الطلب ويمنع
                        // تسريب التركيز.
                        releaseAudioFocus()
                        notifySpeechComplete()
                    }
                }
                pendingFocusTimer = timer
                mainHandler.postDelayed(timer, 3000L)
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                // رفض التركيز (مشغّل آخر حجز الوسائط) — لا نطق فوقه؛ نعيد
                // الجدولة بفاصل وجيز مع التحقق من عدم تقادم دورة النطق،
                // حتى منحٍ أو نفاد المحاولات (ثم إسقاطٌ صامت صريح بدل حلقة
                // لا نهائية فوق الأغنية/المكالمة).
                if (focusRetries > 0) {
                    Log.w(
                        TAG,
                        "[Focus] FAILED — إعادة جدولة" +
                        " (تبقّى $focusRetries)"
                    )
                    mainHandler.postDelayed({
                        if (gen == speechGeneration.get()) {
                            speakWithFocus(gen, speakAction, focusRetries - 1)
                        }
                    }, FOCUS_RETRY_DELAY_MS)
                } else if (isEventCategory(currentCategory) &&
                    audioManager.mode != AudioManager.MODE_IN_CALL &&
                    audioManager.mode != AudioManager.MODE_IN_COMMUNICATION
                ) {
                    Log.w(
                        TAG,
                        "[Focus] FAILED — نطق أفضل جهد" +
                        " للحدث دون تركيز"
                    )
                    releaseAudioFocus()
                    speakAction()
                } else {
                    Log.w(
                        TAG,
                        "[Focus] FAILED — إسقاط صامت" +
                        " بعد نفاد إعادة الجدولة"
                    )
                    releaseAudioFocus()
                    notifySpeechComplete()
                }
            }
            else ->
                // AUDIOFOCUS_REQUEST_GRANTED:
                // التركيز مُنح فوراً — ننطق مباشرة.
                speakAction()
        }
    }

    /** إلحاق إعلانٍ بانتظار انتهاء قراءة قارئ الشاشة: يُحرَّر كلُّ الطابور
     *  عند هبوط علم «نطق جارٍ» (ContentObserver على content://…/speaking)
     *  أو عند انقضاء مهلة الأمان القصوى — مع التحقق من سلامة دورة النطق. */
    private fun enqueueWhileSpeaking(gen: Long, speakAction: () -> Unit) {
        deferredWhileSpeaking.add {
            speakWithFocus(speechGeneration.get(), speakAction)
        }
        registerSpeakingLockObserverIfNeeded()
        if (speakingLockTimeout == null) {
            val timer = Runnable {
                speakingLockTimeout = null
                // المهلة القصوى انتهت — ننطق ما ينتظر على أي حال (لا ضياع
                // إعلان) حتى لو هَبَطَ القفل قبل انقضائها.
                flushDeferredWhileSpeaking(force = true)
            }
            speakingLockTimeout = timer
            mainHandler.postDelayed(timer, SPEAKING_WAIT_TIMEOUT_MS)
        }
    }

    private fun registerSpeakingLockObserverIfNeeded() {
        if (speakingLockObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                flushDeferredWhileSpeaking(force = false)
            }
        }
        speakingLockObserver = observer
        runCatching {
            appContext.contentResolver.registerContentObserver(
                SettingsChangeProvider.speakingUri(), false, observer
            )
        }
    }

    private fun unregisterSpeakingLockObserver() {
        val observer = speakingLockObserver ?: return
        speakingLockObserver = null
        runCatching {
            appContext.contentResolver.unregisterContentObserver(observer)
        }
    }

    /** تحرير إعلانات الانتظار بالترتيب؛ [force] يظل طوعيَّ المهلة: يُنطق
     *  حتى لو كان القفل ما يزال مرفوعاً (لم يعد الانتظار مجدياً). */
    private fun flushDeferredWhileSpeaking(force: Boolean) {
        if (deferredWhileSpeaking.isEmpty()) {
            unregisterSpeakingLockObserver()
            return
        }
        if (!force && SpeechLock.isSpeaking(appContext)) return
        speakingLockTimeout?.let { mainHandler.removeCallbacks(it) }
        speakingLockTimeout = null
        if (force) {
            runCatching { SpeechLock.setSpeaking(appContext, false) }
        }
        val pending = ArrayList<() -> Unit>(deferredWhileSpeaking)
        deferredWhileSpeaking.clear()
        unregisterSpeakingLockObserver()
        pending.forEach { runCatching { it() } }
    }

    /**
     * يقرأ إعدادات فئة «نطق الإيموجي» من الإعدادات؛ يعيد null عند التعطيل
     * (يبقى السلوك القديم: استبعاد الإيموجي من النطق).
     */
    private fun resolveEmojiConfig(baseLocale: Locale): EmojiSpeechConfig? {
        return try {
            val settings =
                (appContext as? AnnouncementAppContext)
                    ?.settingsRepository
                ?: SettingsRepository.create(appContext)
            if (!settings.isEmojiPronunciationEnabled()) return null
            val voiceId = settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            EmojiSpeechConfig(
                voiceId = voiceId,
                // لغة التسمية: صوت الإيموجي المختار يحددها،
                // وإلا فتمرّ للغة النص الفعلية
                arabic = if (voiceId != null) {
                    !isEnglishVoiceName(voiceId)
                } else {
                    baseLocale.language.let { LanguageCode.isArabic(it) }
                },
                rate = settings.getSpeechRateForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                ),
                pitch = settings.getPitchForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                ),
                volume = settings.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "emoji config resolve failed", t)
            null
        }
    }

    /**
     * سمات نطق الأحداث والإعلانات.
     *
     * **فئة المتصل وحدها** على مسار **الإشعار** (`USAGE_NOTIFICATION_EVENT`
     * / `STREAM_NOTIFICATION`) لا مسار الوسائط. **لماذا؟** لأن إعلان المتصل
     * هو الفئةُ الوحيدة التي تُنطق فوق مكالمةٍ جارية، والنظام يخفض صوت
     * `STREAM_MUSIC` تلقائياً أثناء المكالمة (Voice-call Ducking) فيصوت
     * منخفضاً، بينما يعلو نطقُ البطارية والساعة لأن النظام لا يخفضهما.
     * فالمعاملُ في الحالتين 1.0 عند الافتراضي، فالسببُ المسارُ لا المستوى،
     * ولهذا لا يفيد رفعُ شريط مستوى صوت المتصل أصلاً. وقد ظهر الفرقُ
     * على أجهزة بعينها (Pixel بندرويد 17) لأن شدّة التخفيض تختلف بين
     * الأجهزة وإصدارات النظام.
     *
     * مسارُ الإشعار لا تخفضه مكالمةٌ جارية فيصعد الصوت كما تصعد البطارية،
     * ولا يقع في فخّ «الإتاحة شبه الصامت» على سامسونج (كما يقع لو
     * استُعمل `USAGE_ACCESSIBILITY`)، وسمّته `SONIFICATION` لا تُعدّ
     * الموسيقى فيفضّلها النظام، ويبقى قابلاً للرفع إلى قمّته مع
     * [boostStreamVolume].
     *
     * وبقية الفئات على مسار الوسائط كما كان — نفس قناة البطارية.
     *
     * **والسماتُ لا تُغيَّر فوقَ مكالمةٍ جارية (بند 5) — انحرافٌ
     * مقصود:** قرارُ المدير القناةَ وحدها عبر `KEY_PARAM_STREAM`،
     * فتبقى `USAGE_NOTIFICATION_EVENT`. فقاعدةُ «يجب أن تتطابق» أعلاه
     * مستثناةٌ هنا، **وهذا مقايضةٌ موعودةٌ لا نتيجةَ مثبتة**: لم
     * تُكتب السماتُ على مسار المكالمة فلا نضمن أن يجتاز الصوتُ
     * معالجةَ المسار (إلغاءُ الصدى) فلا يتسرّب للطرف الآخر. وهو ما
     * يبقي **إثباتَ عدم التسريب على جهازٍ حقيقيٍّ بمكالمةٍ فعلية**
     * مطلوباً. فلو ثبت التسرّب فالمحاولةُ التالية هي
     * `USAGE_VOICE_COMMUNICATION` — وهي تعديلٌ للسمات لا للقناة.
     */
    private fun speechAudioAttributes(category: String?): AudioAttributes {
        val builder = AudioAttributes.Builder()
        return if (isCallerCategory(category)) {
            builder
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        } else {
            builder
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }
    }

    /** إعادة تطبيق سمات النطق فقط إذا اختلفت فعلياً عن المطبَّقة (بلا
     *  اعتماد على حالة قارئ الشاشة — انظر [speechAudioAttributes]). */
    private fun applySpeechAudioAttributes(category: String?) {
        val attributes = speechAudioAttributes(category)
        if (attributes == lastAppliedAudioAttributes) return
        lastAppliedAudioAttributes = attributes
        try {
            tts?.setAudioAttributes(attributes)
        } catch (t: Throwable) {
            Log.w(TAG, "setAudioAttributes failed", t)
        }
    }

    /**
     * تشغيل المؤثر الصوتي إن وُجد ثم النطق؛ أو النطق مباشرة.
     * يتحقق من عدم تقادم المسار (speechGeneration) لمنع
     * النطق القديم بعد stop()/speak جديد.
     */
    private fun launchWithCue(
        gen: Long,
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?,
        engineOverride: String?,
        cue: AudioCue?,
        immediate: Boolean = false
    ) {
        if (gen != speechGeneration.get()) {
            releaseAudioFocus()
            notifySpeechComplete()
            return
        }
        if (cue == null) {
            startSpeech(
                text, locale, speechRate, pitch, volume,
                emojiCfg, parts, engineOverride,
                immediate = immediate
            )
            return
        }
        // **تراكب تهيئة المحرك مع النغمة (بند ب.txt 3.7):** ربط TTS يكلف
        // 150–800ms؛ نبدأه أثناء عزف المؤثر فيُحجب معظمُها تحت النغمة ويأتي
        // الكلامُ فور انتهائها على محركٍ دافئ بدل «نغمة ← ربط ← كلام».
        // [ensureInit] بوابةُ طيرانٍ مفرد آمنة التزامن — يعاود استدعاءُ
        // startSpeech اللاحق الانضمام إليها بلا سباقٍ ولا تكرار تهيئة.
        ensureInit({ _ -> }, engineOverride)
        AudioCuePlayer.getInstance(appContext).play(cue) { _ ->
            if (gen == speechGeneration.get()) {
                startSpeech(
                    text, locale, speechRate, pitch, volume,
                    emojiCfg, parts, engineOverride,
                    immediate = true
                )
            } else {
                // تقادم دورة النطق أثناء عزف النغمة (stop()/نطق أحدث)
                // فيُحرَّر التركيز هنا بدل بقائه محجوزاً صامتاً.
                releaseAudioFocus()
                notifySpeechComplete()
            }
        }
    }

    /** تهيئة المحرك ثم نطق المقاطع بتأجيل قصير يسمح لاتصال TTS بالاستقرار. */
    private fun startSpeech(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?,
        engineOverride: String? = null,
        immediate: Boolean = false
    ) {
        val wasWarm = tts != null && boundEngine == (
            engineOverride?.takeIf {
                it in EnginePicker.installedEnginePackages(appContext)
            }
        )
        val executeSpeech = {
            if (immediate && wasWarm) {
                doSpeakParts(
                    text, locale, speechRate, pitch, volume,
                    emojiCfg, parts, attempt = 1
                )
            } else {
                // تأجيل قصير يسمح لاتصال محرك TTS بالاستقرار بعد
                // onInit (حتى لو أعلن Success مبكراً، قد يبقى ربط
                // النظام معلقاً لحظياً ويُسقط speak فورياً كما في Vocalizer).
                val delayMs = if (immediate) 60L else 80L
                mainHandler.postDelayed({
                    doSpeakParts(
                        text, locale, speechRate, pitch, volume,
                        emojiCfg, parts, attempt = 1
                    )
                }, delayMs)
            }
        }
        ensureInit({ ready ->
            if (ready) {
                executeSpeech()
            } else if (engineOverride != null) {
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride —" +
                    " التراجع لمحرك بديل"
                )
                val fallback = safeEngineForAnnouncement(
                    appContext, null
                )
                if (fallback != null && fallback != engineOverride) {
                    ensureInit({ fallbackReady ->
                        if (fallbackReady) {
                            executeSpeech()
                        } else {
                            releaseAudioFocus()
                            notifySpeechComplete()
                        }
                    }, requestedEngine = fallback)
                } else {
                    releaseAudioFocus()
                    notifySpeechComplete()
                }
            } else {
                releaseAudioFocus()
                notifySpeechComplete()
            }
        }, engineOverride)
    }

    /** ينطق المقاطع بالتتابع: النصوص بصوت الإعلان (النص المختلط الكتابات
     *  يُقسَّم إلى مقاطع لغوية فيُنطق كلٌّ بلغته وصوته — بند 17)، وأسماء
     *  الإيموجي بصوت فئتها. */
    private fun doSpeakParts(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?,
        attempt: Int
    ) {
        val gen = speechGeneration.get()
        // **بند الأداء (خارج خيط الواجهة):** بناء وحدات النطق (معالجة
        // الدلالات وفصل اللغات والتحويل الرقمي) عبءٌ ثقيل كان يجري على
        // خيط الواجهة عند كل إعلان فيجمّده لحظياً — ننقله إلى منفّذ خلفي
        // تسلسلي ثم نعيد إرسال الأجزاء إلى الخيط الرئيسي فقط إن بقيت
        // دورةُ النطق سليمة (تقدمت دورةٌ أحدث أثناء المعالجة = إسقاط).
        textProcessorExecutor.execute {
            val built = try {
                mergeAdjacentSameVoice(
                    buildSpeakUnits(
                        text, locale, speechRate, pitch, volume, emojiCfg, parts
                    )
                )
            } catch (t: Throwable) {
                // خطأ متزامن أثناء بناء الوحدات (قاموس مفقود...) — تحرير
                // التركيز حتى لا يبقى محجوزاً لمثل هذا الإعلان (بند 2.3).
                mainHandler.post {
                    if (gen == speechGeneration.get()) {
                        cancelSpeechWatchdog()
                        stopInterruptionMonitoring()
                        releaseAudioFocus()
                        notifySpeechComplete()
                        Log.w(TAG, "doSpeakParts failed", t)
                    }
                }
                return@execute
            }
            mainHandler.post {
                if (gen == speechGeneration.get()) {
                    sendSpeakUnits(built, attempt)
                } else {
                    releaseAudioFocus()
                    notifySpeechComplete()
                }
            }
        }
    }

    /** إرسال وحدات النطق الجاهزة إلى المحرك بالتتابع (من الخيط الرئيسي
     *  حصراً): يرفع عداد الدورة، يبدأ رصد الإسكات، يطبق السمات، ثم يُرسل
     *  الأجزاء ويُسلِّح محرسَ انتهاء النطق. */
    private fun sendSpeakUnits(units: List<SpeakUnit>, attempt: Int) {
        val validUnits = units.filter { it.text.isNotBlank() }
        if (validUnits.isEmpty()) {
            cancelSpeechWatchdog()
            stopInterruptionMonitoring()
            releaseAudioFocus()
            notifySpeechComplete()
            nowSpeaking = false
            currentCategory = null
            return
        }
        // **بند 5.1:** أول جزءٍ يُرسل فعلياً يرفع عداد الدورة — سجّلته هنا
        // الأداةُ قبل طلب النطق، فيرفض خطافُها اكتمالَ أي دورةٍ سبقته.
speechCycle.incrementAndGet()
        startInterruptionMonitoring()
        // رفع حجم القناة الصوتية مؤقتاً لكل نطق يُرسل (المتصل والساعة والأحداث
        // بالكامل): يُحفظ المستوى الأصلي ويُستعاد عند الاكتمال/الإيقاف عبر
        // restoreBoostedStreamVolume. تُرفع القناةُ التي يمرّ عليها نطقُ
        // الفئة الحالية — الإشعارُ للمتصل والوسائطُ لغيره (انظر
        // speechAudioAttributes) فلا يُرفع ما لا يُنطق عليه.
        boostStreamVolume(currentCategory)
        // خفضُ الرنين لفئة المتصل وحدها (اختياريّ، بمربّع المستخدم):
        // له نفسُ عمرِ الرفع فيُستعاد في releaseAudioFocus مع مسارات
        // الإنهاء كلّها.
        duckRingVolumeIfCallerCategory(currentCategory)
        // السمات تُطبق عند كل دورة إن اختلفت فعلياً (لا تتبع القارئ).
        applySpeechAudioAttributes(currentCategory)
        val unitUtteranceIds = validUnits.map { nextUtteranceId() }
        lastQueuedUtteranceId = unitUtteranceIds.lastOrNull()
        pendingUnitsForResume.clear()
        if (isResumableCategory(currentCategory)) {
            validUnits.forEachIndexed { index, unit ->
                pendingUnitsForResume.add(unitUtteranceIds[index] to unit)
            }
        }
        try {
            validUnits.forEachIndexed { index, unit ->
                val queueMode = if (index == 0) {
                    // أولُ جزءٍ يصل بـ FLUSH يُبطل كل معرّفات الدورات السابقة
                    invalidateActiveUtterances()
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                }
                doSpeak(
                    unit.text,
                    unit.locale,
                    unit.rate,
                    unit.pitch,
                    unit.volume,
                    partVoice = unit.voiceId,
                    queueMode = queueMode,
                    attempt = attempt,
                    assignedUtteranceId = unitUtteranceIds[index]
                )
            }
            // حد ختام للدورة: مهما طال النص يُفتح حارس الانتهاء قبل إرسال
            // الأجزاء ليلتقط أي محرك يعلّق صامتاً (بلا onDone/onError).
            armSpeechWatchdog(validUnits)
        } catch (t: Throwable) {
            // أي استثناء متزامن أثناء إرسال الوحدات (محرك مكسور،
            // خطأ معاملات...) يقع قبل تسليح الحارس — فيُحرَّر التركيز
            // ويرتفع رصد الإسكات فوراً حتى لا يبقى النظام محجوزاً صامتاً
            // (الحماية المؤقتة الصارمة للتركيز — نصيحة المراجعة 3).
            cancelSpeechWatchdog()
            stopInterruptionMonitoring()
            releaseAudioFocus()
            notifySpeechComplete()
            Log.w(TAG, "sendSpeakUnits failed", t)
        }
    }

    /** بيانات جلسة إشعار/رسالة تم مقاطعتها بحدث آني؛ تُحفظ لاستئنافها فوراً. */
    internal data class ResumableAnnouncement(
        val units: List<SpeakUnit>,
        val engineOverride: String?,
        val category: String?,
        val voiceId: String?
    )

    internal fun shouldPreemptCurrentSpeech(
        incomingCategory: String?
    ): Boolean {
        return nowSpeaking &&
            isResumableCategory(currentCategory) &&
            isEventCategory(incomingCategory) &&
            !isResumableCategory(incomingCategory)
    }

    private fun checkAndResumeInterruptedSpeech() {
        val toResume = resumableAnnouncement ?: return
        resumableAnnouncement = null
        if (toResume.units.isEmpty()) return
        mainHandler.postDelayed({
            resumeInterruptedSpeech(toResume)
        }, 80L)
    }

    private fun resumeInterruptedSpeech(target: ResumableAnnouncement) {
        if (nowSpeaking) return
        currentCategory = target.category
        voiceId = target.voiceId
        val gen = speechGeneration.incrementAndGet()
        ensureInit({ ready ->
            if (ready && gen == speechGeneration.get()) {
                sendSpeakUnits(target.units, attempt = 1)
            }
        }, target.engineOverride)
    }

    /** وحدة نطق مستقلة بمعاملاتها (لغة/صوت/أشرطة)
     *  داخل دورة الإعلان الواحدة. */
    internal data class SpeakUnit(
        val text: String,
        val locale: Locale,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val voiceId: String?
    )

    private fun buildSpeakUnits(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?
    ): List<SpeakUnit> {
        val units = ArrayList<SpeakUnit>()
        val segments = parts ?: listOf(SpeechPart(text, false))
        segments.forEach { part ->
            if (part.isEmojiName && emojiCfg != null) {
                val emojiLocale = if (emojiCfg.arabic) {
                    Locale.forLanguageTag(LanguageCode.AR.tag)
                } else {
                    Locale.forLanguageTag(LanguageCode.EN.tag)
                }
                units.add(
                    SpeakUnit(
                        part.text, emojiLocale, emojiCfg.rate,
                        emojiCfg.pitch, emojiCfg.volume,
                        emojiCfg.voiceId
                    )
                )
            } else {
                addLanguageUnits(
                    units, part.text, locale,
                    speechRate, pitch, volume
                )
            }
        }
        return units
    }

    /** **دمجُ المقاطعِ المتعاقبةِ ذاتِ الصوتِ الواحدِ** (بندُّ 17 + 18):
     *  عندَ نطقِ نصٍّ مختلطِ الكتاباتِ أو إعلانٍّ يحتوي أجزاءً متعددةً
     *  (محدد/غير محدد مثلاً) يُقسِّمُه المحرِّكُ إلى مقاطعَ كلٌّ منها
     *  SpeakUnit بنداءِ speak منفصلٍ — فيُولِّدُ فجواتَ استحواذٍ (~ثانيةٍّ)
     *  بينَ كلِّ مقاطعٍ → سكتاتٌ ظاهرةٌّ متعددةٌّ. الدمجُ الآنَّ: مقطعانِ
     *  متتاليانِ على **صوتٍّ واحدٍّ** (voiceId متساوٍّ أو كلاهما null)
     *  يُدمَجانِ مهما اختلفا في rate/pitch/volume — بمعاملاتِ المقطعِ
     *  الأخيرِ — فينطقانِ بنداءٍ speak واحدٍّ متصلٍّ بلا فجوةٍّ (بند 17).
     *
     *  **لا دمجَ** في حالتَيْنِ:
     *  1) صوتانِ مختلفانِ (voiceId مختلفانِ): تبقى المقاطعُ منفصلةً
     *     (أصواتُ المستخدمِ المخصصةُ لكلِّ لغةٍ — بندُّ 17).
     *  2) **كلمةٌ إنجليزيةٌ مفردةٌ** (بلا مسافةٍ وبنطاقٍ غير عربيٍّ)
     *     تلي مقطعاً عربياً: تبقى وحدةً مستقلةًّ بصوتِ الإنجليزيةِ
     *     لئلاَّ يقرأها الصوتُ العربيُّ (بندُّ 18). */
    private fun mergeAdjacentSameVoice(
        units: List<SpeakUnit>
    ): List<SpeakUnit> {
        if (isResumableCategory(currentCategory)) {
            return units
        }
        if (units.size < 2) return units
        val merged = ArrayList<SpeakUnit>(units.size)
        for (unit in units) {
            val last = merged.lastOrNull()
            val sameVoice = last != null &&
                last.voiceId == unit.voiceId
            // كلمةٌ إنجليزيةٌ مفردةٌ تلي مقطعاً عربياً = مستقلة (بند 18).
            val loneEnAfterAr = sameVoice &&
                !unit.text.contains(' ') &&
                last.locale.language == LanguageCode.AR.tag &&
                unit.locale.language != LanguageCode.AR.tag
            if (sameVoice && !loneEnAfterAr) {
                merged[merged.size - 1] = last.copy(
                    text = last.text + " " + unit.text
                )
            } else {
                merged.add(unit)
            }
        }
        return merged
    }

    /** يضمّ نصاً (قد يكون مختلط الكتابات) للوحدات كلٍّ
     *  بلغةٍ مناسبة: عربي ← صوت
     *  الإعلان الحالي ولغته، إنجليزية/غيرها ← الصوت
     *  الإنجليزي المفضّل (إن حُفظ)
     *  وإلا صوت الإعلان إن كان إنجليزياً وإلا
     *  لسان محركٍ إنجليزي (بند 17). */
    private fun addLanguageUnits(
        out: MutableList<SpeakUnit>,
        text: String,
        baseLocale: Locale,
        baseRate: Float,
        basePitch: Float,
        baseVolume: Float
    ) {
        val numberLanguage = runCatching {
            settings?.getNumberReadingLanguage()
        }.getOrNull() ?: LanguageCode.AR.tag
        val secondaryLanguage = runCatching {
            settings?.getSecondaryLanguage()
        }.getOrNull() ?: LanguageCode.EN.tag
        // معالجةُ المعاني تُطبَّق قبل تقسيم اللغة حتى لا يفصل المقسمُ رمز
            // العملة/الوحدة عن مبلغه (يُقسَّم «1500 USD» مقطعاً واحداً).
            // اللغة المختارة لغةُ السياق (locale) لا العربية الثابتة — كانت
            // عربيةً دائماً فتُنطق الإعلاناتُ الإنجليزية المختلطة معانٍ
            // عربيةً رغم لغةِ قراءتها الإنجليزية.
            val semanticText = runCatching {
                textProcessor.processSemantics(text, baseLocale.language)
            }.getOrDefault(text)
        val languageSegments = runCatching {
            languageSegmenter.segment(
                semanticText,
                fallbackLanguage = baseLocale.language,
                secondaryLanguage = secondaryLanguage,
                numberLanguage = numberLanguage
            )
        }.getOrDefault(emptyList())
        val effective = if (languageSegments.isEmpty()) {
            listOf(Segment(semanticText, LanguageCode.AR.tag))
        } else {
            languageSegments
        }
        val enVoice = englishFallbackVoice()
        effective.forEach { segment ->
            val arabic = LanguageCode.isArabic(segment.languageTag)
            val segmentLocale = if (arabic) {
                baseLocale
            } else {
                Locale.forLanguageTag(LanguageCode.EN.tag)
            }
            // الرقم البحت (بلا حروف) يُنطق بصوتِ فئة الأرقام المحفوظ
            // وأشرطتها لا بالصوت العام — بلا تخصيصٍ للفئة يبقى السلوك
            // الحالي (المسار الموازي المطلوب لنطق الأرقام بالإعلانات).
            val numbersSpeech = numbersCategoryFor(segment)
            val segmentVoice = numbersSpeech?.voiceId
                ?: if (arabic) voiceId else enVoice
            // بند الأوامر 1: معالجة نص المقطع عبر TextProcessor بلغته (أرقام،
            // أوقات، عملات، روابط...) قبل إرساله للمحرك — موحّداً مع القارئ.
            // أي خطأ في المعالجة (قاموس مفقود...) يُسقط النص الخام لا الصمت.
            val readyText = runCatching {
                textProcessor.process(
                    segment.text, segment.languageTag, boundEngine
                )
            }.getOrDefault(segment.text)
            if (isResumableCategory(currentCategory)) {
                val sentences = splitIntoSentences(readyText)
                sentences.forEach { sentence ->
                    // سقفٌ إضافي للمقطع الواحد: الجملة الواحدة قد
                    // تتجاوز حدّ المحرك فيُبتَر نطقُها — فيُقسَّم
                    // كلُّ نصٍّ أطولُ من [SpeechChunker.MAX_CHARS]
                    // إلى مقاطعَ قصيرةٍ عند كلماتٍ واضحة.
                    SpeechChunker.split(sentence).forEach { piece ->
                        out.add(
                            SpeakUnit(
                                piece, segmentLocale,
                                numbersSpeech?.rate ?: baseRate,
                                numbersSpeech?.pitch ?: basePitch,
                                numbersSpeech?.volume ?: baseVolume,
                                segmentVoice
                            )
                        )
                    }
                }
            } else {
                // النطق العادي (غير قابلة للاستئناف): يُقسَّم كل
                // مقطع لغوي طويل إلى مقاطع [SpeechChunker] تصلُ
                // متواصلةً بلا فجوات سكتٍّ إضافية (لأننا ندمج
                // المتعاقب ذي الصوت نفسه لاحقاً إن لم يكن resumable).
                SpeechChunker.split(readyText).forEach { piece ->
                    out.add(
                        SpeakUnit(
                            piece, segmentLocale,
                            numbersSpeech?.rate ?: baseRate,
                            numbersSpeech?.pitch ?: basePitch,
                            numbersSpeech?.volume ?: baseVolume,
                            segmentVoice
                        )
                    )
                }
            }
        }
    }

    /** إعداد نطق فئة الأرقام للمقطع الرقمي البحت — صوتُ الفئة المحفوظ
     *  وأشرطتها (يُطبَّق الصوت عبر [doSpeak]/voiceFor عند البث)؛ أو null
     *  ليُسلك صوت اللغة العام للوحدة. استعلامات مطابقة لفئة الأرقام في
     *  [TimeAnnouncementManager]. */
    private fun numbersCategoryFor(segment: Segment): NumbersCategorySpeech? {
        if (!segment.isNumericOnly()) return null
        val repo = settings ?: return null
        val voiceId = runCatching {
            repo.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrNull() ?: return null
        val rate = runCatching {
            repo.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        val pitch = runCatching {
            repo.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        val volume = runCatching {
            repo.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        return NumbersCategorySpeech(voiceId, rate, pitch, volume)
    }

    /** صوتُ الإنجليزية المفضّل لسقوط مقاطع «en» في الإعلانات المختلطة. */
    private fun englishFallbackVoice(): String? {
        // صوتُ EN المخصص (إن حُفظ في إعدادات اللغة)؛ وإلا صوت الإعلان الحالي
        // إن كان إنجليزياً؛ وإلا null ← المحرك يعلّق Locale("en") بنفسه.
        return runCatching {
            (appContext as? AnnouncementAppContext)?.settingsRepository
                ?: SettingsRepository.create(appContext)
        }.getOrNull()?.getPreferredVoiceId(LanguageCode.EN.tag)
            ?: if (isEnglishVoiceName(voiceId)) voiceId else null
    }

    private fun doSpeak(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        partVoice: String?,
        queueMode: Int,
        attempt: Int
    ) {
        doSpeak(
            text, locale, speechRate, pitch, volume,
            partVoice, queueMode, attempt, null
        )
    }

    private fun doSpeak(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        partVoice: String?,
        queueMode: Int,
        attempt: Int,
        assignedUtteranceId: String?
    ) {
        val tts = tts ?: run {
            cancelSpeechWatchdog()
            stopInterruptionMonitoring()
            releaseAudioFocus()
            notifySpeechComplete()
            nowSpeaking = false
            currentCategory = null
            return
        }
        // مضاعف السرعة العام (إن فعّله المستخدم): يُضرب بالسرعة النهائية
        // قبل قصّها على الحد الآمن — كل الإعلانات (وقت/أرقام/بطارية/متصل/
        // رسائل/إشعارات) تمر من هنا فيُطبَّق تناسقياً على النطق كله.
        val boost = settings?.let { s ->
            if (runCatching { s.isSpeechBoostEnabled() }
                    .getOrDefault(false)
            ) {
                runCatching { s.getSpeechBoostValue() }.getOrDefault(1.0f)
            } else {
                1.0f
            }
        } ?: 1.0f
        tts.setSpeechRate(clampedSpeechRate(speechRate * boost))
        // مضاعف الصوت العام (إن فعّله المستخدم): يُضرب بمستوى الصوت
        // النهائي (يأتي من تفضيل الفئة أو الإعداد العام) قبل قصّه على
        // النطاق الكامل (0..1) — أعلى مستوى متاح للنظام بلا تشويه.
        val volumeBoost = settings?.let { s ->
            if (runCatching { s.isVolumeBoostEnabled() }
                    .getOrDefault(false)
            ) {
                runCatching { s.getVolumeBoostValue() }.getOrDefault(1.0f)
            } else {
                1.0f
            }
        } ?: 1.0f
        tts.setPitch(pitch.coerceAtLeast(MIN_RATE_OR_PITCH))
        // حلّ صوت الوحدة: المعرّف الصريح المخصص (مثل "ar-EG") إن أعرضه
        // المحرك المربوط؛ وإلا أول صوتٍ لسانه لسانُ الوحدة فيضمن تبديل
        // لغة النطق فعلياً للوحدة الإنجليزية المفردة (بند 18) بدل الاعتماد
        // على setLanguage وحده الذي قد يُبقي بعض المحركات لغته السابقة.
        // إن لم يوجد صوتٌ ملائم (محرك خارجي بلا صوتٍ لتلك اللغة) نرجع
        // لتحديد اللغة فقط، فيبقى اختيار الصوت محدوداً بلسان المحرك.
        // تعيينُ صوت الوحدة بنتيجةٍ مُتفحَّصة لا خاصيةً صامتةً: كانت
        // `tts.voice = chosen` تُهمل نتيجة التحكيم، فبعض المحركات (ظاهرة
        // Vocalizer) ترفض الصوتَ بصمتٍ وتُكمل الدورةَ بإخراجِ لا صوتَ فيه.
        // عند الرفض تُجرّب أصواتٌ بديلةٌ من نفس لسانِ المحرك أولاً (رموزُ
        // Vocalizer قد تخالف المعرّف المخصص) قبل الرضى بالـ setLanguage.
        val voices = runCatching { tts.voices }.getOrNull()
        val chosen = voiceFor(voices, partVoice, locale)
        var voiceApplied = false
        if (chosen == null) {
            Log.w(TAG, "[Speaker] no engine voice for $locale")
        } else {
            // محرك Vocalizer ومحركات أخرى تشترط تعيين لغة الصوت أولاً عبر
            // setLanguage قبل setVoice حتى لا يُرفض الصوت لاختلاف سياق المحرك
            runCatching {
                chosen.locale?.let { tts.setLanguage(it) }
            }
            if (tts.setVoice(chosen) == TextToSpeech.SUCCESS) {
                voiceApplied = true
            } else {
                Log.w(
                    TAG,
                    "[Speaker] engine refused voice " +
                        "(name=${chosen.name}) — نبحث بديلاً باللسان ذاته"
                )
                var nameToSkip: String? = chosen.name
                while (!voiceApplied) {
                    val next = fallbackVoiceFor(voices, locale, nameToSkip)
                        ?: break
                    runCatching {
                        next.locale?.let { tts.setLanguage(it) }
                    }
                    if (tts.setVoice(next) == TextToSpeech.SUCCESS) {
                        voiceApplied = true
                    } else {
                        nameToSkip = next.name
                    }
                }
                if (!voiceApplied) {
                    Log.w(TAG, "[Speaker] no usable voice — نحو setLanguage")
                }
            }
        }
        if (!voiceApplied) {
            // بند الأوامر 2: عائد setLanguage كان مُهملاً — إن رجع
            // LANG_NOT_SUPPORTED/LANG_MISSING_DATA يبقى المحرك على آخر لغة
            // ضبطها (غالباً عربية من المقطع السابق) فيقرأ الحروف اللاتينية
            // بصوتٍ عربي — يُسجَّل تحذير (لا افتراض نجاح صامت) مثل
            // SystemVoiceProvider (الجولة الخامسة، الأمر 4) لأن هذا مثيل
            // TextToSpeech منفصل تماماً.
            var langResult = tts.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_NOT_SUPPORTED
                || langResult == TextToSpeech.LANG_MISSING_DATA
            ) {
                val fallbackLocale = resolveFallbackLocale(locale, voices)
                if (fallbackLocale != null && fallbackLocale != locale) {
                    langResult = tts.setLanguage(fallbackLocale)
                }
            }
            if (langResult == TextToSpeech.LANG_NOT_SUPPORTED
                || langResult == TextToSpeech.LANG_MISSING_DATA
            ) {
                Log.w(
                    TAG,
                    "[Speaker] engine lacks $locale" +
                    " (result=$langResult)"
                )
            }
        }
        val params = android.os.Bundle().apply {
            val effectiveVolume = if (volume <= 0f) 0.05f else volume
            val boostedVolume = (effectiveVolume * volumeBoost).coerceIn(
                0.05f, 1f
            )
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                boostedVolume
            )
            // القناة من قرارِ المسار نفسه — الإشعارُ للمتصل خارج
            // المكالمة، و**قناةُ المكالمةِ لمكالمةِ الانتظار** (بند 5)،
            // والوسائطُ لغيره. **يجب أن تتطابق مع السمات** وإلا تجاهل
            // المحرّكُ السمةَ ورجّع النطق إلى `STREAM_MUSIC` الذي تخفضه
            // المكالمة الجارية فيعود العَرَض (صوتٌ منخفض على Pixel).
            putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                streamForRoute(speechRouteForNow(currentCategory))
            )
        }
        // تنظيف النص من الإيموجي قبل النطق (نصوص خارجية قد
        // تحوي رموزاً يُقرؤها المحرك الخارجي أسماءها الإنجليزية).
        // في مسار نطق الإيموجي لا يصل إيموجي لمقاطع النص (قُسمت
        // أصلاً) فالتنظيف هنا لا مساس به.
        // وتطبيع NFC يرمم النصوص القادمة مشكولةً
        // Bidi/NFD من الجذر (SMS/إشعارات).
        val cleanText = ArabicSpeechNormalizer.normalize(
            java.text.Normalizer.normalize(
                stripEmojis(text),
                java.text.Normalizer.Form.NFC
            )
        )
        if (cleanText.isBlank()) {
            if (assignedUtteranceId != null) {
                activeUtteranceIds.remove(assignedUtteranceId)
            }
            val isFinal = (assignedUtteranceId != null &&
                assignedUtteranceId == lastQueuedUtteranceId) ||
                activeUtteranceIds.isEmpty()
            if (isFinal) {
                cancelSpeechWatchdog()
                stopInterruptionMonitoring()
                releaseAudioFocus()
                notifySpeechComplete()
                nowSpeaking = false
                currentCategory = null
            }
            return
        }
        val utteranceId = assignedUtteranceId ?: nextUtteranceId()
        trackUtterance(utteranceId)
        if (assignedUtteranceId == null) {
            lastQueuedUtteranceId = utteranceId
        }
        val status = tts.speak(cleanText, queueMode, params, utteranceId)
        // المحركُ رفض المعرّفَ فلن يُشعِر بعودته لاحقاً، فأخرجه من السجل
        if (status == TextToSpeech.ERROR) {
            activeUtteranceIds.remove(utteranceId)
        }
        if (status == TextToSpeech.ERROR && attempt < 4) {
            mainHandler.postDelayed({
                doSpeak(
                    text, locale, speechRate, pitch, volume,
                    partVoice, queueMode, attempt + 1,
                    assignedUtteranceId = utteranceId
                )
            }, 120)
        } else if (status == TextToSpeech.ERROR) {
            // استنفاد المحاولات: تصريف الموارد حتى لا يبقى التركيز مكتوم الصوت
            // ومحرك مكسور "جاهزاً" للدورات القادمة.
            nowSpeaking = false
            cancelSpeechWatchdog()
            stopInterruptionMonitoring()
            releaseAudioFocus()
            notifySpeechComplete()
            shutdownSafely()
        }
    }

    /** إيقاف أي نطق جارٍ وتحرير الموارد — يُسكت الصوت فوراً ويحرر التركيز
     *  لكنه **يُبقي اتصال محرك TTS قائماً** (الإسكات الفوري بالهز/التقارب
     *  يجب ألا يُجبر الإعلانَ التالي على إعادة بناء المحرك بثانية كاملة —
     *  التدمير الكامل يخصّ [shutdown] عند خروج الخدمة نهائياً). */
    fun stop() {
        resumableAnnouncement = null
        pendingUnitsForResume.clear()
        speechGeneration.incrementAndGet()
        AudioCuePlayer.getInstance(appContext).stop()
        stopInterruptionMonitoring()
        mainHandler.removeCallbacksAndMessages(null)
        speechWatchdog = null
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        tts?.stop()
        // بند 1.1: الإيقاف يبطل كل المعرّفات المعلقة — لا «مستمع معلّق».
        invalidateActiveUtterances()
        deferredWhileSpeaking.clear()
        releaseAudioFocus()
        nowSpeaking = false
        currentCategory = null
    }

    /**
     * إغلاق تام عند خروج الخدمة الأمامية (onDestroy): يوقف النطق، يُبطل كل
     * المؤقتات المعلّقة (انتظار التركيز المؤجل + محاولات إعادة النطق)، يحرر
     * التركيز، ويُغلق محرك TTS نهائياً (بند [7] — منع تسريب مؤقتات/محرك).
     */
    fun shutdown() {
        speechGeneration.incrementAndGet()
        AudioCuePlayer.getInstance(appContext).stop()
        stopInterruptionMonitoring()
        mainHandler.removeCallbacksAndMessages(null)
        speechWatchdog = null
        tts?.stop()
        // بند 1.1: الإغلاق يُبطل صراحةً كل المعرّفات المعلقة.
        invalidateActiveUtterances()
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        // **قفل النطق العابر:** إلغاء مراقبة «نطق جارٍ» ومهلة الانتظار
        // وتفريغ الطابور المؤجَّل — لا بقايا مراقبٍ ولا نطق متأخر بعد
        // إغلاق الخدمة.
        unregisterSpeakingLockObserver()
        speakingLockTimeout?.let { mainHandler.removeCallbacks(it) }
        speakingLockTimeout = null
        deferredWhileSpeaking.clear()
        releaseAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = null
            lastFocusGain = null
            lastAudioAttributes = null
        }
        initGate.reset()
        shutdownSafely()
        nowSpeaking = false
        currentCategory = null
    }

    // طلب تخفيف صوت الوسائط أثناء النطق (Audio Ducking).
    // عند وصول التركيز المؤجل (DELAYED) يُطلق هذا المستمع النطق المنتظر.
    private val onAudioFocusChange =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // تسليم التركيز المؤجل وصل — شغّل النطق المُخزّن.
                hasAudioFocus = true
                val action = pendingFocusAction
                pendingFocusAction = null
                pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
                pendingFocusTimer = null
                if (action != null) {
                    action.invoke()
                } else if (!nowSpeaking) {
                    // إذا وصل AUDIOFOCUS_GAIN متأخراً بعد أن أُلغي الإعلان
                    // أو انتهى ولم نعد ننطق، يجب تحرير التركيز فوراً حتى لا
                    // نحتجز تركيز النظام ونهنج مشغلات الوسائط الخارجية!
                    releaseAudioFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (currentCategory ==
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                ) {
                    Log.d(
                        TAG,
                        "[Focus] فقدان عابر أثناء إعلان المتصل — المتابعة"
                    )
                    return@OnAudioFocusChangeListener
                }
                // فقد التركيز (مكالمة/وسائط) — أوقف النطق فوراً
                hasAudioFocus = false
                AudioCuePlayer.getInstance(appContext).stop()
                tts?.stop()
                nowSpeaking = false
                currentCategory = null
                releaseAudioFocus()
                notifySpeechComplete()
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: إعلاننا قصير، نستمر دون حاجة
            // لخفض الصوت (النظام يخفض الوسائط المخالفة لا إعلاننا).
        }
    }

    /** رفع حجم قناة النطق إلى قمتها مؤقتاً — يُحفظ المستوى الأصلي أولاً
     *  ليُستعاد عند اكتمال النطق ([restoreBoostedStreamVolume]). تُرفع
     *  القناةُ التي يمرّ عليها نطقُ الفئة الحالية: `STREAM_NOTIFICATION`
     *  للمتصل و`STREAM_MUSIC` لغيره (نفس قناة البطارية) — موحدةً لكل
     *  فئات كل مسار، لا قناة الرنين التي يُسكتها مفتاح الصمت — فيعمل
     *  النطق بأقصى صوتٍ حتى في الوضع الصامت. لا شيء لو كان رفعٌ
     *  قائماً (لا نكسر قيمةً سُجِّلت لهذه الدورة).
     *
     *  **الوسائط تُخفَض من النظام أثناء المكالمة الجارية** فالإعلان
     *  عليها لا يرتفع بِرفع قناتها، ولهذا يُنطق المتصل على قناة
     *  الإشعار التي لا تخفضها المكالمة (انظر [speechAudioAttributes]).
     *
     *  **ولا رفعَ على قناة المكالمة أبداً (بند 5):** فوقَ مكالمةٍ
     *  جارية يمرّ النطقُ على `STREAM_VOICE_CALL`، وهي قناةُ
     *  **مكالمةِ المستخدم نفسِه** — رفعُها إلى القمّة يعني الصراخَ في
     *  أذنه وفي وجه الطرف الآخر.')[boostedStream] على
     *  `STREAM_NOTIFICATION` لا يسري أصلاً على هذا المسار. */
    @VisibleForTesting
    internal fun boostStreamVolume(category: String? = null) {
        if (boostedStream != -1) return
        val route = speechRouteForNow(category)
        if (route == SpeechRoute.CALL) return
        val stream = streamForRoute(route)
        runCatching {
            val max = audioManager.getStreamMaxVolume(stream)
            val current = audioManager.getStreamVolume(stream)
            if (max <= 0 || current >= max) return@runCatching
            savedStreamVolume = current
            boostedStream = stream
            audioManager.setStreamVolume(stream, max, 0)
        }
    }

    /** استعادة مستوى الحجم الأصلي للقناة المرفوعة بعد اكتمال نطق المتصل
     *  أو إيقافه/إغلاقه — لا يبقى الجهاز مرتفع الحجم بعد الإعلان. آمنة
     *  (no-op) عند غياب رفعٍ قائم. */
    @VisibleForTesting
    internal fun restoreBoostedStreamVolume() {
        val stream = boostedStream
        val saved = savedStreamVolume
        if (stream == -1 || saved == -1) return
        boostedStream = -1
        savedStreamVolume = -1
        runCatching { audioManager.setStreamVolume(stream, saved, 0) }
    }

    /**
     * خفضُ نغمة الرنين أثناء إعلان المتصل — لفئة المتصل وحدها (بقرار
     * المستخدم ومربّعه).
     *
     * **لماذا قناةٌ نخفّضها بأنفسنا؟** خفضُ الصوت المؤقت في AOSP
     * (ducking) يشمل الموسيقى والإشعارات ولا يشمل `STREAM_RING`،
     * فرنّةُ الاتصال تعلو على الاسم. فنخفضها بالمقدار الذي اختاره
     * المستخدم في [RingtoneDuckMath]، **من مستوى الرنين الحالي** (لا من
     * قمّته) فيبقى المقدارُ متناسباً مع ما سمعه، ولا خفضَ في الوضع
     * الصامت/المهتز أو على رنّةٍ أصلاً معدومة.
     *
     * **ولا خفضَ فوقَ مكالمةٍ جارية (بند 5):** رنّةُ متصلٍ آخر لا
     * معنى لها والمستخدمُ على مكالمته، فلا نلمس مستوى الرنين.
     */
    @VisibleForTesting
    internal fun duckRingVolumeIfCallerCategory(category: String?) {
        if (!isCallerCategory(category)) {
            return
        }
        if (speechRouteForNow(category) == SpeechRoute.CALL) return
        if (duckedRingStream != -1) return
        val repo = settings ?: return
        if (!repo.isCallerRingDuckingEnabled()) return
        val stream = AudioManager.STREAM_RING
        runCatching {
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            ) {
                return@runCatching
            }
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            if (max <= 0 || current <= 0) return@runCatching
            val target = RingtoneDuckMath.duckedLevel(
                current, max, repo.getCallerRingDuckPercent()
            )
            if (target >= current) return@runCatching
            savedRingVolume = current
            duckedRingStream = stream
            audioManager.setStreamVolume(stream, target, 0)
        }
    }

    /**
     * إعادةُ الرنين لمستواه الأصلي بعد النطق أو إيقافه — **مشروطةٌ
     * بالقيمة التي تركناها**: إن غيّر المستخدمُ الرنينَ بنفسه أثناء
     * النطق فلا نطمس اختياره. آمنة (no-op) عند غياب خفضٍ قائم.
     *
     * **مقصودةٌ خارجَ [restoreBoostedStreamVolume]**: استدعاؤها من داخله
     * كانت ستبتلعها خروجهُ المبكر حين لا رفعَ قائم (قناةُ الموسيقى على
     * قمّتها أصلاً) فيبقى الرنينُ مخفوضاً بعد كل إعلان. فالمَجمَعُ الوحيد
     * هو [releaseAudioFocus] — كل مسارات الاكتمال والإيقاف تمرّ به.
     */
    @VisibleForTesting
    internal fun restoreDuckedRingVolume() {
        val stream = duckedRingStream
        val saved = savedRingVolume
        duckedRingStream = -1
        savedRingVolume = -1
        if (stream == -1 || saved == -1) return
        val percent = settings?.getCallerRingDuckPercent()
            ?: return
        runCatching {
            val max = audioManager.getStreamMaxVolume(stream)
            val expected = RingtoneDuckMath.duckedLevel(saved, max, percent)
            if (audioManager.getStreamVolume(stream) != expected) {
                return@runCatching
            }
            audioManager.setStreamVolume(stream, saved, 0)
        }
    }

    /**
     * تركيز الصوت لإعلانات ناطق:
     * لمنع خفض صوت الوسائط تماماً (Zero Audio Ducking) وضمان استمرار
     * تشغيل الموسيقى ومقاطع الفيديو بنفس مستوى الصوت بالتوازي مع النطق
     * دون أي تأخير، لا نطلب Audio Focus من نظام أندرويد (إذ إن طلب التركيز
     * هو ما يدفع النظام لإرسال إشارة خفض الصوت لمشغلات الوسائط).
     * يتم التحقق المباشر من نمط الصوت (Mode) لحماية المكالمات الهاتفية فقط.
     */
    private fun requestAudioFocus(): Int {
        val inCall = runCatching {
            val mode = audioManager.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)

        // أثناء المكالمات الهاتفية: يُرفض التركيز لمنع التشويش على المكالمة —
        // إلا للفئات المسموح بها صراحةً عبر مفاتيح «أثناء المكالمة»:
        // إعلانُ المتصل (مكالمة انتظار) والوقت؛ تُمنح فوراً وبلا طلب تركيز
        // من النظام (Zero-Ducking) فينبثق الإعلان فوق المكالمة النشطة.
        if (inCall) {
            return if (isAllowedDuringCall()) {
                hasAudioFocus = true
                skippedFocusForMedia = true
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
        }

        // خارج المكالمات: نمنح الإذن بالنطق فوراً وبلا طلب تركيز من النظام
        // (Zero-Ducking)، فيقوم AudioFlinger بمزج الصوت مع الوسائط بالتوازي.
        hasAudioFocus = true
        skippedFocusForMedia = true
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** هل تُسمح فئة النطق الحالية بالمرور فوق مكالمةٍ هاتفية نشطة؟
     *  المتصلُ (بجميع أنواعه) عند تفعيل «نطق اسم المتصل أثناء المكالمة»،
     *  والوقت عند تفعيل «نطق الوقت أثناء المكالمة»؛ ما عداها (البطارية/الرسائل/
     *  الإشعارات/نصوص عامة) يُرفضُ فوق المكالمة لحمايتها من التشويش. */
    private fun isAllowedDuringCall(): Boolean {
        val repo = settings ?: return false
        return when (currentCategory) {
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN ->
                repo.isCallerAnnouncementDuringCallEnabled()
            SettingsRepository.VOICE_CATEGORY_TIME ->
                repo.isAnnounceTimeDuringCalls()
            else -> false
        }
    }

    /**
     * إعادة ضبط تركيز الصوت يدوياً — يستدعي abandon **بلا شرط** بصرف
     * النظر عن قيمة [hasAudioFocus] الداخلية؛ مخصّص للاستدعاء الصريح من
     * زر إعادة الضبط أو عند اكتشاف حالة تركيز غير متزامنة.
     * يُلغي أيضاً أي نطق معلّق.
     */
    fun resetAudioFocusUnconditionally() {
        Log.i(TAG, "[Focus] إعادة ضبط يدوية — abandon بلا شرط")
        // لا تبقى قناةٌ مرفوعة بعد إعادة الضبط — استعادة فورية لها.
        restoreBoostedStreamVolume()
        hasAudioFocus = false
        skippedFocusForMedia = false
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                audioFocusRequest = null
                lastFocusGain = null
                lastAudioAttributes = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(onAudioFocusChange)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resetAudioFocusUnconditionally failed", t)
        }
    }

    /**
     * التخلي عن Audio Focus بعد انتهاء النطق مع إبقاء مثيل الطلب
     * لإعادة استخدامه.
     */
    private fun releaseAudioFocus() {
        hasAudioFocus = false
        // استعادة مستوى الحجم الأصلي للقناة المرفوعة لنطق المتصل/الساعة
        // (إن كانت دورةُ نطقٍ مرفوعةً لا تزال قائمة) — كل مسارات الاكتمال/
        // الإيقاف تُطلق التركيز فتمرّ عبر هذه النقطة فتُستعاد مرةً واحدة
        // صحيحة.
        restoreBoostedStreamVolume()
        // إعادةُ الرنين إن كان مخفوضاً أثناء إعلان المتصل (نفسُ المسار
        // المجمَع لكل مسارات الإنهاء).
        restoreDuckedRingVolume()
        // إلغاء أي نطق معلّق بانتظار التركيز حتى لا يُنطق نص قديم لاحقاً.
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        if (skippedFocusForMedia) {
            skippedFocusForMedia = false
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                // تمرير نفس المستمع المسجَّل عند الطلب (لا null): null يُطلق
                // التركيز لكنه يترك تسجيل المستمع في
                // AudioService قائماً فتتسرب
                // مراجع المستمعين مع تتابع دورات النطق على أندرويد ما قبل O.
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(onAudioFocusChange)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "releaseAudioFocus failed", t)
        }
    }

    private fun shutdownSafely() {
        try {
            tts?.shutdown()
        } catch (ignored: Throwable) {
        }
        tts = null
    }

    /** بوابة التهيئة (بند [5] المحكم): تحسم بذرّيةٍ تامة بدء التهيئة أو
     *  الانضمام، وتصفّي النداءات ضدّ محرك التهيئة الجارية — فلا ينضمّ
     *  طلبُ محركٍ مختلف إلى تهيئةٍ قائمةٍ لمحركٍ آخر (كان يحدث سابقاً
     *  فيُنطق النص بالمحرك الخطأ) — وتُسلسل إعادة تهيئةٍ للمحركان
     *  المتبقيان. الاختبار الآلي: [InitGateTest]. */
    private val initGate = InitGate()
}

/**
 * محرك دورة الإعلان: صريحُ الفئة إن وُجد (محرك متصلٍ عربي/إنجليزي…)؛
 * وإلا المحرك المفضّل للغة النص من الإعدادات — كان غيابُ الصريح يربط
 * المتحدثَ بمحرك النظام الافتراضي فيبقى نطقُ الوقت/البطارية/الإشعارات
 * على أصواتٍ افتراضية ثابتة لا تتغير بعد اختيار محركٍ غيره في الإعدادات.
 * null في النهاية ← محرك تلقائي.
 * دالة نقية مستقلة (بلا حالة) لسهولة الاختبار الآلي.
 */
internal fun resolveAnnouncementEngine(
    requested: String?,
    configuredEngine: String?
): String? = if (!requested.isNullOrBlank()) {
    requested
} else {
    configuredEngine
}

/**
 * حسم محرك آمن لنطق الإعلانات والأحداث:
 * يمنع التكرار الذاتي وحلقات الربط الفاشلة؛ إن كان المحرك المطلوب فارغاً
 * أو هو حزمة التطبيق نفسها (التي تتطلب BIND_TTS_SERVICE للنظام فتفشل عند ربط
 * التطبيق بذاته)، يتم تفويض أول محرك خارجي مثبت من [EnginePicker].
 */
internal fun safeEngineForAnnouncement(
    context: Context,
    engine: String?,
    defaultSynthProvider: () -> String? = {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
        }.getOrNull()
    },
    installedEnginesProvider: () -> List<String> = {
        EnginePicker.installedEnginePackages(context)
    }
): String? {
    if (!engine.isNullOrBlank() && engine != context.packageName) {
        return engine
    }
    val defaultSynth = defaultSynthProvider()
    if (!defaultSynth.isNullOrBlank() &&
        defaultSynth != context.packageName
    ) {
        return defaultSynth
    }
    return installedEnginesProvider().firstOrNull {
        it != context.packageName
    }
}
