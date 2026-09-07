package com.aymankhattab.nateq.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.EngineWithVoices
import com.aymankhattab.nateq.engine.VoiceCatalog
import com.aymankhattab.nateq.providers.EnginePicker
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** بيانات محرك TTS معروض في قوائم الاختيار. */
internal data class EngineInfo(val packageName: String, val label: String)

/**
 * ضابط قسم المحرك وإعدادات التحويل التلقائي عبر كل اللغات.
 *
 * عُزل من [VoiceSelectionFragment] ليقلّص حجم الأخير: يبقى هنا اختيار
 * محرك النطق في صندوقه (مع المحركات النظامية وغير المُصدَّرة عبر
 * TTS_SERVICE intent وMATCH_ALL)، وفتح قائمة اللغات المكتشفة فعلياً عبر
 * كل محركات TTS المثبتة — صف لكل لغة (محرك ← صوت + نبرة/سرعة/سعة) —
 * ومعاينة النطق. بدل نظام «اللغتين» الثابت (1/2) صار الدعم لكل لغة مُكتشفة.
 */
internal class EngineSectionController(
    private val fragment: Fragment,
    private val settings: SettingsRepository,
    private val engines: MutableList<EngineInfo>
) {

    /** يربطه المضيف بعد إنشائه ليُعيد بناء أسطر حالة الأقسام عند أي تغيير. */
    var onStatusChanged: () -> Unit = {}

    // ===== صندوق المحركات (اختيار محرك TTS ضمن قسم اللغات) =====
    fun setupEngineSpinner(spinnerEngine: Spinner) {
        engines.clear()

        // محركات النظام المتاحة عبر TTS_SERVICE intent.
        // نستخدم MATCH_ALL ليظهر القارئان/المحركات غير-المُصدَّرة (مثل eSpeak
        // داخل Jieshuo/TalkMan) التي لا تُستعلم على أندرويد 7+ دونها.
        engines.addAll(discoverEngines())

        bindNoEnginesUi()

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

    /** عند غياب أي محرك TTS إطلاقاً: يُظهر رسالة توجيهية + زر تثبيت من المتجر. */
    private fun bindNoEnginesUi() {
        val view = fragment.view ?: return
        val message = view.findViewById<TextView>(R.id.tv_no_engines_message)
        val btn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_install_engine)
        val noEngines = engines.isEmpty()
        message.visibility = if (noEngines) View.VISIBLE else View.GONE
        btn.visibility = if (noEngines) View.VISIBLE else View.GONE
        if (noEngines) {
            btn.setOnClickListener { openEngineInPlayStore() }
        }
    }

    /** يفتح صفحة Google TTS على متجر التطبيقات، مع مسار احتياطي عبر المتصفح. */
    private fun openEngineInPlayStore() {
        val ctx = fragment.requireContext()
        // market:// يفتح الـ Play Store مباشرة؛ وإن لم يوجد معالج نتراجع للرابط عبر الويب.
        val playStoreUri = Uri.parse("market://details?id=com.google.android.tts")
        val opened = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, playStoreUri))
        }.isSuccess
        if (!opened) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"))
                )
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

    // ===== التحويل التلقائي عبر كل اللغات =====
    fun setupAutoConvertUI(view: View) {
        val chk = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_auto_convert)
        val btn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_convert_languages)

        chk.isChecked = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        chk.setOnClickListener { v ->
            val enabled = (v as com.google.android.material.checkbox.MaterialCheckBox).isChecked
            runCatching { settings.setAutoConvertEnabled(enabled) }
            btn.isEnabled = enabled
            onStatusChanged()
        }

        btn.isEnabled = runCatching { settings.isAutoConvertEnabled() }.getOrDefault(false)
        btn.setOnClickListener { showConvertLanguagesDialog() }

        setupLanguageInstallHint(view)
    }

    /**
     * يربط التوضيح الاختياري (نص فقط، غير افتراضي) عن سبب غياب بعض اللغات:
     * بياناتها الصوتية غير مثبتة على الجهاز. مخفي افتراضياً حتى يفعّله
     * المستخدم صراحةً عبر شريط الاختيار.
     */
    private fun setupLanguageInstallHint(view: View) {
        val chk = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_language_install_hint)
        val tv = view.findViewById<TextView>(R.id.tv_language_install_hint)

        chk.isChecked = runCatching { settings.isLanguageInstallHintEnabled() }.getOrDefault(false)
        tv.visibility = if (chk.isChecked) View.VISIBLE else View.GONE
        chk.setOnClickListener { v ->
            val enabled = (v as com.google.android.material.checkbox.MaterialCheckBox).isChecked
            runCatching { settings.setLanguageInstallHintEnabled(enabled) }
            tv.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    /** قائمة كل المحركات المتاحة عبر INTENT_ACTION_TTS_SERVICE (MATCH_ALL) */
    private fun discoverEngines(): List<EngineInfo> {
        val discovered = EnginePicker.installedEngines(fragment.requireContext())
        // سجل تشخيصي لفوز شكاوى «لا يظهر محركي» على أجهزة Xiaomi/Huawei/Oppo
        // التي قد تُقيّد package visibility رغم <queries> الصحيحة في الـ Manifest.
        Log.i(
            "NATEQ_ENGINE_DISCOVERY",
            "installedEngines count=${discovered.size} packages=[${discovered.joinToString(", ") { it.packageName }}]"
        )
        return discovered.map { EngineInfo(it.packageName, it.label) }
    }

    /**
     * يفتح قائمة كل اللغات المكتشفة فعلياً عبر كل محركات TTS المثبتة، صفاً
     * لكل لغة — من أصوات getVoices فقط، بلا لغات افتراضية أو نظرية. أي لغة
     * تُعلن بياناتها غير مثبتة (LANG_MISSING_DATA) تُحجب من القائمة تماماً.
     * الاكتشاف خلفي بدون تجميد: يُعرض مؤشر تحميل ثم تُبني الصفوف فور
     * جاهزية النتائج.
     */
    fun showConvertLanguagesDialog() {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_convert_languages, null)
        val tvHint = dialogView.findViewById<TextView>(R.id.tv_convert_languages_hint)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rv_convert_languages)

        tvHint.text = fragment.getString(R.string.convert_languages_loading)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.convert_languages_dialog_title))
            .setView(dialogView)
            .setPositiveButton(fragment.getString(R.string.reset_cancel), null)
            .create()

        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val discovered = runCatching {
                VoiceCatalog.discoverAllLanguagesAcrossEngines(ctx.applicationContext)
            }.getOrDefault(emptyMap())
            withContext(Dispatchers.Main) {
                val rows = buildLanguageRows(discovered)
                val adapter = LanguageConvertAdapter(
                    ctx,
                    settings,
                    rows
                ) { enginePkg, voiceName, volume, pitch, rate ->
                    playbackPreview(enginePkg, voiceName, volume, pitch, rate)
                }
                adapter.onRowSaved = { onStatusChanged() }
                rv.layoutManager = LinearLayoutManager(ctx)
                rv.adapter = adapter
                tvHint.text = fragment.getString(R.string.convert_languages_hint)
            }
        }

        dialog.show()
    }

    /**
     * يُبني صفوف اللغات من المكتشف الفعلي فقط — لا تُعرض أي لغة لم تُرجعها
     * getVoices — مع ضمان إدراج العربية والإنجليزية دائماً كحد أدنى مضمون:
     * قد يحجبا الاكتشافُ حين تُعلن كل أصواتهما عبر محركٍ ما بياناتٍ غير
     * مثبتة (LANG_MISSING_DATA/NOT_INSTALLED أو عبر الشبكة فقط)، فلا بدّ
     * من بقائهما في القائمة بعرض لغتهما مع بقبه صندوق المحركات فارغاً
     * (أشرطة قابلة للحفظ — نفس سلوك البرتب الصف «بلا محرك» في المهايئ).
     */
    private fun buildLanguageRows(
        discovered: Map<String, List<EngineWithVoices>>
    ): List<LanguageRow> {
        val tags = LinkedHashSet<String>()
        tags.addAll(discovered.keys.sorted())
        tags.add("ar") // الحد الأدنى المضمون دائماً
        tags.add("en")
        return tags.map { tag ->
            LanguageRow(
                languageTag = tag,
                displayName = Locale.forLanguageTag(tag).displayName,
                engines = discovered[tag].orEmpty()
            )
        }
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
        var previewTts: TextToSpeech? = null
        // ربط مباشر بالمحرك المعيّن (منشئ ثلاثي المعاملات) بدل الافتراضي ثم
        // setEngineByPackageName: معاينة العينة يجب أن تعمل حتى لو كان محرك
        // النطق الافتراضي للنظام هو حزمة LORD نفسها (التطبيق محرك TTS أصلياً).
        val created = runCatching {
            @Suppress("DEPRECATION")
            previewTts = TextToSpeech(fragment.requireContext(), { status ->
                if (status != TextToSpeech.SUCCESS) {
                    // فشل تهيئة محرك المعاينة: نغلق فوراً حتى لا تبقى نسخة TTS معلقة
                    runCatching { previewTts?.shutdown() }
                    return@TextToSpeech
                }
                try {
                    val avail = runCatching { previewTts?.getVoices().orEmpty() }.getOrDefault(emptySet())
                    val voice = avail.firstOrNull { it.name == voiceName }
                    if (voice != null) {
                        // نضبط المحرك على لسان الصوت المختار حتى لا يقرأ النص
                        // بلغة المحرك الافتراضية (مثلاً الإنجليزية رغم اختيار العربي).
                        runCatching { previewTts?.setVoice(voice) }
                        runCatching { previewTts?.let { it.language = voice.locale } }
                    }
                } catch (_: Exception) {}
                previewTts?.setSpeechRate(rate)
                runCatching { previewTts?.setPitch(pitch) }
                // نمرر مستوى الصوت للمحرك عبر المعاملات (كان بلا مستوى صوت إطلاقاً)
                // ليقترب ناتج المعاينة من النطق الفعلي الذي يطبق نفس القيم.
                // السرعة والنبرة تمران عبر setSpeechRate/setPitch (لا توجد ثوابت
                // عامة لهما في Bundle الكلامة).
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
                    previewTts?.speak(
                        sampleText,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        "preview"
                    )
                }.getOrDefault(TextToSpeech.ERROR)
                if (speakResult == TextToSpeech.ERROR) {
                    // فشل النطق (مثلاً المحرك دون لغة محمّلة): نغلق فوراً عوضاً عن تعليقه
                    runCatching { previewTts?.shutdown() }
                    return@TextToSpeech
                }
                previewTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    @Deprecated("Java Deprecated")
                    override fun onDone(utteranceId: String?) { previewTts?.shutdown() }

                    @Deprecated("Java Deprecated")
                    override fun onError(utteranceId: String?) { previewTts?.shutdown() }
                })
            }, enginePkg)
        }
        if (created.isFailure) {
            // تعذّر ربط محرك المعاينة بذاته (حزمة غير صالحة): لا نترك نسخة معلقة.
            runCatching { previewTts?.shutdown() }
            return
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