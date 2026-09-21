package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aymankhattab.nateq.core.data.SettingsRepository

/** بيانات محرك TTS معروض في قوائم الاختيار. */
internal data class EngineInfo(val packageName: String, val label: String)

/**
 * ضابط قسم المحرك وإعدادات التحويل التلقائي عبر كل اللغات.
 *
 * عُزل من [VoiceSelectionFragment] ليقلّص حجم الأخير: يبقى هنا اختيار
 * محرك النطق في صندوقه (مع المحركات النظامية وغير المُصدَّرة عبر
 * TTS_SERVICE intent وMATCH_ALL)، وفتح قائمة اللغات المكتشفة فعلياً عبر
 * كل محركات TTS المثبتة — صف لكل لغة (محرك ← صوت + نبرة/سرعة/سعة) —
 * ومعاينة النطق. بدل نظام «اللغتين» الثابت (1/2) صار الدعم لكل لغة مُكتشفة.
 */
internal class EngineSectionController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val engines: MutableList<EngineInfo>,
    private val preview: VoicePreviewHelper
) {

    /** يربطه المضيف بعد إنشائه ليُعيد بناء أسطر حالة الأقسام عند أي تغيير. */
    var onStatusChanged: () -> Unit = {}

    // ===== صندوق المحركات (اكتشاف فقط بلا اختيار عام) =====
    /**
     * يكتشف محركات TTS المثبتة لإظهار حالة القسم ورسالة/زر التثبيت عند غياب
     * أي محرك. لا يوجد «محرك افتراضي» عام: محرك كل لغة وكل فئة يُحسم
     * في [EngineRegistry] وقت النطق فقط.
     */
    fun setupEngineDiscovery() {
        engines.clear()
        engines.addAll(discoverEngines())
        bindNoEnginesUi()
    }

    /** عند غياب أي محرك TTS إطلاقاً: يُظهر رسالة توجيهية
     *  + زر تثبيت من المتجر. */
    private fun bindNoEnginesUi() {
        val view = fragment.view ?: return
        val message = view.findViewById<TextView>(R.id.tv_no_engines_message)
        val btn: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_install_engine)
        val noEngines = engines.isEmpty()
        message.visibility = if (noEngines) View.VISIBLE else View.GONE
        btn.visibility = if (noEngines) View.VISIBLE else View.GONE
        if (noEngines) {
            btn.setOnClickListener { openEngineInPlayStore() }
        }
    }

    /** يفتح صفحة Google TTS على متجر التطبيقات،
     *  مع مسار احتياطي عبر المتصفح. */
    private fun openEngineInPlayStore() {
        val ctx = fragment.requireContext()
        // market:// يفتح الـ Play Store مباشرة؛ وإن لم يوجد
        // معالج نتراجع للرابط عبر الويب.
        val playStoreUri =
            Uri.parse("market://details?id=com.google.android.tts")
        val opened = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, playStoreUri))
        }.isSuccess
        if (!opened) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://play.google.com/store/apps/" +
                                "details?id=com.google.android.tts"
                        )
                    )
                )
            }
        }
    }

    // ===== التحويل التلقائي عبر كل اللغات =====
    private var autoConvertCheckbox:
        com.google.android.material.checkbox.MaterialCheckBox? = null
    private var autoConvertButton:
        com.google.android.material.button.MaterialButton? = null

    /** يعيد تزامن واجهة التحويل مع الإعداد الحالي. عند تفعيل المستخدم محركاً
     *  من حوار اللغات يُقلب التفعيلُ مفتاح «التحويل التلقائي» تلقائياً —
     *  بلا هذه الدالة كان المفتاح الرئيسي يبقى ظاهرياً «غير مفعّل» رغم
     *  تفعيله الفعلي حتى إعادة فتح الشاشة (حالة «حفظت المحرك ولم يعمل»). */
    private fun refreshAutoConvertUi() {
        val enabled = runCatching { settings.isAutoConvertEnabled() }
            .getOrDefault(false)
        autoConvertCheckbox?.isChecked = enabled
        autoConvertButton?.isEnabled = enabled
    }

    fun setupAutoConvertUI(view: View) {
        val chk: com.google.android.material.checkbox.MaterialCheckBox =
            view.findViewById(R.id.checkbox_auto_convert)
        val btn: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_convert_languages)
        autoConvertCheckbox = chk
        autoConvertButton = btn
        refreshAutoConvertUi()
        chk.setOnClickListener { v ->
            val enabled =
                (v as com.google.android.material.checkbox.MaterialCheckBox)
                    .isChecked
            runCatching { settings.setAutoConvertEnabled(enabled) }
            refreshAutoConvertUi()
            onStatusChanged()
        }

        btn.setOnClickListener { showConvertLanguagesDialog() }

        setupLanguageInstallHint(view)
    }

    /**
     * يربط التوضيح الاختياري (نص فقط، غير افتراضي) عن سبب غياب بعض اللغات:
     * بياناتها الصوتية غير مثبتة على الجهاز. مخفي افتراضياً حتى يفعّله
     * المستخدم صراحةً عبر شريط الاختيار.
     */
    private fun setupLanguageInstallHint(view: View) {
        val chk: com.google.android.material.checkbox.MaterialCheckBox =
            view.findViewById(R.id.checkbox_language_install_hint)
        val tv = view.findViewById<TextView>(R.id.tv_language_install_hint)

        chk.isChecked = runCatching { settings.isLanguageInstallHintEnabled() }
            .getOrDefault(false)
        tv.visibility = if (chk.isChecked) View.VISIBLE else View.GONE
        chk.setOnClickListener { v ->
            val enabled =
                (v as com.google.android.material.checkbox.MaterialCheckBox)
                    .isChecked
            runCatching { settings.setLanguageInstallHintEnabled(enabled) }
            tv.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    /** قائمة كل المحركات المتاحة عبر INTENT_ACTION_TTS_SERVICE (MATCH_ALL) */
    private fun discoverEngines(): List<EngineInfo> {
        val discovered = EnginePicker.installedEngines(
            fragment.requireContext()
        )
        // سجل تشخيصي لفوز شكاوى «لا يظهر محركي» على أجهزة Xiaomi/Huawei/Oppo
        // التي قد تُقيّد package visibility رغم <queries>
        // الصحيحة في الـ Manifest.
        Log.i(
            "NATEQ_ENGINE_DISCOVERY",
            "installedEngines count=${discovered.size} " +
                "packages=[" +
                "${discovered.joinToString(", ") { it.packageName }}]"
        )
        return discovered.map { EngineInfo(it.packageName, it.label) }
    }

    /**
     * يفتح قائمة كل اللغات المكتشفة فعلياً عبر كل محركات TTS المثبتة، صفاً
     * لكل لغة — من أصوات getVoices فقط، بلا لغات افتراضية أو نظرية. أي لغة
     * تُعلن بياناتها غير مثبتة (LANG_MISSING_DATA) تُحجب من القائمة تماماً.
     * الاكتشاف خلفي بدون تجميد: يُعرض مؤشر تحميل ثم تُبني الصفوف فور
     * جاهزية النتائج.
     */
    fun showConvertLanguagesDialog() {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(
            R.layout.dialog_convert_languages, null
        )
        val tvHint =
            dialogView.findViewById<TextView>(R.id.tv_convert_languages_hint)
        val rv =
            dialogView.findViewById<RecyclerView>(R.id.rv_convert_languages)

        tvHint.text = fragment.getString(R.string.convert_languages_loading)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(
                fragment.getString(R.string.convert_languages_dialog_title)
            )
            .setView(dialogView)
            .setPositiveButton(fragment.getString(R.string.reset_cancel), null)
            .create()

        fragment.lifecycleScope.launch(AppDispatchers.io) {
            val discovered = runCatching {
                VoiceCatalog.discoverAllLanguagesAcrossEngines(
                    ctx.applicationContext
                )
            }.getOrDefault(emptyMap())
            withContext(AppDispatchers.main) {
                if (!fragment.isAdded || !dialog.isShowing) return@withContext
                val rows = buildLanguageRows(discovered)
                val adapter = LanguageConvertAdapter(
                    ctx,
                    settings,
                    rows
                ) { enginePkg, voiceName, volume, pitch, rate ->
                    playbackPreview(enginePkg, voiceName, volume, pitch, rate)
                }
                adapter.onRowSaved = {
                    // التفعيل التلقائي من الحوار (اختيار محرك/صوت لحفظ) قد
                    // يقلب المفتاح الرئيسي — تُزامَن الصناديق فوراً.
                    refreshAutoConvertUi()
                    onStatusChanged()
                }
                rv.layoutManager = LinearLayoutManager(ctx)
                rv.adapter = adapter
                tvHint.text =
                    fragment.getString(R.string.convert_languages_hint)
            }
        }

        dialog.also { fragment.trackDialog(it) }.show()
    }

    /**
     * يُبني قائمة صفوف اللغات من خريطة الاكتشاف القصوى: العربية والإنجليزية
     * مضمونتان دائماً في المقدمة (حتى إن لم تُكتشفا من أي محرك)،
     * ثم بقية اللغات
     * المكتشفة فعلياً عبر كل المحركات المثبتة مرتّبة أبجدياً — لا حصر ثنائياً
     * بأي لغة. المحركات والأصوات تبقى مستقراة من الجهاز وليست نظرية.
     */
    private fun buildLanguageRows(
        discovered: Map<String, List<EngineWithVoices>>
    ): List<LanguageRow> = buildAllLanguageRows(discovered)

    /** يُشغّل تكليفاً تجريبياً عبر [VoicePreviewHelper] المشترك بأية القيم
     *  المختارة دون حفظ. بند 4.2 (إغلاق أي معاينة جارية والإغلاق عند
     *  مغادرة الشاشة) محفوظ داخل المساعد — لا يُفقد عند الاستخراج. */
    private fun playbackPreview(
        enginePkg: String,
        voiceName: String,
        volume: Float,
        pitch: Float,
        rate: Float
    ) {
        // نعرض عينة بنفس لغة الصوت: عربي إن كان الصوت
        // عربياً وإلا إنجليزي.
        // (سبق: كان النص التجريبي إنجليزياً دائماً فبدا
        // للمستخدم أن الصوت إنجليزي.)
        val isArabic = voiceName.lowercase().contains("ar") ||
            voiceName.lowercase().contains("arab")
        val sampleText = if (isArabic) {
            fragment.getString(R.string.sample_text_preview_ar)
        } else {
            fragment.getString(R.string.sample_text_default_en)
        }
        // نُعلن بداية ونهاية المعاينة لقارئ الشاشة (بند الأوامر 4) عبر
        // previewSpeech، وهو يشغّلها على المساعد المشترك نفسه.
        fragment.previewSpeech(
            PreviewParams(
                enginePkg = enginePkg,
                voiceName = voiceName,
                languageTag = if (isArabic) {
                    LanguageCode.AR.tag
                } else {
                    LanguageCode.EN.tag
                },
                speechRate = rate,
                pitch = pitch,
                volume = volume,
                sampleText = sampleText
            )
        )
    }

    /** يصفّر كل المراجع (بند 4.1 + 4.2): تحرير واجهات التحويل التلقائي —
     *  يُستدعى من onDestroyView. المعاينة الجارية يغلقها مضيف الفصيل عبر
     *  [VoicePreviewHelper.release]. */
    fun cleanup() {
        autoConvertCheckbox = null
        autoConvertButton = null
    }
}

/** بناء صفوف لغات التحويل من الناتج الاكتشافي الكامل: «ar» و«en» مضمونتان في
 *  المقدمة دائماً (حتى لو لم تظهرا في الاكتشاف)، ثم بقية اللغات المرتّبة
 *  أبجدياً — بلا أي حصر ثنائيّ في اللغتين
 *  (بند 17.2: إتاحة كل اللغات المكتشفة). */
internal fun buildAllLanguageRows(
    discovered: Map<String, List<EngineWithVoices>>
): List<LanguageRow> {
    val tags = LinkedHashSet<String>()
    tags.add(LanguageCode.AR.tag)
    tags.add(LanguageCode.EN.tag)
    discovered.keys.sorted().forEach { tags.add(it) }
    return tags.map { tag ->
        LanguageRow(
            languageTag = tag,
            displayName = Locale.forLanguageTag(tag).displayName,
            engines = discovered[tag].orEmpty()
        )
    }
}

/**
 * محلّل صفوف الـ Spinner الأساسي (مشترك بين كل أقسام الشاشة).
 */
internal fun simpleAdapter(
    context: Context,
    items: List<String>
): ArrayAdapter<String> {
    return ArrayAdapter(
        context,
        android.R.layout.simple_spinner_item,
        items
    ).also {
        it.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )
    }
}
