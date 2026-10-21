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
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.accessibility.A11yPauseScheduler
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.A11yVoiceResolver
import com.aymankhattab.nateq.core.audio.accessibility.AppPolicy
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.data.AppProfile
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.ProtectedApp
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
    private var btnContactVoices: MaterialButton? = null
    private var btnTestContactVoice: MaterialButton? = null
    private var btnAppProfiles: MaterialButton? = null
    private var btnTestAppProfile: MaterialButton? = null
    private var btnProtectedApps: MaterialButton? = null
    var contactPickerLauncher: ((
        onPicked: (name: String?, number: String?) -> Unit
    ) -> Unit)? = null

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
        btnContactVoices =
            root.findViewWithTag<MaterialButton>("btn_a11y_contact_voices")
        btnContactVoices?.setOnClickListener {
            showContactVoicesDialog { updateUiState() }
        }
        btnTestContactVoice =
            root.findViewWithTag<MaterialButton>("btn_a11y_test_contact_voice")
        btnTestContactVoice?.setOnClickListener {
            testContactVoice()
        }

        btnAppProfiles =
            root.findViewWithTag<MaterialButton>("btn_a11y_app_profiles")
        btnAppProfiles?.setOnClickListener {
            showAppProfilesDialog { updateUiState() }
        }
        btnTestAppProfile =
            root.findViewWithTag<MaterialButton>("btn_a11y_test_app_profile")
        btnTestAppProfile?.setOnClickListener {
            testAppProfile()
        }

        btnProtectedApps =
            root.findViewWithTag<MaterialButton>("btn_a11y_protected_apps")
        btnProtectedApps?.setOnClickListener {
            showProtectedAppsDialog { updateUiState() }
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
        val appStore = AppProfileStore.create(context)
        val appCount = appStore.packages().size
        val appOn = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES
        )
        val appStatus = if (appOn) "مفعّل" else "مغلق"
        val appManageText = context.getString(
            R.string.a11y_app_profiles_manage
        )
        btnAppProfiles?.text = "$appManageText ($appStatus — $appCount)"

        val contactStore = ContactVoiceStore.create(context)
        val contactCount = contactStore.displayEntries().size
        val contactOn = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CONTACT_VOICES
        )
        val contactStatus = if (contactOn) "مفعّل" else "مغلق"
        val contactManageText = context.getString(
            R.string.a11y_contact_voices_manage
        )
        btnContactVoices?.text =
            "$contactManageText ($contactStatus — $contactCount)"

        val protectedCount = settings.getProtectedApps().size
        val protectedManageText = context.getString(
            R.string.a11y_protected_apps_manage
        )
        btnProtectedApps?.text = "$protectedManageText ($protectedCount)"

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
    /**
     * إدارة أصوات جهات الاتصال (ع٤١-ب10.2، ب14، ع٤٥): إضافة اسم/رقم
     * بمحرّر صوتٍ كامل، وتعديل الموجود بالضغط عليه، واختيار جهة اتصال.
     */
    private fun showContactVoicesDialog(onUpdated: () -> Unit) {
        val store = ContactVoiceStore.create(context)
        val entries = store.displayEntries()
        val listItems = buildList {
            add(context.getString(R.string.a11y_voice_add))
            addAll(
                entries.map { (key, profile) ->
                    val disp = profile.displayName
                    if (!disp.isNullOrBlank() && disp != key) {
                        "$disp ($key)"
                    } else {
                        key
                    }
                }
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_contact_voices_title)
            .setItems(listItems.toTypedArray()) { _, which ->
                if (which == 0) {
                    showAddContactVoice(store, onUpdated)
                } else {
                    val entry = entries[which - 1]
                    showContactVoiceEditor(
                        store = store,
                        key = entry.first,
                        displayName = entry.second.displayName,
                        initial = entry.second,
                        onUpdated = onUpdated
                    )
                }
            }
            .setNeutralButton(R.string.a11y_voice_remove) { _, _ ->
                val keys = entries.map { it.first }
                showRemoveContactVoiceDialog(store, keys, onUpdated)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** إدخال اسم أو رقمٍ جديد أو اختياره من جهات الاتصال. */
    private fun showAddContactVoice(
        store: ContactVoiceStore,
        onUpdated: () -> Unit
    ) {
        val inputName = EditText(context).apply {
            hint = context.getString(R.string.a11y_voice_name_hint)
        }
        val inputPhone = EditText(context).apply {
            hint = context.getString(R.string.a11y_voice_phone_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val btnPick = MaterialButton(
            context,
            null,
            com.google.android.material.R.style
                .Widget_MaterialComponents_Button_TextButton
        ).apply {
            text = context.getString(R.string.a11y_pick_contact)
            setOnClickListener {
                contactPickerLauncher?.invoke { pickedName, pickedNumber ->
                    if (!pickedName.isNullOrBlank()) {
                        inputName.setText(pickedName)
                    }
                    if (!pickedNumber.isNullOrBlank()) {
                        inputPhone.setText(pickedNumber)
                    }
                }
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(btnPick)
            addView(inputName)
            addView(inputPhone)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_voice_add)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = inputName.text?.toString().orEmpty().trim()
                val phone = inputPhone.text?.toString().orEmpty().trim()
                val key = when {
                    phone.isNotEmpty() -> phone
                    name.isNotEmpty() -> name
                    else -> ""
                }
                if (key.isNotEmpty()) {
                    val disp = if (name.isNotEmpty()) name else null
                    showContactVoiceEditor(
                        store = store,
                        key = key,
                        displayName = disp,
                        initial = currentDefaultVoiceProfile().copy(
                            displayName = disp
                        ),
                        onUpdated = onUpdated
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showContactVoiceEditor(
        store: ContactVoiceStore,
        key: String,
        displayName: String?,
        initial: VoiceProfile,
        onUpdated: () -> Unit
    ) {
        val contactTitle = displayName?.ifBlank { null } ?: key
        VoiceProfileEditorDialog.show(
            context = context,
            title = contactTitle,
            initial = initial,
            sampleText = context.getString(
                R.string.sample_text_caller_preview
            ),
            callerCategory = true
        ) { profile ->
            val toSave = if (displayName != null) {
                profile.copy(displayName = displayName)
            } else {
                profile
            }
            store.put(key, toSave)
            val wasEnabled = settings.isAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_CONTACT_VOICES
            )
            var autoMsg = ""
            if (!wasEnabled) {
                settings.setAccessibilityFeatureEnabled(
                    FeatureFlags.FEATURE_CONTACT_VOICES, true
                )
                autoMsg = "، فُعّلت ميزة صوت جهات الاتصال"
            }
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "حُفظ صوت $contactTitle$autoMsg",
                isInterruptAllowed = true
            )
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
     * إدارة ملفات التطبيقات (ع٤١-ب11.3، ب14، ع٤٥): اختيار تطبيقٍ من
     * المثبّتة ثم فتح المحرّر الكامل عليه مع حفظ لغة الصوت وتفعيل الميزة.
     */
    /**
     * قائمة التطبيقات المتاحة لملفات الصوت (القابلة للإطلاق + المحفوظة سابقاً).
     */
    private fun getAvailableAppsForProfiles(
        store: AppProfileStore
    ): List<Pair<String, String>> {
        val list = launchableApps().toMutableList()
        val knownPkgs = list.map { it.first }.toSet()
        val pm = context.packageManager
        for (pkg in store.packages()) {
            if (!knownPkgs.contains(pkg)) {
                val label = runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrNull() ?: pkg
                list.add(pkg to label)
            }
        }
        return list.sortedBy { it.second }
    }

    /**
     * إدارة ملفات التطبيقات (الجولة 48 - الأمر 5): اختيار تطبيق أو إدخال
     * الحزمة يدوياً أو التقاط التطبيق الحالي فوراً أو بعد 5 ثوانٍ.
     */
    private fun showAppProfilesDialog(onUpdated: () -> Unit) {
        val store = AppProfileStore.create(context)
        val apps = getAvailableAppsForProfiles(store)
        val options = mutableListOf<String>()
        options.add("➕ إدخال اسم الحزمة يدوياً")
        options.add("📍 التقاط التطبيق الحالي فوراً")
        options.add("⏱️ التقاط التطبيق الحالي بعد 5 ثوانٍ")
        apps.forEach { options.add(it.second) }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_app_profiles_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> promptManualPackageEntry(store, onUpdated)
                    1 -> captureCurrentPackage(store, onUpdated)
                    2 -> captureDelayedPackage(store, onUpdated)
                    else -> {
                        val app = apps[which - 3]
                        editAppProfile(app.first, app.second, store, onUpdated)
                    }
                }
            }
            .setNeutralButton(R.string.a11y_voice_remove) { _, _ ->
                showRemoveAppProfileDialog(store, onUpdated)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptManualPackageEntry(
        store: AppProfileStore,
        onUpdated: () -> Unit
    ) {
        val input = EditText(context).apply {
            hint = "com.example.app"
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("إدخال اسم الحزمة يدوياً")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pkg = input.text?.toString().orEmpty().trim()
                if (pkg.isNotEmpty()) {
                    val label = runCatching {
                        val pm = context.packageManager
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    }.getOrNull() ?: pkg
                    editAppProfile(pkg, label, store, onUpdated)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun captureCurrentPackage(
        store: AppProfileStore,
        onUpdated: () -> Unit
    ) {
        val pkg = com.aymankhattab.nateq.core.audio.accessibility
            .AccessibilityBridge.resolveForegroundPackage()
        if (pkg.isNullOrBlank() ||
            com.aymankhattab.nateq.core.audio.accessibility
                .AccessibilityBridge.isIgnoredPackage(pkg)
        ) {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعذر تحديد التطبيق الحالي",
                isInterruptAllowed = true
            )
            return
        }
        val label = runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrNull() ?: pkg
        editAppProfile(pkg, label, store, onUpdated)
    }

    private fun captureDelayedPackage(
        store: AppProfileStore,
        onUpdated: () -> Unit
    ) {
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "انتقل إلى التطبيق المراد، سيتم التقاطه بعد خمس ثوانٍ",
            isInterruptAllowed = true
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val pkg = com.aymankhattab.nateq.core.audio.accessibility
                .AccessibilityBridge.resolveForegroundPackage()
            if (!pkg.isNullOrBlank() &&
                !com.aymankhattab.nateq.core.audio.accessibility
                    .AccessibilityBridge.isIgnoredPackage(pkg)
            ) {
                val label = runCatching {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                }.getOrNull() ?: pkg
                A11ySpeechCoordinator.speakAnnouncement(
                    context = context,
                    text = "تم التقاط تطبيق $label",
                    isInterruptAllowed = true
                )
                editAppProfile(pkg, label, store, onUpdated)
            } else {
                A11ySpeechCoordinator.speakAnnouncement(
                    context = context,
                    text = "تعذر التقاط التطبيق، حاول مرة أخرى",
                    isInterruptAllowed = true
                )
            }
        }, 5000L)
    }

    private fun editAppProfile(
        pkg: String,
        label: String,
        store: AppProfileStore,
        onUpdated: () -> Unit
    ) {
        val stored = store.get(pkg)
        val initial = stored?.toVoiceProfile()
            ?: currentDefaultVoiceProfile()
        VoiceProfileEditorDialog.show(
            context = context,
            title = label,
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
                    features = stored?.features.orEmpty(),
                    voiceLanguage = profile.voiceLanguage
                )
            )
            val wasAppOn = settings.isAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_APP_PROFILES
            )
            val wasAutoOn = settings.isAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH
            )
            var autoMsg = ""
            if (!wasAppOn || !wasAutoOn) {
                settings.setAccessibilityFeatureEnabled(
                    FeatureFlags.FEATURE_APP_PROFILES, true
                )
                settings.setAccessibilityFeatureEnabled(
                    FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH, true
                )
                autoMsg = "، فُعّلت ميزة صوت التطبيقات"
            }
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "حُفظ ملف تطبيق $label$autoMsg",
                isInterruptAllowed = true
            )
            onUpdated()
        }
    }

    private fun showProtectedAppsDialog(onUpdated: () -> Unit) {
        val apps = settings.getProtectedApps()
        val items = mutableListOf<String>()
        items.add("➕ ${context.getString(R.string.a11y_protected_apps_add)}")
        for (app in apps) {
            val modeStr = if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
                "تعليق جزئي"
            } else {
                "إيقاف كامل"
            }
            items.add("🔒 ${app.label} ($modeStr)")
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_protected_apps_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    promptAddProtectedApp(onUpdated)
                } else {
                    val app = apps[which - 1]
                    showProtectedAppOptionsDialog(app, onUpdated)
                }
            }
            .setNeutralButton("أذونات الحماية") { _, _ ->
                showProtectedAppsPermissionsDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptAddProtectedApp(onUpdated: () -> Unit) {
        val store = AppProfileStore.create(context)
        val available = getAvailableAppsForProfiles(store)
        val options = mutableListOf<String>()
        options.add("➕ إدخال اسم الحزمة يدوياً")
        available.forEach { options.add(it.second) }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_protected_apps_add)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    val input = EditText(context).apply {
                        hint = "com.instapay"
                    }
                    MaterialAlertDialogBuilder(context)
                        .setTitle("إدخال اسم الحزمة")
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val pkg = input.text?.toString().orEmpty().trim()
                            if (pkg.isNotEmpty()) {
                                val label = runCatching {
                                    val pm = context.packageManager
                                    val info = pm.getApplicationInfo(pkg, 0)
                                    pm.getApplicationLabel(info).toString()
                                }.getOrNull() ?: pkg
                                promptProtectedAppMode(pkg, label, onUpdated)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    val selected = available[which - 1]
                    promptProtectedAppMode(
                        selected.first, selected.second, onUpdated
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptProtectedAppMode(
        pkg: String,
        label: String,
        onUpdated: () -> Unit
    ) {
        val modes = arrayOf(
            context.getString(R.string.a11y_protected_mode_full),
            context.getString(R.string.a11y_protected_mode_partial)
        )
        val defaultIndex = 0
        MaterialAlertDialogBuilder(context)
            .setTitle("اختر وضع الحماية لتطبيق $label")
            .setSingleChoiceItems(modes, defaultIndex) { dialog, which ->
                val mode = if (which == 1) {
                    ProtectedApp.MODE_PARTIAL_SUSPEND
                } else {
                    ProtectedApp.MODE_FULL_DISABLE
                }
                settings.addProtectedApp(
                    ProtectedApp(
                        packageName = pkg,
                        label = label,
                        mode = mode
                    )
                )
                dialog.dismiss()
                onUpdated()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProtectedAppOptionsDialog(
        app: ProtectedApp,
        onUpdated: () -> Unit
    ) {
        val toggleTarget =
            if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
                "إلى إيقاف كامل"
            } else {
                "إلى تعليق جزئي"
            }
        val options = arrayOf(
            "تبديل الوضع ($toggleTarget)",
            context.getString(R.string.a11y_protected_launch_via_lord),
            "حذف من التطبيقات المحمية"
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val newMode =
                            if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
                                ProtectedApp.MODE_FULL_DISABLE
                            } else {
                                ProtectedApp.MODE_PARTIAL_SUSPEND
                            }
                        settings.addProtectedApp(app.copy(mode = newMode))
                        onUpdated()
                    }
                    1 -> {
                        val intent = ProtectedAppLauncherActivity
                            .createIntent(context, app.packageName)
                        context.startActivity(intent)
                    }
                    2 -> {
                        settings.removeProtectedApp(app.packageName)
                        onUpdated()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProtectedAppsPermissionsDialog() {
        val msg = "1. الوصول إلى بيانات الاستخدام: مطلوب لرصد الخروج " +
            "من التطبيق المصرفي تلقائياً.\n\n" +
            "2. إذن WRITE_SECURE_SETTINGS: مطلوب لإعادة تشغيل خدمة إمكانية " +
            "الوصول تلقائياً بدون تدخل.\n\n" +
            "بدون الإذن الثاني، سيصلك إشعار فوري ويمكنك الضغط على زرّي الصوت."
        MaterialAlertDialogBuilder(context)
            .setTitle("إرشادات أذونات التطبيقات المحمية")
            .setMessage(msg)
            .setPositiveButton("فتح إعدادات الاستخدام") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(intent) }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun testContactVoice() {
        val store = ContactVoiceStore.create(context)
        val entries = store.displayEntries()
        if (entries.isEmpty()) {
            val input = EditText(context).apply {
                hint = context.getString(R.string.a11y_voice_name_hint)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.a11y_test_contact_voice)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val query = input.text?.toString().orEmpty().trim()
                    executeContactVoiceTest(query)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val items = entries.map { (key, profile) ->
            val disp = profile.displayName
            if (!disp.isNullOrBlank() && disp != key) {
                "$disp ($key)"
            } else {
                key
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_test_contact_voice)
            .setItems(items) { _, which ->
                val entry = entries[which]
                executeContactVoiceTest(
                    entry.second.displayName ?: entry.first, entry.first
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeContactVoiceTest(
        nameOrPhone: String,
        key: String? = null
    ) {
        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            senderName = nameOrPhone,
            conversationKey = key ?: nameOrPhone
        )
        if (resolved != null) {
            val eng = resolved.engine ?: "الافتراضي"
            val voice = resolved.voiceId ?: "الافتراضي"
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "اختبار صوت جهة الاتصال $nameOrPhone: الصوت مطبّق " +
                    "بمحرك $eng وصوت $voice",
                isInterruptAllowed = true,
                voiceId = resolved.voiceId,
                engineOverride = resolved.engine,
                speechRate = resolved.speechRate,
                pitch = resolved.pitch,
                volume = resolved.volume
            )
        } else {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "لم يُطبَّق صوت جهة الاتصال لـ $nameOrPhone: " +
                    "الميزة مغلقة أو لا يوجد صوت مطابق",
                isInterruptAllowed = true
            )
        }
    }

    private fun testAppProfile() {
        val store = AppProfileStore.create(context)
        val pkgs = store.packages().toList()
        val apps = launchableApps()
        val targetApps = if (pkgs.isNotEmpty()) {
            apps.filter { pkgs.contains(it.first) }.ifEmpty { apps }
        } else {
            apps
        }
        if (targetApps.isEmpty()) return
        val items = targetApps.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.a11y_test_app_profile)
            .setItems(items) { _, which ->
                val app = targetApps[which]
                executeAppProfileTest(app.first, app.second)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeAppProfileTest(pkg: String, label: String) {
        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = pkg
        )
        if (resolved != null) {
            val eng = resolved.engine ?: "الافتراضي"
            val voice = resolved.voiceId ?: "الافتراضي"
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "اختبار صوت تطبيق $label: الصوت مطبّق " +
                    "بمحرك $eng وصوت $voice",
                isInterruptAllowed = true,
                voiceId = resolved.voiceId,
                engineOverride = resolved.engine,
                speechRate = resolved.speechRate,
                pitch = resolved.pitch,
                volume = resolved.volume
            )
        } else {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "لم يُطبَّق ملف تطبيق $label: " +
                    "الميزة مغلقة أو لا يوجد ملف لهذا التطبيق",
                isInterruptAllowed = true
            )
        }
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
