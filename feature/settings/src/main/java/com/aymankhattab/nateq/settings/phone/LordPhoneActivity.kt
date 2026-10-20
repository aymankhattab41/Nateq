package com.aymankhattab.nateq.settings.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.audio.keyboard.LordKeyboardView
import com.aymankhattab.nateq.core.audio.phone.CallRecordItem
import com.aymankhattab.nateq.core.audio.phone.CallRecordManager
import com.aymankhattab.nateq.core.audio.phone.DialpadKey
import com.aymankhattab.nateq.core.audio.phone.LordDialpadView
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.aymankhattab.nateq.settings.phone.SimCallHelper
import com.aymankhattab.nateq.core.data.SpeedDialManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * نشاط هاتف Lord TTS الميسّر لذوي الإعاقة البصرية.
 */
@Suppress("DEPRECATION")
class LordPhoneActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPEED_DIAL_SLOT = "extra_speed_dial_slot"
    }

    private lateinit var settingsRepo: SettingsRepository
    private var speedDialTargetSlot: Int = 0
    private var tvDisplay: TextView? = null
    private var dialpadView: LordDialpadView? = null
    private var llDefaultBanner: View? = null
    private var keyboardContacts: LordKeyboardView? = null
    private var isContactsSymbolsLayer: Boolean = false
    private var btnToggleContactsKeyboard: MaterialButton? = null

    // الحاويات
    private var llTabDialpad: View? = null
    private var llTabCallLog: View? = null
    private var llTabContacts: View? = null
    private var llTabRecordings: View? = null

    // أزرار التنقل السفلي
    private var btnNavDialpad: MaterialButton? = null
    private var btnNavCallLog: MaterialButton? = null
    private var btnNavContacts: MaterialButton? = null
    private var btnNavRecordings: MaterialButton? = null

    // القوائم
    private var rvCallLog: RecyclerView? = null
    private var rvContacts: RecyclerView? = null
    private var rvRecordings: RecyclerView? = null
    private var tvCallLogEmpty: View? = null
    private var tvContactsEmpty: View? = null
    private var tvRecordingsEmpty: View? = null
    private var tvRecordingsPath: TextView? = null
    private var btnOpenRecordingsFolder: MaterialButton? = null

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull() ?: ""
            if (spoken.isNotEmpty()) {
                val etSearch = findViewById<EditText>(R.id.et_contact_search)
                etSearch.setText(spoken)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.READ_CALL_LOG] == true) {
            loadCallLog()
        }
        if (permissions[Manifest.permission.READ_CONTACTS] == true) {
            loadContacts()
        }
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDefaultDialerBanner()
    }

    private val callPlacer = CallPlacer(this) { text ->
        speak(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lord_phone)

        settingsRepo = SettingsRepository.create(this)
        initTts()
        initViews()
        handleIncomingIntent(intent)
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateDefaultDialerBanner()
        applyDialpadSettings()
        applyContactsKeyboardSettings()
        updateDynamicTabsVisibility()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                isTtsReady = true
            }
        }
    }

    private fun speak(text: String?) {
        if (!isTtsReady || text.isNullOrEmpty()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phone_speech")
    }

    private fun initViews() {
        tvDisplay = findViewById(R.id.tv_phone_number_display)
        dialpadView = findViewById(R.id.dialpad_view)
        llDefaultBanner = findViewById(R.id.ll_default_dialer_banner)

        llTabDialpad = findViewById(R.id.ll_tab_dialpad)
        llTabCallLog = findViewById(R.id.ll_tab_call_log)
        llTabContacts = findViewById(R.id.ll_tab_contacts)
        llTabRecordings = findViewById(R.id.ll_tab_recordings)

        btnNavDialpad = findViewById(R.id.btn_nav_dialpad)
        btnNavCallLog = findViewById(R.id.btn_nav_call_log)
        btnNavContacts = findViewById(R.id.btn_nav_contacts)
        btnNavRecordings = findViewById(R.id.btn_nav_recordings)

        rvCallLog = findViewById(R.id.rv_call_log)
        rvContacts = findViewById(R.id.rv_contacts)
        rvRecordings = findViewById(R.id.rv_recordings)

        tvCallLogEmpty = findViewById(R.id.tv_call_log_empty)
        tvContactsEmpty = findViewById(R.id.tv_contacts_empty)
        tvRecordingsEmpty = findViewById(R.id.tv_recordings_empty)
        tvRecordingsPath = findViewById(R.id.tv_recordings_path)
        btnOpenRecordingsFolder = findViewById(R.id.btn_open_recordings_folder)
        btnOpenRecordingsFolder?.setOnClickListener {
            openRecordingsFolder()
        }

        rvCallLog?.layoutManager = LinearLayoutManager(this)
        rvContacts?.layoutManager = LinearLayoutManager(this)
        rvRecordings?.layoutManager = LinearLayoutManager(this)

        val btnSettings =
            findViewById<MaterialButton>(R.id.btn_phone_settings)
        btnSettings.setOnClickListener {
            val i = Intent(this, PhoneSettingsActivity::class.java)
            startActivity(i)
        }

        val btnSetDefault =
            findViewById<MaterialButton>(R.id.btn_banner_set_default)
        btnSetDefault.setOnClickListener {
            requestDefaultDialerRole()
        }

        val btnDelete = findViewById<MaterialButton>(R.id.btn_phone_delete)
        btnDelete.setOnClickListener {
            deleteLastDigit()
        }
        btnDelete.setOnLongClickListener {
            clearAllDigits()
            true
        }

        findViewById<MaterialButton>(R.id.btn_phone_speed_dial)
            ?.setOnClickListener {
                val intent = Intent(this, LordSpeedDialActivity::class.java)
                startActivity(intent)
            }

        val btnCallSim1 =
            findViewById<MaterialButton>(R.id.btn_phone_call_sim1)
        btnCallSim1?.setOnClickListener {
            makeCall(tvDisplay?.text?.toString() ?: "", 0, "dialpad_sim1")
        }

        val btnCallSim2 =
            findViewById<MaterialButton>(R.id.btn_phone_call_sim2)
        btnCallSim2?.setOnClickListener {
            makeCall(tvDisplay?.text?.toString() ?: "", 1, "dialpad_sim2")
        }

        setupDialpad()
        setupBottomNavigation()
        setupContactsSearch()

        speedDialTargetSlot = intent.getIntExtra(EXTRA_SPEED_DIAL_SLOT, 0)
        if (speedDialTargetSlot > 0) {
            selectTab(2)
        }
    }

    private fun setupDialpad() {
        val dp = dialpadView ?: return
        applyDialpadSettings()

        dp.actionListener = object : LordDialpadView.OnDialpadActionListener {
            override fun onKeySelected(key: DialpadKey) {
                val current = tvDisplay?.text?.toString() ?: ""
                val updated = current + key.digit
                tvDisplay?.text = updated
                if (settingsRepo.isPhoneAnnounceAfterLiftEnabled()) {
                    speak(key.spokenLabel)
                }
            }

            override fun onKeyHover(key: DialpadKey) {
                speak(key.spokenLabel)
            }
        }
    }

    private fun applyDialpadSettings() {
        val dp = dialpadView ?: return
        dp.phoneMode = settingsRepo.getPhoneMode()
        dp.activationMode = settingsRepo.getPhoneActivation()
        dp.soundEnabled = settingsRepo.isPhoneSoundEnabled()
        dp.hapticEnabled = settingsRepo.isPhoneHapticEnabled()
    }

    private fun deleteLastDigit() {
        val current = tvDisplay?.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val deletedChar = current.last()
            val remaining = current.dropLast(1)
            tvDisplay?.text = remaining
            speak("تم مسح $deletedChar")
        }
    }

    private fun clearAllDigits() {
        tvDisplay?.text = ""
        speak("تم مسح الرقم بالكامل")
    }

    private fun setupBottomNavigation() {
        btnNavDialpad?.setOnClickListener {
            selectTab(0)
            speak("لوحة الاتصال")
        }
        btnNavCallLog?.setOnClickListener {
            selectTab(1)
            speak("سجل المكالمات")
            loadCallLog()
        }
        btnNavContacts?.setOnClickListener {
            selectTab(2)
            speak("جهات الاتصال")
            loadContacts()
        }
        btnNavRecordings?.setOnClickListener {
            selectTab(3)
            speak("التسجيلات")
            loadRecordings()
        }
    }

    private fun selectTab(index: Int) {
        llTabDialpad?.visibility = if (index == 0) View.VISIBLE else View.GONE
        llTabCallLog?.visibility = if (index == 1) View.VISIBLE else View.GONE
        llTabContacts?.visibility = if (index == 2) View.VISIBLE else View.GONE
        llTabRecordings?.visibility =
            if (index == 3) View.VISIBLE else View.GONE

        if (index == 2) {
            applyContactsKeyboardSettings()
            updateContactsKeyboardLayout()
        }

        val colorPrimary = 0xFF00796B.toInt()
        val colorSecondary = 0xFF9E9E9E.toInt()

        btnNavDialpad?.setTextColor(
            if (index == 0) colorPrimary else colorSecondary
        )
        btnNavCallLog?.setTextColor(
            if (index == 1) colorPrimary else colorSecondary
        )
        btnNavContacts?.setTextColor(
            if (index == 2) colorPrimary else colorSecondary
        )
        btnNavRecordings?.setTextColor(
            if (index == 3) colorPrimary else colorSecondary
        )
    }

    /**
     * إظهار تبويب التسجيلات فقط في النمط اليدوي لتقليل التبويبات.
     */
    private fun updateDynamicTabsVisibility() {
        val mode = settingsRepo.getPhoneRecordMode()
        val isManual =
            mode == SettingsRepository.PHONE_RECORD_MODE_MANUAL &&
                settingsRepo.isPhoneRecordEnabled()

        btnNavRecordings?.visibility = if (isManual) View.VISIBLE else View.GONE
        if (!isManual && llTabRecordings?.visibility == View.VISIBLE) {
            selectTab(0)
        }
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE)
                as? RoleManager
            if (roleManager != null &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return true
            }
        }
        val telecom = getSystemService(Context.TELECOM_SERVICE)
            as? TelecomManager
        return telecom?.defaultDialerPackage == packageName
    }

    private fun updateDefaultDialerBanner() {
        val isDefault = isDefaultDialer()
        llDefaultBanner?.visibility =
            if (isDefault) View.GONE else View.VISIBLE
    }

    private fun requestDefaultDialerRole() {
        if (isDefaultDialer()) {
            updateDefaultDialerBanner()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE)
                as? RoleManager
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                try {
                    val intent =
                        roleManager.createRequestRoleIntent(
                            RoleManager.ROLE_DIALER
                        )
                    defaultDialerLauncher.launch(intent)
                    return
                } catch (e: Exception) {
                    Log.w("LordPhone", "Role request failed", e)
                }
            }
        }

        try {
            val intent = Intent(
                TelecomManager.ACTION_CHANGE_DEFAULT_DIALER
            ).apply {
                putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    packageName
                )
            }
            defaultDialerLauncher.launch(intent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            defaultDialerLauncher.launch(intent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            defaultDialerLauncher.launch(intent)
        } catch (_: Exception) {}
    }

    private fun makeCall(
        number: String,
        simSlot: Int? = null,
        source: String = "phone_activity"
    ) {
        val clean = number.trim()
        if (clean.isEmpty()) {
            speak("الرجاء كتابة رقم الهاتف أولاً")
            return
        }

        if (simSlot != null) {
            callPlacer.placeCall(clean, simSlot, source)
        } else {
            SimCallHelper.showSimChooserDialog(this, clean) { slot ->
                callPlacer.placeCall(clean, slot, "${source}_sim_chooser")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            handleIncomingIntent(it)
            val slot = it.getIntExtra(EXTRA_SPEED_DIAL_SLOT, 0)
            if (slot > 0) {
                speedDialTargetSlot = slot
                selectTab(2)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        val data = intent.data
        if (data != null && data.scheme == "tel") {
            val number = data.schemeSpecificPart ?: ""
            if (number.isNotEmpty()) {
                tvDisplay?.text = number
                selectTab(0)
            }
        }
    }

    private fun setupContactsSearch() {
        val etSearch = findViewById<EditText>(R.id.et_contact_search)
        val btnVoice =
            findViewById<MaterialButton>(R.id.btn_contact_voice_search)
        val btnDelete =
            findViewById<MaterialButton>(R.id.btn_contact_search_delete)
        btnToggleContactsKeyboard =
            findViewById(R.id.btn_toggle_contacts_keyboard)
        keyboardContacts = findViewById(R.id.keyboard_contacts_search)

        // تعطيل لوحة مفاتيح النظام التلقائية لمنع حجب اللوحة الميسرة
        etSearch.showSoftInputOnFocus = false

        btnVoice.setOnClickListener {
            launchVoiceSearch()
        }

        btnDelete?.setOnClickListener {
            deleteContactSearchChar()
        }
        btnDelete?.setOnLongClickListener {
            clearContactSearch()
            true
        }

        btnToggleContactsKeyboard?.setOnClickListener {
            val kb = keyboardContacts ?: return@setOnClickListener
            val isCurrentlyVisible = kb.visibility == View.VISIBLE
            toggleContactsKeyboard(!isCurrentlyVisible)
        }

        setupContactsKeyboard()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}
            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                loadContacts(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupContactsKeyboard() {
        val kb = keyboardContacts ?: return
        applyContactsKeyboardSettings()
        updateContactsKeyboardLayout()

        kb.actionListener = object : LordKeyboardView.OnKeyboardActionListener {
            override fun onKeySelected(key: KeyModel) {
                val etSearch = findViewById<EditText>(R.id.et_contact_search)
                when (key.type) {
                    KeyType.CHARACTER -> {
                        val current = etSearch.text?.toString() ?: ""
                        etSearch.setText(current + key.label)
                        etSearch.setSelection(etSearch.text.length)
                        if (settingsRepo.isKeyboardAnnounceAfterLift()) {
                            speak("تم إدخال ${key.spokenText}")
                        }
                    }
                    KeyType.ACTION_BACKSPACE -> {
                        deleteContactSearchChar()
                    }
                    KeyType.ACTION_SPACE -> {
                        val current = etSearch.text?.toString() ?: ""
                        etSearch.setText("$current ")
                        etSearch.setSelection(etSearch.text.length)
                        speak("مسافة")
                    }
                    KeyType.ACTION_SYMBOLS -> {
                        isContactsSymbolsLayer = !isContactsSymbolsLayer
                        val announcement = if (isContactsSymbolsLayer) {
                            "الأرقام والرموز"
                        } else {
                            "الحروف"
                        }
                        speak(announcement)
                        updateContactsKeyboardLayout()
                    }
                    KeyType.ACTION_VOICE -> {
                        launchVoiceSearch()
                    }
                    KeyType.ACTION_HIDE -> {
                        toggleContactsKeyboard(false)
                    }
                    else -> {}
                }
            }

            override fun onKeyHover(key: KeyModel) {
                val isPhonetic = settingsRepo.isKeyboardPhoneticEnabled()
                val desc = if (isPhonetic && !key.phonetic.isNullOrEmpty()) {
                    key.phonetic
                } else {
                    key.spokenText
                }
                speak(desc)
            }

            override fun onSwipeLeft() {
                deleteContactSearchChar()
            }

            override fun onSwipeRight() {
                val etSearch = findViewById<EditText>(R.id.et_contact_search)
                val current = etSearch.text?.toString() ?: ""
                etSearch.setText("$current ")
                etSearch.setSelection(etSearch.text.length)
                speak("مسافة")
            }

            override fun onSwipeDown() {}
            override fun onSwipeUp() {}
        }
    }

    private fun launchVoiceSearch() {
        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                R.string.phone_voice_search_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyContactsKeyboardSettings() {
        val kb = keyboardContacts ?: return
        kb.keyboardMode = settingsRepo.getKeyboardMode()
        kb.activationMode = settingsRepo.getKeyboardActivation()
        kb.soundEnabled = settingsRepo.isKeyboardSoundEnabled()
        kb.hapticEnabled = settingsRepo.isKeyboardHapticEnabled()
        kb.announceAfterLift = settingsRepo.isKeyboardAnnounceAfterLift()
    }

    private fun updateContactsKeyboardLayout() {
        val baseRows = if (isContactsSymbolsLayer) {
            KeyboardLayoutHelper.getSymbolsRows()
        } else {
            KeyboardLayoutHelper.getArabicRows()
        }
        val rows = if (settingsRepo.isKeyboardAlwaysShowNumberRow() &&
            !isContactsSymbolsLayer
        ) {
            listOf(KeyboardLayoutHelper.getNumberRow()) + baseRows
        } else {
            baseRows
        }
        keyboardContacts?.setKeyRows(rows)
    }

    private fun toggleContactsKeyboard(show: Boolean) {
        val kb = keyboardContacts ?: return
        kb.visibility = if (show) View.VISIBLE else View.GONE
        btnToggleContactsKeyboard?.setText(
            if (show) R.string.phone_contacts_keyboard_hide
            else R.string.phone_contacts_keyboard_show
        )
        speak(
            getString(
                if (show) R.string.phone_contacts_keyboard_shown
                else R.string.phone_contacts_keyboard_hidden
            )
        )
    }

    private fun deleteContactSearchChar() {
        val etSearch = findViewById<EditText>(R.id.et_contact_search)
        val current = etSearch.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val deleted = current.last()
            val remaining = current.dropLast(1)
            etSearch.setText(remaining)
            etSearch.setSelection(remaining.length)
            speak("تم مسح $deleted")
        }
    }

    private fun clearContactSearch() {
        val etSearch = findViewById<EditText>(R.id.et_contact_search)
        etSearch.setText("")
        speak("تم مسح البحث بالكامل")
    }

    private fun loadCallLog() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            tvCallLogEmpty?.visibility = View.VISIBLE
            return
        }

        val items = mutableListOf<CallLogEntry>()
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 50"
            )
            cursor?.let { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                while (c.moveToNext()) {
                    val number = c.getString(numIdx) ?: ""
                    val name = c.getString(nameIdx) ?: ""
                    val type = c.getInt(typeIdx)
                    val date = c.getLong(dateIdx)
                    items.add(CallLogEntry(number, name, type, date))
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        tvCallLogEmpty?.visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        rvCallLog?.adapter = CallLogAdapter(items)
    }

    private fun loadContacts(query: String = "") {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            tvContactsEmpty?.visibility = View.VISIBLE
            return
        }

        val items = mutableListOf<ContactEntry>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var cursor: Cursor? = null
        try {
            val selection = if (query.isNotEmpty()) {
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            } else null
            val selectionArgs = if (query.isNotEmpty()) {
                arrayOf("%$query%")
            } else null

            val sortOrder =
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} " +
                    "ASC LIMIT 100"
            cursor = contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            cursor?.let { c ->
                val nameIdx =
                    c.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                val numIdx =
                    c.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: ""
                    val num = c.getString(numIdx) ?: ""
                    items.add(ContactEntry(name, num))
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        tvContactsEmpty?.visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        rvContacts?.adapter = ContactsAdapter(items)
    }

    private fun loadRecordings() {
        val recordings = CallRecordManager.getRecordings(this)
        tvRecordingsEmpty?.visibility =
            if (recordings.isEmpty()) View.VISIBLE else View.GONE
        val folderName = CallRecordManager.getRecordingFolderDisplayName()
        tvRecordingsPath?.text = getString(
            R.string.phone_record_folder_label,
            folderName
        )
        rvRecordings?.adapter = RecordingsAdapter(recordings)
    }

    private fun openRecordingsFolder() {
        val folderUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary:Recordings%2FLord%20TTS"
            )
        } else {
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary:Music%2FLord%20TTS"
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                startActivity(
                    Intent.createChooser(
                        fallback,
                        getString(R.string.phone_open_recordings_folder)
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private var originalMusicVolume: Int? = null

    private fun playRecordingAtMaxVolume(file: File) {
        val item = CallRecordItem(
            id = "",
            phoneNumber = "",
            callerName = "",
            timestamp = file.lastModified(),
            durationSeconds = 1L,
            filePath = file.absolutePath,
            fileSize = file.length()
        )
        playRecording(item)
    }

    private fun playRecording(item: CallRecordItem) {
        if (item.isSilent) {
            val msg = getString(R.string.phone_record_silent_warning)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            speak(msg)
            return
        }

        val targetUri: Uri = if (!item.uriString.isNullOrEmpty()) {
            Uri.parse(item.uriString)
        } else {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(
                    this,
                    R.string.phone_recording_file_missing,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            Uri.fromFile(file)
        }

        val audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol =
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (currentVol < maxVol * 0.3) {
            originalMusicVolume = currentVol
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (maxVol * 0.6).toInt(),
                0
            )
        }

        fun restoreVolume() {
            originalMusicVolume?.let { prev ->
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    prev,
                    0
                )
                originalMusicVolume = null
            }
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@LordPhoneActivity, targetUri)
                setOnPreparedListener {
                    start()
                    speak(
                        getString(
                            R.string.phone_playing_recording_announcement
                        )
                    )
                }
                setOnCompletionListener {
                    restoreVolume()
                }
                setOnErrorListener { _, what, extra ->
                    restoreVolume()
                    val reason = "$what / $extra"
                    val msg = getString(
                        R.string.phone_recording_play_failed_reason,
                        reason
                    )
                    Toast.makeText(
                        this@LordPhoneActivity,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()
                    speak(msg)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            restoreVolume()
            val msg = getString(
                R.string.phone_recording_play_failed_reason,
                e.message ?: "error"
            )
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            speak(msg)
        }
    }

    private fun shareRecording(item: CallRecordItem) {
        val uri: Uri? = if (!item.uriString.isNullOrEmpty()) {
            Uri.parse(item.uriString)
        } else {
            val file = File(item.filePath)
            if (!file.exists()) return
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
        }
        if (uri == null) return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(R.string.phone_share_chooser_title)
            )
        )
    }

    // نماذج البيانات ومحولات RecyclerView
    data class CallLogEntry(
        val number: String,
        val name: String,
        val type: Int,
        val date: Long
    )

    data class ContactEntry(val name: String, val number: String)

    private inner class CallLogAdapter(
        private val list: List<CallLogEntry>
    ) : RecyclerView.Adapter<CallLogAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_call_log_name)
            val tvDetails: TextView = v.findViewById(R.id.tv_call_log_details)
            val btnCall: MaterialButton = v.findViewById(R.id.btn_call_log_call)
            val btnPlayRec: MaterialButton =
                v.findViewById(R.id.btn_call_log_play_rec)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(
                R.layout.item_call_log,
                parent,
                false
            )
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val displayName =
                if (item.name.isNotEmpty()) item.name else item.number
            val typeStr = when (item.type) {
                CallLog.Calls.INCOMING_TYPE -> "مكالمة واردة"
                CallLog.Calls.OUTGOING_TYPE -> "مكالمة صادرة"
                CallLog.Calls.MISSED_TYPE -> "مكالمة فائتة"
                else -> "مكالمة"
            }
            val dateStr = SimpleDateFormat(
                "d MMM - h:mm a",
                Locale.getDefault()
            ).format(Date(item.date))

            holder.tvName.text = displayName
            holder.tvDetails.text = "$typeStr • $dateStr"

            holder.btnCall.setOnClickListener {
                makeCall(item.number, null, "call_log_item")
            }

            // فحص وجود تسجيل صوتي لهذه المكالمة
            val recs = CallRecordManager.getRecordingsForNumber(
                this@LordPhoneActivity,
                item.number
            )
            if (recs.isNotEmpty()) {
                holder.btnPlayRec.visibility = View.VISIBLE
                holder.btnPlayRec.setOnClickListener {
                    playRecording(recs.first())
                }
            } else {
                holder.btnPlayRec.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = list.size
    }

    private inner class ContactsAdapter(
        private val list: List<ContactEntry>
    ) : RecyclerView.Adapter<ContactsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_call_log_name)
            val tvDetails: TextView = v.findViewById(R.id.tv_call_log_details)
            val btnCall: MaterialButton = v.findViewById(R.id.btn_call_log_call)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(
                R.layout.item_call_log,
                parent,
                false
            )
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            holder.tvDetails.text = item.number

            val clickListener = View.OnClickListener {
                if (speedDialTargetSlot > 0) {
                    assignContactToSpeedDial(item)
                } else {
                    makeCall(item.number, null, "contacts_item")
                }
            }

            holder.btnCall.setOnClickListener(clickListener)
            holder.itemView.setOnClickListener(clickListener)
        }

        override fun getItemCount(): Int = list.size
    }

    private fun assignContactToSpeedDial(item: ContactEntry) {
        val slot = speedDialTargetSlot
        val numbers = getNumbersForContact(item.name)
        if (numbers.size <= 1) {
            val chosenNumber =
                if (numbers.isNotEmpty()) numbers[0] else item.number
            finalizeSpeedDialAssignment(slot, item.name, chosenNumber)
        } else {
            val options = numbers.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    getString(R.string.speed_dial_select_number_title, slot)
                )
                .setItems(options) { _, which ->
                    finalizeSpeedDialAssignment(
                        slot,
                        item.name,
                        numbers[which]
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun finalizeSpeedDialAssignment(
        slot: Int,
        name: String,
        number: String
    ) {
        SpeedDialManager.saveSlot(this, slot, name, number)
        val msg = getString(R.string.speed_dial_added_toast, name, slot)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        speak(msg)
        speedDialTargetSlot = 0
        finish()
    }

    private fun getNumbersForContact(name: String): List<String> {
        val numbers = mutableListOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(name)
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.let { c ->
                val numIdx = c.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (c.moveToNext()) {
                    val num = c.getString(numIdx) ?: continue
                    if (num.isNotBlank() && !numbers.contains(num)) {
                        numbers.add(num)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return numbers
    }

    private inner class RecordingsAdapter(
        private val list: List<CallRecordItem>
    ) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_record_title)
            val tvDetails: TextView = v.findViewById(R.id.tv_record_details)
            val btnPlay: MaterialButton = v.findViewById(R.id.btn_record_play)
            val btnShare: MaterialButton = v.findViewById(R.id.btn_record_share)
            val btnDelete: MaterialButton =
                v.findViewById(R.id.btn_record_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(
                R.layout.item_call_record,
                parent,
                false
            )
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val title = if (item.callerName.isNotEmpty()) {
                "${item.callerName} (${item.phoneNumber})"
            } else {
                item.phoneNumber
            }
            val dateStr = SimpleDateFormat(
                "d MMM yyyy - h:mm a",
                Locale.getDefault()
            ).format(Date(item.timestamp))
            val durMin = item.durationSeconds / 60
            val durSec = item.durationSeconds % 60
            val sizeKb = item.fileSize / 1024

            val durStr = String.format("%02d:%02d", durMin, durSec)
            holder.tvTitle.text = title
            holder.tvDetails.text = "$dateStr • $durStr • $sizeKb KB"

            holder.btnPlay.setOnClickListener {
                playRecording(item)
            }

            holder.btnShare.setOnClickListener {
                shareRecording(item)
            }

            holder.btnDelete.setOnClickListener {
                CallRecordManager.deleteRecording(
                    this@LordPhoneActivity,
                    item.id
                )
                speak("تم حذف التسجيل")
                loadRecordings()
            }
        }

        override fun getItemCount(): Int = list.size
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
