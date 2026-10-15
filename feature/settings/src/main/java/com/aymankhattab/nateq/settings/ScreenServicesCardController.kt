package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aymankhattab.nateq.core.audio.accessibility.A11yPauseScheduler
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.AppPolicy
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.data.AppProfile
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoiceProfile
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar

/**
 * ضابط بطاقة خدمات الشاشة وإمكانية الوصول (ع٢٧-13، ع٢٧-15، ع٢٩-14).
 * يدير:
 * - فحص تفعيل الخدمة في النظام وتوجيه المستخدم لشاشة التهيئة.
 * - **عند عدم التفعيل: زر واحد «فعّل خدمة إمكانية الوصول»**
 *   يفتح AccessibilityOnboardingActivity
 * - **أندرويد 13+: كشف «الإعدادات المقيّدة» وإظهار خطوات التفعيل**
 * - حوار الإيقاف المؤقت لمدد محددة (15 دقيقة، ساعة، منتصف الليل، يدوي).
 * - حوار قائمة التطبيقات المسموحة (AppPolicy).
 * - مفاتيح جميع ميزات قراءة الشاشة والمحادثات.
 */
class ScreenServicesCardController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit = {}
) {

    private val stateStore = A11yStateStore.getInstance(context)

    private var tvStatus: TextView? = null
    private var tvDescription: TextView? = null
    private var btnSetup: MaterialButton? = null
    private var btnPause: MaterialButton? = null
    private var btnAllowedApps: MaterialButton? = null
    private var switchesContainer: View? = null

    fun isServiceEnabledInSystem(): Boolean {
        return AccessibilityUtils.isNateqAccessibilityServiceEnabled(
            context
        )
    }

    fun isServicePaused(): Boolean {
        return stateStore?.isPaused() ?: settings.isA11yPaused()
    }

    fun getServiceStatusText(): String {
        return when {
            !isServiceEnabledInSystem() -> {
                context.getString(R.string.a11y_status_disabled)
            }
            isServicePaused() -> {
                context.getString(R.string.a11y_status_paused)
            }
            else -> {
                context.getString(R.string.a11y_status_active)
            }
        }
    }

    fun setup(root: View) {
        tvStatus = root.findViewById(R.id.tv_a11y_services_status)
        tvDescription =
            root.findViewById<TextView>(R.id.tv_a11y_services_description)
        btnSetup =
            root.findViewWithTag<MaterialButton>("btn_a11y_setup_service")
        btnPause =
            root.findViewWithTag<MaterialButton>("btn_a11y_pause_duration")
        btnAllowedApps =
            root.findViewWithTag<MaterialButton>("btn_a11y_allowed_apps")
        switchesContainer =
            root.findViewWithTag<View>("ll_a11y_switches_container")

        // ربط المستمعين مرة واحدة؛ الإظهار/الإخفاء يُعاد تطبيقه في
        // applyState() دون إعادة استدعاء setup.
        btnPause?.setOnClickListener {
            showPauseDurationDialog { updateUiState() }
        }
        btnAllowedApps?.setOnClickListener {
            showAppPolicyDialog { updateUiState() }
        }
        root.findViewWithTag<MaterialButton>("btn_a11y_contact_voices")
            ?.setOnClickListener {
                showContactVoicesDialog { updateUiState() }
            }
        root.findViewWithTag<MaterialButton>("btn_a11y_app_profiles")
            ?.setOnClickListener {
                showAppProfilesDialog { updateUiState() }
            }
        root.findViewWithTag<MaterialButton>("btn_open_keyboard_settings")
            ?.setOnClickListener {
                val intent =
                    Intent(context, KeyboardSettingsActivity::class.java)
                context.startActivity(intent)
            }

        bindSwitch(
            root,
            "switch_a11y_read_more",
            FeatureFlags.FEATURE_READ_MORE_EXPAND
        )
        bindSwitch(
            root,
            "switch_a11y_chat_live",
            FeatureFlags.FEATURE_CHAT_LIVE_READING
        )
        bindSwitch(
            root,
            "switch_a11y_chat_summary",
            FeatureFlags.FEATURE_CHAT_SUMMARY
        )
        bindSwitch(
            root,
            "switch_a11y_typing_echo",
            FeatureFlags.FEATURE_TYPING_ECHO
        )
        bindSwitch(
            root,
            "switch_a11y_otp_reading",
            FeatureFlags.FEATURE_OTP_READING
        )
        bindSwitch(
            root,
            "switch_a11y_url_shortening",
            FeatureFlags.FEATURE_URL_SHORTENING
        )
        bindSwitch(
            root,
            "switch_a11y_popup",
            FeatureFlags.FEATURE_POPUP_ANNOUNCEMENT
        )
        bindSwitch(
            root,
            "switch_a11y_progress",
            FeatureFlags.FEATURE_PROGRESS_MONITORING
        )
        bindSwitch(
            root,
            "switch_a11y_bluetooth",
            FeatureFlags.FEATURE_BLUETOOTH_ANNOUNCEMENT
        )
        bindSwitch(
            root,
            "switch_a11y_continuous_reading",
            FeatureFlags.FEATURE_CONTINUOUS_READING
        )
        bindSwitch(
            root,
            "switch_a11y_ocr_reading",
            FeatureFlags.FEATURE_OCR_READING
        )
        bindSwitch(
            root,
            "switch_a11y_key_shortcuts",
            FeatureFlags.FEATURE_KEY_SHORTCUTS
        )
        bindSwitch(
            root,
            "switch_a11y_contact_voices",
            FeatureFlags.FEATURE_CONTACT_VOICES
        )
        bindSwitch(
            root,
            "switch_a11y_app_profiles",
            FeatureFlags.FEATURE_APP_PROFILES
        )
        bindSwitch(
            root,
            "switch_a11y_auto_profile",
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH
        )

        val switchCall = root.findViewWithTag<SwitchMaterial>(
            "switch_a11y_smart_pause_call"
        )
        switchCall?.isChecked = settings.isA11ySmartPauseCallEnabled()
        switchCall?.setOnCheckedChangeListener { _, checked ->
            settings.setA11ySmartPauseCallEnabled(checked)
            onStatusChanged()
        }

        val switchGame = root.findViewWithTag<SwitchMaterial>(
            "switch_a11y_smart_pause_game"
        )
        switchGame?.isChecked = settings.isA11ySmartPauseGameEnabled()
        switchGame?.setOnCheckedChangeListener { _, checked ->
            settings.setA11ySmartPauseGameEnabled(checked)
            onStatusChanged()
        }

        root.findViewWithTag<MaterialButton>("btn_a11y_shortcut_bindings")
            ?.setOnClickListener {
                ShortcutBindingsDialog(context, settings).show()
            }

        updateUiState()
    }

    /**
     * أندرويد 13+ (API 33): تحقق إن كانت الخدمة مقيّدة (Restricted Settings).
     * إذا كانت مقيّدة، لا يمكن تفعيلها مباشرة من إعدادات إمكانية الوصول
     * بل يجب أن يضغط المستخدم «السماح بالإعدادات المقيّدة» في معلومات التطبيق.
     */
    private fun isRestrictedByAndroid13(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // على أندرويد 13+، التحقق الحقيقي يتم عبر محاولة فتح إعدادات
        // إمكانية الوصول
        // إن لم تظهر الخدمة قابلة للتفعيل، نعتبرها مقيّدة.
        return true // افتراضياً على Tiramisu+ نعرض تحذير الإعدادات المقيّدة
    }

    /**
     * فتح إعدادات التطبيق للسماح بالإعدادات المقيّدة (أندرويد 13+).
     */
    private fun openRestrictedSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .apply {
                data = android.net.Uri.fromParts(
                    "package", context.packageName, null
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private fun bindSwitch(root: View, tag: String, feature: String) {
        val sw = root.findViewWithTag<SwitchMaterial>(tag) ?: return
        sw.isChecked = settings.isAccessibilityFeatureEnabled(feature)
        sw.setOnCheckedChangeListener { _, checked ->
            settings.setAccessibilityFeatureEnabled(feature, checked)
            if (checked &&
                feature == FeatureFlags.FEATURE_BLUETOOTH_ANNOUNCEMENT
            ) {
                requestBluetoothConnect(root)
            }
            if (checked &&
                feature == FeatureFlags.FEATURE_CALL_KEY_ACTIONS
            ) {
                requestAnswerPhoneCalls(root)
            }
            onStatusChanged()
        }
    }

    /**
     * ع٤١-ب8.2: يطلب إذن ANSWER_PHONE_CALLS عند تفعيل أوامر المكالمة؛
     * وإن غاب الإذن تبقى الميزة معطّلة فعلياً حتى يُمنح.
     */
    private fun requestAnswerPhoneCalls(root: View) {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val activity = findActivity(root.context) ?: return
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.ANSWER_PHONE_CALLS),
            ANSWER_CALLS_PERMISSION_REQUEST
        )
    }

    /**
     * ع٤١-ب9: يطلب إذن BLUETOOTH_CONNECT على API 31+ عند تفعيل إعلان
     * البلوتوث؛ وإن غاب الإذن يبقى الإعلان باسمٍ عام («جهاز بلوتوث»).
     */
    private fun requestBluetoothConnect(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val granted = context.checkSelfPermission(
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val activity = findActivity(root.context) ?: return
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
            BLUETOOTH_PERMISSION_REQUEST
        )
    }

    private fun findActivity(start: Context?): Activity? {
        var current = start
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun updateUiState() {
        tvStatus?.text = getServiceStatusText()
        applyState()
    }

    /**
     * يطبّق منطق الإظهار/الإخفاء وزر التفعيل حسب حالة الخدمة.
     * منفصل عن [setup] عمداً: كان updateUiState() ينادي setup() فينادي
     * setup() بدوره updateUiState() — تكرار لا نهائي يُسقط الشاشة
     * بـ StackOverflowError.
     */
    private fun applyState() {
        val enabled = isServiceEnabledInSystem()
        if (!enabled) {
            // حالة غير مفعّلة: أظهر زر الإعداد، أخفِ باقي العناصر
            btnSetup?.visibility = View.VISIBLE
            if (isRestrictedByAndroid13()) {
                tvDescription?.text =
                    context.getString(R.string.a11y_restricted_settings_desc)
                btnSetup?.text =
                    context.getString(R.string.a11y_open_restricted_settings)
                btnSetup?.setOnClickListener { openRestrictedSettings() }
            } else {
                tvDescription?.text =
                    context.getString(R.string.a11y_onboarding_explanation)
                btnSetup?.text =
                    context.getString(R.string.a11y_open_onboarding)
                btnSetup?.setOnClickListener { openOnboarding() }
            }
            btnPause?.visibility = View.GONE
            btnAllowedApps?.visibility = View.GONE
            switchesContainer?.visibility = View.GONE
        } else {
            // حالة مفعّلة: أظهر جميع التحكمات
            btnSetup?.visibility = View.GONE
            btnPause?.visibility = View.VISIBLE
            btnAllowedApps?.visibility = View.VISIBLE
            switchesContainer?.visibility = View.VISIBLE
        }
    }

    fun openOnboarding() {
        val intent = Intent(
            context,
            AccessibilityOnboardingActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * عرض حوار اختيار مدة الإيقاف المؤقت (ع٢٧-15).
     */
    fun showPauseDurationDialog(onSelected: () -> Unit = {}) {
        val options = arrayOf(
            context.getString(R.string.a11y_pause_15m),
            context.getString(R.string.a11y_pause_1h),
            context.getString(R.string.a11y_pause_3h),
            context.getString(R.string.a11y_pause_midnight),
            context.getString(R.string.a11y_pause_manual)
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_pause_duration_title)
            .setItems(options) { _, which ->
                val now = System.currentTimeMillis()
                val (paused, untilEpoch) = when (which) {
                    0 -> true to (now + 15 * 60 * 1000L)
                    1 -> true to (now + 60 * 60 * 1000L)
                    2 -> true to (now + 3 * 60 * 60 * 1000L)
                    3 -> {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                        }
                        true to cal.timeInMillis
                    }
                    4 -> true to 0L // يدوي
                    else -> false to 0L
                }

                stateStore?.setPaused(paused, untilEpoch, 1)
                if (paused && untilEpoch > now) {
                    A11yPauseScheduler.scheduleResumeAlarm(
                        context, untilEpoch
                    )
                } else if (!paused) {
                    A11yPauseScheduler.cancelResumeAlarm(context)
                }
                updateUiState()
                onStatusChanged()
                onSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun resumeService() {
        stateStore?.setPaused(false, 0L, 1)
        A11yPauseScheduler.cancelResumeAlarm(context)
        updateUiState()
        onStatusChanged()
    }

    /**
     * عرض حوار اختيار سياسة التطبيقات المسموحة (ع٢٧-9).
     */
    fun showAppPolicyDialog(onUpdated: () -> Unit = {}) {
        val currentAllowed = (settings.getA11yAllowedPackages()
            ?: AppPolicy.DEFAULT_WHITELIST).toMutableSet()
        val defaultApps = AppPolicy.DEFAULT_WHITELIST.toList()

        val appLabels = defaultApps.map { pkg ->
            when (pkg) {
                "com.whatsapp" -> context.getString(R.string.app_whatsapp)
                "com.whatsapp.w4b" ->
                    context.getString(R.string.app_whatsapp_business)
                "org.telegram.messenger" ->
                    context.getString(R.string.app_telegram)
                else -> pkg
            }
        }.toTypedArray()

        val checkedItems = defaultApps.map { pkg ->
            currentAllowed.contains(pkg)
        }.toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.a11y_tile_label))
            .setMultiChoiceItems(
                appLabels, checkedItems
            ) { _, which, checked ->
                val pkg = defaultApps[which]
                if (checked) {
                    currentAllowed.add(pkg)
                } else {
                    currentAllowed.remove(pkg)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setA11yAllowedPackages(currentAllowed)
                onUpdated()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * إدارة أصوات جهات الاتصال (ع٤١-ب10.2، ب14): إضافة رقمٍ بمحرّر صوتٍ
     * كامل، وتعديل الموجود بالضغط عليه، وحذفه. المفتاح رقمٌ مطبَّع.
     */
    private fun showContactVoicesDialog(onUpdated: () -> Unit) {
        val store = ContactVoiceStore.create(context)
        val existing = store.keys().toList()
        val listItems = buildList {
            add(context.getString(R.string.a11y_voice_add))
            addAll(existing)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_contact_voices_title)
            .setItems(listItems.toTypedArray()) { _, which ->
                if (which == 0) {
                    showAddContactVoice(store, onUpdated)
                } else {
                    val key = existing[which - 1]
                    showContactVoiceEditor(
                        store,
                        key,
                        store.get(key) ?: currentDefaultVoiceProfile(),
                        onUpdated
                    )
                }
            }
            .setNeutralButton(R.string.a11y_voice_remove) { _, _ ->
                showRemoveContactVoiceDialog(store, existing, onUpdated)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** إدخال رقمٍ جديد ثم فتح المحرّر الكامل عليه. */
    private fun showAddContactVoice(
        store: ContactVoiceStore,
        onUpdated: () -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.a11y_voice_phone_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_voice_add)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = input.text?.toString().orEmpty().trim()
                if (number.isNotEmpty()) {
                    showContactVoiceEditor(
                        store,
                        number,
                        currentDefaultVoiceProfile(),
                        onUpdated
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showContactVoiceEditor(
        store: ContactVoiceStore,
        key: String,
        initial: VoiceProfile,
        onUpdated: () -> Unit
    ) {
        VoiceProfileEditorDialog.show(
            context = context,
            title = context.getString(R.string.a11y_contact_voices_title),
            initial = initial,
            sampleText = context.getString(
                R.string.sample_text_caller_preview
            ),
            callerCategory = true
        ) { profile ->
            store.put(key, profile)
            onUpdated()
        }
    }

    private fun showRemoveContactVoiceDialog(
        store: ContactVoiceStore,
        keys: List<String>,
        onUpdated: () -> Unit
    ) {
        if (keys.isEmpty()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_voice_remove)
            .setItems(keys.toTypedArray()) { _, which ->
                store.remove(keys[which])
                onUpdated()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * إدارة ملفات التطبيقات (ع٤١-ب11.3، ب14): اختيار تطبيقٍ من المثبّتة
     * ثم فتح المحرّر الكامل عليه (وإن كان له ملفٌّ سابق يُحمَّل للتحرير)،
     * وحذف الموجود.
     */
    private fun showAppProfilesDialog(onUpdated: () -> Unit) {
        val store = AppProfileStore.create(context)
        val apps = launchableApps()
        if (apps.isEmpty()) return
        val labels = apps.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_app_profiles_title)
            .setItems(labels) { _, which ->
                val pkg = apps[which].first
                val stored = store.get(pkg)
                val initial = stored?.toVoiceProfile()
                    ?: currentDefaultVoiceProfile()
                VoiceProfileEditorDialog.show(
                    context = context,
                    title = apps[which].second,
                    initial = initial,
                    sampleText = context.getString(
                        R.string.sample_text_notification_preview
                    )
                ) { profile ->
                    store.put(
                        AppProfile(
                            packageName = pkg,
                            engine = profile.engine,
                            voiceId = profile.voiceId,
                            speechRate = profile.speechRate,
                            pitch = profile.pitch,
                            volume = profile.volume,
                            detailLevel = stored?.detailLevel,
                            features = stored?.features.orEmpty()
                        )
                    )
                    onUpdated()
                }
            }
            .setNeutralButton(R.string.a11y_voice_remove) { _, _ ->
                showRemoveAppProfileDialog(store, onUpdated)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoveAppProfileDialog(
        store: AppProfileStore,
        onUpdated: () -> Unit
    ) {
        val keys = store.packages().toList()
        if (keys.isEmpty()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_voice_remove)
            .setItems(keys.toTypedArray()) { _, which ->
                store.remove(keys[which])
                onUpdated()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** صوت الفئة الافتراضية الحالية يُنسخ إلى مدخلٍ جديد. */
    private fun currentDefaultVoiceProfile(): VoiceProfile = VoiceProfile(
        engine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        ),
        voiceId = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        ),
        speechRate = settings.getSpeechRateForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        ),
        pitch = settings.getPitchForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        ),
        volume = settings.getVolumeForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )
    )

    /** التطبيقات القابلة للإطلاق (التسمية ← اسم الحزمة)، مرتّبة. */
    private fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .map {
                    it.activityInfo.packageName to
                        it.loadLabel(pm).toString()
                }
                .distinctBy { it.first }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** رمز طلب إذن BLUETOOTH_CONNECT (ع٤١-ب9). */
        private const val BLUETOOTH_PERMISSION_REQUEST = 4109
        private const val ANSWER_CALLS_PERMISSION_REQUEST = 4110
    }
}
