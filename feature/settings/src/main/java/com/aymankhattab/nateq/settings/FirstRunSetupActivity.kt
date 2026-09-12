package com.aymankhattab.nateq.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * «معالج الإعداد الأولي» القابل للتخطي: شاشة تُعرض مرة واحدة عند أول تشغيل
 * (قبل استكشاف الإعدادات) تتيح اختيار لغة الواجهة ومحرك النطق المحبوك
 * باللغة — ثم تُعلِّم الاكتمال فلا تُعرض ثانيةً. «تخطّي» يُغلقها بلا
 * أي تغيير. البداية، لا تفرض شيئاً؛ كل إعداد اختياري يبقى قابلاً
 * للتعديل لاحقاً من شاشة الإعدادات.
 */
@AndroidEntryPoint
class FirstRunSetupActivity :
    AppCompatActivity(R.layout.activity_first_run_setup) {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** مواضع فهرسها: 0 = العربية، 1 = الإنجليزية (متطابقة في كل الضبط). */
    private val languageCodes = listOf("ar", "en")

    /** المحركات المثبتة (مع «تلقائي» في الموضع 0). */
    private val engines = mutableListOf<EnginePicker.InstalledEngine>()

    private var selectedLanguageIndex = 0
    private var selectedEngineIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val spinnerLanguage =
            findViewById<MaterialAutoCompleteTextView>(
                R.id.spinner_first_run_language
            )
        val spinnerEngine =
            findViewById<MaterialAutoCompleteTextView>(
                R.id.spinner_first_run_engine
            )

        val languageOptions = arrayOf(
            getString(R.string.first_run_lang_arabic),
            getString(R.string.first_run_lang_english)
        )
        spinnerLanguage.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                languageOptions
            )
        )
        // اختيار لغة الواجهة الحالية إن كانت عربية/إنجليزية (وإلا العربية)
        val currentLang = runCatching { settingsRepository.getAppLanguage() }
            .getOrNull()
        selectedLanguageIndex = languageCodes.indexOf(
            currentLang?.takeIf { it in languageCodes }
        ).coerceAtLeast(0)
        spinnerLanguage.setText(
            languageOptions[selectedLanguageIndex], false
        )
        // لمسُ الحقل يفتح القائمة (لا متفرق لوحة المفاتيح)
        spinnerLanguage.setKeyListener(null)
        spinnerLanguage.setOnClickListener { spinnerLanguage.showDropDown() }
        spinnerLanguage.setOnItemClickListener { _, _, position, _ ->
            selectedLanguageIndex = position
        }

        engines.clear()
        engines.addAll(
            runCatching { EnginePicker.installedEngines(this) }
                .getOrDefault(emptyList())
        )
        val engineOptions = buildList {
            add(getString(R.string.first_run_engine_auto))
            addAll(engines.map { it.label })
        }
        spinnerEngine.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                engineOptions)
        )
        // المحرك يبدأ «تلقائي» دائماً (لا محرك افتراضي عام): ربط المحرك
        // بلغة الواجهة يتم فقط عند الحفظ.
        spinnerEngine.setText(engineOptions[0], false)
        spinnerEngine.setKeyListener(null)
        spinnerEngine.setOnClickListener { spinnerEngine.showDropDown() }
        spinnerEngine.setOnItemClickListener { _, _, position, _ ->
            selectedEngineIndex = position
        }

        findViewById<View>(R.id.btn_first_run_save)
            .setOnClickListener {
                onSaveClicked(selectedLanguageIndex, selectedEngineIndex)
            }
        findViewById<View>(R.id.btn_first_run_skip)
            .setOnClickListener { finishSkipped() }
        findViewById<View>(R.id.btn_first_run_default_engine)
            .setOnClickListener {
                // فتح شاشة TTS النظامية لاختيار Lord كالمحرك الافتراضي
                runCatching {
                    startActivity(
                        Intent("com.android.settings.TTS_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        // شريط الحالة عملياً (edge-to-edge): إزاحة المحتوى للأسفل حتى لا
        // يتداخل زر الحفظ مع النافذة النظامية
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(android.R.id.content)
        ) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        registerBackAsSkip()
    }

    private fun onSaveClicked(
        languageIndex: Int,
        engineIndex: Int
    ) {
        val language = languageCodes[languageIndex]
        val enginePkg = engines
            .getOrNull(engineIndex - 1)?.packageName
        val previousLanguage = runCatching {
            settingsRepository.getAppLanguage()
        }.getOrNull()
        runCatching {
            settingsRepository.setAppLanguage(language)
            // ربط المحرك باللغة المختارة فوراً (صوت المحرك يُحدَّد تلقائياً
            // لتلك اللغة — تفضيل تحويل بلا تعديل أشرطة). بلا محرك → مسح.
            settingsRepository.setEnginePreferenceForLanguage(
                language, enginePkg, null, 1f, 1f, 1f
            )
            // اختيار محرك صريح يفعّل «التحويل التلقائي» تلقائياً: من دون ذلك
            // كان المستخدم يختار محركاً ثم يكتشف أن الإعلانات ما تزال بالمحرك
            // الافتراضي. «تلقائي» أعلاه لا يفعّله (لا محرك محدداً).
            if (enginePkg != null) {
                settingsRepository.setAutoConvertEnabled(true)
            }
            settingsRepository.setFirstRunSetupCompleted(true)
        }
        setResult(RESULT_OK)
        if (enginePkg != null) {
            Toast.makeText(
                this,
                R.string.auto_convert_auto_enabled,
                Toast.LENGTH_LONG
            ).show()
        }
        if (language != previousLanguage) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language)
            )
        }
        finish()
    }

    /** «تخطّي»: يُعلَّم الاكتمال (لا يُزعج ثانية) ويُغلق بلا تغيير. */
    private fun finishSkipped() {
        runCatching { settingsRepository.setFirstRunSetupCompleted(true) }
        setResult(RESULT_CANCELED)
        finish()
    }

    /** زر الرجوع = تخطٍّ (لا يُعاد المعالج مرة أخرى). */
    private fun registerBackAsSkip() {
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishSkipped()
                }
            }
        )
    }
}