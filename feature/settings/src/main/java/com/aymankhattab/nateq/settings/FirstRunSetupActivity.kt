package com.aymankhattab.nateq.settings

import android.os.Bundle
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.feature.settings.R
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val spinnerLanguage =
            findViewById<Spinner>(R.id.spinner_first_run_language)
        val spinnerEngine =
            findViewById<Spinner>(R.id.spinner_first_run_engine)

        val languageOptions = arrayOf(
            getString(R.string.first_run_lang_arabic),
            getString(R.string.first_run_lang_english)
        )
        spinnerLanguage.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageOptions
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }
        // اختيار لغة الواجهة الحالية إن كانت عربية/إنجليزية (وإلا العربية)
        val currentLang = runCatching { settingsRepository.getAppLanguage() }
            .getOrNull()
        val currentIndex = languageCodes.indexOf(
            currentLang?.takeIf { it in languageCodes }
        ).coerceAtLeast(0)
        spinnerLanguage.setSelection(currentIndex)

        engines.clear()
        engines.addAll(
            runCatching { EnginePicker.installedEngines(this) }
                .getOrDefault(emptyList())
        )
        val engineOptions = buildList {
            add(getString(R.string.first_run_engine_auto))
            addAll(engines.map { it.label })
        }
        spinnerEngine.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            engineOptions
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }
        // المحرك يبدأ «تلقائي» دائماً (لا محرك افتراضي عام): ربط المحرك
        // بلغة الواجهة يتم فقط عند الحفظ.

        findViewById<android.view.View>(R.id.btn_first_run_save)
            .setOnClickListener {
                onSaveClicked(spinnerLanguage, spinnerEngine)
            }
        findViewById<android.view.View>(R.id.btn_first_run_skip)
            .setOnClickListener { finishSkipped() }
        registerBackAsSkip()
    }

    private fun onSaveClicked(
        spinnerLanguage: Spinner,
        spinnerEngine: Spinner
    ) {
        val language = languageCodes[spinnerLanguage.selectedItemPosition]
        val enginePkg = engines
        .getOrNull(spinnerEngine.selectedItemPosition - 1)?.packageName
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
            settingsRepository.setFirstRunSetupCompleted(true)
        }
        setResult(RESULT_OK)
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