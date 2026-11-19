package com.aymankhattab.nateq.settings

import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.app.DownloadManager
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.UpdateChecker
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.audio.engine.DictTestProvider
import android.net.Uri
import android.widget.LinearLayout
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.DiscoveryResult
import com.aymankhattab.nateq.core.audio.engine.UnavailableEngine
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceImporter
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.VocalizerDownloader
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.engine.TtsDiagnosticsProvider
import com.aymankhattab.nateq.core.audio.engine.SpeechFallbackTracker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NetworkMetering
import com.aymankhattab.nateq.util.VoiceIdContract
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.EnginePicker.InstalledEngine
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.settings.OemVendor
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation

// ===== قاموس النطق =====
internal fun VoiceSelectionFragment.testDictionaryEntry(
    original: String,
    language: String = "ar",
    resultView: TextView? = null
): String {
    val textProcessor = TextProcessor(
        requireContext(),
        settings,
        pronunciationDict
    )
    val result = textProcessor.process(original, language)
    val output = if (result != original) {
        "قبل: $original\nبعد: $result ✓"
    } else {
        "لم يُعثر على تطابق: $original"
    }
    resultView?.apply {
        visibility = View.VISIBLE
        text = output
    }
    return output
}

internal fun VoiceSelectionFragment.showDictEditDialog(
    existing: Pair<String, String>? = null
) {
    val builder = MaterialAlertDialogBuilder(requireContext())
    val inflater = LayoutInflater.from(requireContext())
    val dialogView = inflater.inflate(R.layout.dialog_dict_entry, null)
    val etWord = dialogView.findViewById<EditText>(R.id.et_dict_word)
    val etPhonetic =
        dialogView.findViewById<EditText>(R.id.et_dict_phonetic)
    val spinnerMatchMode =
        dialogView.findViewById<Spinner>(R.id.spinner_dict_match_mode)
    val spinnerScope =
        dialogView.findViewById<Spinner>(R.id.spinner_dict_scope)
    val spinnerValLang =
        dialogView.findViewById<Spinner>(R.id.spinner_dict_value_lang)
    val tvTestResult =
        dialogView.findViewById<TextView>(R.id.tv_dict_test_result)
    val btnTest =
        dialogView.findViewById<View>(R.id.btn_test_dict_entry)

    val matchModeCodes = listOf("AUTO", "WHOLE", "ANYWHERE")
    val matchModeLabels = listOf(
        getString(R.string.dict_match_mode_auto),
        getString(R.string.dict_match_mode_whole),
        getString(R.string.dict_match_mode_anywhere)
    )
    spinnerMatchMode.adapter = ArrayAdapter(
        requireContext(),
        android.R.layout.simple_spinner_dropdown_item,
        matchModeLabels
    )

    val scopeCodes = listOf(null, "ar", "en", "fr", "de", "es")
    val scopeLabels = listOf(
        getString(R.string.dict_scope_global),
        getString(R.string.dict_scope_ar),
        getString(R.string.dict_scope_en),
        getString(R.string.dict_scope_fr),
        getString(R.string.dict_scope_de),
        getString(R.string.dict_scope_es)
    )
    spinnerScope.adapter = ArrayAdapter(
        requireContext(),
        android.R.layout.simple_spinner_dropdown_item,
        scopeLabels
    )

    val valLangCodes = listOf(null, "ar", "en", "fr", "de", "es")
    val valLangLabels = listOf(
        getString(R.string.dict_pronunciation_lang_auto),
        getString(R.string.dict_scope_ar),
        getString(R.string.dict_scope_en),
        getString(R.string.dict_scope_fr),
        getString(R.string.dict_scope_de),
        getString(R.string.dict_scope_es)
    )
    spinnerValLang.adapter = ArrayAdapter(
        requireContext(),
        android.R.layout.simple_spinner_dropdown_item,
        valLangLabels
    )

    if (existing != null) {
        etWord.setText(existing.first)
        etPhonetic.setText(existing.second)
        val currentScope = pronunciationDict.findEntryScope(existing.first)
        val scopeIdx = scopeCodes.indexOf(currentScope).coerceAtLeast(0)
        spinnerScope.setSelection(scopeIdx)

        val currentMode = pronunciationDict.getMatchMode(
            existing.first, currentScope
        )
        val modeIdx = matchModeCodes.indexOf(currentMode.uppercase())
            .coerceAtLeast(0)
        spinnerMatchMode.setSelection(modeIdx)

        val currentValLang = pronunciationDict.getValueLang(
            existing.first, currentScope
        )
        val valLangIdx = valLangCodes.indexOf(currentValLang)
            .coerceAtLeast(0)
        spinnerValLang.setSelection(valLangIdx)
    }

    btnTest?.setOnClickListener {
        val word = etWord.text.toString().trim()
        val phonetic = etPhonetic.text.toString().trim()
        if (word.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.enter_word_and_pronunciation),
                Toast.LENGTH_SHORT
            ).show()
            return@setOnClickListener
        }
        val scope = scopeCodes.getOrNull(spinnerScope.selectedItemPosition)
        val mode = matchModeCodes.getOrNull(
            spinnerMatchMode.selectedItemPosition
        ) ?: "AUTO"
        val valLang = valLangCodes.getOrNull(
            spinnerValLang.selectedItemPosition
        )
        if (phonetic.isNotEmpty()) {
            runCatching {
                pronunciationDict.addEntry(
                    abbreviation = word,
                    pronunciation = phonetic,
                    languageTag = scope,
                    matchMode = mode,
                    valueLang = valLang
                )
            }
            refreshDictAdapter()
        }
        testDictionaryEntry(word, scope ?: "ar", tvTestResult)
    }
    builder.setView(dialogView)
        .setTitle(
            if (existing == null) getString(R.string.dict_add_title)
            else getString(R.string.dict_edit_entry)
        )
        .setPositiveButton(getString(R.string.save)) { _, _ ->
            val word = etWord.text.toString().trim()
            val phonetic = etPhonetic.text.toString().trim()
            if (word.isNotEmpty() && phonetic.isNotEmpty()) {
                val scope = scopeCodes.getOrNull(
                    spinnerScope.selectedItemPosition
                )
                val mode = matchModeCodes.getOrNull(
                    spinnerMatchMode.selectedItemPosition
                ) ?: "AUTO"
                val valLang = valLangCodes.getOrNull(
                    spinnerValLang.selectedItemPosition
                )
                if (existing != null && (existing.first != word)) {
                    val oldScope = pronunciationDict.findEntryScope(
                        existing.first
                    )
                    runCatching {
                        pronunciationDict.removeEntry(
                            existing.first, oldScope
                        )
                    }
                }
                runCatching {
                    pronunciationDict.addEntry(
                        abbreviation = word,
                        pronunciation = phonetic,
                        languageTag = scope,
                        matchMode = mode,
                        valueLang = valLang
                    )
                }
                if (word.length == 1 &&
                    mode.equals("ANYWHERE", ignoreCase = true)
                ) {
                    val warn = getString(
                        R.string.dict_warning_single_char_anywhere
                    )
                    Toast.makeText(
                        requireContext(), warn, Toast.LENGTH_LONG
                    ).show()
                    view?.announceCompat(warn)
                }
                refreshDictAdapter()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.enter_word_and_pronunciation),
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(
                    getString(R.string.enter_word_and_pronunciation)
                )
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .create().also(::trackDialog).show()
}

/** مربع تأكيد لمسح القاموس بالكامل */
internal fun VoiceSelectionFragment.showClearDictConfirmDialog() {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.dict_clear_all))
        .setMessage(getString(R.string.dict_clear_confirm))
        .setPositiveButton(getString(R.string.dict_clear_all)) { _, _ ->
            runCatching { pronunciationDict.clearAll() }
            refreshDictAdapter()
            Toast.makeText(
                requireContext(),
                getString(R.string.dict_cleared_ok),
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(getString(R.string.dict_cleared_ok))
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .create().also(::trackDialog).show()
}

/** المثيل الوحيد لمسند القاموس — يُعاد استخدامه عبر submitList بدل
 *  بناء مسند جديد وإعادة تسنيده بالكامل مع كل تغيير. */

/** إعادة رسم قائمة إدخالات القاموس بعد أي تغيير
 *  (إضافة/تعديل/حذف/استيراد) — يحسب المسند الفرق فيحدّث الصفوف المتغيّرة
 *  فقط ويحافظ على موضع التمرير. */
internal fun VoiceSelectionFragment.refreshDictAdapter() {
    val entries = runCatching { pronunciationDict.getAllEntries() }
        .getOrDefault(emptyMap())
        .toList()
    val adapter = pronunciationDictAdapter
        ?: PronunciationDictAdapter(
            onRowClick = ::showDictRowOptions,
            getMatchMode = { word ->
                val scope = pronunciationDict.findEntryScope(word)
                pronunciationDict.getMatchMode(word, scope)
            }
        ).also {
            pronunciationDictAdapter = it
            rvPronunciationDict.adapter = it
        }
    adapter.submitList(entries)
}

internal fun VoiceSelectionFragment.showDictTraceDialog() {
    val container = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 24)
    }
    val input = EditText(requireContext()).apply {
        hint = getString(R.string.dict_trace_hint)
    }
    val btnRow = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 12, 0, 12)
    }
    val btnPickLast = MaterialButton(
        requireContext(), null,
        android.R.attr.borderlessButtonStyle
    ).apply {
        text = "آخر رسالة قُرئت"
        setOnClickListener {
            val uri = Uri.parse(
                "content://${requireContext().packageName}" +
                    ".dict_test_provider"
            )
            val res = runCatching {
                requireContext().contentResolver.call(
                    uri,
                    DictTestProvider.METHOD_GET_LAST_SPOKEN,
                    null,
                    null
                )
            }.getOrNull()
            val rawList = res?.getStringArrayList(
                DictTestProvider.KEY_LAST_SPOKEN_LIST
            ).orEmpty()
            val displays = res?.getStringArrayList(
                DictTestProvider.KEY_LAST_SPOKEN_DISPLAYS
            ).orEmpty()
            if (rawList.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "لا توجد رسائل مسجلة بعد",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val arrDisplays = displays.toTypedArray()
                val arrRaw = rawList.toTypedArray()
                val itemsToShow = if (arrDisplays.size == arrRaw.size) {
                    arrDisplays
                } else arrRaw
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("اختر رسالة للتتبع")
                    .setItems(itemsToShow) { _, which ->
                        input.setText(arrRaw[which])
                    }
                    .show()
            }
        }
    }
    val btnFull = MaterialButton(
        requireContext(), null,
        android.R.attr.borderlessButtonStyle
    ).apply {
        text = "تتبّع المسار كاملاً"
    }
    btnRow.addView(btnPickLast)
    btnRow.addView(btnFull)

    val tvResult = TextView(requireContext()).apply {
        setPadding(0, 24, 0, 0)
        setTextIsSelectable(true)
    }
    val scroll = android.widget.ScrollView(requireContext()).apply {
        addView(tvResult)
    }
    container.addView(input)
    container.addView(btnRow)
    container.addView(scroll)

    btnFull.setOnClickListener {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return@setOnClickListener
        val uri = Uri.parse(
            "content://${requireContext().packageName}.dict_test_provider"
        )
        val res = runCatching {
            requireContext().contentResolver.call(
                uri, DictTestProvider.METHOD_FULL_TRACE, raw, null
            )
        }.getOrNull()
        if (res != null) {
            val names = res.getStringArrayList(
                DictTestProvider.KEY_FULL_TRACE_NAMES
            ).orEmpty()
            val values = res.getStringArrayList(
                DictTestProvider.KEY_FULL_TRACE_VALUES
            ).orEmpty()
            val sb = java.lang.StringBuilder()
            for (i in names.indices) {
                val n = names[i]
                val v = if (i < values.size) values[i] else ""
                sb.append("══════════════════════════════\n")
                sb.append("$n:\n$v\n\n")
            }
            tvResult.text = sb.toString()
        } else {
            tvResult.text = "تعذر استدعاء تتبع المسار الكامل عبر :tts"
        }
    }

    val builder = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.dict_trace_dialog_title)
        .setView(container)
        .setPositiveButton(R.string.dict_trace_run_btn, null)
        .setNegativeButton(R.string.cancel, null)

    val dialog = builder.create().also(::trackDialog)
    dialog.show()

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return@setOnClickListener

        val bundle = runCatching {
            val uri = Uri.parse(
                "content://${requireContext().packageName}" +
                    ".dict_test_provider"
            )
            val extras = Bundle().apply {
                putString(DictTestProvider.KEY_TEXT, raw)
                putString(DictTestProvider.KEY_LANG, "ar")
            }
            requireContext().contentResolver.call(
                uri,
                DictTestProvider.METHOD_TRACE,
                raw,
                extras
            )
        }.getOrNull()

        if (bundle != null) {
            val orig = bundle.getString(
                DictTestProvider.KEY_ORIGINAL
            ).orEmpty()
            val proc = bundle.getString(
                DictTestProvider.KEY_PROCESSED
            ).orEmpty()
            val standaloneMax = bundle.getInt(
                DictTestProvider.KEY_STANDALONE_MAX, 2
            )
            val traceLines = bundle.getStringArrayList(
                DictTestProvider.KEY_TRACE_LINES
            ) ?: emptyList<String>()

            val note = getString(R.string.dict_trace_diacritic_note)
            val tracesText = if (traceLines.isEmpty()) {
                getString(R.string.dict_trace_no_matches)
            } else {
                traceLines.joinToString("\n")
            }

            val text = getString(R.string.dict_trace_before, orig) + "\n" +
                getString(R.string.dict_trace_after_tts, proc) + "\n" +
                getString(
                    R.string.dict_trace_standalone_tts, standaloneMax
                ) + "\n\n" +
                note + "\n\n" +
                getString(R.string.dict_trace_results_header) + "\n" +
                tracesText
            tvResult.text = text
            tvResult.contentDescription = text
        } else {
            val dict = pronunciationDict
            val standaloneMax = dict.standaloneMaxLength
            val traces = dict.traceMatches(raw, "ar")
            val proc = TextProcessor(
                requireContext(),
                injectedDict = dict
            ).applyDictionaryFirst(raw, "ar")
            val traceLines = traces.map { tr ->
                val modeStr = when (tr.matchMode.uppercase()) {
                    "WHOLE", "STANDALONE" -> "كلمة مستقلة"
                    "ANYWHERE" -> "أي موضع"
                    else -> "تلقائي"
                }
                val decision = if (tr.accepted) "مقبول" else "مرفوض"
                "المفتاح المختار: «${tr.key}» ← النطق: «${tr.value}». " +
                    "الوضع: $modeStr. " +
                    "القرار: $decision. " +
                    "السبب: ${tr.reason}"
            }
            val note = getString(R.string.dict_trace_diacritic_note)
            val tracesText = if (traceLines.isEmpty()) {
                getString(R.string.dict_trace_no_matches)
            } else {
                traceLines.joinToString("\n")
            }
            val text = getString(R.string.dict_trace_before, raw) + "\n" +
                getString(R.string.dict_trace_after_local, proc) + "\n" +
                getString(
                    R.string.dict_trace_standalone_local, standaloneMax
                ) + "\n\n" +
                note + "\n\n" +
                getString(R.string.dict_trace_results_header) + "\n" +
                tracesText + "\n" +
                getString(R.string.dict_trace_fallback_note)
            tvResult.text = text
            tvResult.contentDescription = text
        }
    }
}

internal fun VoiceSelectionFragment.showDictProcessTestDialog() {
    val container = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 24)
    }
    val input = EditText(requireContext()).apply {
        hint = getString(R.string.dict_test_hint)
    }
    val tvResult = TextView(requireContext()).apply {
        setPadding(0, 24, 0, 0)
        setTextIsSelectable(true)
    }
    container.addView(input)
    container.addView(tvResult)

    val builder = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.dict_test_dialog_title)
        .setView(container)
        .setPositiveButton(R.string.dict_test_run_btn, null)
        .setNegativeButton(R.string.cancel, null)

    val dialog = builder.create().also(::trackDialog)
    dialog.show()

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return@setOnClickListener

        val mainEntries = pronunciationDict.getAllEntries()
        val mainCount = mainEntries.size

        val bundle = runCatching {
            val uri = Uri.parse(
                "content://${requireContext().packageName}" +
                    ".dict_test_provider"
            )
            val extras = Bundle().apply {
                putString(DictTestProvider.KEY_TEXT, raw)
                putString(DictTestProvider.KEY_LANG, "ar")
                putInt(DictTestProvider.KEY_MAIN_COUNT, mainCount)
            }
            requireContext().contentResolver.call(
                uri,
                DictTestProvider.METHOD_TEST,
                raw,
                extras
            )
        }.getOrNull()

        if (bundle != null) {
            val orig = bundle.getString(
                DictTestProvider.KEY_ORIGINAL
            ).orEmpty()
            val proc = bundle.getString(
                DictTestProvider.KEY_PROCESSED
            ).orEmpty()
            val ttsCount = bundle.getInt(
                DictTestProvider.KEY_TTS_COUNT, 0
            )
            val matched = bundle.getInt(
                DictTestProvider.KEY_MATCHED_COUNT, 0
            )

            val splitAlert = if (mainCount != ttsCount) {
                "\n⚠️ تنبيه: انقسام بين العمليتين! " +
                    "(الرئيسية: $mainCount | :tts: $ttsCount)"
            } else {
                "\n✓ العمليتان متزامنتان (العدد: $mainCount)"
            }

            val text = "قبل: $orig\nبعد (عبر مسار :tts الحقيقي): $proc\n" +
                "إدخالات مطابقة: $matched" + splitAlert
            tvResult.text = text
        } else {
            val localProc = TextProcessor(
                requireContext(), injectedDict = pronunciationDict
            ).applyDictionaryFirst(raw, "ar")
            tvResult.text = "قبل: $raw\nبعد (المسار المحلي): $localProc\n" +
                "إجمالي الإدخالات: $mainCount\n" +
                "(تعذّر الاتصال بمزود :tts؛ جرى استخدام النسخة المحلية)"
        }
    }
}

/** نطق الرقم المدخل في حقل المعاينة بنفس منطق النطق الفعلي للإعلانات */
internal fun VoiceSelectionFragment.previewNumber() {
    // نقبل الأرقام الهندية/الشرقية في الحقل (٠١٢٣...) مع الغربية
    val raw = com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(
        etNumberPreview.text.toString().trim()
    )
    // نقبل الأرقام الصحيحة والعشرية والسالبة
    val isValid = raw.isNotEmpty() && raw.matches(
        Regex("""^-?\d+(\.\d+)?$""")
    )
    if (!isValid) {
        Toast.makeText(
            requireContext(),
            R.string.number_preview_invalid,
            Toast.LENGTH_SHORT
        ).show()
        view?.announceCompat(getString(R.string.number_preview_invalid))
        return
    }
    val forced =
        runCatching { settings.getAnnouncementSpeechLanguage() }
            .getOrNull()
    val appLang = runCatching { settings.getAppLanguage() }.getOrNull()
        ?: Locale.getDefault().language
    val isEnglish = if (forced != null) LanguageCode.isEnglish(forced)
        else LanguageCode.isEnglish(appLang)
    val mode = runCatching { settings.getNumberReadingMode() }
        .getOrDefault(1).coerceIn(1, 8)
    val text = NumberSpeech.formatByMode(mode, raw, isEnglish)
    val langTag =
        if (isEnglish) LanguageCode.EN.tag else LanguageCode.AR.tag
    // يمر عبر مسار فئة الأرقام (speakWithCategory) لا speakWithVoice:
    // تحترم المعاينة محركَ/سرعةَ/صوتَ فئة الأرقام المحفوظة — كانت تُنطق
    // دائماً بمحرك اللغة/النظام (جوجل غالباً) وتتجاهل اختيار فئة الأرقام.
    speakWithCategory(
        SettingsRepository.VOICE_CATEGORY_NUMBERS, langTag, text
    )
}

/** حوار إدارة أسماء المتصلين المخصصة (رقم → اسم يُنطق به) */
internal fun VoiceSelectionFragment.showCallerNamesDialog() {
    val names = runCatching { settings.getCustomCallerNames() }
        .getOrDefault(emptyMap()).toMutableMap()
    val rows = names.toList().toMutableList()
    val root = android.widget.LinearLayout(requireContext()).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        // هوامش بمعامل الكثافة حتى لا تتضخم على الشاشات عالية الدقة
        val density = resources.displayMetrics.density
        setPadding(
            (48 * density).toInt(),
            (24 * density).toInt(),
            (48 * density).toInt(),
            0
        )
    }
    val listContainer = android.widget.LinearLayout(
        requireContext()
    ).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }

    fun addRow(number: String = "", name: String = "") {
        val numberPicker =
            com.google.android.material.textfield.TextInputEditText(
                requireContext()
            ).apply {
            hint = getString(R.string.caller_names_number_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(number)
        }
        val namePicker =
            com.google.android.material.textfield.TextInputEditText(
                requireContext()
            ).apply {
            hint = getString(R.string.caller_names_name_hint)
            setText(name)
        }
        val removeBtn = com.google.android.material.button.MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.style
                .Widget_MaterialComponents_Button_TextButton
        ).apply {
            text = getString(R.string.caller_names_delete)
            textSize = 13f
            // وصولية (بند 4.5): زرُ الحذف يعرّف نفسه باسم أو رقم
            // الجهة المستهدفة حتى لا تُقرأ «حذف، زر» متكررة مجهولة
            // لقارئ الشاشة عند وجود عدة أرقام.
            contentDescription = when {
                name.isNotBlank() -> getString(
                    R.string.caller_names_delete_for, name
                )
                number.isNotBlank() -> getString(
                    R.string.caller_names_delete_for, number
                )
                else -> getString(R.string.caller_names_delete)
            }
            // أهداف لمس لا تقل عن 48dp لقارئ الشاشة
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        val numberInputLayout =
            com.google.android.material.textfield.TextInputLayout(
                requireContext()
            ).apply {
                addView(numberPicker)
            }
        val nameInputLayout =
            com.google.android.material.textfield.TextInputLayout(
                requireContext()
            ).apply {
                addView(namePicker)
            }
        val rowLayout =
            android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(
                    numberInputLayout,
                    android.widget.LinearLayout.LayoutParams(0, -2, 2f)
                )
                addView(
                    nameInputLayout,
                    android.widget.LinearLayout.LayoutParams(0, -2, 2f)
                )
                addView(
                    removeBtn,
                    android.widget.LinearLayout.LayoutParams(-2, -2)
                )
            }
        removeBtn.setOnClickListener {
            listContainer.removeView(rowLayout)
            val key = numberPicker.text.toString().trim()
            rows.removeAll { it.first == key }
        }
        listContainer.addView(rowLayout)
        rows.add(number to name)
    }

    names.forEach { (n, nm) -> addRow(n, nm) }
    if (rows.isEmpty()) {
        val emptyHint = TextView(requireContext()).apply {
            text = getString(R.string.caller_names_empty)
            textSize = 13f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }
        listContainer.addView(emptyHint)
    }
    val addBtn = com.google.android.material.button.MaterialButton(
        requireContext(),
        null,
        com.google.android.material.R.style
                .Widget_MaterialComponents_Button_TextButton
    ).apply {
        text = getString(R.string.caller_names_add)
        minHeight = (48 * resources.displayMetrics.density).toInt()
    }
    addBtn.setOnClickListener { addRow() }

    val scroll = android.widget.ScrollView(requireContext()).apply {
        addView(listContainer)
    }
    root.addView(
        scroll,
        android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
    )
    root.addView(addBtn)

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.caller_names_title))
        .setView(root)
        .setPositiveButton(getString(R.string.save)) { _, _ ->
            val newNames = mutableMapOf<String, String>()
            var duplicate = false
            for (i in 0 until listContainer.childCount) {
                val rowLayout = listContainer.getChildAt(i)
                    as? android.widget.LinearLayout ?: continue
                if (rowLayout.childCount < 2) continue
                val numLayout = rowLayout.getChildAt(0)
                    as? com.google.android.material.textfield
                        .TextInputLayout ?: continue
                val nameLayout = rowLayout.getChildAt(1)
                    as? com.google.android.material.textfield
                        .TextInputLayout ?: continue
                val num = numLayout.editText?.text?.toString()
                    ?.trim().orEmpty()
                val nm = nameLayout.editText?.text?.toString()
                    ?.trim().orEmpty()
                if (num.isEmpty() || nm.isEmpty()) continue
                if (newNames.containsKey(num)) {
                    duplicate = true
                    continue
                }
                newNames[num] = nm
            }
            if (duplicate) {
                Toast.makeText(
                    requireContext(),
                    R.string.caller_names_duplicate,
                    Toast.LENGTH_LONG
                ).show()
                view?.announceCompat(
                    getString(R.string.caller_names_duplicate)
                )
            }
            runCatching { settings.setCustomCallerNames(newNames) }
            if (!duplicate) {
                Toast.makeText(
                    requireContext(),
                    R.string.caller_names_saved,
                    Toast.LENGTH_SHORT
                ).show()
                view?.announceCompat(
                    getString(R.string.caller_names_saved)
                )
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .create().also(::trackDialog).show()
}

/** طي/فتح قسم فرعي قابل للطيّ مع تدوير سهم الرأس برمز متجهي (دون نص سهمي).
 *  internal حتى تستدعيه المتحكمات الجانبية الأخرى أيضاً. */
internal fun VoiceSelectionFragment.toggleCollapsible(
    content: View,
    arrow: TextView,
    header: View
) {
    val collapsed =
        content.visibility == View.GONE ||
            content.visibility == View.INVISIBLE
    val willExpand = collapsed
    content.visibility = if (willExpand) View.VISIBLE else View.GONE
    // سهم متجهي يلون بلون النص (مطوي → للأسفل، موسّع → للأعلى)
    arrow.text = ""
    val res = if (willExpand) R.drawable.ic_expand_less
    else R.drawable.ic_expand_more
    val icon = androidx.core.content.ContextCompat.getDrawable(
        requireContext(), res
    )?.apply { setTint(arrow.currentTextColor) }
    arrow.setCompoundDrawablesRelativeWithIntrinsicBounds(
        null, null, icon, null
    )
    // وصف حالة العنصر: العنوان الأساسي + حالة (موسَّع/مطوي)
    val stateLabel =
        if (willExpand) getString(R.string.collapse)
        else getString(R.string.expand)
    val base = header.getTag() as? String
    val desc = if (base != null) {
        "$base - $stateLabel"
    } else {
        stateLabel
    }
    header.contentDescription = desc
    // إعلان الحالة لقارئ الشاشة ونقل التركيز منطقياً عند الظهور/الإخفاء
    view?.announceCompat(desc)
    if (willExpand) {
        val firstFocusable =
            if (content.isFocusable) content else content.findFocus()
        if (firstFocusable != null && firstFocusable !== content) {
            firstFocusable.requestFocus()
        } else {
            header.requestFocus()
        }
    } else {
        header.requestFocus()
    }
}
internal fun VoiceSelectionFragment.showDictRowOptions(
    entry: Pair<String, String>
) {
    val options = arrayOf(
        getString(R.string.dict_edit_entry),
        getString(R.string.dict_delete_entry)
    )
    MaterialAlertDialogBuilder(requireContext())
        .setItems(options) { _, which ->
            when (which) {
                0 -> showDictEditDialog(entry)
                1 -> {
                    runCatching {
                        pronunciationDict.removeEntry(entry.first)
                    }
                    // إعلان مسموع بنتيجة الحذف لتوفير تغذية راجعة لقارئ
                    // الشاشة
                    announcementSpeaker?.speak(
                        getString(R.string.dict_removed),
                        if (com.aymankhattab.nateq.util.LocaleUtils
                                .containsArabic(entry.second)
                        ) {
                            Locale.forLanguageTag(LanguageCode.AR.tag)
                        } else {
                            Locale.forLanguageTag(LanguageCode.EN.tag)
                        },
                        1.0f, 1.0f, 1.0f
                    )
                    refreshDictAdapter()
                }
            }
        }
        .create().also(::trackDialog).show()
}

/** إيقاف النطق الجاري للمتحدث الخاص بمعاينات الأقسام (يُستخدمه ضابط
 *  صحة الجهاز وزر إيقاف معاينة الأرقام). */
internal fun VoiceSelectionFragment.stopPreviewSpeech() {
    announcementSpeaker?.stop()
}

internal fun VoiceSelectionFragment.speakWithVoice(
    languageTag: String,
    text: String
) {
    // نستخدم نفس مسار الإعلانات الصوتية التلقائية (AnnouncementSpeaker)
    // الذي يربط المحرك المختار فعليا عبر setEngineByPackageName —
    // لا نعتمد على المحرك الافتراضي للنظام حتى لا يكون "نحن" أنفسنا.
    val speaker = announcementSpeaker
        ?: AnnouncementSpeaker(requireContext())
            .also { announcementSpeaker = it }
    speaker.speak(
        text,
        Locale.forLanguageTag(languageTag),
        runCatching { settings.getSpeechRate(languageTag) }
            .getOrDefault(1.0f),
        1.0f,
        1.0f,
    )
}

/** معاينة صوت زر فئة (بند 4.3): تحسم محرك الفئة المخصص وسرعتها
 *  فيُسمع الصوت كما سيُعلن فعلاً في تلك الفئة (لا الصوت العام). */
internal fun VoiceSelectionFragment.speakWithCategory(
    category: String,
    languageTag: String,
    text: String
) {
    val speaker = announcementSpeaker
        ?: AnnouncementSpeaker(requireContext())
            .also { announcementSpeaker = it }
    val engine = runCatching {
        settings.getEngineForCategory(category)
    }.getOrNull()
    val rate = runCatching {
        settings.getSpeechRateForCategory(category)
    }.getOrDefault(1.0f)
    // يُحترم صوت الفئة المحفوظ (قد يكون من محرك خارجي) فيُمرَّر
    // name المطابق إلى voiceId — resetVoice قصير الدارة بلا
    // إعادة تهيئة غير ضرورية.
    runCatching {
        settings.getPreferredVoiceIdForCategory(category)
    }.getOrNull()?.let { speaker.resetVoice(it) }
    speaker.speak(
        text,
        Locale.forLanguageTag(languageTag),
        rate,
        1.0f,
        1.0f,
        engineOverride = engine
    )
}

/**
 * يعرض معاينة نطق عبر المساعد المشترك ويُعلن بدايتها ونهايتها لقارئ
 * الشاشة (بند الأوامر 4) — يُستدعى من أزرار المعاينة في الأقسام.
 */
internal fun VoiceSelectionFragment.previewSpeech(params: PreviewParams) {
    val startMsg = getString(R.string.sample_preview_starting)
    view?.announceCompat(startMsg)
    voicePreview.play(params) {
        runCatching {
            if (isAdded) {
                view?.announceCompat(
                    getString(R.string.sample_preview_done)
                )
            }
        }
    }
}

