package com.aymankhattab.nateq.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.VoiceProfile
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * محرّر صوتٍ كامل (ع٤١-ب14 توسعة): يحرّر ملف صوتٍ واحداً بكل حقوله —
 * المحرك، الصوت، السرعة، النبرة، مستوى الصوت — مع معاينةٍ فورية، ويخدم
 * أصوات جهات الاتصال وملفات التطبيقات (ونظير الفئات).
 *
 * **لماذا حوارٌ مستقلّ لا إعادة استخدام `CategoryVoicePanelController`؟**
 * ذاك مربوطٌ بتخطيط شاشة «الصوت الافتراضي» ودورة حياة `Fragment`؛ فبدل
 * كسر مسارٍ عاملٍ استُخرجت *طبقة البيانات* المشتركة ([EnginePicker] و
 * [VoicePreviewHelper] و[buildPreviewParamsFrom]) في بنيةٍ مستقلة.
 */
internal object VoiceProfileEditorDialog {

    fun show(
        context: Context,
        title: String,
        initial: VoiceProfile,
        sampleText: String,
        callerCategory: Boolean = false,
        onSave: (VoiceProfile) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val engines = EnginePicker.installedEngines(context)
        val engineLabels = buildList {
            add(context.getString(R.string.a11y_voice_system_engine))
            addAll(engines.map { it.label })
        }
        val enginePackages: List<String?> =
            listOf(null) + engines.map { it.packageName }

        val engineSpinner = labelSpinner(
            context, root, R.string.a11y_voice_engine
        )
        engineSpinner.adapter = simpleAdapter(context, engineLabels)

        val voiceSpinner = labelSpinner(
            context, root, R.string.a11y_voice_voice
        )
        val voiceStatus = TextView(context).also {
            it.isVisible = false
            root.addView(it)
        }
        val voiceLangLabel = TextView(context).also {
            it.isVisible = false
            root.addView(it, matchWrap())
        }

        val rate = seekRow(context, root, R.string.a11y_voice_rate, 200)
        val pitch = seekRow(context, root, R.string.a11y_voice_pitch, 200)
        val volume = seekRow(context, root, R.string.a11y_voice_volume, 100)

        val previewHelper = VoicePreviewHelper(context)
        val voiceNames = arrayOfNulls<List<String>>(1)
        voiceNames[0] = emptyList()
        val currentVoiceLang = arrayOfNulls<String>(1)
        currentVoiceLang[0] = initial.voiceLanguage

        var tts: TextToSpeech? = null
        var isApplyingInitial = false

        fun updateVoiceLangDisplay(
            chosenEngine: String?,
            chosenVoice: String?
        ) {
            if (chosenVoice.isNullOrBlank()) {
                voiceLangLabel.isVisible = false
                return
            }
            val loc = if (isEmbeddedEngine(chosenEngine)) {
                VoiceCatalog.declaredVoices().firstOrNull {
                    it.name == chosenVoice
                }?.locale
            } else {
                tts?.voices?.firstOrNull { it.name == chosenVoice }?.locale
            }
            val tag = loc?.toLanguageTag() ?: loc?.language
                ?: chosenVoice.substringBefore('-').substringBefore('_')
                    .takeIf { it.length in 2..3 }
                ?: initial.voiceLanguage
            currentVoiceLang[0] = tag
            val disp = loc?.displayName?.ifBlank { null }
                ?: loc?.displayLanguage?.ifBlank { null }
                ?: tag ?: "غير محدد"
            voiceLangLabel.text = "لغة هذا الصوت: $disp (${tag.orEmpty()})"
            voiceLangLabel.isVisible = true
        }

        fun loadVoices(enginePkg: String?) {
            voiceStatus.setText(R.string.a11y_voice_loading)
            voiceStatus.isVisible = true
            voiceLangLabel.isVisible = false
            runCatching { tts?.shutdown() }
            tts = null
            voiceNames[0] = emptyList()
            voiceSpinner.adapter = simpleAdapter(context, emptyList())

            if (isEmbeddedEngine(enginePkg)) {
                val declared = VoiceCatalog.declaredVoices()
                val names = declared.map { it.name }
                voiceNames[0] = names
                val labels = declared.map { v ->
                    val disp = v.locale?.displayName.orEmpty()
                    if (disp.isBlank()) v.name else "$disp — ${v.name}"
                }
                voiceSpinner.adapter = simpleAdapter(context, labels)
                voiceStatus.isVisible = false
                val initialIdx = initial.voiceId?.let { names.indexOf(it) }
                    ?.takeIf { it >= 0 } ?: 0
                voiceSpinner.setSelection(initialIdx)
                updateVoiceLangDisplay(enginePkg, names.getOrNull(initialIdx))
                return
            }

            val holder = arrayOfNulls<TextToSpeech>(1)
            val listener: (Int) -> Unit = { status ->
                val instance = holder[0]
                if (status != TextToSpeech.SUCCESS || instance == null) {
                    voiceStatus.setText(R.string.a11y_voice_no_voices)
                } else {
                    val names = runCatching {
                        instance.voices.orEmpty()
                            .filter { it.name != null }
                            .sortedWith(
                                compareBy(
                                    { it.locale?.displayName.orEmpty() },
                                    { it.name }
                                )
                            )
                            .map { it.name }
                    }.getOrDefault(emptyList())
                    voiceNames[0] = names
                    voiceSpinner.adapter = simpleAdapter(
                        context,
                        names.map {
                            voiceLabel(instance, it)
                        }
                    )
                    voiceStatus.isVisible = false
                    val initialIdx = initial.voiceId?.let { names.indexOf(it) }
                        ?.takeIf { it >= 0 } ?: 0
                    voiceSpinner.setSelection(initialIdx)
                    updateVoiceLangDisplay(
                        enginePkg,
                        names.getOrNull(initialIdx)
                    )
                }
            }
            val created = runCatching {
                @Suppress("DEPRECATION")
                val instance = if (enginePkg == null) {
                    TextToSpeech(context, listener)
                } else {
                    TextToSpeech(context, listener, enginePkg)
                }
                holder[0] = instance
                tts = instance
                instance
            }
            if (created.isFailure) {
                voiceStatus.setText(R.string.a11y_voice_no_voices)
            }
        }

        engineSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (isApplyingInitial) return
                    loadVoices(enginePackages.getOrNull(position))
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        voiceSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val names = voiceNames[0].orEmpty()
                    val voice = names.getOrNull(position)
                    val engine = enginePackages.getOrNull(
                        engineSpinner.selectedItemPosition
                    )
                    updateVoiceLangDisplay(engine, voice)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        fun applyInitial(profile: VoiceProfile) {
            rate.progress = factorToProgress(profile.speechRate, 200)
            pitch.progress = factorToProgress(profile.pitch, 200)
            volume.progress = volumeToProgress(profile.volume)
        }

        isApplyingInitial = true
        engineSpinner.setSelection(
            enginePackages.indexOf(initial.engine).takeIf { it >= 0 } ?: 0
        )
        applyInitial(initial)
        isApplyingInitial = false
        loadVoices(enginePackages.getOrNull(engineSpinner.selectedItemPosition))

        val preview = Button(context).apply {
            setText(R.string.a11y_voice_preview)
            setOnClickListener {
                val names = voiceNames[0].orEmpty()
                val chosenVoice = names.getOrNull(
                    voiceSpinner.selectedItemPosition
                ).orEmpty()
                val chosenEngine = enginePackages.getOrNull(
                    engineSpinner.selectedItemPosition
                )
                val langTag = if (isEmbeddedEngine(chosenEngine)) {
                    chosenVoice.substringBefore('-')
                } else {
                    ""
                }
                previewHelper.play(
                    buildPreviewParamsFrom(
                        voiceName = chosenVoice,
                        languageTag = langTag,
                        enginePkg = chosenEngine,
                        rateProgress = rate.progress,
                        pitchProgress = pitch.progress,
                        volumePercent = volume.progress,
                        sampleText = sampleText,
                        isCallerCategory = callerCategory
                    )
                )
            }
        }
        root.addView(preview, matchWrap())

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(
                    VoiceProfile(
                        engine = enginePackages.getOrNull(
                            engineSpinner.selectedItemPosition
                        ),
                        voiceId = voiceNames[0].orEmpty().getOrNull(
                            voiceSpinner.selectedItemPosition
                        ),
                        speechRate = rate.progress.speedFactor(),
                        pitch = pitch.progress.speedFactor(),
                        volume = volume.progress / 100f,
                        voiceLanguage = currentVoiceLang[0]
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                runCatching { tts?.shutdown() }
                tts = null
                previewHelper.release()
            }
            .create()
        dialog.show()
    }

    private fun labelSpinner(
        context: Context,
        root: LinearLayout,
        labelRes: Int
    ): Spinner {
        root.addView(
            TextView(context).apply { setText(labelRes) },
            matchWrap()
        )
        return Spinner(context).also { root.addView(it, matchWrap()) }
    }

    private fun seekRow(
        context: Context,
        root: LinearLayout,
        labelRes: Int,
        max: Int
    ): SeekBar {
        val value = TextView(context)
        root.addView(
            TextView(context).apply { setText(labelRes) },
            matchWrap()
        )
        val seek = SeekBar(context).apply {
            this.max = max
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        val label = factorLabel(progress.speedFactor())
                        value.text = label
                        bar.setSeekStateDescription(label)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                }
            )
        }
        root.addView(seek, matchWrap())
        root.addView(value, matchWrap())
        return seek
    }

    private fun voiceLabel(tts: TextToSpeech, name: String): String {
        val locale = tts.voices?.firstOrNull { it.name == name }
            ?.locale?.displayName.orEmpty()
        return if (locale.isBlank()) name else "$locale — $name"
    }

    private fun factorLabel(value: Float): String =
        VoiceEditorProgress.factorLabel(value)

    private fun factorToProgress(factor: Float?, max: Int): Int =
        VoiceEditorProgress.factorToProgress(factor, max)

    private fun volumeToProgress(volume: Float?): Int =
        VoiceEditorProgress.volumeToProgress(volume)

    private fun isEmbeddedEngine(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val norm = HostProtocol.normalizeEngineId(pkg)
        return norm == HostProtocol.ENGINE_VOCALIZER
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
}

/**
 * حسابات تحويل قيم المحرّر (نقيّة قابلة للاختبار): تحويل معامل السرعة/
 * النبرة أو نسبة المستوى إلى تقدّم المؤشر — والعكس — وعرض المعامل المُنسّق.
 */
internal object VoiceEditorProgress {

    /** معامل (0.25..2.0) ← تقدّم مؤشر (25..200)، بافتراضٍ 1.0 عند الفراغ. */
    fun factorToProgress(factor: Float?, max: Int): Int {
        val raw = ((factor ?: 1.0f) * 100f).roundToInt()
        return raw.coerceIn(MIN_SPEED_PITCH_PERCENT, max)
    }

    /** نسبة (0.0..1.0) ← تقدّم مؤشر (0..100)، بافتراضٍ 1.0 عند الفراغ. */
    fun volumeToProgress(volume: Float?): Int =
        (((volume ?: 1.0f) * 100f).roundToInt()).coerceIn(0, 100)

    /** عرض المعامل بخانتين عشريتين مستقلّتين عن لغة الجهاز. */
    fun factorLabel(value: Float): String =
        String.format(Locale.US, "%.2f", value)
}
