package com.aymankhattab.nateq.settings.extras

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.ScreenSearch
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * نافذة البحث النصي داخل الشاشة الحالية (ع٥٦-أمر 2).
 */
class ScreenSearchActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private lateinit var ibMic: ImageButton
    private lateinit var btnGo: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                etInput.setText(spoken)
                executeSearch(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_search)

        etInput = findViewById(R.id.et_search_input)
        ibMic = findViewById(R.id.ib_search_mic)
        btnGo = findViewById(R.id.btn_search_go)
        btnCancel = findViewById(R.id.btn_search_cancel)

        btnCancel.setOnClickListener { finish() }

        btnGo.setOnClickListener {
            val query = etInput.text.toString().trim()
            if (query.isNotBlank()) {
                executeSearch(query)
            }
        }

        ibMic.setOnClickListener {
            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault().language
                )
            }
            try {
                speechLauncher.launch(intent)
            } catch (_: Exception) {
                // المحرك غير متوفر على الجهاز
            }
        }

        etInput.setOnEditorActionListener { _, _, _ ->
            val query = etInput.text.toString().trim()
            if (query.isNotBlank()) {
                executeSearch(query)
                true
            } else {
                false
            }
        }
    }

    private fun executeSearch(query: String) {
        val root = AccessibilityBridge.activeService()?.rootInActiveWindow
        ScreenSearch.search(this, root, query)
        finish()
    }
}
