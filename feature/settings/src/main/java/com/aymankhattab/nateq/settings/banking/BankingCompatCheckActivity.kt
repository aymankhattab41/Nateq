package com.aymankhattab.nateq.settings.banking

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.accessibility.RestoreDiagnosticsTracker
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.audio.announcement.EventVoiceTrace
import com.aymankhattab.nateq.settings.OemGuidanceController
import com.aymankhattab.nateq.settings.WriteSecureSettingsGuideActivity
import com.aymankhattab.nateq.settings.OemVendor
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * شاشة «فحص توافق التطبيقات المصرفية» + معالج «اعزل السبب» (ع٢٩-13).
 *
 * **الغرض**: المستخدم يشكو أن إنستاباي يتوقف مع ناطق ويعمل مع قارئ شاشةٍ
 * ويعمل مع تصحيح USB. بدل إعلان «السبب هو ناطق» بلا معرفة القدرةِ
 * المُعيِّنة، تُعرض هنا كل قدرةٍ بحالتها، ثم يُعزل السبب خطوةً خطوةً
 * بمقارنةٍ قبلية/بعدية على كل قدرةٍ على حدة.
 *
 * **الاستئناف**: كل نتيجة خطوةٍ تُكتب فوراً في ملف [resultsFile]؛ فلو
 * غادر المستخدم التطبيق ليذهب إلى إعدادات النظام ثم عاد، بقيت نتائجه
 * محفوظةً والشاشة تُكمل من حيث توقّفت (تُعاد القراءة في [onResume]).
 *
 * **القارئ الشاشة**: كل عنصرٍ له نصٌّ عربيٌّ كامل مُعرَّف العناوين،
 * والخطوةُ الحالية تُعلَن عند تغيّرها فلا تُفوت على من يعتمد على قارئ
 * الشاشة.
 */
class BankingCompatCheckActivity : AppCompatActivity() {

    /** محرك نطق الخطوات — يُسلَّم إليه نصُّ الخطوة عند تغيّرها. */
    private var tts: TextToSpeech? = null

    private var itemsContainer: LinearLayout? = null
    private var resultsContainer: LinearLayout? = null
    private var tvLordA11y: TextView? = null
    private var tvLordNotif: TextView? = null
    private var tvLordOverlay: TextView? = null
    private var tvChosenApp: TextView? = null
    private var tvStepCounter: TextView? = null
    private var tvStepTitle: TextView? = null
    private var tvStepDetail: TextView? = null
    private var tvSummary: TextView? = null
    private var btnOpenSettingsTarget: MaterialButton? = null
    private var btnOpenBankingApp: MaterialButton? = null
    private var btnNext: MaterialButton? = null
    private var btnFinish: MaterialButton? = null

    /** نتيجة كل خطوةٍ كما سجّلها المستخدم (الخطوة → هل فتحت). */
    private val results = mutableMapOf<IsolateStep, Boolean>()

    /** فهرس الخطوة الحالية المعروضة. */
    private var stepIndex: Int = 0

    /** حزمة التطبيق المصرف المختار (تُحفظ ليبقى بعد إغلاق الشاشة). */
    private var bankingPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banking_compat_check)
        bindViews()
        setupToolbar()
        setupWizardControls()
        loadPersistedState()
        renderWizard()
        renderSummary()
    }

    override fun onResume() {
        super.onResume()
        // عاد المستخدم من إعدادات النظام: الحالة تغيّرت هناك، فلا تُعرض
        // على قارئ الشاشة صورةٌ قديمة بعد العودة.
        refreshCheckItems()
        refreshLordCapabilities()
        refreshTimeline()
        refreshRestoreReadinessAndLog()
        refreshVoiceTrace()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun bindViews() {
        itemsContainer = findViewById(R.id.ll_banking_items)
        resultsContainer = findViewById(R.id.ll_wizard_results)
        tvLordA11y = findViewById(R.id.tv_lord_a11y)
        tvLordNotif = findViewById(R.id.tv_lord_notif)
        tvLordOverlay = findViewById(R.id.tv_lord_overlay)
        tvChosenApp = findViewById(R.id.tv_chosen_app)
        tvStepCounter = findViewById(R.id.tv_step_counter)
        tvStepTitle = findViewById(R.id.tv_step_title)
        tvStepDetail = findViewById(R.id.tv_step_detail)
        tvSummary = findViewById(R.id.tv_wizard_summary)
        btnOpenSettingsTarget = findViewById(R.id.btn_open_settings_target)
        btnOpenBankingApp = findViewById(R.id.btn_open_banking_app)
        btnNext = findViewById(R.id.btn_next_step)
        btnFinish = findViewById(R.id.btn_finish_wizard)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupWizardControls() {
        findViewById<MaterialButton>(R.id.btn_choose_banking_app)
            .setOnClickListener { pickBankingApp() }
        btnOpenSettingsTarget?.setOnClickListener { openSettingsForStep() }
        btnOpenBankingApp?.setOnClickListener { openBankingApp() }
        findViewById<MaterialButton>(R.id.btn_record_opened)
            .setOnClickListener { recordStep(opened = true) }
        findViewById<MaterialButton>(R.id.btn_record_not_opened)
            .setOnClickListener { recordStep(opened = false) }
        findViewById<MaterialButton>(R.id.btn_reset_wizard)
            .setOnClickListener { resetWizard() }
        btnNext?.setOnClickListener { goToNextStep() }
        btnFinish?.setOnClickListener { finishWizard() }
        setupTimelineAndReadinessControls()
        initSpeaker()
    }

    private fun initSpeaker() {
        runCatching {
            tts = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    tts = null
                }
            }
        }.onFailure { tts = null }
    }

    // ===== جمع الحالة الحقيقية =====

    /** إعادة بناء بنود الفحص من الحالة الفعلية للجهاز. */
    private fun refreshCheckItems() {
        val container = itemsContainer ?: return
        runCatching {
            val items = BankingCompatProbe.probe(this)
            container.removeAllViews()
            for (item in items) {
                container.addView(buildItemView(item))
            }
        }
    }

    /** صفُّ بندٍ واحد: العنوان ثم الحالة ثم التصنيف. */
    private fun buildItemView(item: BankingCheckItem): View {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding, 0, padding)
        }
        val label = TextView(this).apply {
            text = item.label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = item.detail
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(this@BankingCompatCheckActivity,
                    R.color.text_secondary)
            )
        }
        val risk = TextView(this).apply {
            text = getString(
                when (item.risk) {
                    BankingRisk.HIGH -> R.string.banking_risk_high
                    BankingRisk.LOW -> R.string.banking_risk_low
                }
            )
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(
                    this@BankingCompatCheckActivity,
                    if (item.risk == BankingRisk.HIGH) {
                        R.color.banking_risk_high
                    } else {
                        R.color.text_secondary
                    }
                )
            )
        }
        wrapper.addView(label)
        wrapper.addView(detail)
        wrapper.addView(risk)
        return wrapper
    }

    /** ترويسة «قدرات ناطق الآن» — الأقرب إلى شكوى المستخدم. */
    private fun refreshLordCapabilities() {
        runCatching {
            val state = BankingCompatProbe.lordCapabilityState(this)
            tvLordA11y?.text = getString(
                if (state.accessibilityServiceEnabled) {
                    R.string.banking_lord_a11y_on
                } else {
                    R.string.banking_lord_a11y_off
                }
            )
            tvLordNotif?.text = getString(
                if (state.notificationListenerEnabled) {
                    R.string.banking_lord_notif_on
                } else {
                    R.string.banking_lord_notif_off
                }
            )
            tvLordOverlay?.text = getString(
                if (state.overlayGranted) {
                    R.string.banking_lord_overlay_on
                } else {
                    R.string.banking_lord_overlay_off
                }
            )
        }
    }

    // ===== المعالج =====

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadPersistedState() {
        bankingPackage = prefs().getString(KEY_BANKING_PACKAGE, null)
        val step = prefs().getInt(KEY_STEP_INDEX, 0)
            .coerceIn(0, IsolateCauseWizard.steps.size - 1)
        stepIndex = step
        val stored = runCatching {
            File(filesDir, IsolateCauseWizard.RESULTS_FILE_NAME)
                .readText(Charsets.UTF_8)
        }.getOrDefault("")
        results.clear()
        results.putAll(
            IsolateCauseWizard.parseResults(stored)
                .associate { it.step to it.opened }
        )
        renderChosenApp()
    }

    private fun persistState() {
        runCatching {
            prefs().edit()
                .putString(KEY_BANKING_PACKAGE, bankingPackage)
                .putInt(KEY_STEP_INDEX, stepIndex)
                .apply()
            val lines = IsolateCauseWizard.steps
                .filter { results.containsKey(it) }
                .joinToString("\n") {
                    IsolateCauseWizard.formatResult(it, results[it] ?: false)
                }
            File(filesDir, IsolateCauseWizard.RESULTS_FILE_NAME)
                .writeText(lines, Charsets.UTF_8)
        }
    }

    private fun currentStep(): IsolateStep =
        IsolateCauseWizard.steps[stepIndex]

    private fun renderChosenApp() {
        val pkg = bankingPackage
        tvChosenApp?.text = if (pkg == null) {
            getString(R.string.banking_wizard_no_app_chosen)
        } else {
            val label = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)
            getString(R.string.banking_wizard_app_chosen, label)
        }
    }

    private fun renderWizard() {
        val step = currentStep()
        tvStepCounter?.text = getString(
            R.string.banking_wizard_step_of, stepIndex + 1,
            IsolateCauseWizard.stepCount()
        )
        tvStepTitle?.text = getString(stepTitleRes(step))
        tvStepDetail?.text = getString(stepDetailRes(step))
        renderResults()
        announceStep(step)
    }

    /** سطرُ نتيجة كل خطوةٍ سُجِّلت — بترتيب التنفيذ لا ترتيب الملف. */
    private fun renderResults() {
        val container = resultsContainer ?: return
        container.removeAllViews()
        for (step in IsolateCauseWizard.steps) {
            val recorded = results[step] ?: continue
            val row = TextView(this).apply {
                text = getString(
                    if (recorded) {
                        R.string.banking_wizard_result_opened
                    } else {
                        R.string.banking_wizard_result_not_opened
                    }
                ) + " — " + getString(stepTitleRes(step))
                textSize = 13f
            }
            container.addView(row)
        }
    }

    /** نُطق الخطوة عند تغيّرها — لا يُفوت على من يعتمد النطق. */
    private fun announceStep(step: IsolateStep) {
        val text = getString(
            R.string.banking_wizard_step_of, stepIndex + 1,
            IsolateCauseWizard.stepCount()
        ) + ". " + getString(stepTitleRes(step))
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "banking-step")
        }
        // @Suppress(DEPRECATION): لا بديل عموميّ للإعلان — أزال androidx نسخته.
        @Suppress("DEPRECATION")
        tvStepTitle?.announceForAccessibility(text)
    }

    /**
     * اختيار التطبيق المصرف من قائمة تطبيقاتٍ قابلةٍ للإطلاق على الجهاز.
     *
     * لا نستخدم `Intent.createChooser` لأن نتيجةَها لا تُعيِد حزمةَ
     * التطبيق المختار في كل الأجهزة (بعض المصنّعات تستبدلها بمُنتقيها
     * الخاص)، ونحتج حزمتَه كي نفتحها في كل خطوةٍ بعدها.
     */
    private fun pickBankingApp() {
        runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos =
                packageManager.queryIntentActivities(launcherIntent, 0)
            if (resolveInfos.isEmpty()) {
                Toast.makeText(
                    this, R.string.banking_wizard_app_missing,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val sorted = resolveInfos.sortedBy {
                it.loadLabel(packageManager).toString().lowercase()
            }
            val labels = sorted.map {
                it.loadLabel(packageManager).toString()
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.banking_wizard_choose_app)
                .setItems(labels) { _, which ->
                    bankingPackage = sorted[which].activityInfo.packageName
                    persistState()
                    renderChosenApp()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.onFailure {
            Toast.makeText(
                this, R.string.banking_wizard_app_missing,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openBankingApp() {
        val pkg = bankingPackage
        if (pkg == null) {
            renderChosenApp()
            return
        }
        if (currentStep() == IsolateStep.ISOLATE_ANDROID_17) {
            ProtectedAppTimelineTracker.recordDeviceFingerprint(this)
            ProtectedAppManager.disableAccessibilityServiceDirectly(this)
            ProtectedAppManager.applyAndroid17Hardening(this)
        }
        runCatching {
            val launch = packageManager
                .getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Toast.makeText(
                    this, R.string.banking_wizard_app_missing,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startActivity(launch)
            }
        }.onFailure {
            Toast.makeText(
                this, R.string.banking_wizard_app_missing,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openSettingsForStep() {
        val intent = settingsIntentForStep(currentStep())
        if (intent == null) {
            Toast.makeText(
                this, R.string.banking_wizard_open_failed,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                this, R.string.banking_wizard_open_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun recordStep(opened: Boolean) {
        val step = currentStep()
        results[step] = opened
        persistState()
        renderResults()
        Toast.makeText(
            this, R.string.banking_wizard_result_saved, Toast.LENGTH_SHORT
        ).show()
        if (stepIndex < IsolateCauseWizard.steps.size - 1) {
            goToNextStep()
        } else {
            renderSummary()
        }
    }

    private fun goToNextStep() {
        if (stepIndex < IsolateCauseWizard.steps.size - 1) {
            stepIndex++
            persistState()
            renderWizard()
        }
    }

    private fun resetWizard() {
        results.clear()
        stepIndex = 0
        runCatching {
            File(filesDir, IsolateCauseWizard.RESULTS_FILE_NAME).delete()
        }
        persistState()
        renderWizard()
        tvSummary?.text = ""
    }

    /** نتائج كل خطواتٍ سُجِّلت، بترتيب التنفيذ لا ترتيب الملف. */
    private fun recordedList(): List<IsolateResult> =
        IsolateCauseWizard.steps
            .filter { results.containsKey(it) }
            .map { IsolateResult(it, results[it] ?: false) }

    private fun finishWizard() {
        if (results[IsolateStep.BASELINE] == null) {
            tvSummary?.text =
                getString(R.string.banking_wizard_need_baseline)
            return
        }
        renderSummary()
        // @Suppress(DEPRECATION): انظر تعليق announceStep — لا بديل عموميّ.
        @Suppress("DEPRECATION")
        tvSummary?.announceForAccessibility(tvSummary?.text ?: "")
    }

    private fun renderSummary() {
        val list = recordedList()
        if (list.isEmpty()) {
            tvSummary?.text = ""
            return
        }
        val summary = IsolateCauseWizard.summarize(list)
        tvSummary?.text = getString(causeTextRes(summary.cause))
    }

    private fun causeTextRes(cause: IsolateCause): Int = when (cause) {
        IsolateCause.NONE -> R.string.banking_wizard_cause_none
        IsolateCause.NOTIFICATION_LISTENER ->
            R.string.banking_wizard_cause_notif_listener
        IsolateCause.ACCESSIBILITY ->
            R.string.banking_wizard_cause_accessibility
        IsolateCause.OVERLAY -> R.string.banking_wizard_cause_overlay
        IsolateCause.ANDROID_17 -> R.string.banking_wizard_cause_android17
        IsolateCause.OTHER_APP -> R.string.banking_wizard_cause_other_app
    }

    private fun stepTitleRes(step: IsolateStep): Int = when (step) {
        IsolateStep.BASELINE -> R.string.banking_step_baseline_title
        IsolateStep.DISABLE_NOTIFICATION_LISTENER ->
            R.string.banking_step_notif_title
        IsolateStep.DISABLE_ACCESSIBILITY ->
            R.string.banking_step_a11y_title
        IsolateStep.REVOKE_OVERLAY -> R.string.banking_step_overlay_title
        IsolateStep.ISOLATE_ANDROID_17 ->
            R.string.banking_step_isolate_android17_title
        IsolateStep.RESTORE_SERVICES ->
            R.string.banking_step_restore_title
    }

    private fun stepDetailRes(step: IsolateStep): Int = when (step) {
        IsolateStep.BASELINE -> R.string.banking_step_baseline_detail
        IsolateStep.DISABLE_NOTIFICATION_LISTENER ->
            R.string.banking_step_notif_detail
        IsolateStep.DISABLE_ACCESSIBILITY ->
            R.string.banking_step_a11y_detail
        IsolateStep.REVOKE_OVERLAY -> R.string.banking_step_overlay_detail
        IsolateStep.ISOLATE_ANDROID_17 ->
            R.string.banking_step_isolate_android17_detail
        IsolateStep.RESTORE_SERVICES ->
            R.string.banking_step_restore_detail
    }

    /** شاشةُ الإعداد المناسبة لكل خطوةٍ — بلا تخمين. */
    private fun settingsIntentForStep(step: IsolateStep): Intent? =
        when (step) {
            IsolateStep.BASELINE,
            IsolateStep.RESTORE_SERVICES ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            IsolateStep.DISABLE_NOTIFICATION_LISTENER ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            IsolateStep.DISABLE_ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            IsolateStep.REVOKE_OVERLAY ->
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            IsolateStep.ISOLATE_ANDROID_17 ->
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }

    private fun setupTimelineAndReadinessControls() {
        findViewById<MaterialButton?>(R.id.btn_copy_timeline)
            ?.setOnClickListener {
                val report = ProtectedAppTimelineTracker.getTimelineReport()
                copyTextToClipboard("سجل المخطط الزمني", report)
                Toast.makeText(
                    this, "تم نسخ المخطط الزمني", Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<MaterialButton?>(R.id.btn_share_timeline)
            ?.setOnClickListener {
                val report = ProtectedAppTimelineTracker.getTimelineReport()
                val uri =
                    ProtectedAppTimelineTracker.exportTimelineToFile(this)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (uri != null) {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            sendIntent,
                            getString(R.string.banking_share_timeline_btn)
                        )
                    )
                }
            }

        findViewById<MaterialButton?>(R.id.btn_clear_timeline)
            ?.setOnClickListener {
                ProtectedAppTimelineTracker.clear()
                refreshTimeline()
                Toast.makeText(
                    this, "تم مسح المخطط الزمني", Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<MaterialButton?>(R.id.btn_copy_restore_log)
            ?.setOnClickListener {
                val history =
                    RestoreDiagnosticsTracker.getRecentAttempts(this)
                val text = history.joinToString("\n") { it.formatLine() }
                copyTextToClipboard("سجل محاولات الاسترجاع", text)
                Toast.makeText(
                    this, "تم نسخ سجل الاسترجاع", Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<MaterialButton?>(R.id.btn_clear_restore_log)
            ?.setOnClickListener {
                RestoreDiagnosticsTracker.clear(this)
                refreshRestoreReadinessAndLog()
                Toast.makeText(
                    this, "تم مسح سجل الاسترجاع", Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<MaterialButton?>(R.id.btn_share_voice_trace)
            ?.setOnClickListener {
                val report = EventVoiceTrace.formatReport()
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            sendIntent,
                            getString(R.string.event_voice_trace_share_btn)
                        )
                    )
                }
            }

        findViewById<MaterialButton?>(R.id.btn_clear_voice_trace)
            ?.setOnClickListener {
                EventVoiceTrace.clear()
                refreshVoiceTrace()
                Toast.makeText(
                    this, "تم مسح سجل تتبع الأصوات", Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun refreshVoiceTrace() {
        val tvTrace = findViewById<TextView?>(R.id.tv_voice_trace_log)
        val report = EventVoiceTrace.formatReport()
        tvTrace?.text = if (report.isBlank()) {
            "لا توجد تسجيلات حتى الآن."
        } else {
            report
        }
    }

    private fun refreshTimeline() {
        val tvTimeline = findViewById<TextView?>(R.id.tv_banking_timeline)
        val report = ProtectedAppTimelineTracker.getTimelineReport()
        tvTimeline?.text = if (report.isBlank()) {
            "لا توجد أحداث مسجلة حتى الآن."
        } else {
            report
        }
    }

    private fun refreshRestoreReadinessAndLog() {
        val readiness = ProtectedAppManager.checkReadiness(this)
        val tvSummary =
            findViewById<TextView?>(R.id.tv_restore_readiness_summary)
        tvSummary?.text = if (readiness.canEnableFullDisable) {
            "جاهز للإعادة التلقائية: المتطلبات الأساسية مكتملة."
        } else {
            "تنبيه: يجب منح الأذونات الموضحة بالأحمر لضمان الإعادة التلقائية."
        }

        val itemsContainer =
            findViewById<LinearLayout?>(R.id.ll_restore_readiness_items)
        if (itemsContainer != null) {
            itemsContainer.removeAllViews()
            itemsContainer.addView(
                buildReadinessItem(
                    title = "إذن WRITE_SECURE_SETTINGS",
                    detail = "مطلوب للاسترجاع التلقائي لخدمة الوصول بعد " +
                        "إنستاباي، والتبديل المباشر لمحركات النطق بلا شاشات.",
                    granted = readiness.hasWriteSecure,
                    actionTitle = "دليل منح الإذن عبر ADB",
                    onAction = {
                        startActivity(
                            Intent(
                                this,
                                WriteSecureSettingsGuideActivity::class.java
                            )
                        )
                    }
                )
            )
            itemsContainer.addView(
                buildReadinessItem(
                    title = "الوصول إلى بيانات الاستخدام (Usage Stats)",
                    detail = "مطلوب لاكتشاف لحظة الخروج من التطبيق المحمي " +
                        "واستعادة خدمة إمكانية الوصول تلقائياً.",
                    granted = readiness.hasUsageStats,
                    actionTitle = "فتح إعدادات الوصول للاستخدام",
                    onAction = {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            )
                        }
                    }
                )
            )
            itemsContainer.addView(
                buildReadinessItem(
                    title = "استثناء من تحسين البطارية",
                    detail = "يمنع النظام من قتل خدمة الكشف أثناء " +
                        "إيقاف الوصول.",
                    granted = readiness.isBatteryOptimizationsIgnored,
                    actionTitle = "طلب استثناء البطارية",
                    onAction = {
                        runCatching {
                            val intent = Intent(
                                Settings
                                    .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            ).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        }
                    }
                )
            )
            itemsContainer.addView(
                buildReadinessItem(
                    title = "إذن الإشعارات",
                    detail = "لإصدار إشعار تنبيهي بديل عند تعذر " +
                        "التفعيل المباشر.",
                    granted = readiness.hasNotificationPermission,
                    actionTitle = "إعدادات الإشعارات",
                    onAction = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).apply {
                                putExtra(
                                    Settings.EXTRA_APP_PACKAGE, packageName
                                )
                            }
                            startActivity(intent)
                        }
                    }
                )
            )
            itemsContainer.addView(
                buildReadinessItem(
                    title = "التشغيل التلقائي OEM",
                    detail = "إرشادات حماية التطبيق من الإيقاف بحسب " +
                        "مصنّع الهاتف.",
                    granted = readiness.isAutostartGuided,
                    actionTitle = "عرض إرشادات الشركة المصنعة",
                    onAction = {
                        OemGuidanceController.openAutostartSettings(this)
                    }
                )
            )
            itemsContainer.addView(
                buildReadinessItem(
                    title = "خدمة الاستماع للإشعارات (مضيف احتياطي)",
                    detail = "تعمل كمضيف احتياطي ثانٍ لكشف الخروج حتى لو " +
                        "قُتلت الخدمة الأمامية.",
                    granted = readiness.isNotificationListenerEnabled,
                    actionTitle = "إعدادات الاستماع للإشعارات",
                    onAction = {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings
                                        .ACTION_NOTIFICATION_LISTENER_SETTINGS
                                )
                            )
                        }
                    }
                )
            )
        }

        // تحديث سجل المحاولات
        val tvLog = findViewById<TextView?>(R.id.tv_restore_history_log)
        val history = RestoreDiagnosticsTracker.getRecentAttempts(this)
        tvLog?.text = if (history.isEmpty()) {
            "لا توجد محاولات استرجاع مسجلة حتى الآن."
        } else {
            history.reversed().joinToString("\n") { it.formatLine() }
        }
    }

    private fun buildReadinessItem(
        title: String,
        detail: String,
        granted: Boolean,
        actionTitle: String,
        onAction: () -> Unit
    ): View {
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp8, 0, dp8)
        }
        val tvTitle = TextView(this).apply {
            text = (if (granted) "✓ " else "✗ ") + title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    this@BankingCompatCheckActivity,
                    if (granted) android.R.color.holo_green_dark
                    else android.R.color.holo_red_dark
                )
            )
        }
        val tvDetail = TextView(this).apply {
            text = detail
            textSize = 12f
            setPadding(0, dp4, 0, dp4)
            setTextColor(
                ContextCompat.getColor(
                    this@BankingCompatCheckActivity,
                    R.color.text_secondary
                )
            )
        }
        wrapper.addView(tvTitle)
        wrapper.addView(tvDetail)

        if (!granted) {
            val btn = MaterialButton(
                this, null,
                android.R.attr.borderlessButtonStyle
            ).apply {
                text = actionTitle
                textSize = 12f
                setOnClickListener { onAction() }
            }
            wrapper.addView(btn)
        }
        return wrapper
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val cm =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        cm?.setPrimaryClip(clip)
    }

    companion object {

        private const val PREFS_NAME = "banking_compat_check"
        private const val KEY_BANKING_PACKAGE = "banking_package"
        private const val KEY_STEP_INDEX = "step_index"
    }
}