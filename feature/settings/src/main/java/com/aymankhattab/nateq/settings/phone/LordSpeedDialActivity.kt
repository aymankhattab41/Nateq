package com.aymankhattab.nateq.settings.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.core.audio.phone.DialpadKey
import com.aymankhattab.nateq.core.audio.phone.LordDialpadView
import com.aymankhattab.nateq.settings.phone.SimCallHelper
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.SpeedDialManager
import com.aymankhattab.nateq.core.data.SpeedDialManager.SpeedDialEntry
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * شاشة الاتصال السريع الميسّرة (ذاكرة من 1 إلى 10000 موقع).
 * تتيح فحص واستكشاف الموقع، الاتصال عبر SIM 1 أو SIM 2،
 * والإضافة بالبحث، والحذف المباشر.
 */
class LordSpeedDialActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    private var dialpadView: LordDialpadView? = null
    private var tvSlotBadge: TextView? = null
    private var tvSlotInfo: TextView? = null
    private var btnClearDigits: MaterialButton? = null
    private var btnSlotAction: MaterialButton? = null
    private var cardCurrentSlot: View? = null

    private var btnTabDialpad: MaterialButton? = null
    private var btnTabSavedList: MaterialButton? = null
    private var llDialpadSection: LinearLayout? = null
    private var flListSection: FrameLayout? = null

    private var rvEntries: RecyclerView? = null
    private var tvListEmpty: TextView? = null

    private var currentSlotNumber: Int = 1
    private var typedDigits: String = "1"

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lord_speed_dial)

        settingsRepo = SettingsRepository(this)
        initTts()

        findViewById<ImageButton>(R.id.btn_speed_dial_back).setOnClickListener {
            finish()
        }

        bindViews()
        setupTabs()
        setupDialpad()
        setupSlotCard()
        setupRecyclerView()

        updateSlotDisplay(announce = false)
    }

    override fun onResume() {
        super.onResume()
        updateSlotDisplay(announce = false)
        loadSavedList()
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
        if (!text.isNullOrBlank() && isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speed_dial")
        }
    }

    private fun bindViews() {
        tvSlotBadge = findViewById(R.id.tv_speed_dial_slot_badge)
        tvSlotInfo = findViewById(R.id.tv_speed_dial_slot_info)
        btnClearDigits = findViewById(R.id.btn_speed_dial_clear_digits)
        btnSlotAction = findViewById(R.id.btn_speed_dial_slot_action)
        cardCurrentSlot = findViewById(R.id.card_speed_dial_current_slot)

        btnTabDialpad = findViewById(R.id.btn_tab_dialpad)
        btnTabSavedList = findViewById(R.id.btn_tab_saved_list)
        llDialpadSection = findViewById(R.id.ll_speed_dial_pad_section)
        flListSection = findViewById(R.id.fl_speed_dial_list_section)

        rvEntries = findViewById(R.id.rv_speed_dial_entries)
        tvListEmpty = findViewById(R.id.tv_speed_dial_list_empty)
        dialpadView = findViewById(R.id.dialpad_speed_dial)

        btnClearDigits?.setOnClickListener {
            deleteLastDigit()
        }
    }

    private fun setupTabs() {
        btnTabDialpad?.setOnClickListener {
            selectSection(0)
            speak("لوحة الموقع")
        }
        btnTabSavedList?.setOnClickListener {
            selectSection(1)
            speak("قائمة المحفوظات")
        }
    }

    private fun selectSection(index: Int) {
        val isDialpad = index == 0
        llDialpadSection?.visibility =
            if (isDialpad) View.VISIBLE else View.GONE
        flListSection?.visibility = if (isDialpad) View.GONE else View.VISIBLE

        val primary = Color.rgb(46, 125, 50)
        btnTabDialpad?.setBackgroundColor(
            if (isDialpad) primary else Color.TRANSPARENT
        )
        btnTabDialpad?.setTextColor(
            if (isDialpad) Color.WHITE else primary
        )

        btnTabSavedList?.setBackgroundColor(
            if (!isDialpad) primary else Color.TRANSPARENT
        )
        btnTabSavedList?.setTextColor(
            if (!isDialpad) Color.WHITE else primary
        )

        if (!isDialpad) {
            loadSavedList()
        }
    }

    private fun setupDialpad() {
        val dp = dialpadView ?: return
        dp.phoneMode = settingsRepo.getPhoneMode()
        dp.activationMode = settingsRepo.getPhoneActivation()
        dp.soundEnabled = settingsRepo.isPhoneSoundEnabled()
        dp.hapticEnabled = settingsRepo.isPhoneHapticEnabled()

        dp.actionListener = object : LordDialpadView.OnDialpadActionListener {
            override fun onKeySelected(key: DialpadKey) {
                appendDigit(key.digit.toString())
            }

            override fun onKeyHover(key: DialpadKey) {
                speak(key.spokenLabel)
            }
        }
    }

    private fun appendDigit(digit: String) {
        val digitChar = digit.firstOrNull() ?: return
        if (!digitChar.isDigit()) return

        val newDigits = if (typedDigits == "1" && digit != "1") {
            digit
        } else {
            typedDigits + digit
        }

        val slot = newDigits.toIntOrNull() ?: currentSlotNumber
        if (slot in SpeedDialManager.MIN_SLOT..SpeedDialManager.MAX_SLOT) {
            typedDigits = newDigits
            currentSlotNumber = slot
            updateSlotDisplay(announce = true)
        } else {
            speak("الحد الأقصى هو 10000")
        }
    }

    private fun deleteLastDigit() {
        if (typedDigits.length > 1) {
            typedDigits = typedDigits.dropLast(1)
            currentSlotNumber = typedDigits.toIntOrNull() ?: 1
            updateSlotDisplay(announce = true)
        } else {
            typedDigits = "1"
            currentSlotNumber = 1
            updateSlotDisplay(announce = true)
        }
    }

    private fun updateSlotDisplay(announce: Boolean) {
        val entry = SpeedDialManager.getSlot(this, currentSlotNumber)
        tvSlotBadge?.text = getString(
            R.string.speed_dial_slot_title,
            currentSlotNumber
        )

        if (entry != null) {
            val details = "${entry.name} - ${entry.phoneNumber}"
            tvSlotInfo?.text = details
            tvSlotInfo?.setTextColor(Color.rgb(46, 125, 50))
            if (announce) {
                speak("${entry.name} ${entry.phoneNumber}")
            }
        } else {
            tvSlotInfo?.text = getString(R.string.speed_dial_slot_empty)
            tvSlotInfo?.setTextColor(Color.GRAY)
            if (announce) {
                speak(
                    getString(
                        R.string.speed_dial_slot_empty,
                        currentSlotNumber
                    )
                )
            }
        }
    }

    private fun setupSlotCard() {
        val clickListener = View.OnClickListener {
            showSlotOptionsDialog(currentSlotNumber)
        }
        cardCurrentSlot?.setOnClickListener(clickListener)
        btnSlotAction?.setOnClickListener(clickListener)
    }

    private fun showSlotOptionsDialog(slot: Int) {
        val entry = SpeedDialManager.getSlot(this, slot)
        if (entry == null) {
            // خيار وحيد: إضافة للاتصال السريع
            val options = arrayOf(
                getString(R.string.speed_dial_add_action)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    getString(R.string.speed_dial_slot_empty, slot)
                )
                .setItems(options) { _, which ->
                    if (which == 0) {
                        openPhoneToPickContact(slot)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            // اتصال شريحة 1 و 2، إضافة/تعديل، وحذف
            val accounts = SimCallHelper.getCallCapableAccounts(this)
            val sim1Label = SimCallHelper.getSimLabel(this, 0, accounts)
            val sim2Label = SimCallHelper.getSimLabel(this, 1, accounts)

            val options = arrayOf(
                "${getString(R.string.speed_dial_call_sim1)}: $sim1Label",
                "${getString(R.string.speed_dial_call_sim2)}: $sim2Label",
                getString(R.string.speed_dial_add_action),
                getString(R.string.speed_dial_delete_action)
            )

            val title = "${entry.name} - ${entry.phoneNumber} (موقع $slot)"
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> SimCallHelper.makeCall(
                            this,
                            entry.phoneNumber,
                            0
                        )
                        1 -> SimCallHelper.makeCall(
                            this,
                            entry.phoneNumber,
                            1
                        )
                        2 -> openPhoneToPickContact(slot)
                        3 -> deleteSlot(slot)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openPhoneToPickContact(slot: Int) {
        val intent = Intent(this, LordPhoneActivity::class.java).apply {
            putExtra(LordPhoneActivity.EXTRA_SPEED_DIAL_SLOT, slot)
        }
        startActivity(intent)
    }

    private fun deleteSlot(slot: Int) {
        SpeedDialManager.deleteSlot(this, slot)
        val msg = getString(R.string.speed_dial_deleted_toast, slot)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        speak(msg)
        updateSlotDisplay(announce = false)
        loadSavedList()
        selectSection(1)
    }

    private fun setupRecyclerView() {
        val rv = rvEntries ?: return
        rv.layoutManager = LinearLayoutManager(this)
    }

    private fun loadSavedList() {
        val entries = SpeedDialManager.getAllAssignedSlots(this)
        tvListEmpty?.visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
        rvEntries?.adapter = SpeedDialAdapter(entries)
    }

    private inner class SpeedDialAdapter(
        private val list: List<SpeedDialEntry>
    ) : RecyclerView.Adapter<SpeedDialAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvBadge: TextView = v.findViewById(R.id.tv_item_slot_badge)
            val tvName: TextView = v.findViewById(R.id.tv_item_slot_name)
            val tvPhone: TextView = v.findViewById(R.id.tv_item_slot_phone)
            val btnCall: MaterialButton =
                v.findViewById(R.id.btn_item_slot_call)
            val btnDelete: MaterialButton =
                v.findViewById(R.id.btn_item_slot_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(
                R.layout.item_speed_dial_entry,
                parent,
                false
            )
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvBadge.text = item.slot.toString()
            holder.tvName.text = item.name
            holder.tvPhone.text = item.phoneNumber

            holder.itemView.setOnClickListener {
                speak("${item.name} ${item.phoneNumber}")
                showSlotOptionsDialog(item.slot)
            }

            holder.btnCall.setOnClickListener {
                SimCallHelper.showSimChooserDialog(
                    this@LordSpeedDialActivity,
                    item.phoneNumber,
                    item.name
                ) { simSlot ->
                    SimCallHelper.makeCall(
                        this@LordSpeedDialActivity,
                        item.phoneNumber,
                        simSlot
                    )
                }
            }

            holder.btnDelete.setOnClickListener {
                deleteSlot(item.slot)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
