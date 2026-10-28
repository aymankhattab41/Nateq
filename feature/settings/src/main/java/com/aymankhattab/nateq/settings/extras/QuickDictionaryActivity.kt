package com.aymankhattab.nateq.settings.extras

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.audio.R as AudioR
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.engine.LastSpokenTextsTracker
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * نافذة الإضافة السريعة للقاموس (الجولة 56 - إضافات 5 - أمر 3).
 * تتيح اختيار كلمة من آخر النصوص المنطوقة وتحديد نطقها البديل.
 */
class QuickDictionaryActivity : AppCompatActivity() {

    private lateinit var tilWord: TextInputLayout
    private lateinit var etWord: TextInputEditText
    private lateinit var tilPron: TextInputLayout
    private lateinit var etPron: TextInputEditText
    private lateinit var cgRecent: ChipGroup
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_dictionary)

        tilWord = findViewById(R.id.til_quick_dict_word)
        etWord = findViewById(R.id.et_quick_dict_word)
        tilPron = findViewById(R.id.til_quick_dict_pronunciation)
        etPron = findViewById(R.id.et_quick_dict_pronunciation)
        cgRecent = findViewById(R.id.cg_recent_phrases)
        btnCancel = findViewById(R.id.btn_quick_dict_cancel)
        btnSave = findViewById(R.id.btn_quick_dict_save)

        btnCancel.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveEntry() }

        populateRecentPhrases()
    }

    private fun populateRecentPhrases() {
        val recents = LastSpokenTextsTracker.getRecent().take(5)
        if (recents.isNotEmpty()) {
            etWord.setText(recents.first())
        }

        cgRecent.removeAllViews()
        for (phrase in recents) {
            val chip = Chip(this).apply {
                text = if (phrase.length > 25) {
                    phrase.take(25) + "…"
                } else {
                    phrase
                }
                isCheckable = false
                setOnClickListener {
                    etWord.setText(phrase)
                    etPron.requestFocus()
                }
            }
            cgRecent.addView(chip)
        }
    }

    private fun saveEntry() {
        tilWord.error = null
        tilPron.error = null

        val word = etWord.text?.toString()?.trim().orEmpty()
        val pronunciation = etPron.text?.toString()?.trim().orEmpty()

        if (word.isBlank()) {
            tilWord.error = getString(AudioR.string.quick_dict_word)
            return
        }

        if (pronunciation.isBlank()) {
            tilPron.error = getString(
                AudioR.string.quick_dict_pronunciation
            )
            return
        }

        // يمنع إدخال مفتاح مطابق تماماً لقيمته
        if (word.equals(pronunciation, ignoreCase = true)) {
            tilPron.error = getString(
                AudioR.string.quick_dict_pronunciation
            )
            return
        }

        val dict = PronunciationDictionary.shared(applicationContext)
        val success = dict.addEntry(word, pronunciation)

        if (success) {
            val announcement = getString(AudioR.string.quick_dict_added)
            A11ySpeechCoordinator.speakAnnouncement(
                context = this,
                text = announcement,
                isInterruptAllowed = true
            )
            Toast.makeText(this, announcement, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
