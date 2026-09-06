package com.aymankhattab.nateq.settings

import android.app.AlertDialog
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.providers.EnginePicker
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale

/** بيانات محرك TTS معروض في قوائم الاختيار. */
internal data class EngineInfo(val packageName: String, val label: String)

/**
 * ضابط قسم المحرك وإعدادات التحويل التلقائي بين اللغتين.
 *
 * عُزل من [VoiceSelectionFragment] ليقلّص حجم الأخير: يبقى هنا اختيار
 * محرك النطق في صندوقه (مع المحركات النظامية وغير المُصدَّرة عبر
 * TTS_SERVICE intent وMATCH_ALL)، وكتالوج لغات/أصوات المحرك، وحوار
 * إعداد اللغتين (محرك ← لغة ← صوت + نبرة/سرعة/سعة)، ومعاينة النطق.
 */
internal class EngineSectionController(
    private val fragment: Fragment,
    private val settings: SettingsRepository,
    private val engines: MutableList<EngineInfo>
) {

    /** يربطه المضيف بعد إنشائه ليُعيد بناء أسطر حالة الأقسام عند أي تغيير. */
    var onStatusChanged: () -> Unit = {}

    // languageTags اللغات المعروضة حالياً في حوار التحويل (حسب ترتيب صفوف اللغة)،
    // تُملأ في (showConvertDialog) ليُستخرج languageTag الصحيح عند الحفظ.
    private var convertDialogLangTags: List<String> = emptyList()

    // ===== صندوق المحركات (اختيار محرك TTS ضمن قسم اللغة الأولى/الثانية) =====
    fun setupEngineSpinner(spinnerEngine: Spinner) {
        engines.clear()

        // محركات النظام المتاحة عبر TTS_SERVICE intent.
        // نستخدم MATCH_ALL ليظهر القارئان/المحركات غير-المُصدَّرة (مثل eSpeak
        // داخل Jieshuo/TalkMan) التي لا تُستعلم على أندرويد 7+ دونها.
        engines.addAll(discoverEngines())

        if (engines.isEmpty()) {
            spinnerEngine.adapter = simpleAdapter(
                fragment.requireContext(),
                listOf(fragment.getString(R.string.no_voices_available))
            )
        } else {
            spinnerEngine.adapter = simpleAdapter(fragment.requireContext(), engines.map { it.label })
            spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onEngineSelected(engines[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // نستعيد المحرك المحفوظ، أو نفضّل محركاً حقيقياً غير قارئ شاشة
            // (نفس ترتيب اختيار النطق التلقائي في EnginePicker).
            val saved = settings.getSelectedEnginePackage()
            val preferred = EnginePicker.pickPreferredEngineFrom(
                engines.map { it.packageName }
            )
            val target = engineIndexOf(saved) ?: if (saved == null) engineIndexOf(preferred) else null
            if (target != null && target < engines.size) {
                spinnerEngine.setSelection(target)
            }
        }
    }

    /** فهرس المحرك بمطابقة الحزمة في قائمة المحركات */
    private fun engineIndexOf(pkg: String?): Int? {
        if (pkg == null) return null
        val idx = engines.indexOfFirst { it.packageName == pkg }
        return if (idx >= 0) idx else null
    }

    private fun onEngineSelected(engine: EngineInfo) {
        // عند اختيار محرك نُخزّنه ليستخدمه مزوّد الصوت عند النطق.
        settings.setSelectedEnginePackage(engine.packageName)
    }

    /**
     * يحمّل كل اللغات التي يوفّرها محرك نظامي معيّن، ويُفشل بأمان إن تعذّر.
     * يستخدم نفس نهج (queryIntentServices) الذي كشف "smart voice" تلقائياً:
     * المحرك يُتعامل معه كصندوق معتم، فاللغات والأصوات تُستعرض ديناميكياً.
     */
    @Suppress("DEPRECATION")
    private fun loadEngineCatalog(
        enginePackage: String,
        onResult: (voices: List<Voice>) -> Unit
    ) {
        lateinit var eng: TextToSpeech
        eng = TextToSpeech(fragment.requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = runCatching { eng.setEngineByPackageName(enginePackage) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (result == TextToSpeech.SUCCESS) {
                    val avail = runCatching { eng.getVoices() }.getOrDefault(emptySet())
                    onResult(avail.toList())
                } else {
                    onResult(emptyList())
                }
                eng.shutdown()
            } else {
                Log.w("LordTTS", "تعذّر إنشاء TextToSpeech لمحرك: $enginePackage (status=$status)")
                onResult(emptyList())
            }
        }
    }

    // ===== التحويل التلقائي بين اللغتين =====
    fun setupAutoConvertUI(view: View) {
        val chk = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_auto_convert)
        val btn1 = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang1)
        val btn2 = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang2)

        chk.isChecked = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        chk.setOnClickListener { v ->
            val enabled = (v as com.google.android.material.checkbox.MaterialCheckBox).isChecked
            runCatching { settings.setAutoConvertEnabled(enabled) }
            refreshConvertButtonsEnabled(view, enabled)
            onStatusChanged()
        }

        btn1.setOnClickListener { showConvertDialog(1) }
        btn2.setOnClickListener { showConvertDialog(2) }

        refreshConvertButtonsEnabled(view, runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false))
    }

    /** يُفعّل/يعطّل زري اللغتين تبعاً لمربع التفعيل */
    private fun refreshConvertButtonsEnabled(view: View, enabled: Boolean) {
        val btn1 = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang1)
        val btn2 = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_lang2)
        btn1.isEnabled = enabled
        btn2.isEnabled = enabled
    }

    /** قائمة كل المحركات المتاحة عبر INTENT_ACTION_TTS_SERVICE (MATCH_ALL) */
    private fun discoverEngines(): List<EngineInfo> {
        return EnginePicker.installedEngines(fragment.requireContext())
            .map { EngineInfo(it.packageName, it.label) }
    }

    /**
     * يفتح حوار إعداد إحدى اللغتين (1 أو 2) فيقوم الكفيف بتحديد:
     * المحرك ← اللغة ← الصوت، ثم مستوى الصوت والنبرة والسرعة، وحفظ.
     */
    private fun showConvertDialog(lang: Int) {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_auto_convert, null)
        val spEngine = dialogView.findViewById<Spinner>(R.id.spinner_convert_engine)
        val spLang = dialogView.findViewById<Spinner>(R.id.spinner_convert_language)
        val spVoice = dialogView.findViewById<Spinner>(R.id.spinner_convert_voice)
        val seekVol = dialogView.findViewById<SeekBar>(R.id.seek_convert_volume)
        val tvVol = dialogView.findViewById<TextView>(R.id.tv_convert_volume_value)
        val seekPitch = dialogView.findViewById<SeekBar>(R.id.seek_convert_pitch)
        val tvPitch = dialogView.findViewById<TextView>(R.id.tv_convert_pitch_value)
        val seekRate = dialogView.findViewById<SeekBar>(R.id.seek_convert_rate)
        val tvRate = dialogView.findViewById<TextView>(R.id.tv_convert_rate_value)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_save)
        val btnPlay = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_play)
        val tvSaved = dialogView.findViewById<TextView>(R.id.tv_convert_dialog_saved)

        // استرجاع القيم المحفوظة (أو الافتراضية)
        val savedEngine = if (lang == 1) settings.getConvertEngine1() else settings.getConvertEngine2()
        val savedLang = if (lang == 1) settings.getConvertLanguageTag1() else settings.getConvertLanguageTag2()
        val savedVoice = if (lang == 1) settings.getConvertVoice1() else settings.getConvertVoice2()
        val savedVol = if (lang == 1) settings.getConvertVolume1() else settings.getConvertVolume2()
        val savedPitch = if (lang == 1) settings.getConvertPitch1() else settings.getConvertPitch2()
        val savedRate = if (lang == 1) settings.getConvertRate1() else settings.getConvertRate2()

        // ===== المحركات =====
        val dialogEngines = discoverEngines()
        if (dialogEngines.isEmpty()) {
            spEngine.adapter = simpleAdapter(ctx, listOf(fragment.getString(R.string.no_voices_available)))
        } else {
            spEngine.adapter = simpleAdapter(ctx, dialogEngines.map { it.label })
            spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val engPkg = dialogEngines[position].packageName
                    spLang.adapter = simpleAdapter(ctx, listOf(fragment.getString(R.string.auto_convert_loading)))
                    spVoice.adapter = simpleAdapter(ctx, emptyList())
                    loadEngineCatalog(engPkg) { voices ->
                        // جميع اللغات التي يوفرها هذا المحرك (تلقائياً مهما كان عددها)،
                        // مجمعةً بحسب اللسان دون البلد حتى يظهر لكل لغة خيارٌ واحد
                        // وتُعرَض كل أصواتها.
                        val langs = voices.distinctBy { it.locale.language }
                        // نحفظ الـ languageTag لكل صف لغة (بالترتيب) ليُستخرج موثوقاً عند الحفظ.
                        convertDialogLangTags = langs.map { it.locale.language }
                        spLang.adapter = simpleAdapter(
                            ctx,
                            if (langs.isEmpty()) listOf(fragment.getString(R.string.auto_convert_none))
                            else langs.map { displayLang(it.locale) }
                        )
                        // اختيار اللغة المحفوظة إن وجدت (باللسان، لأننا نخزن كود اللغة فقط)
                        val savedTagIndex = if (savedLang != null)
                            langs.indexOfFirst { it.locale.language == savedLang } else -1

                        spLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                if (position < 0 || position >= langs.size) {
                                    spVoice.adapter = simpleAdapter(ctx, emptyList())
                                    return
                                }
                                val langCode = langs[position].locale.language
                                // كل الأصوات التي تلائم هذا اللسان (مهما كان البلد) تُعرض كلها،
                                // حتى لا يظهر صوتٌ واحد فقط لأن البلد قسّمها.
                                val matching = voices.filter {
                                    it.locale.language == langCode
                                }
                                spVoice.adapter = simpleAdapter(
                                    ctx,
                                    if (matching.isEmpty()) listOf(fragment.getString(R.string.auto_convert_none))
                                    else matching.map { it.name }
                                )
                                if (savedVoice != null) {
                                    val vi = matching.indexOfFirst { it.name == savedVoice }
                                    if (vi >= 0) spVoice.setSelection(vi)
                                }
                            }

                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                        }
                        if (savedTagIndex >= 0) spLang.setSelection(savedTagIndex)
                        else spLang.setSelection(0)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            // اختيار المحرك المحفوظ
            if (savedEngine != null) {
                val ei = dialogEngines.indexOfFirst { it.packageName == savedEngine }
                if (ei >= 0) spEngine.setSelection(ei)
            }
        }

        // ===== مستوى الصوت / النبرة / السرعة =====
        seekVol.progress = (savedVol * 100).toInt().coerceIn(0, 100)
        tvVol.text = "${(savedVol * 100).toInt()}%"
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvVol.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        seekPitch.progress = (savedPitch * 100).toInt().coerceIn(0, 200)
        tvPitch.text = String.format(Locale.US, "%.1fx", savedPitch)
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                tvPitch.text = String.format(Locale.US, "%.1fx", v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val v = seekBar.progress / 100f
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", v))
            }
        })

        seekRate.progress = (savedRate * 100).toInt().coerceIn(0, 200)
        tvRate.text = String.format(Locale.US, "%.1fx", savedRate)
        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                tvRate.text = String.format(Locale.US, "%.1fx", v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val v = seekBar.progress / 100f
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", v))
            }
        })

        // ===== زر حفظ: يخزّن كل قيم هذه اللغة =====
        btnSave.setOnClickListener {
            val engIdx = spEngine.selectedItemPosition
            val langIdx = spLang.selectedItemPosition

            // الأشرطة (سرعة/نبرة/صوت) تُحفظ دائماً مهما كانت حالة المحرك/اللغة.
            val volume = seekVol.progress / 100f
            val pitch = seekPitch.progress / 100f
            val rate = seekRate.progress / 100f

            // المحرك/اللغة/الصوت تُحفظ فقط عند اختيار صالح.
            val canSaveEngine = engIdx >= 0 && dialogEngines.isNotEmpty() && langIdx >= 0
            val engPkg = if (canSaveEngine) dialogEngines[engIdx].packageName else null
            val langTag = if (canSaveEngine) spLangAdapterTag(spLang) else null
            val voiceName = if (canSaveEngine) spVoice.selectedItem?.toString() else null

            if (lang == 1) {
                if (canSaveEngine) {
                    settings.setConvertEngine1(engPkg)
                    settings.setConvertLanguageTag1(langTag)
                    settings.setConvertVoice1(voiceName)
                }
                settings.setConvertVolume1(volume)
                settings.setConvertPitch1(pitch)
                settings.setConvertRate1(rate)
            } else {
                if (canSaveEngine) {
                    settings.setConvertEngine2(engPkg)
                    settings.setConvertLanguageTag2(langTag)
                    settings.setConvertVoice2(voiceName)
                }
                settings.setConvertVolume2(volume)
                settings.setConvertPitch2(pitch)
                settings.setConvertRate2(rate)
            }

            tvSaved.text = fragment.getString(R.string.auto_convert_saved)
            tvSaved.announceCompat(fragment.getString(R.string.auto_convert_saved))
        }

        // ===== زر استماع (تجربة) =====
        btnPlay.setOnClickListener {
            val engIdx = spEngine.selectedItemPosition
            val voiceName = spVoice.selectedItem?.toString() ?: return@setOnClickListener
            if (engIdx < 0 || dialogEngines.isEmpty() || voiceName.isBlank()) return@setOnClickListener
            val engPkg = dialogEngines[engIdx].packageName
            val rate = seekRate.progress / 100f
            val pitch = seekPitch.progress / 100f
            val volume = seekVol.progress / 100f
            playbackPreview(engPkg, voiceName, volume, pitch, rate)
        }

        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.auto_convert_dialog_title) + " — " +
                fragment.getString(if (lang == 1) R.string.auto_convert_lang1_button else R.string.auto_convert_lang2_button))
            .setView(dialogView)
            .setPositiveButton(fragment.getString(R.string.reset_cancel), null)
            .show()
    }

    /** إرجاع languageTag للصف المحدد في spinner لغة الحوار (بشكل موثوق) */
    private fun spLangAdapterTag(spLang: Spinner): String? {
        val idx = spLang.selectedItemPosition
        // نستعيد الـ languageTag الحقيقي من القائمة المحفوظة عند بناء الحوار؛
        // وليس من الاسم المعروض لأنه لا يحمل الكود بدقة.
        return if (idx in convertDialogLangTags.indices) convertDialogLangTags[idx]
            else runCatching { spLang.selectedItem?.toString() }.getOrNull()
    }

    /** صيغة عرضٍ للغة: اسم وعرض البلاد/اللغة معاً */
    private fun displayLang(locale: Locale): String {
        val l = locale
        return "${l.displayName} (${l.country} ${l.language})".trim()
    }

    /** يُشغّل تكليفاً تجريبياً عبر محرك مؤقت بأية القيم المختارة دون حفظ */
    @Suppress("DEPRECATION")
    private fun playbackPreview(
        enginePkg: String,
        voiceName: String,
        volume: Float,
        pitch: Float,
        rate: Float
    ) {
        lateinit var previewTts: TextToSpeech
        previewTts = TextToSpeech(fragment.requireContext()) { status ->
            if (status != TextToSpeech.SUCCESS) {
                // فشل تهيئة محرك المعاينة: نغلق فوراً حتى لا تبقى نسخة TTS معلقة
                runCatching { previewTts.shutdown() }
                return@TextToSpeech
            }
            runCatching { previewTts.setEngineByPackageName(enginePkg) }
            try {
                val avail = runCatching { previewTts.getVoices() }.getOrDefault(emptySet())
                val voice = avail.firstOrNull { it.name == voiceName }
                if (voice != null) {
                    // نضبط المحرك على لسان الصوت المختار حتى لا يقرأ النص
                    // بلغة المحرك الافتراضية (مثلاً الإنجليزية رغم اختيار العربي).
                    runCatching { previewTts.setVoice(voice) }
                    runCatching { previewTts.language = voice.locale }
                }
            } catch (_: Exception) {}
            previewTts.setSpeechRate(rate)
            runCatching { previewTts.setPitch(pitch) }
            // نمرر مستوى الصوت للمحرك عبر المعاملات (كان بلا مستوى صوت إطلاقاً)
            // ليقترب ناتج المعاينة من النطق الفعلي الذي يطبق نفس القيم.
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            // نعرض عينة بنفس لغة الصوت: عربي إن كان الصوت عربياً وإلا إنجليزي.
            // (سبق: كان النص تجريبياً إنجليزياً دائماً فبدا للمستخدم أن الصوت إنجليزي.)
            val sampleText = if (voiceName.lowercase().contains("ar") || voiceName.lowercase().contains("arab"))
                fragment.getString(R.string.sample_text_preview_ar)
            else
                fragment.getString(R.string.sample_text_default_en)
            val speakResult = runCatching {
                previewTts.speak(
                    sampleText,
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    "preview"
                )
            }.getOrDefault(TextToSpeech.ERROR)
            if (speakResult == TextToSpeech.ERROR) {
                // فشل النطق (مثلاً المحرك دون لغة محمّلة): نغلق فوراً عوضاً عن تعليقه
                runCatching { previewTts.shutdown() }
                return@TextToSpeech
            }
            previewTts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                @Deprecated("Java Deprecated")
                override fun onDone(utteranceId: String?) { previewTts.shutdown() }

                @Deprecated("Java Deprecated")
                override fun onError(utteranceId: String?) { previewTts.shutdown() }
            })
        }
    }
}

/**
 * محلّل صفوف الـ Spinner الأساسي (مشترك بين كل أقسام الشاشة).
 */
internal fun simpleAdapter(context: Context, items: List<String>): ArrayAdapter<String> {
    return ArrayAdapter(
        context,
        android.R.layout.simple_spinner_item,
        items
    ).also {
        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
}