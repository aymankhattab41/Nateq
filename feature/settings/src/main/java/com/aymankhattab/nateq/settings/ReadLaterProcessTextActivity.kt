package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R

/**
 * نشاط مستقل لاستقبال نصوص ACTION_PROCESS_TEXT المحددة في التطبيقات الأخرى.
 * يحفظ النص في مخزن «اقرأ لاحقاً»، ولا يعدل النص في التطبيق المصدر.
 */
class ReadLaterProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleProcessTextIntent(intent)
        finish()
    }

    private fun handleProcessTextIntent(intent: Intent?) {
        if (intent == null) return
        val raw = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        val text = raw?.toString()?.trim().orEmpty()

        val repo = SettingsRepository.create(this)
        val result = repo.saveReadLaterText(text)

        val msgRes = when (result) {
            SettingsRepository.ReadLaterSaveResult.SAVED ->
                R.string.read_later_saved_toast
            SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED ->
                R.string.read_later_saved_truncated_toast
            SettingsRepository.ReadLaterSaveResult.DUPLICATE ->
                R.string.read_later_duplicate_toast
            SettingsRepository.ReadLaterSaveResult.EMPTY ->
                R.string.read_later_empty_toast
            SettingsRepository.ReadLaterSaveResult.FAILED ->
                R.string.read_later_failed_toast
        }
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()

        // احترام EXTRA_PROCESS_TEXT_READONLY وعدم تعديل النص الأصلي:
        // لا نمرر EXTRA_PROCESS_TEXT بديلاً في النتيجة، بل نغلق بنجاح.
        setResult(Activity.RESULT_OK)
    }
}
