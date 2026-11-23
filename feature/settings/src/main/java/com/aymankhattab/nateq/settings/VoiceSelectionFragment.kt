package com.aymankhattab.nateq.settings

import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.app.DownloadManager
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.aymankhattab.nateq.core.audio.engine.VoiceReadinessCoordinator
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.UpdateChecker
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.engine.NumberSpeech
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.audio.engine.DictTestProvider
import android.net.Uri
import android.widget.LinearLayout
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.DiscoveryResult
import com.aymankhattab.nateq.core.audio.engine.UnavailableEngine
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceImporter
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.VocalizerDownloader
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.engine.TtsDiagnosticsProvider
import com.aymankhattab.nateq.core.audio.engine.SpeechFallbackTracker
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NetworkMetering
import com.aymankhattab.nateq.util.VoiceIdContract
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.EnginePicker.InstalledEngine
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.settings.OemVendor
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.util.ToastDebouncer
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation

/**
 * Ø´Ø§Ø´Ø© Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ© - ØªØªØ¶Ù…Ù†:
 * 1. Ø§Ø®ØªÙŠØ§Ø± Ø§Ù„Ù…Ø­Ø±Ùƒ Ø«Ù… Ø§Ù„Ù„ØºØ© Ø«Ù… Ø§Ù„ØµÙˆØª (Ù‡Ø±Ù…ÙŠØ©)
 * 2. Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø§Ù„ÙØ¦Ø§Øª (ØµÙˆØªØŒ Ø³Ø±Ø¹Ø©ØŒ Ù†Ø¨Ø±Ø©ØŒ Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª)
 * 3. Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„ÙˆÙ‚Øª
 * 4. Ù‚Ø§Ù…ÙˆØ³ Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„Ø´Ø®ØµÙŠ
 */
@AndroidEntryPoint
class VoiceSelectionFragment : Fragment(R.layout.fragment_voice_selection) {

    companion object {
        // Ø±Ø§Ø¨Ø· ØªÙˆØ§ØµÙ„ Ø§Ù„Ù…Ø·ÙˆÙ‘Ø± Ø§Ù„Ø±Ø³Ù…ÙŠ â€” ÙŠÙÙØªØ­ Ø®Ø§Ø±Ø¬ÙŠØ§Ù‹ ÙÙŠ Ø§Ù„Ù…ØªØµÙØ­ ÙÙ„Ø§ ÙŠØ­ØªØ§Ø¬
        // Ø£ÙŠ Ø¥Ø°Ù†.
        // Ø§Ù„Ù…Ø¹Ø±Ù‘Ù Ø§Ù„Ø±Ø³Ù…ÙŠ Ù„Ø¨ÙˆØª Ø§Ù„Ø¯Ø¹Ù…: @LordTTSBot (Ø£Ù†Ø´Ø¦ Ø¹Ø¨Ø± @BotFather).
        const val DEVELOPER_SUPPORT_URL = "https://t.me/LordTTSBot"

        /**
         * Ø³Ù‚ÙÙ Ø£Ø³Ø·Ø± Â«Ø¨ØµÙ…Ø© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Â» ÙÙŠ Ø±Ø£Ø³ Ø§Ù„ØªÙ‚Ø±ÙŠØ± â€” ÙŠØ­Ù…ÙŠ Ø§Ù„Ù‚Ø§Ø±Ø¦ Ù…Ù†
         ØªÙ‚Ø±ÙŠØ±Ù Ø¶Ø®Ù…Ù ÙˆÙŠÙƒÙÙŠ ÙƒÙ„ÙŽÙ‘ Ù…Ø§ ÙŠÙ‡Ù…Ù‘ Ø§Ù„ØªØ´Ø®ÙŠØµ.
         */
        internal const val MAX_DIAGNOSTIC_LINES = 800

        /** Ø§Ø³Ù… Ù…Ù„Ù ØªØµØ¯ÙŠØ± Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ (Ø¨Ù‚Ø±Ø§Ø± Ø§Ù„Ù…Ø¯ÙŠØ±: `LOR_dictionary` Ù„Ø§
         *  `nateq_dictionary`) â€” Ù…Ø¹ Ø§Ù…ØªØ¯Ø§Ø¯ JSON Ø§Ù„Ø°ÙŠ ÙŠÙØ±Ø¶Ù‡ Ù†ÙˆØ¹Ù
         *  `CreateDocument`ØŒ ÙˆÙ‚Ø¯ ÙŠØ¶ÙŠÙ Ø§Ù„Ù†Ø¸Ø§Ù… Ø±Ù‚Ù…Ø§Ù‹ Ø¹Ù†Ø¯ Ø§Ù„ØªØµØ¯ÙŠØ± Ù…Ø±ØªÙŠÙ†. */
        private const val DICT_EXPORT_FILE_NAME = "LOR_dictionary.json"
    }

    // Ø·Ø¨Ù‚Ø© Ø§Ù„Ø­Ø§Ù„Ø© Ø§Ù„Ù…Ø­Ù‚ÙˆÙ†Ø© Ø¹Ø¨Ø± Hilt (ØªØ­ÙˆÙŠ Ù…ØµØ¯Ø±ÙŽÙŠ Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª ÙˆØ§Ù„Ù‚Ø§Ù…ÙˆØ³).
    internal val vm: SettingsViewModel by viewModels()

    internal val settings: SettingsRepository
        get() = vm.settings

    internal val pronunciationDict: PronunciationDictionary
        get() = vm.pronunciationDict

    internal lateinit var categoryVoicePanel: CategoryVoicePanelController
    internal lateinit var rvPronunciationDict: RecyclerView

    /** Ù…Ø­Ø±ÙƒØ§Øª TTS Ø§Ù„Ù…Ø«Ø¨ØªØ© (Ø§ÙƒØªØ´Ø§Ù ÙÙ‚Ø· Ø¨Ù„Ø§ Ø§Ø®ØªÙŠØ§Ø± Ø¹Ø§Ù… â€” Ù„Ø§ Ù…Ø­Ø±Ùƒ Ø§ÙØªØ±Ø§Ø¶ÙŠ). */
    private val engines = mutableListOf<EngineInfo>()
    internal lateinit var engineSection: EngineSectionController

    /** Ù…Ø¹Ø§ÙŠÙ†Ø© Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„Ù…Ø´ØªØ±ÙƒØ© Ù„ÙƒÙ„ Ø£Ù‚Ø³Ø§Ù… Ø§Ù„Ø´Ø§Ø´Ø© (Ø¨Ù†Ø¯ Ø§Ù„Ø£ÙˆØ§Ù…Ø± 4) â€” ØªÙØºÙ„Ù‚
     *  ÙÙŠ [onDestroyView] Ø¹Ø¨Ø± [VoicePreviewHelper.release]. */
    internal lateinit var voicePreview: VoicePreviewHelper

    /** Ù…Ø³ØªÙ‚Ø¨Ù„ Ø§ÙƒØªÙ…Ø§Ù„ ØªÙ†Ø²ÙŠÙ„ Ø§Ù„ØªØ­Ø¯ÙŠØ« â€” ÙŠÙØ­ØªÙØ¸ Ø¨Ù…Ø±Ø¬Ø¹Ù‡ Ù„ÙŠÙÙ„ØºÙ‰ ØªØ³Ø¬ÙŠÙ„Ù‡ ÙÙŠ
     *  [onDestroyView] (Ø¨Ù†Ø¯ 6.6) Ø­ØªÙ‰ Ù„Ø§ ÙŠØªØ³Ø±Ø¨ Ø§Ù„ÙØµÙŠÙ„ Ø¹Ù†Ø¯ ØªØ¯ÙˆÙŠØ± Ø§Ù„Ø´Ø§Ø´Ø©
     *  Ø£Ùˆ Ù…ØºØ§Ø¯Ø±ØªÙ‡Ø§ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„ØªÙ†Ø²ÙŠÙ„. */
    internal var updateReceiver: android.content.BroadcastReceiver? = null

    /** Ø­ÙˆØ§Ø± Ù…ØªØ§Ø¨Ø¹Ø© ØªÙ†Ø²ÙŠÙ„ Ø§Ù„ØªØ­Ø¯ÙŠØ« Ø§Ù„Ù†Ø´Ø· ÙˆÙ…Ù‡Ù…Ø© Ø§Ù„Ø§Ø³ØªØ¹Ù„Ø§Ù… Ø§Ù„Ø¯ÙˆØ±ÙŠ Ø¹Ù† ØªÙ‚Ø¯Ù…Ù‡. */
    internal var updateProgressDialog: AlertDialog? = null
    internal var updatePollingJob: Job? = null
    internal var updateDialogDismissedByUser: Boolean = false

    // Ù‚Ø³Ù… Ø§Ù„Ù…Ø­Ø±Ùƒ ÙˆØ§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ (Ø§Ù„Ø§ÙƒØªØ´Ø§Ù/setupAutoConvertUI/
    // Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ù„ØºØ§Øª Ø§Ù„Ù…ÙƒØªØ´ÙØ© Ù„ÙƒÙ„ Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª...) Ø§Ù†ØªÙ‚Ù„ Ø¨Ø§Ù„ÙƒØ§Ù…Ù„ Ø¥Ù„Ù‰
    // EngineSectionController.

    // Ø¶Ø§Ø¨Ø·Ø§Øª Ø£Ù‚Ø³Ø§Ù… Ø§Ù„Ø´Ø§Ø´Ø© (Ù†Ù‚Ù„ Ù…Ù†Ø·Ù‚ Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø¥Ù„ÙŠÙ‡Ø§ â€” Ø§Ù„Ù…Ø±Ø­Ù„Ø© Ø¬ Ù…Ù† Ø§Ù„ØªÙÙƒÙŠÙƒ)
    internal lateinit var timeSection: TimeAnnouncementController
    internal lateinit var batterySection: BatteryAnnouncementController
    internal lateinit var notificationSection: NotificationReadingController
    internal lateinit var callerSection: CallerAnnouncementController

/** Ø¥Ø±Ø´Ø§Ø¯Ø§Øª ØªÙˆØ§ÙÙ‚ Ø§Ù„Ø¬Ù‡Ø§Ø² â€” ØªØ¶Ù…Ù‘ Ø£ÙŠØ¶Ø§Ù‹ Ù…Ø³Ø§Ø± Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„ÙÙˆØ±ÙŠ Ù„Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„ØŒ
     *  ÙÙŠÙÙ†Ø¹Ø´ Ø­Ø§Ù„ØªÙ‡ ÙÙŠ [onResume] Ù„Ø£Ù†Ù‘Ù‡Ø§ ØªØªØ¨Ø¹ Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø§Ù„Ù†Ø¸Ø§Ù… Ù„Ø§ Ø¨ÙŠØ§Ù†Ø§Øª
     *  Ø§Ù„ØªØ·Ø¨ÙŠÙ‚. */
    private lateinit var oemGuidance: OemGuidanceController
    internal lateinit var smsSection: SmsReadingController
    internal lateinit var generalSection: GeneralSettingsController
    internal lateinit var numberSection: NumberReadingController
    internal lateinit var deviceHealthSection: DeviceHealthController
    internal lateinit var textReadingSection: TextReadingController
    internal lateinit var textProcessingSection: TextProcessingController
    internal lateinit var instantSilenceSection: InstantSilenceController
    private lateinit var a11yServicesSection: ScreenServicesCardController
    internal lateinit var embeddedDownloaderSection:
        EmbeddedEngineDownloaderController

    /** Ø¢Ø®Ø± Ø±Ù‚Ù… Ø¥ØµØ¯Ø§Ø± ØªØ®Ø²ÙŠÙ† Ø´ÙˆÙ‡Ø¯Øª Ø§Ù„Ø´Ø§Ø´Ø© (Ø¹Ù¢Ù©-9) â€” ÙŠÙ‚Ø§Ø±ÙŽÙ† ÙÙŠ
     *  [onResume] Ù„ÙƒØ´Ù ØªØºÙŠÙ‘Ø± Ø§Ù„Ù…Ù„ÙØ§Øª Ø£Ø«Ù†Ø§Ø¡ Ø®Ù„ÙÙŠØ© Ø§Ù„Ø´Ø§Ø´Ø©. */
    private var lastSeenStorageVersion: Int = -1

    // Ù…ÙØªØ§Ø­ ØªØ¨Ø¯ÙŠÙ„ Ù„ØºØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ (Ø£Ø³ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©)
    internal lateinit var btnToggleLanguage:
        com.google.android.material.button.MaterialButton

    // Ø²Ø± Ø§Ù„ØªÙˆØ§ØµÙ„ Ù…Ø¹ Ø§Ù„Ù…Ø·ÙˆÙ‘Ø± (ÙŠÙÙØªØ­ Ø®Ø§Ø±Ø¬ÙŠØ§Ù‹ Ø¨Ù„Ø§ Ø£Ø°ÙˆÙ†Ø§Øª)
    private lateinit var btnContactDeveloper:
        com.google.android.material.button.MaterialButton

    // Ø²Ø± Ø§Ù„Ø¥Ø¨Ù„Ø§Øº Ø¹Ù† Ø®Ø·Ø£ (ÙŠØ¬Ù…Ø¹ Ø§Ù„Ø£Ø®Ø·Ø§Ø¡ Ù…Ù† Ø³Ø¬Ù„ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ ÙˆÙŠØ´Ø§Ø±ÙƒÙ‡Ø§)
    internal lateinit var btnReportError:
        com.google.android.material.button.MaterialButton

    /** Ø¨Ø§Ø¨ Ù…Ù†Ø¹ ØªÙƒØ±Ø§Ø± Ø¬Ù…Ø¹ ØªÙ‚Ø±ÙŠØ± Ø§Ù„Ø£Ø®Ø·Ø§Ø¡ (Ù…Ù„Ø§Ø­Ø¸Ø© Ù…Ø±Ø§Ø¬Ø¹Ø© Ø§Ù„Ø³Ø¬Ù„): Ø¶ØºØ·ØªØ§Ù†
     *  Ø³Ø±ÙŠØ¹ØªØ§Ù† ÙƒØ§Ù†ØªØ§ ØªØ´ØºÙ‘Ù„Ø§Ù† ÙƒÙˆØ±ÙˆØªÙŠÙ†ÙŠÙ† Ù…ØªÙˆØ§Ø²ÙŠÙŠÙ† ÙŠØ¨Ù†ÙŠØ§Ù† Ø§Ù„ØªÙ‚Ø±ÙŠØ± ÙˆÙŠÙƒØªØ¨Ø§Ù†
     *  Ù†ÙØ³ Ù…Ù„Ù Ø§Ù„ÙƒØ§Ø´ â€” Ø§Ù„Ø¶ØºØ· Ø§Ù„Ø«Ø§Ù†ÙŠ ÙŠÙØ±ÙØ¶ Ø­ØªÙ‰ ØªÙƒØªÙ…Ù„ Ø§Ù„Ø±Ø­Ù„Ø© Ø§Ù„Ø£ÙˆÙ„Ù‰. */
    internal val errorReportGate = ErrorReportGate()

    // Ø²Ø± Ø¢Ø®Ø± Ø§Ù„ØªØ­Ø¯ÙŠØ«Ø§Øª (ÙŠØ¹Ø±Ø¶ Ù…Ù„Ø®Øµ Ø§Ù„Ø¥ØµØ¯Ø§Ø± Ø§Ù„Ø­Ø§Ù„ÙŠ ÙˆØªØºÙŠÙŠØ±Ø§ØªÙ‡ ÙÙŠ Ø­ÙˆØ§Ø±)
    private lateinit var btnChangelog:
        com.google.android.material.button.MaterialButton

    // Ø§Ù„Ù…ÙØªØ§Ø­ Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠ Ù„ÙƒÙ„ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª
    internal lateinit var switchAllAnnouncements: SwitchMaterial

    internal lateinit var switchLockScreenPrivacy: SwitchMaterial
    private lateinit var btnSetDefaultEngine:
        com.google.android.material.button.MaterialButton

    // Ù…Ø¹Ø§ÙŠÙ†Ø© Ù†Ø·Ù‚ Ø±Ù‚Ù…
    internal lateinit var etNumberPreview: TextView
    private lateinit var btnPreviewNumber:
        com.google.android.material.button.MaterialButton
    private lateinit var btnStopNumberPreview:
        com.google.android.material.button.MaterialButton

    // Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ù…ØªØµÙ„ÙŠÙ† Ø§Ù„Ù…Ø®ØµØµØ©
    private lateinit var btnCallerNames:
        com.google.android.material.button.MaterialButton

    // Ø§Ø³ØªÙŠØ±Ø§Ø¯/ØªØµØ¯ÙŠØ± Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ Ø¹Ø¨Ø± Ø´Ø§Ø´Ø© Ø§Ù„ÙˆØ«Ø§Ø¦Ù‚ (SAF)
    internal lateinit var btnImportDict:
        com.google.android.material.button.MaterialButton
    internal lateinit var btnExportDict:
        com.google.android.material.button.MaterialButton
    // Ù†Øµ Ù…Ù„Ù Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ Ø§Ù„Ù…ÙØ®ØªØ§Ø± Ù…Ù† SAF Ù„Ø­ÙŠÙ† Ø§Ø®ØªÙŠØ§Ø± Ø·Ø±ÙŠÙ‚Ø© Ø§Ù„Ø§Ø³ØªÙŠØ±Ø§Ø¯ (Ø¯Ù…Ø¬/Ø§Ø³ØªØ¨Ø¯Ø§Ù„)
    internal var pendingImportJson: String? = null

    // Ù†ÙˆØ§ÙØ° Ø­ÙˆØ§Ø±ÙŠØ© Ù…ÙØªÙˆØ­Ø©: ØªÙØºÙ„Ù‚ ÙƒÙ„Ù‡Ø§ Ø¹Ù†Ø¯ ØªØ¯Ù…ÙŠØ± Ø§Ù„Ø¹Ø±Ø¶ Ø­ØªÙ‰ Ù„Ø§ ÙŠØªØ³Ø±Ø¨
    // Ù…Ø±Ø¬Ø¹ Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© (WindowLeaked) Ø¹Ù†Ø¯ ØªØ¯ÙˆÙŠØ± Ø§Ù„Ø´Ø§Ø´Ø©.
    private val activeDialogs =
        java.util.Collections.synchronizedSet(mutableSetOf<Dialog>())

    /** ÙŠØ³Ø¬Ù‘Ù„ Ø­ÙˆØ§Ø±Ø§Ù‹ Ù…ÙØªÙˆØ­Ø§Ù‹ Ù„ÙŠÙØºÙ„Ù‚ ØªÙ„Ù‚Ø§Ø¦ÙŠØ§Ù‹ Ù…Ø¹ ØªØ¯Ù…ÙŠØ± Ø¹Ø±Ø¶ Ø§Ù„Ø´Ø§Ø´Ø©ØŒ ÙˆÙŠØ²ÙŠÙ„Ù‡
     *  Ù…Ù† Ø§Ù„Ø³Ø¬Ù„ Ø¹Ù†Ø¯ Ø¥ØºÙ„Ø§Ù‚Ù‡ ÙŠØ¯ÙˆÙŠØ§Ù‹. */
    internal fun trackDialog(dialog: Dialog) {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
    }

    /**
     * Ø§Ø³ØªÙŠØ±Ø§Ø¯ Ø§Ù„Ù‚Ø§Ù…ÙˆØ³: **`.json` ÙÙ‚Ø·** (Ø¨Ù‚Ø±Ø§Ø± Ø§Ù„Ù…Ø¯ÙŠØ±) â€” Ù„Ø§ ØªÙØ¹Ø±Ø¶ ÙƒÙ„
     * Ø§Ù„Ù…Ù„ÙØ§Øª ÙˆÙ„Ø§ ØªÙÙ‚Ø¨Ù„ Ø¥Ù„Ø§ JSON.
     *
     * **Ù„Ù…Ø§Ø°Ø§ Ø«Ù„Ø§Ø«Ø© Ø£Ù†ÙˆØ§Ø¹ MIME Ù„Ø§ ÙˆØ§Ø­Ø¯ØŸ** ÙÙ„ØªØ± SAF ÙŠØ³ØªØ«Ù†ÙŠ Ù…Ø§ Ù„Ø§ ÙŠØ·Ø§Ø¨Ù‚
     * Ù†ÙˆØ¹Ù‡ØŒ ÙˆÙƒØ«ÙŠØ±ÙŒ Ù…Ù† Ù…Ø²ÙˆÙ‘Ø¯ÙŠ Ø§Ù„Ù…Ù„ÙØ§Øª ÙŠÙØ¨Ù„Ù‘Øº Ø¹Ù† `.json` Ø¨ØµÙØ© `text/plain`
     * Ø£Ùˆ `application/octet-stream` Ù„Ø§ `application/json` â€” ÙÙŠØ®ØªÙÙŠ Ø§Ù„Ù…Ù„Ù
     * Ø§Ù„ØµØ­ÙŠØ­ Ù…Ù† Ø§Ù„Ù‚Ø§Ø¦Ù…Ø© ÙˆÙ„Ø§ ÙŠØ±Ø§Ù‡ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…. ÙØ§Ù„Ù…ÙÙ„ØªØ± ÙŠØ¹Ø±Ø¶ Ø§Ù„Ø«Ù„Ø§Ø«Ø©ØŒ Ø«Ù…
     * ÙŠÙØªØ­Ù‚ÙŽÙ‘Ù‚ Ù…Ù† Ø§Ù„Ø§Ù…ØªØ¯Ø§Ø¯ Ø£Ø¯Ù†Ø§Ù‡ ÙÙŠÙØ±ÙØ¶ ØºÙŠØ±Ù `.json` ØµØ±Ø§Ø­Ø©Ù‹. ÙØ§Ù„Ù†ØªÙŠØ¬Ø©
     * Ø§Ù„Ù…Ø·Ù„ÙˆØ¨Ø© Ù…Ø­Ù‚Ù‚Ø©: **Ù„Ø§ ÙŠÙØ¹Ø±Ø¶ Ø¥Ù„Ø§ JSON ÙˆÙ„Ø§ ÙŠÙÙ‚Ø¨Ù„ Ø¥Ù„Ø§ JSON**.
     */
    private val jsonOnlyMimeTypes = arrayOf(
        "application/json",
        "text/json",
        "text/plain"
    )


    internal val openDictLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolver =
                context?.contentResolver ?: return@registerForActivityResult
            // Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø§Ù…ØªØ¯Ø§Ø¯: ÙŠÙÙ‚Ø¨Ù„ Ù…Ø§ ÙŠÙ†ØªÙ‡ÙŠ Ø¨Ù€ `.json` ÙÙ‚Ø·ØŒ ÙˆÙ…Ø§
            // Ù„Ø§ Ø§Ø³Ù…ÙŽ Ù„Ù‡ (Ø¨Ø¹Ø¶ Ø§Ù„Ù…Ø²ÙˆÙ‘Ø¯ÙŠÙ†) ÙŠÙØ­Ù…Ù„ Ø¹Ù„Ù‰ Ø£Ù†Ù‡ JSON ÙˆÙŠÙØ®Ø¶Ø¹Ù‡
            // Ù„ÙØ´Ù„ Ø§Ù„ØªØ¬Ø²Ø¦Ø© Ù„Ø§Ø­Ù‚Ø§Ù‹ â€” ÙÙ„Ø§ ÙŠØ¯Ø®Ù„ Ø¥Ù„Ø§ Ù…Ø§ ÙŠÙ…ÙƒÙ† Ù‚Ø±Ø§Ø¡ØªÙ‡.
            val name = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cur ->
                    val idx = cur.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)
                    else null
                }
            }.getOrNull()
            if (name != null &&
                !name.endsWith(".json", ignoreCase = true)
            ) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.dict_import_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            // Ù†Ù‚Ø±Ø£ Ø§Ù„Ù…Ù„Ù Ø¹Ù„Ù‰ Ø®ÙŠØ· IO Ø«Ù… Ù†Ø¹Ø±Ø¶ Ø­ÙˆØ§Ø± Ø·Ø±ÙŠÙ‚Ø© Ø§Ù„Ø§Ø³ØªÙŠØ±Ø§Ø¯ (Ø¯Ù…Ø¬/Ø§Ø³ØªØ¨Ø¯Ø§Ù„)
            lifecycleScope.launch(AppDispatchers.io) {
                val result = runCatching { readJsonWithinLimit(resolver, uri) }
                    .getOrDefault(DictReadResult.Unreadable)
                withContext(AppDispatchers.main) {
                    if (!isAdded) return@withContext
                    when (result) {
                        is DictReadResult.Ok -> {
                            pendingImportJson = result.json
                            showImportModeDialog()
                        }
                        // Ø³Ø¨Ø¨Ø§Ù† Ù…Ø®ØªÙ„ÙØ§Ù† ÙˆØ±Ø³Ø§Ù„ØªØ§Ù†: Â«Ø§Ù„ÙƒØ¨ÙŠØ±Â» Ù„ÙŠØ³ Â«Ø§Ù„ÙØ§Ø³Ø¯Â».
                        DictReadResult.TooLarge -> toast(
                            getString(R.string.dict_import_too_large)
                        )
                        DictReadResult.Unreadable -> toast(
                            getString(R.string.dict_import_failed)
                        )
                    }
                }
            }
        }
    }

    internal fun toast(message: String) {
        ToastDebouncer.show(requireContext(), message)
    }

    /** Ù†ØªÙŠØ¬Ø© Ù‚Ø±Ø§Ø¡Ø© Ù…Ù„Ù Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ â€” ØªÙÙ…ÙŠÙ‘Ø² Ø³Ø¨Ø¨ÙŽ Ø§Ù„Ø±ÙØ¶ Ù„ÙŠÙÙ‚Ø§Ù„ Ù„Ù„Ù…Ø³ØªØ®Ø¯Ù…. */
    private sealed interface DictReadResult {
        data class Ok(val json: String) : DictReadResult
        data object TooLarge : DictReadResult
        data object Unreadable : DictReadResult
    }

    /**
     * Ù‚Ø±Ø§Ø¡Ø©ÙŒ **Ù…Ø­Ø¯ÙˆØ¯Ø©ÙŒ Ø¨Ø§Ù„Ø¨Ø§ÙŠØª** Ù„Ø§ `readText()` Ø§Ù„Ù…ÙØªÙˆØ­.
     *
     * **Ù„Ù…Ø§Ø°Ø§ØŸ** `readText()` ÙŠÙ‚Ø±Ø£ Ø§Ù„Ù…Ù„Ù ÙƒÙ„ÙŽÙ‘Ù‡ Ø«Ù… Ù†ÙÙØ­Øµ Ø§Ù„Ø­Ø¬Ù… Ø¨Ø¹Ø¯Ù‡ â€” ÙØ³Ù‚ÙÙ
     * 8 Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª Ù„Ø§ ÙŠÙ…Ù†Ø¹ Ø´ÙŠØ¦Ø§Ù‹ØŒ Ø¨Ù„ ÙŠÙƒØªØ´Ù Ø§Ù„Ø®Ø³Ø§Ø±Ø© Ø¨Ø¹Ø¯ ÙˆÙ‚ÙˆØ¹Ù‡Ø§: Ù…Ù„ÙÙŒ
     * Ø¨Ù…Ø¦Ø© Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª ÙŠÙØ­Ù…ÙŽÙ‘Ù„ ÙƒØ§Ù…Ù„Ø§Ù‹ ÙÙŠÙOOM Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ Ù‚Ø¨Ù„ Ø£Ù† ØªÙÙ‚Ø§Ù„ Â«Ø±ÙÙØ¶Â».
     * ÙÙ†ØªÙˆÙ‚Ù‘Ù Ø¹Ù†Ø¯ ØªØ¬Ø§ÙˆØ² Ø§Ù„Ø³Ù‚Ù Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù‚Ø±Ø§Ø¡Ø©ØŒ ÙÙ„Ø§ ÙŠÙØ­Ù…ÙŽÙ‘Ù„ Ø¥Ù„Ø§ Ù…Ø§ ÙŠÙØ³Ù…Ø­.
     */
    private fun readJsonWithinLimit(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri
    ): DictReadResult {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val max = PronunciationDictionary.MAX_IMPORT_BYTES
        val stream = resolver.openInputStream(uri)
            ?: return DictReadResult.Unreadable
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // Ù„Ø§ Ù†Ù‚Ø±Ø£ Ø£ÙƒØ«Ø± Ù…Ù† Ø§Ù„Ø³Ù‚Ù Ø£Ø¨Ø¯Ø§Ù‹: Ø§Ù„Ù…Ù„ÙÙ Ø§Ù„Ø²Ø§Ø¦Ø¯ ÙŠÙØ±ÙØ¶
                // ÙˆÙ‡Ùˆ Ù„Ù… ÙŠÙØ­Ù…ÙŽÙ‘Ù„ Ø¨Ø¹Ø¯.
                if (out.size() + read > max) return DictReadResult.TooLarge
                out.write(buffer, 0, read)
            }
        }
        return DictReadResult.Ok(String(out.toByteArray(), Charsets.UTF_8))
    }

    internal val createDictLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val resolver =
                context?.contentResolver ?: return@registerForActivityResult
            // Ø§Ù„ØªØµØ¯ÙŠØ± ÙƒØ§Ù…Ù„Ø§Ù‹ Ø¹Ù„Ù‰ Ø®ÙŠØ· IO Ø¹Ø¨Ø± Ø§Ù„ÙÙŠ Ø¥Ù… (Ù„Ø§ ØªØ¬Ù…ÙŠØ¯ ÙÙŠ Main)
            vm.exportDict(uri, resolver)
        }
    }

    /** Ø­ÙˆØ§Ø± Ø·Ø±ÙŠÙ‚Ø© Ø§Ù„Ø§Ø³ØªÙŠØ±Ø§Ø¯: Ø¯Ù…Ø¬ Ù…Ø¹ Ø§Ù„Ø¥Ø¯Ø®Ø§Ù„Ø§Øª Ø§Ù„Ø­Ø§Ù„ÙŠØ© Ø£Ùˆ Ø§Ø³ØªØ¨Ø¯Ø§Ù„ ÙƒØ§Ù…Ù„ØŒ Ø«Ù…
     *  ØªØ·Ø¨ÙŠÙ‚ Ù…Ù„Ù Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ Ø§Ù„Ù…ÙØ®ØªØ§Ø± Ù…Ù† SAF Ø¨Ø§Ù„Ø·Ø±ÙŠÙ‚Ø© Ø§Ù„Ù…Ø®ØªØ§Ø±Ø© (ÙŠÙØ­ÙØ¸ Ù†ØµÙ‡ ÙÙŠ
     *  [pendingImportJson] ÙƒÙŠ Ù„Ø§ ÙŠÙ‚ÙØ² Ø­ÙˆØ§Ø± Ø§Ù„Ø·Ø±ÙŠÙ‚Ø© Ø®Ø§Ø±Ø¬ Ø³ÙŠØ§Ù‚ Ø§Ù„Ù†ØªÙŠØ¬Ø©). */
    private fun showImportModeDialog() {
        val json = pendingImportJson ?: return
        val currentContext = context ?: return
        val options = arrayOf(
            getString(R.string.dict_import_merge),
            getString(R.string.dict_import_replace)
        )
        MaterialAlertDialogBuilder(currentContext)
            .setTitle(R.string.dict_import_mode_title)
            .setItems(options) { _, which ->
                pendingImportJson = null
                // Ø§Ù„Ø§Ø³ØªÙŠØ±Ø§Ø¯ ÙƒØ§Ù…Ù„Ø§Ù‹ Ø¹Ù„Ù‰ Ø®ÙŠØ· IO Ø¹Ø¨Ø± Ø§Ù„ÙÙŠ Ø¥Ù…
                vm.importDict(json, merge = which == 0)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                pendingImportJson = null
            }
            .setOnCancelListener { pendingImportJson = null }
            .create().also(::trackDialog).show()
    }

    // ===== Ø§Ù„Ù†Ø³Ø® Ø§Ù„Ø§Ø­ØªÙŠØ§Ø·ÙŠ / Ø§Ù„Ø§Ø³ØªØ¹Ø§Ø¯Ø© Ø§Ù„ÙƒØ§Ù…Ù„Ø§Ù†
    // (Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª + Ù‚Ø§Ù…ÙˆØ³ + Ø£Ø³Ù…Ø§Ø¡ Ù…ØªØµÙ„ÙŠÙ†) =====
    internal val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val resolver =
                context?.contentResolver ?: return@registerForActivityResult
            // Ø§Ù„ØªØµØ¯ÙŠØ± ÙƒØ§Ù…Ù„Ø§Ù‹ Ø¹Ù„Ù‰ Ø®ÙŠØ· IO Ø¹Ø¨Ø± Ø§Ù„ÙÙŠ Ø¥Ù…
            vm.exportBackup(uri, resolver)
        }
    }

    internal val openRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolver =
                context?.contentResolver ?: return@registerForActivityResult
            // Ø§Ù„Ù‚Ø±Ø§Ø¡Ø© ÙˆØ§Ù„ØªÙÙƒÙŠÙƒ ÙˆØ§Ù„Ø§Ø³ØªØ¹Ø§Ø¯Ø© ÙƒÙ„Ù‡Ø§ Ø¹Ù„Ù‰ Ø®ÙŠØ· IO Ø¹Ø¨Ø± Ø§Ù„ÙÙŠ Ø¥Ù…
            vm.restoreBackup(uri, resolver)
        }
    }

    internal var announcementSpeaker: AnnouncementSpeaker? = null

    // ÙƒØªØ§Ù„ÙˆØ¬ ØµØ±ÙŠØ­ Ù„Ù„Ø£ØµÙˆØ§Øª Ø§Ù„Ù…Ø¯Ø¹ÙˆÙ…Ø© ÙÙŠ Ù†Ø§Ø·Ù‚ (ÙŠØªØ·Ø§Ø¨Ù‚ Ù…Ø¹ onGetVoices)
    // Ù…Ø¨Ø³Ù‘Ø·Ø© Ø¥Ù„Ù‰ Ù„ØºØªÙŠÙ† ÙÙ‚Ø·: "Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©" Ùˆ"Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ©"
    private lateinit var nateqVoices: List<NateqVoice>

    // ÙƒØªØ§Ù„ÙˆØ¬ Ù…ØªØ¯Ø±Ø¬ (Ù„ØºØ© â† Ù…Ø­Ø±Ùƒ â† Ø£ØµÙˆØ§Øª) Ù„ØµÙÙˆÙ Ø§Ù„ÙØ¦Ø§Øª ÙˆØ§Ù„Ø±Ø³Ø§Ø¦Ù„
    // ÙˆØ§Ù„Ù…ØªØµÙ„ ÙˆØ§Ù„Ø¨Ø·Ø§Ø±ÙŠØ© â€” ÙŠÙÙ…Ù„Ø£ Ù…Ù† Ø§ÙƒØªØ´Ø§Ù Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ø®Ù„ÙÙŠ.
    private lateinit var engineCatalog: EngineVoicesCatalog

    // Ù…Ù‡Ù…Ø© Ø§ÙƒØªØ´Ø§Ù Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ø¬Ø§Ø±ÙŠØ© â€” Ø¥Ù„ØºØ§Ø¤Ù‡Ø§ Ø¹Ù†Ø¯ Ø¨Ø¯Ø¡ Ø¬ÙˆÙ„Ø© Ø¬Ø¯ÙŠØ¯Ø© ÙŠÙ…Ù†Ø¹ Ø§Ù„ØªØ³Ø§Ø¨Ù‚
    private var catalogDiscoveryJob: kotlinx.coroutines.Job? = null

    // Ø·Ù„Ø¨ Ø¥Ø°Ù†ÙŽÙŠ Ø§Ù„Ù‚Ø±Ø§Ø¡Ø© Ø¹Ù†Ø¯ ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ (READ_PHONE_STATE Ù„Ø§Ø³ØªÙ‚Ø¨Ø§Ù„
    // Ø¨Ø« PHONE_STATE Ø§Ù„Ù…Ø­Ù…ÙŠØŒ ÙˆREAD_CALL_LOG Ù„Ù„ÙˆØµÙˆÙ„ Ø¥Ù„Ù‰ Ø§Ù„Ø±Ù‚Ù… Ø¹Ù„Ù‰ Ø£Ù†Ø¯Ø±ÙˆÙŠØ¯ 12+
    // ÙˆØ§Ù„Ø§Ø³Ù… Ù…Ù† Ø³Ø¬Ù„ Ø§Ù„Ù…ÙƒØ§Ù„Ù…Ø§ØªØŒ ÙˆREAD_CONTACTS Ù„Ù„Ø¨Ø­Ø« Ø¹Ù† Ø§Ù„Ø§Ø³Ù… ÙÙŠ Ø¯ÙØªØ±
    // Ø§Ù„Ø§ØªØµØ§Ù„Ø§Øª).
    // ÙŠØ¨Ù‚Ù‰ Ø§Ù„Ù…ÙØ·Ù„ÙÙ‚ ÙÙŠ Ø§Ù„ÙØµÙŠÙ„ (ÙŠØ­ØªØ§Ø¬ registerForActivityResult) ÙˆÙ†ØªÙŠØ¬ØªÙÙ‡
    // ØªÙØ­Ù…ÙŽÙ‘Ù„ Ø¥Ù„Ù‰ Ø¶Ø§Ø¨Ø· Ù‚Ø³Ù… Ø§Ù„Ù…ØªØµÙ„ Ø§Ù„Ø°ÙŠ ÙŠÙ…Ù„Ùƒ Ø§Ù„Ù…ÙØªØ§Ø­ ÙˆØ§Ù„Ø­Ø§Ø±Ø³.
    internal val callerPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (::callerSection.isInitialized) {
            callerSection.onPermissionsResult(granted)
        }
    }

    /** Ù…Ù†ØªÙ‚ÙŠ Ù…Ù„Ù Ù†ØºÙ…Ø© Ø§Ù„Ø³Ø§Ø¹Ø© Ø§Ù„Ù…Ø®ØµØµØ© (ACTION_OPEN_DOCUMENT). */
    internal val customChimeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (::timeSection.isInitialized) {
            timeSection.onCustomChimeResult(result)
        }
    }

    /** Ù…Ù†ØªÙ‚ÙŠ Ù…Ù„ÙØ§Øª Ø£ØµÙˆØ§Øª Acapela Ø§Ù„Ù…Ø¶ØºÙˆØ·Ø© (.zip) */
    internal val openAcapelaZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ctx = context ?: return@registerForActivityResult
            val fileName = runCatching {
                ctx.contentResolver.query(
                    uri, null, null, null, null
                )?.use { cur ->
                    val idx = cur.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)
                    else null
                }
            }.getOrNull()

            lifecycleScope.launch(AppDispatchers.main) {
                Toast.makeText(
                    ctx,
                    R.string.acapela_import_select_file,
                    Toast.LENGTH_SHORT
                ).show()

                val result = withContext(AppDispatchers.io) {
                    AcapelaVoiceImporter.importFromZip(ctx, uri, fileName)
                }

                if (!isAdded) return@launch
                if (result.success) {
                    val voicesNames = result.importedVoices.joinToString("ØŒ ")
                    val msg = getString(
                        R.string.acapela_import_success,
                        voicesNames
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                } else {
                    val err = result.errorMessage ?: getString(
                        R.string.acapela_import_error,
                        ""
                    )
                    Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                }
                refreshEmbeddedUiIfStorageChanged()
                refreshVoiceCatalogDiscovery()
            }
        }
    }

    /** Ù…Ù†ØªÙ‚ÙŠ Ù…Ø¬Ù„Ø¯ Ø£ØµÙˆØ§Øª Acapela Ø¹Ø¨Ø± SAF */
    internal val openAcapelaFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val ctx = context ?: return@registerForActivityResult
            lifecycleScope.launch(AppDispatchers.main) {
                Toast.makeText(
                    ctx,
                    R.string.acapela_import_folder_select,
                    Toast.LENGTH_SHORT
                ).show()

                val result = withContext(AppDispatchers.io) {
                    AcapelaVoiceImporter.importFromTreeUri(ctx, uri)
                }

                if (!isAdded) return@launch
                if (result.success) {
                    val voicesNames = result.importedVoices.joinToString("ØŒ ")
                    val msg = getString(
                        R.string.acapela_import_success,
                        voicesNames
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                } else {
                    val err = result.errorMessage ?: getString(
                        R.string.acapela_import_error,
                        ""
                    )
                    Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                }
                refreshEmbeddedUiIfStorageChanged()
                refreshVoiceCatalogDiscovery()
            }
        }
    }

    // Ø·Ù„Ø¨ Ø¥Ø°Ù† Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„ÙˆØ§Ø±Ø¯Ø© Ù„Ø­Ø¸Ø© ØªÙØ¹ÙŠÙ„ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ ÙÙ‚Ø· (Ù„Ø§ Ø¹Ù†Ø¯
    // Ø£ÙˆÙ„ ØªØ´ØºÙŠÙ„). Ø§Ù„ÙˆØ¶Ø¹ Ø§Ù„Ù…Ø®ØªØ§Ø± (full/source) Ù„Ø§ ÙŠÙØ­ÙØ¸ Ø¥Ù„Ø§ Ø¨Ø¹Ø¯ Ø§Ù„Ù…Ù†Ø­ Ø§Ù„ÙØ¹Ù„ÙŠ â€”
    // Ù…Ø³Ø¤ÙˆÙ„ÙŠØ© Ø§Ù„Ø¶Ø§Ø¨Ø· (Ø¨Ù†Ø¯ [9]).
    internal val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::smsSection.isInitialized) {
            smsSection.onSmsPermissionResult(granted)
        }
    }

    internal var pendingContactPickerCallback: ((String?, String?) -> Unit)? =
        null

    internal val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK &&
            uri != null
        ) {
            val resolver = context?.contentResolver
            val cursor = runCatching {
                resolver?.query(
                    uri,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds
                            .Phone.NUMBER,
                        android.provider.ContactsContract.CommonDataKinds
                            .Phone.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )
            }.getOrNull()
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val numIdx = c.getColumnIndex(
                        android.provider.ContactsContract.CommonDataKinds
                            .Phone.NUMBER
                    )
                    val nameIdx = c.getColumnIndex(
                        android.provider.ContactsContract.CommonDataKinds
                            .Phone.DISPLAY_NAME
                    )
                    val num = if (numIdx >= 0) c.getString(numIdx) else null
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    pendingContactPickerCallback?.invoke(name, num)
                }
            }
        }
        pendingContactPickerCallback = null
    }

    // ===== Ø¶Ø§Ø¨Ø· Ø§Ù„Ø£ÙƒÙˆØ±Ø¯ÙŠÙˆÙ† ÙˆØ§Ù„ØªÙ†Ù‚Ù‘Ù„: Ø¨Ø·Ø§Ù‚Ø§Øª Ø§Ù„Ø£Ù‚Ø³Ø§Ù… ÙˆØ¨Ù†Ø§Ø¡ Ø£Ø³Ø·Ø± Ø§Ù„Ø­Ø§Ù„Ø© =====
    internal lateinit var accordion: SettingsAccordionController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Trace.beginSection("VoiceSelectionFragment.onViewCreated")
        try {
            super.onViewCreated(view, savedInstanceState)
        // Ù„Ø§ Ø¥Ù†Ø´Ø§Ø¡ Ù…Ø¨Ø§Ø´Ø± Ù„Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª/Ø§Ù„Ù‚Ø§Ù…ÙˆØ³: ÙƒÙ„Ø§Ù‡Ù…Ø§ Ù…Ø­Ù‚ÙˆÙ† Ø¹Ø¨Ø±
        // SettingsViewModel.

        // edge-to-edge (Ø¥Ù„Ø²Ø§Ù…ÙŠ Ù…Ù† targetSdk 35+): Ù†Ø·Ø¨Ù‘Ù‚ Ø§Ù„ÙˆØ³Ø§Ø¦Ø¯ ÙŠØ¯ÙˆÙŠØ§Ù‹ Ø¹Ø¨Ø±
        // ViewCompat.setOnApplyWindowInsetsListener
        // Ø¨Ø¯Ù„ android:fitsSystemWindows
        // (Ø§Ù„Ø­Ù„ Ø§Ù„Ù…Ù‡Ù…Ù„) Ø­ØªÙ‰ Ù„Ø§ ØªÙØºØ·Ù‰ Ø£ÙŠ Ø¹Ù†Ø§ØµØ± ØªÙØ§Ø¹Ù„ÙŠØ© ØªØ­Øª Ø´Ø±ÙŠØ· Ø§Ù„Ø­Ø§Ù„Ø©/Ø§Ù„ØªÙ†Ù‚Ù„.
        // ØªÙØ¹Ø§Ø¯ Ø§Ù„ÙˆØ³Ø§Ø¦Ø¯ Ø¹Ù†Ø¯ ÙƒÙ„ ØªØºÙŠÙŠØ± (Ø¥Ø¸Ù‡Ø§Ø±/Ø¥Ø®ÙØ§Ø¡ Ø§Ù„Ø£Ø´Ø±Ø·Ø©) ÙØªÙ†Ø²Ù„Ù‚ Ø§Ù„Ø¹Ø¨Ø§Ø±Ø©
        // Ø§Ù„Ø¹Ù„ÙŠØ§ ÙˆØ§Ù„Ù…Ù„Ø§Ø­Ø© Ø§Ù„Ø³ÙÙ„ÙŠØ© ØªØ­Øª Ø§Ù„Ù†Ø¸Ø§Ù… ØªÙ„Ù‚Ø§Ø¦ÙŠØ§Ù‹.
        ViewCompat.setOnApplyWindowInsetsListener(
            view.findViewById(R.id.sv_settings_scroll)
        ) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // ØªÙ‡ÙŠØ¦Ø© Ø§Ù„Ø£ØµÙˆØ§Øª Ù‡Ù†Ø§ Ø¨Ø¹Ø¯ Ø§Ù„Ø§Ù†Ø¶Ù…Ø§Ù… Ù„Ù„Ø³ÙŠØ§Ù‚ (Ù„Ø§ ÙŠØ¬ÙˆØ² ÙÙŠ Ù…ÙÙ†Ø´Ø¦/Ø®Ø§ØµÙŠØ©
        // ØªØ³ØªØ¯Ø¹ÙŠ getString())
        nateqVoices = listOf(
            NateqVoice(
                "ar-EG",
                LanguageCode.AR.tag,
                getString(R.string.voice_name_arabic),
                Locale.forLanguageTag(LanguageCode.AR.tag)
            ),
            NateqVoice(
                "en-US",
                LanguageCode.EN.tag,
                getString(R.string.voice_name_english),
                Locale.forLanguageTag(LanguageCode.EN.tag)
            )
        )

        // ÙƒØªØ§Ù„ÙˆØ¬ Ù…ØªØ¯Ø±Ø¬ â€” ÙŠØ¨Ø¯Ø£ ÙØ§Ø±ØºØ§Ù‹ ÙÙŠØ¹ØªÙ…Ø¯ Ø¹Ù„Ù‰ Ø§Ù„Ø£ØµÙˆØ§Øª Ø§Ù„Ø§Ø­ØªÙŠØ§Ø·ÙŠØ©
        // (Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©/Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ©) Ø«Ù… ÙŠÙÙ…Ù„Ø£ Ù…Ù† Ø§Ù„Ø§ÙƒØªØ´Ø§Ù Ø§Ù„Ø®Ù„ÙÙŠ.
        engineCatalog = EngineVoicesCatalog(
            emptyMap(),
            getString(R.string.voice_name_arabic),
            getString(R.string.voice_name_english)
        )

        // Ù‚Ø³Ù… Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª: Ø§ÙƒØªØ´Ø§Ù Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù…Ø«Ø¨ØªØ© ÙÙ‚Ø· (Ù„Ø§ ØµÙ†Ø¯ÙˆÙ‚ Ø§Ø®ØªÙŠØ§Ø± Ø¹Ø§Ù… â€”
        // Ù„Ø§ Ù…Ø­Ø±Ùƒ Ø§ÙØªØ±Ø§Ø¶ÙŠØ› Ù…Ø­Ø±Ùƒ ÙƒÙ„ Ù„ØºØ©/ÙØ¦Ø© ÙŠÙØ­Ø³Ù… ÙˆÙ‚Øª Ø§Ù„Ù†Ø·Ù‚)
        voicePreview = VoicePreviewHelper(requireContext().applicationContext)
        accordion = SettingsAccordionController(
            this,
            settings,
            nateqVoices,
            engineCatalog,
            onNavigate = { group, sectionId ->
                // ØªØ£Ø¬ÙŠÙ„ Ø§ÙƒØªØ´Ø§Ù Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø­ØªÙ‰ ÙØªØ­ Ù‚Ø³Ù…Ù‡Ø§ Ø£Ùˆ Ù…Ø¬Ù…ÙˆØ¹Ø© Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª
                if (group == 0 ||
                    sectionId == R.id.ll_caller_announcement_settings ||
                    sectionId == R.id.ll_sms_reading_settings
                ) {
                    ensureVoiceCatalogDiscovered()
                }
            }
        ).apply {
            setup(view, viewLifecycleOwner)
        }
        engineSection = EngineSectionController(
            this, settings, engines, voicePreview
        ).apply {
                onStatusChanged = { accordion.updateSectionStatuses() }
            }
        engineSection.setupEngineDiscovery(viewLifecycleOwner.lifecycleScope)

        // Ù„ÙˆØ­Ø© ÙØ¦Ø§Øª Ø§Ù„Ø£ØµÙˆØ§Øª Ø¨Ø¹Ø¯ Ø¥Ø¹Ø§Ø¯Ø© Ø§Ù„ØªØµÙ…ÙŠÙ…: ÙØ¦Ø© ÙˆØ§Ø­Ø¯Ø© Ù…Ø¹Ø±ÙˆØ¶Ø© ÙÙŠ ÙƒÙ„
        // Ù…Ø±Ø© (Ù„ØºØ©/Ù…Ø­Ø±Ùƒ/ØµÙˆØª/Ø£Ø´Ø±Ø·Ø©) ØªØ®ØªØ§Ø±Ù‡Ø§ Ù…Ù† Ø³Ø¨nner Ø¹Ù„ÙˆÙŠ ÙˆØ§Ø­Ø¯ØŒ Ø£Ùˆ Ù„ÙˆØ­Ø©
        // Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ â€” ÙˆØ§Ù„ØªØ¹Ø¯ÙŠÙ„Ø§Øª Ù„Ø§ ØªÙØ­ÙØ¸ Ø¥Ù„Ø§ Ø¨Ø¶ØºØ· Ø²Ø± Ø§Ù„Ø­ÙØ¸ Ø£Ø³ÙÙ„
        // Ø§Ù„Ø´Ø§Ø´Ø© (Ù…Ø¹ ØªØ£ÙƒÙŠØ¯Ù Ø¹Ù†Ø¯ Ù…ØºØ§Ø¯Ø±Ø© ÙØ¦Ø© Ø¨ØªØ¹Ø¯ÙŠÙ„Ø§Øª ØºÙŠØ± Ù…Ø­ÙÙˆØ¸Ø©).
        categoryVoicePanel = CategoryVoicePanelController(
            this,
            settings,
            engineCatalog,
            // Ù…Ø¹Ø§ÙŠÙ†Ø© Ø§Ù„Ù…ØªØµÙ„ Ø¨Ø§Ù„Ù„ØºØ© Ø§Ù„Ù…Ø®ØªØ§Ø±Ø© (Ø¹Ø±Ø¨ÙŠ/Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠ). Ø§Ù„Ø¶Ø§Ø¨Ø· ÙŠÙÙ†Ø´Ø£
            // Ù‚Ø¨Ù„ callerSection ÙÙŠÙØ­Ø§Ù„ Ø§Ù„Ù†Ø¯Ø§Ø¡ Ø¥Ù„ÙŠÙ‡ Ù…ØªÙ‰ Ø¬ÙÙ‡Ù‘Ø².
            onPreviewCaller = { langTag ->
                if (::callerSection.isInitialized) {
                    callerSection.previewCaller(langTag)
                }
            }
        ).apply { setup(view) }

        // Ø²Ø± Ø§Ù„Ø­ÙØ¸ Ø§Ù„ØµØ±ÙŠØ­: ÙŠÙƒØªØ¨ Ø§Ù„ÙØ¦Ø© Ø§Ù„Ù…Ø¹Ø±ÙˆØ¶Ø© ÙÙ‚Ø· (Ù„Ø§ ÙƒÙ„ Ø§Ù„ÙØ¦Ø§Øª Ø¯ÙØ¹Ø©),
        // ÙÙŠØ³ØªÙ‚Ø± Ø§Ù„ØµÙˆØª Ø§Ù„Ù…Ø­ÙÙˆØ¸ Ø¨Ù„Ø§ Ø§Ø¹ØªÙ…Ø§Ø¯ Ø¹Ù„Ù‰ Ø§Ù„Ø§Ø³ØªØ¯Ù„Ø§Ù„ Ø§Ù„Ù„ØºÙˆÙŠ.
        val btnSaveCategories:
            com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_save_categories)
        btnSaveCategories.setOnClickListener {
            if (performExplicitVoiceSave()) {
                view.announceCompat(getString(R.string.categories_saved))
                accordion.updateSectionStatuses()
            } else {
                view.announceCompat(
                    getString(R.string.settings_save_failed)
                )
            }
        }

        val btnRefreshVoices = view.findViewById<
            com.google.android.material.button.MaterialButton
        >(R.id.btn_refresh_voices)
        btnRefreshVoices?.setOnClickListener {
            view.announceCompat(getString(R.string.convert_languages_loading))
            Toast.makeText(
                requireContext(),
                R.string.convert_languages_loading,
                Toast.LENGTH_SHORT
            ).show()
            refreshVoiceCatalogDiscovery {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.voices_refreshed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Pronunciation dictionary RecyclerView
        rvPronunciationDict = view.findViewById(R.id.rv_pronunciation_dict)
        rvPronunciationDict.layoutManager = LinearLayoutManager(
            requireContext()
        )
        rvPronunciationDict.adapter = null
        // Ø§Ù„ØªÙ…Ø±ÙŠØ± Ø§Ù„Ø¯Ø§Ø®Ù„ÙŠ Ù…ÙØ¹Ù‘Ù„ Ù„ÙŠØªØ¯Ø­Ø±Ø¬ Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ Ø§Ù„Ù…Ø­Ø¯ÙˆØ¯ Ø§Ù„Ø§Ø±ØªÙØ§Ø¹ Ø¯Ø§Ø®Ù„ Ø§Ù„ØµÙØ­Ø©
        rvPronunciationDict.isNestedScrollingEnabled = true
        refreshDictAdapter()

        // Add dictionary entry button
        val btnAddDictEntry = view.findViewById(
            R.id.btn_add_dict_entry
        ) as com.google.android.material.button.MaterialButton
        btnAddDictEntry.setOnClickListener { showDictEditDialog() }

        btnImportDict = view.findViewById(R.id.btn_import_dict)
        btnImportDict.setOnClickListener {
            runCatching {
                openDictLauncher.launch(jsonOnlyMimeTypes)
            }
        }
        btnExportDict = view.findViewById(R.id.btn_export_dict)
        btnExportDict.setOnClickListener {
            runCatching { createDictLauncher.launch(DICT_EXPORT_FILE_NAME) }
        }
        val btnClearDict = view.findViewById<MaterialButton>(
            R.id.btn_clear_dict
        )
        btnClearDict.setOnClickListener { showClearDictConfirmDialog() }

        val btnDictStandalone = view.findViewById<MaterialButton>(
            R.id.btn_dict_standalone_max_len
        )
        fun updateDictStandaloneButton() {
            val maxLen = runCatching {
                settings.getDictionaryStandaloneMaxLength()
            }.getOrDefault(1)
            btnDictStandalone?.text = getString(
                R.string.dict_standalone_max_length_summary, maxLen
            )
        }
        updateDictStandaloneButton()
        btnDictStandalone?.setOnClickListener {
            val current = runCatching {
                settings.getDictionaryStandaloneMaxLength()
            }.getOrDefault(1)
            val options = arrayOf(
                "0 (Ù…Ø¹Ø·Ù‘Ù„ - ÙÙŠ Ø£ÙŠ Ù…ÙˆØ¶Ø¹)",
                "1 (Ø­Ø±Ù ÙˆØ§Ø­Ø¯ Ø£Ùˆ Ø£Ù‚Ù„ - Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ)",
                "2 (Ø­Ø±ÙØ§Ù† Ø£Ùˆ Ø£Ù‚Ù„)",
                "3 (3 Ø£Ø­Ø±Ù Ø£Ùˆ Ø£Ù‚Ù„)"
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dict_standalone_max_length_title)
                .setSingleChoiceItems(
                    options, current.coerceIn(0, 3)
                ) { dialog, which ->
                    runCatching {
                        settings.setDictionaryStandaloneMaxLength(which)
                    }
                    updateDictStandaloneButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        val switchCaseInsensitive = view.findViewById<SwitchMaterial>(
            R.id.switch_dict_case_insensitive
        )
        switchCaseInsensitive?.isChecked = runCatching {
            settings.isDictionaryCaseInsensitive()
        }.getOrDefault(true)
        switchCaseInsensitive?.setOnCheckedChangeListener { _, isChecked ->
            runCatching {
                settings.setDictionaryCaseInsensitive(isChecked)
                pronunciationDict.invalidate()
            }
        }

        val btnTimeSuffixM = view.findViewById<MaterialButton>(
            R.id.btn_time_suffix_m_mode
        )
        val mModes = listOf(
            SettingsRepository.TIME_SUFFIX_M_CONTEXT to
                getString(R.string.time_suffix_m_context),
            SettingsRepository.TIME_SUFFIX_M_LETTER to
                getString(R.string.time_suffix_m_letter),
            SettingsRepository.TIME_SUFFIX_M_EVENING to
                getString(R.string.time_suffix_m_evening)
        )
        fun updateTimeSuffixMButton() {
            val cur = runCatching {
                settings.getTimeSuffixMMode()
            }.getOrDefault(SettingsRepository.TIME_SUFFIX_M_CONTEXT)
            val label = mModes.find { it.first == cur }?.second
                ?: mModes[0].second
            btnTimeSuffixM?.text = getString(R.string.time_suffix_m_btn, label)
        }
        updateTimeSuffixMButton()
        btnTimeSuffixM?.setOnClickListener {
            val cur = runCatching {
                settings.getTimeSuffixMMode()
            }.getOrDefault(SettingsRepository.TIME_SUFFIX_M_CONTEXT)
            val curIdx = mModes.indexOfFirst { it.first == cur }
                .coerceAtLeast(0)
            val labels = mModes.map { it.second }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.time_suffix_m_title)
                .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                    runCatching {
                        settings.setTimeSuffixMMode(mModes[which].first)
                    }
                    updateTimeSuffixMButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        val btnTestDictText = view.findViewById<MaterialButton>(
            R.id.btn_test_dict_text
        )
        btnTestDictText?.setOnClickListener {
            showDictProcessTestDialog()
        }

        val btnTraceDictText = view.findViewById<MaterialButton>(
            R.id.btn_trace_dict_text
        )
        btnTraceDictText?.setOnClickListener {
            showDictTraceDialog()
        }

        // Ø§Ù„Ù…ÙØªØ§Ø­ Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠ Ù„ÙƒÙ„ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª
        switchAllAnnouncements =
            view.findViewById(R.id.switch_all_announcements)
        switchAllAnnouncements.isChecked =
            runCatching { settings.isAllAnnouncementsEnabled() }
                .getOrDefault(true)
        switchAllAnnouncements.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setAllAnnouncementsEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(requireContext())
                accordion.warnIfNotificationsHidden()
            } else {
                runCatching {
                    requireContext().stopService(
                        Intent(
                            requireContext(),
                            AnnouncementSchedulerService::class.java
                        )
                    )
                }
            }
            accordion.updateSectionStatuses()
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // Ø­Ù…Ø§ÙŠØ© Ø®ØµÙˆØµÙŠØ© Ù‚ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©: Ø­Ø¬Ø¨ ØªÙØ§ØµÙŠÙ„ Ø§Ù„Ø±Ø³Ø§Ø¦Ù„/Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª/Ø§Ù„Ù…ØªØµÙ„
        // Ø¹Ù†Ø¯ Ø§Ù„Ù‚ÙÙ„
        switchLockScreenPrivacy =
            view.findViewById(R.id.switch_lock_screen_privacy)
        switchLockScreenPrivacy.isChecked =
            runCatching { settings.isLockScreenPrivacyEnabled() }
                .getOrDefault(true)
        switchLockScreenPrivacy.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setLockScreenPrivacyEnabled(checked) }
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // Ù†Ø·Ù‚ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠ ÙˆØ±Ù…ÙˆØ² Ø§Ù„Ù…Ø´Ø§Ø¹Ø±: ØªÙÙ†Ø·Ù‚ Ø§Ù„Ø£Ø³Ù…Ø§Ø¡ Ø¨Ø¯Ù„ Ø­Ø°Ù Ø§Ù„Ø±Ù…ÙˆØ² Ù…Ù† Ø§Ù„Ù†Ø·Ù‚
        val switchEmojiReading: SwitchMaterial =
            view.findViewById(R.id.switch_emoji_reading)
        switchEmojiReading.isChecked =
            runCatching { settings.isEmojiPronunciationEnabled() }
                .getOrDefault(true)
        switchEmojiReading.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setEmojiPronunciationEnabled(checked) }
            accordion.updateSectionStatuses()
            view?.announceCompat(
                getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        val btnEmojiLanguage = view.findViewById<MaterialButton>(
            R.id.btn_emoji_language
        )
        fun updateEmojiLanguageButton() {
            val current = runCatching {
                settings.getEmojiPronunciationLanguage()
            }.getOrDefault("AUTO")
            val label = when (current.uppercase()) {
                "AR" -> getString(R.string.emoji_reading_language_arabic)
                "EN" -> getString(R.string.emoji_reading_language_english)
                else -> getString(R.string.emoji_reading_language_auto)
            }
            btnEmojiLanguage?.text = getString(
                R.string.emoji_reading_language_btn, label
            )
        }
        updateEmojiLanguageButton()
        btnEmojiLanguage?.setOnClickListener {
            val current = runCatching {
                settings.getEmojiPronunciationLanguage()
            }.getOrDefault("AUTO")
            val currentIdx = when (current.uppercase()) {
                "AR" -> 1
                "EN" -> 2
                else -> 0
            }
            val options = arrayOf(
                getString(R.string.emoji_reading_language_auto),
                getString(R.string.emoji_reading_language_arabic),
                getString(R.string.emoji_reading_language_english)
            )
            val codes = listOf("AUTO", "AR", "EN")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.emoji_reading_language_title)
                .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                    val chosen = codes[which]
                    runCatching {
                        settings.setEmojiPronunciationLanguage(chosen)
                    }
                    updateEmojiLanguageButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .create().also(::trackDialog).show()
        }

        // Ø²Ø± Ø¬Ø¹Ù„ Ù†Ø§Ø·Ù‚ Ø§Ù„Ù…Ø­Ø±Ùƒ Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ (ÙŠÙØªØ­ Ø´Ø§Ø´Ø© TTS Ø§Ù„Ù†Ø¸Ø§Ù…ÙŠØ© Ù„Ø§Ø®ØªÙŠØ§Ø±Ù‡
        // ÙŠØ¯ÙˆÙŠØ§Ù‹)
        btnSetDefaultEngine = view.findViewById(R.id.btn_set_default_engine)
        btnSetDefaultEngine.setOnClickListener {
            runCatching {
                startActivity(
                    // Ø§Ù„Ø«Ø§Ø¨Øª Ø§Ù„Ø±Ø³Ù…ÙŠ Ù„Ù†Ø§ÙØ°Ø© Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª TTS ØºÙŠØ± Ù…ØªØ§Ø­ ÙÙŠ ÙƒÙ„ Ù…Ø³ØªÙˆÙŠØ§Øª
                    // SDKØŒ Ù„Ø°Ø§ Ù†Ø³ØªØ®Ø¯Ù… Ø§Ù„Ø¥Ø¬Ø±Ø§Ø¡ Ø§Ù„Ù†ØµÙŠ Ø§Ù„Ø«Ø§Ø¨Øª Ù†ÙØ³Ù‡.
                    Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Toast.makeText(
                requireContext(),
                R.string.set_default_engine_hint,
                Toast.LENGTH_LONG
            ).show()
            view?.announceCompat(getString(R.string.set_default_engine_hint))
        }

        // Ù…Ø¹Ø§ÙŠÙ†Ø© Ù†Ø·Ù‚ Ø±Ù‚Ù…
        etNumberPreview = view.findViewById(R.id.et_number_preview)
        btnPreviewNumber = view.findViewById(R.id.btn_preview_number)
        btnStopNumberPreview = view.findViewById(R.id.btn_stop_number_preview)
        btnPreviewNumber.setOnClickListener { previewNumber() }
        btnStopNumberPreview.setOnClickListener {
            announcementSpeaker?.stop()
        }

        // Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ù…ØªØµÙ„ÙŠÙ† Ø§Ù„Ù…Ø®ØµØµØ©
        btnCallerNames = view.findViewById(R.id.btn_caller_names)
        btnCallerNames.setOnClickListener { showCallerNamesDialog() }

        // Ù…ÙØªØ§Ø­ ØªØ¨Ø¯ÙŠÙ„ Ù„ØºØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ (Ø£Ø³ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©)
        btnToggleLanguage = view.findViewById(R.id.btn_toggle_language)

        // Ø²Ø± Ø§Ù„ØªÙˆØ§ØµÙ„ Ù…Ø¹ Ø§Ù„Ù…Ø·ÙˆÙ‘Ø±: ÙŠÙØªØ­ Ø±Ø§Ø¨Ø· Ø§Ù„Ø¯Ø¹Ù… Ø®Ø§Ø±Ø¬ÙŠØ§Ù‹ Ø¯ÙˆÙ† ÙƒØ´Ù ÙˆØ³ÙŠÙ„Ø© Ø§Ù„ØªÙˆØ§ØµÙ„
        btnContactDeveloper = view.findViewById(R.id.btn_contact_developer)
        btnContactDeveloper.setOnClickListener { openDeveloperSupport() }

        // Ø²Ø± Ø§Ù„Ø¥Ø¨Ù„Ø§Øº Ø¹Ù† Ø®Ø·Ø£: Ø§Ù„Ø¶ØºØ·Ø© Ø§Ù„Ø£ÙˆÙ„Ù‰ ØªØ¨Ø¯Ø£ Ù…Ø±Ø§Ù‚Ø¨Ø© Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªÙ‚Ù†ÙŠ Ø«Ù…
        // Â«ØªÙ…Â» ØªØ¬Ù…Ø¹ Ø§Ù„ØªÙ‚Ø±ÙŠØ± Ù…Ù† Ù†Ø§ÙØ°Ø© Ø§Ù„Ø§Ù„ØªÙ‚Ø§Ø· Ø§Ù„Ø­ÙŠØ© ÙˆØªØ´Ø§Ø±ÙƒÙ‡ (Ù…Ø±Ø§Ø¬Ø¹Ø©
        // ØªÙ‚Ø±ÙŠØ± Â«Ø£Ø¨Ùˆ Ø£Ø­Ù…Ø¯Â»: Ø§Ù„ØªÙ‚Ø§Ø· Ù„Ø­Ø¸Ø© Ø§Ù„Ø¶ØºØ· ÙÙ‚Ø· ÙƒØ§Ù† ÙŠÙÙØ±Øº Ø§Ù„ØªÙ‚Ø±ÙŠØ±).
        btnReportError = view.findViewById(R.id.btn_report_error)
        btnReportError.setOnClickListener { onReportErrorClicked() }
        syncReportErrorButtonText()

        // Ø²Ø± Ø¢Ø®Ø± Ø§Ù„ØªØ­Ø¯ÙŠØ«Ø§Øª: ÙŠØ¹Ø±Ø¶ Ù…Ù„Ø®Øµ Ø£Ø­Ø¯Ø« Ø¥ØµØ¯Ø§Ø± ÙˆØªØºÙŠÙŠØ±Ø§ØªÙ‡ ÙÙŠ Ø­ÙˆØ§Ø±
        btnChangelog = view.findViewById(R.id.btn_changelog)
        btnChangelog.setOnClickListener { showChangelogDialog() }

        view.findViewById<View>(R.id.btn_read_later_open)
            .setOnClickListener { showReadLaterDialog() }

        // Ø²Ø± Ø¯Ù„ÙŠÙ„ Ø§Ù„Ù…ÙŠØ²Ø§Øª ÙˆØ§Ù„Ø¥ÙŠÙ…Ø§Ø¡Ø§Øª (Ø¹Ù¤Ù¡-Ø¨14.3): Ø´Ø±Ø­ Ù…Ø®ØªØµØ± ÙŠÙ‚Ø±Ø¤Ù‡ TalkBack
        view.findViewById<View>(R.id.btn_a11y_guide).setOnClickListener {
            showA11yGuideDialog()
        }

        // Ø²Ø± Ø§Ù„ØªØ´Ø®ÙŠØµ: ÙŠØ¹Ø±Ø¶ Ù…Ø¹Ù„ÙˆÙ…Ø§Øª Ø´Ø§Ù…Ù„Ø© Ø¹Ù† Ø­Ø§Ù„Ø© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ ÙˆØ§Ù„Ù…Ø­Ø±ÙƒØ§Øª ÙˆØ§Ù„Ø£Ø°ÙˆÙ†Ø§Øª
        // Ù…Ø¹ Ø¥Ù…ÙƒØ§Ù†ÙŠØ© Ø§Ù„Ù†Ø³Ø® Ø¥Ù„Ù‰ Ø§Ù„Ø­Ø§ÙØ¸Ø©
        val btnDiagnostics = view.findViewById<View>(R.id.btn_diagnostics)
        btnDiagnostics.setOnClickListener { showDiagnosticsDialog() }

        // Ø¥Ù†Ø´Ø§Ø¡ Ø¶Ø§Ø¨Ø·Ø§Øª Ø§Ù„Ø£Ù‚Ø³Ø§Ù… ÙˆØ±Ø¨Ø·Ù‡Ø§ (Ø§Ù„Ù…Ø±Ø­Ù„Ø© Ø¬): ÙƒÙ„ Ø¶Ø§Ø¨Ø· ÙŠØ³Ø­Ø¨ Ø¹Ù†Ø§ØµØ±Ù‡
        // ÙˆÙŠØ¨Ù†ÙŠ Ù…Ø³ØªÙ…Ø¹ÙŠÙ‡ Ø¹Ù†Ø¯ setup()ØŒ ÙˆonStatusChanged ØªÙØ­Ø¯Ù‘Ø« Ø£Ø³Ø·Ø± Ø­Ø§Ù„Ø©
        // Ø§Ù„Ø£ÙƒÙˆØ±Ø¯ÙŠÙˆÙ†.
        timeSection = TimeAnnouncementController(
            this, settings, nateqVoices,
            { accordion.navigateToSection(R.id.ll_oem_guidance_content) },
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        batterySection = BatteryAnnouncementController(
            this, settings,
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        notificationSection = NotificationReadingController(
            this, settings, nateqVoices, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        callerSection = CallerAnnouncementController(
            this, settings, engineCatalog,
            onStatusChanged = { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        smsSection = SmsReadingController(
            this, settings, engineCatalog,
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        generalSection = GeneralSettingsController(
            this, settings, voicePreview,
            { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        numberSection = NumberReadingController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        deviceHealthSection = DeviceHealthController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        textReadingSection = TextReadingController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        textProcessingSection = TextProcessingController(
            settings, requireContext(), { accordion.updateSectionStatuses() }
        ).apply { bind(view) }
        instantSilenceSection = InstantSilenceController(
            this, settings, { accordion.updateSectionStatuses() }
        ).apply { setup(view) }
        a11yServicesSection = ScreenServicesCardController(
            requireContext(), settings
        ) { accordion.updateSectionStatuses() }.apply {
            contactPickerLauncher = { callback ->
                pendingContactPickerCallback = callback
                val pickIntent = Intent(
                    Intent.ACTION_PICK,
                    android.provider.ContactsContract.CommonDataKinds
                        .Phone.CONTENT_URI
                )
                this@VoiceSelectionFragment.contactPickerLauncher
                    .launch(pickIntent)
            }
            setup(view)
        }
        embeddedDownloaderSection = EmbeddedEngineDownloaderController(
            this, settings, voicePreview
        ).apply {
            onStatusChanged = { accordion.updateSectionStatuses() }
            setup(view)
        }
        accordion.embeddedDownloaderStatusSupplier = {
            embeddedDownloaderSection.buildStatusSummary()
        }
        oemGuidance = OemGuidanceController(this).apply { setup(view) }

        view.findViewById<View>(R.id.btn_emergency_voice)?.setOnClickListener {
            EmergencyVoiceController.show(
                requireContext(),
                settings,
                engineCatalog,
                voicePreview
            )
        }
        EngineFilterController(
            requireContext(),
            settings
        ) {
            accordion.updateSectionStatuses()
            refreshVoiceCatalogDiscovery {
                if (::categoryVoicePanel.isInitialized) {
                    categoryVoicePanel.refreshCategoryEngines()
                    categoryVoicePanel.refreshLanguages()
                }
                if (::callerSection.isInitialized) {
                    callerSection.refreshCallerVoices()
                }
                if (::smsSection.isInitialized) {
                    smsSection.refreshSmsVoices()
                }
            }
        }.setup(view)
        setupLanguageToggle()
        setupCheckUpdates()
        engineSection.setupAutoConvertUI(view)
        setupSaveAndResetButtons()
        setupBackupRestoreButtons()
        setupToolsAppShortcuts()
        accordion.updateSectionStatuses()
        // Ø¨Ù†Ø¯ 4.6: Ø¥Ø¹Ø§Ø¯Ø© ÙØªØ­ Ø§Ù„Ø´Ø§Ø´Ø© (Ù…Ø¬Ù…ÙˆØ¹Ø©/Ù‚Ø³Ù…) Ø§Ù„ØªÙŠ ÙƒØ§Ù† ÙŠØ¹Ø¯Ù‘Ù„Ù‡Ø§ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…
        // Ù‚Ø¨Ù„ ØªØ¯ÙˆÙŠØ± Ø§Ù„Ø¬Ù‡Ø§Ø² Ø¨Ø¯Ù„ Ø·Ø±Ø¯ÙÙ‡ Ø¥Ù„Ù‰ Ø§Ù„ØµÙØ­Ø© Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©.
        if (savedInstanceState != null) {
            accordion.restoreState(savedInstanceState)
            if (::timeSection.isInitialized) {
                timeSection.restoreInstanceState(savedInstanceState)
            }
        }

        // Ø§Ù„ØªØ­Ø¯ÙŠØ« Ø§Ù„ØªÙØ§Ø¹Ù„ÙŠ: Ø§Ù„Ù…Ø±Ø§Ø¬Ø¹Ø© Ø§Ù„Ø§Ø¨ØªØ¯Ø§Ø¦ÙŠØ© (0) Ù„Ø§ ØªÙØ­Ø¯Ù‘Ø« Ø´ÙŠØ¦Ø§Ù‹ØŒ
        // ÙˆØ£ÙŠ Ù…Ø±Ø§Ø¬Ø¹Ø© Ù„Ø§Ø­Ù‚Ø© (Ø§Ø³ØªØ¹Ø§Ø¯Ø©/Ø¥Ø¹Ø§Ø¯Ø© Ø¶Ø¨Ø·) ØªÙØ¹ÙŠØ¯ Ø¨Ù†Ø§Ø¡ ÙƒÙ„ Ø£Ù‚Ø³Ø§Ù…
        // Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© ØªÙ„Ù‚Ø§Ø¦ÙŠØ§Ù‹.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.settingsRevision.collect { revision ->
                        if (revision > 0) refreshAllSettingsUi()
                    }
                }
                launch {
                    vm.operationEvents.collect(::handleOperationEvent)
                }
            }
        }

            // ÙØ­Øµ ØªÙ„Ù‚Ø§Ø¦ÙŠ Ø¹Ù†Ø¯ ÙØªØ­ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚: ÙŠÙÙ†Ø¨Ù‡ Ø¨ÙˆØ¬ÙˆØ¯ ØªØ­Ø¯ÙŠØ« (ØµØ§Ù…Øª Ø¥Ù† Ù„Ù… ÙŠÙˆØ¬Ø¯)
            checkForUpdatesOnStart()
            VoiceReadinessCoordinator.prepareActiveVoices(requireContext())
        } finally {
            Trace.endSection()
        }
    }

    /** Ø¨Ù†Ø¯ 4.7: Ø¹Ù†Ø¯ Ø§Ù„Ø¹ÙˆØ¯Ø© Ù…Ù† Ø´Ø§Ø´Ø§Øª Ø§Ù„Ù†Ø¸Ø§Ù… (Ù…Ù†Ø­ Ø¥Ø°Ù† Ø§Ù„Ù…Ù†Ø¨Ù‡Ø§Øª Ø§Ù„Ø¯Ù‚ÙŠÙ‚Ø© /
     *  Ø¥Ø´Ø¹Ø§Ø±Ø§Øª) ØªÙØ¹Ø§Ø¯ Ø­Ø§Ù„Ø© ØµÙÙˆÙ Ø·Ù„Ø¨ Ø§Ù„Ø¥Ø°Ù† ÙˆÙ…ÙÙ„Ø®Ù‘ØµØ§Øª Ø§Ù„Ø£Ù‚Ø³Ø§Ù… ÙÙˆØ±Ø§Ù‹ Ø¨Ø¯Ù„
     *  Ø§Ù„Ø§Ù†ØªØ¸Ø§Ø± Ø­ØªÙ‰ ÙŠØ¹ÙˆØ¯ Ø§Ù„ÙØµÙŠÙ„ Ù…Ù† Ø§Ù„Ø®Ù„ÙÙŠØ©. */
    override fun onResume() {
        super.onResume()
        if (::timeSection.isInitialized) timeSection.refreshExactAlarmRow()
        if (::oemGuidance.isInitialized) {
            oemGuidance.refreshInstantIdentityStatus()
        }
        if (::a11yServicesSection.isInitialized) {
            a11yServicesSection.updateUiState()
        }
        if (::accordion.isInitialized) accordion.updateSectionStatuses()
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.onResume()
        }
        refreshEmbeddedUiIfStorageChanged()
        ensureVoiceCatalogDiscovered()
        resumeUpdateDownloadIfNeeded()
        refreshToolsShortcuts()
    }

    /**
     * Ø¹Ù¢Ù©-9: Ø¹Ù†Ø¯ Ø¹ÙˆØ¯Ø© Ø§Ù„Ø´Ø§Ø´Ø© Ù„Ù„Ù…Ù‚Ø¯Ù…Ø©ØŒ Ø¥Ù† ØªØºÙŠÙ‘Ø± Ø±Ù‚Ù… Ø¥ØµØ¯Ø§Ø± Ø§Ù„ØªØ®Ø²ÙŠÙ†
     * (ØªÙ†Ø²ÙŠÙ„/Ø­Ø°Ù/Ù‡Ø¬Ø±Ø© Ø­Ø¯Ø«Øª ÙˆÙ‡ÙŠ ÙÙŠ Ø§Ù„Ø®Ù„ÙÙŠØ©) ÙŠÙØ¨Ø·Ù„ Ø§Ù„ÙƒØ§Ø´ ÙˆØªÙØ¹Ø§Ø¯ Ù‚ÙˆØ§Ø¦Ù…
     * Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª ÙÙˆØ±Ø§Ù‹ Ø¨Ø¯Ù„ Ø§Ù†ØªØ¸Ø§Ø± Ù…Ù‡Ù„Ø© Ø§Ù„ÙƒØ§Ø´.
     */
    internal fun refreshEmbeddedUiIfStorageChanged() {
        val version = EmbeddedStorage.version
        if (version == lastSeenStorageVersion) return
        val isFirstRead = lastSeenStorageVersion == -1
        lastSeenStorageVersion = version
        if (isFirstRead) return

        EnginePicker.invalidateAfterStorageChange()
        if (::engineSection.isInitialized) {
            engineSection.setupEngineDiscovery(
                viewLifecycleOwner.lifecycleScope
            )
        }
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.refreshCategoryEngines()
        }
        if (::accordion.isInitialized) accordion.updateSectionStatuses()
        view?.let { root ->
            if (::embeddedDownloaderSection.isInitialized) {
                embeddedDownloaderSection.refresh(root)
            }
        }
        refreshVoiceCatalogDiscovery()
    }

    /** Ø¨Ù†Ø¯ 4.6: Ø­ÙØ¸ Ù…ÙˆØ¶Ø¹ Ø§Ù„ØªÙ†Ù‚Ù‘Ù„ ÙÙŠ Ø§Ù„Ø£ÙƒÙˆØ±Ø¯ÙŠÙˆÙ† Ù„Ø§Ø³ØªØ¹Ø§Ø¯ØªÙ‡ Ø¨Ø¹Ø¯ Ø§Ù„ØªØ¯ÙˆÙŠØ±. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::accordion.isInitialized) {
            outState.putAll(accordion.saveState())
        }
        if (::timeSection.isInitialized) {
            timeSection.saveInstanceState(outState)
        }
    }

    override fun onDestroyView() {
        // ØªÙØ±ÙŠØº Ù…Ø³Ø§Ù†Ø¯ Ø§Ù„Ù‚ÙˆØ§Ø¦Ù… Ù„Ù…Ù†Ø¹ ØªØ³Ø±ÙŠØ¨ Ø§Ù„Ù…Ø±Ø§Ø¬Ø¹ ÙˆØ§Ù„ÙˆØ§Ø¬Ù‡Ø§Øª
        if (::rvPronunciationDict.isInitialized) {
            rvPronunciationDict.adapter = null
        }
        pronunciationDictAdapter = null

        // Ø¥ØºÙ„Ø§Ù‚ Ø§Ù„Ù…ØªØ­Ø¯Ø« Ø§Ù„Ù…Ø³ØªÙ‚Ù„ Ø§Ù„Ø®Ø§Øµ Ø¨Ø§Ù„Ù…Ø¹Ø§ÙŠÙ†Ø© (Ø¥Ù† Ø£ÙÙ†Ø´Ø¦) Ø­ØªÙ‰ Ù„Ø§ ÙŠØ¨Ù‚Ù‰ Ù…Ø­Ø±Ùƒ
        // TTS Ù…ÙØªÙˆØ­Ø§Ù‹ Ø¨Ø¹Ø¯ Ù…ØºØ§Ø¯Ø±Ø© Ø§Ù„Ø´Ø§Ø´Ø©. Ø§Ù„Ù…Ø«ÙŠÙ„ Ù‡Ù†Ø§ Ø®Ø§Øµ Ø¨Ø§Ù„Ø´Ø§Ø´Ø© ÙˆÙ„ÙŠØ³ Ø§Ù„Ù…Ø´ØªØ±Ùƒ
        // (getInstance) Ø§Ù„Ø°ÙŠ ØªÙØ¯Ø§Ø± Ø­ÙŠØ§ØªÙ‡ ÙÙŠ Ù…Ø³ØªÙ‚Ø¨Ù„Ø§Øª Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠØ©.
        // **Ø¨Ù†Ø¯ 4.4:** shutdown() Ù„Ø§ stop() â€” Ø§Ù„Ù…Ø¹Ø§ÙŠÙ†Ø© Ø§Ù„Ø®Ø§ØµØ© ØªÙØºÙ„ÙŽÙ‚ ØªØ§Ù…Ø§Ù‹
        // (Ù…Ø­Ø±Ùƒ TTS Ù†ÙØ³Ù‡) Ø¹Ù†Ø¯ Ù…ØºØ§Ø¯Ø±Ø© Ø§Ù„Ø´Ø§Ø´Ø© Ø¨Ø¯Ù„ Ø¨Ù‚Ø§Ø¦Ù‡Ø§ Ù‚ÙŠØ¯ Ø§Ù„Ø­ÙŠØ§Ø© Ø­ØªÙ‰
        // ØªØ­Ø±Ø± Ø§Ù„Ù…Ø­Ø±Ùƒ ÙˆØªØªÙˆÙ‚Ù ØªØ³Ø±ÙŠØ¨Ø§Øª Ù…Ø¤Ù‚ØªØ§Øª Ø§Ù„Ø§Ø³ØªØ±Ø¯Ø§Ø¯.
        announcementSpeaker?.shutdown()
        announcementSpeaker = null
        // Ø¥ØºÙ„Ø§Ù‚ Ù…Ø¹Ø§ÙŠÙ†Ø© Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„Ù…Ø´ØªØ±ÙƒØ© (Ø¨Ù†Ø¯ 4.2) â€” Ø£ÙŠ Ù…Ø­Ø±Ùƒ TTS Ù…Ø¤Ù‚Øª ÙÙØªØ­
        // Ù„Ù„Ù…Ø¹Ø§ÙŠÙ†Ø© ÙŠÙØºÙ„Ù‚ ØªÙ…Ø§Ù…Ø§Ù‹ Ø¹Ù†Ø¯ Ù…ØºØ§Ø¯Ø±Ø© Ø§Ù„Ø´Ø§Ø´Ø©.
        if (::voicePreview.isInitialized) voicePreview.release()
        if (::oemGuidance.isInitialized) oemGuidance.release()
        // Ø¥ØºÙ„Ø§Ù‚ ÙƒÙ„ Ø§Ù„Ù†ÙˆØ§ÙØ° Ø§Ù„Ù…ÙØªÙˆØ­Ø© Ø­ØªÙ‰ Ù„Ø§ ØªØªØ³Ø±Ø¨ Ù…Ø±Ø§Ø¬Ø¹ Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© (WindowLeaked)
        // Ø¹Ù†Ø¯ ØªØ¯ÙˆÙŠØ± Ø§Ù„Ø´Ø§Ø´Ø© Ø£Ùˆ Ù…ØºØ§Ø¯Ø±ØªÙ‡Ø§.
        val open = activeDialogs.toList()
        activeDialogs.clear()
        open.forEach { runCatching { it.dismiss() } }
        // ØªØµÙÙŠØ± Ù…Ø±Ø§Ø¬Ø¹ Ø§Ù„Ø¹Ø±Ø¶ ÙÙŠ ÙƒÙ„ Ø§Ù„Ø¶ÙˆØ§Ø¨Ø· (Ø¨Ù†Ø¯ 4.1) ÙˆØ­ØªÙ‰ Ù„Ø§ ØªØ¨Ù‚Ù‰ Ø´Ø¬Ø±Ø© Ø§Ù„Ø¹Ø±Ø¶
        // Ø§Ù„Ù‚Ø¯ÙŠÙ…Ø© Ù…Ø­ØªØ¬Ø²Ø© Ø¹Ø¨Ø± Ø£ÙŠ Ø¶Ø§Ø¨Ø· Ø¨Ø¹Ø¯ ØªØ¯ÙˆÙŠØ± Ø§Ù„Ø´Ø§Ø´Ø© Ø£Ùˆ Ù…ØºØ§Ø¯Ø±ØªÙ‡Ø§.
        if (::categoryVoicePanel.isInitialized) categoryVoicePanel.cleanup()
        if (::engineSection.isInitialized) engineSection.cleanup()
        if (::timeSection.isInitialized) timeSection.cleanup()
        if (::batterySection.isInitialized) batterySection.cleanup()
        if (::notificationSection.isInitialized) notificationSection.cleanup()
        if (::callerSection.isInitialized) callerSection.cleanup()
        if (::smsSection.isInitialized) smsSection.cleanup()
        if (::generalSection.isInitialized) generalSection.cleanup()
        if (::numberSection.isInitialized) numberSection.cleanup()
        if (::deviceHealthSection.isInitialized) deviceHealthSection.cleanup()
        if (::textReadingSection.isInitialized) textReadingSection.cleanup()
        if (::instantSilenceSection.isInitialized) {
            instantSilenceSection.cleanup()
        }
        if (::embeddedDownloaderSection.isInitialized) {
            embeddedDownloaderSection.detach()
        }
        updateReceiver?.let { receiver ->
            runCatching {
                requireContext().applicationContext
                    .unregisterReceiver(receiver)
            }
            updateReceiver = null
        }
        if (::accordion.isInitialized) accordion.cleanup()
        catalogDiscoveryJob?.cancel()
        catalogDiscoveryJob = null
        updatePollingJob?.cancel()
        updatePollingJob = null
        updateProgressDialog = null
        super.onDestroyView()
    }

    /**
     * Ø¹Ù¤Ù -2: Ø§ÙƒØªØ´Ø§Ù Ù…ØªØ¯Ø±Ø¬ ÙˆÙ…Ø­Ø¯Ø« Ù„Ù„Ù…Ø­Ø±ÙƒØ§Øª ÙˆØ§Ù„Ø£ØµÙˆØ§Øª Ø¨ØªØ²Ø§Ù…Ù† ÙˆØ§Ø­Ø¯:
     * ÙŠÙ„ØºÙŠ Ø£ÙŠ Ø¹Ù…Ù„ÙŠØ© Ø¬Ø§Ø±ÙŠØ© Ø³Ø§Ø¨Ù‚Ø© Ø­ØªÙ‰ Ù„Ø§ ØªØªØ³Ø§Ø¨Ù‚ Ù†ØªÙŠØ¬ØªØ§Ù†ØŒ ÙˆÙŠØ³Ø¬Ù„ Ø§Ù„Ø£Ø®Ø·Ø§Ø¡
     * Ø¯ÙˆÙ† Ø§Ø¨ØªÙ„Ø§Ø¹Ù‡Ø§ØŒ ÙˆÙŠÙ…Ø±Ø± unavailableEngines Ø¥Ù„Ù‰ EngineVoicesCatalog.
     */
    internal fun buildActiveEnginesSet(): Set<String>? {
        if (!VisibleEngines.isFilterEnabled()) {
            return null
        }
        // [Ø¨Ù†Ø§Ø¡ 10728] Ù„Ø§ ÙŠÙØ¶Ø§Ù Ù…Ø­Ø±Ùƒ Ø£Ø®ÙØ§Ù‡ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…: ÙƒÙ„ Ø¥Ø¶Ø§ÙØ© ØªÙ…Ø±Ù‘ Ø¹Ù„Ù‰
        // VisibleEngines.isVisibleØŒ ÙˆØ¥Ù„Ø§ Ø¹Ø§Ø¯ Ø§Ù„Ù…Ø­Ø±Ùƒ Ø§Ù„Ù…Ø®ÙÙŠ Ø¥Ù„Ù‰ Ø§Ù„Ø§ÙƒØªØ´Ø§Ù
        // Ø¹Ø¨Ø± ØªÙØ¶ÙŠÙ„Ø§Øª Ø§Ù„Ù„ØºØ© Ø£Ùˆ Ø§Ù„ÙØ¦Ø§Øª.
        val candidates = mutableListOf<String?>(
            settings.getEnginePreferenceForLanguage("ar").engine,
            // Â«Ø¹Ù¤Ù -2Â»: ØªÙØ¶ÙŠÙ„ Ø§Ù„Ù„ØºØ© Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ© (Ø§Ù„Ø®Ø±ÙŠØ·Ø© Ø§Ù„Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ©)
            // ÙŠØ´Ø§Ø±Ùƒ ÙÙŠ Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù†Ø´Ø·Ø©
            settings.getEnginePreferenceForLanguage("en").engine
        )

        val categories = SettingsRepository.ALL_VOICE_CATEGORIES +
            listOf("emoji")
        for (cat in categories) {
            candidates.add(settings.getEngineForCategory(cat))
        }
        for (lang in listOf("ar", "en")) {
            candidates.add(settings.getEngineForLanguage(lang))
        }
        return activeEnginesOrNull(
            allowed = VisibleEngines.visibleSet(),
            candidates = candidates,
            isVisible = VisibleEngines::isVisible
        )
    }

    /** Ø¹Ù¤Ù -2: ÙƒØ´Ù Ø¹Ø¯Ù… Ø§ØªØ³Ø§Ù‚ Ø£Ø²ÙˆØ§Ø¬ (Ù…Ø­Ø±ÙƒØŒ ØµÙˆØª) ÙÙŠ Ù„ØºØ§Øª Ù†Ù‚Ø·Ø© Ø§Ù„Ø¯Ø®ÙˆÙ„. */
    internal fun checkEntrypointVoicesConsistency():
        List<Triple<String, String, String>> {
        val entries = listOf("ar", "en").map { lang ->
            val prefs = settings.getEnginePreferenceForLanguage(lang)
            Triple(lang, prefs.engine, prefs.voiceName)
        }
        return EngineLanguageConsistency.mismatchedLanguages(entries)
    }

    internal fun checkMismatchedCategoryVoices():
        List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val categories = SettingsRepository.ALL_VOICE_CATEGORIES +
            listOf("emoji")
        for (cat in categories) {
            val eng = settings.getEngineForCategory(cat)
            val voice = settings.getPreferredVoiceIdForCategory(cat)
            if (!eng.isNullOrBlank() && !voice.isNullOrBlank()) {
                if (!VoiceIdContract.isVoiceBelongingToEngine(eng, voice)) {
                    result.add(Triple(cat, voice, eng))
                }
            }
        }
        return result
    }

    internal fun checkAndPromptVoiceMismatchFix() {
        if (!isAdded) return
        val mismatched = checkMismatchedCategoryVoices()
        if (mismatched.isEmpty()) return
        val msgList = mismatched.joinToString("\n") { (cat, voice, eng) ->
            "ÙØ¦Ø© $cat: Ø§Ù„ØµÙˆØª $voice Ù„Ø§ ÙŠØªØ¨Ø¹ Ø§Ù„Ù…Ø­Ø±Ùƒ $eng"
        }
        val announcement = "ØªÙ†Ø¨ÙŠÙ‡: " + mismatched.joinToString("ØŒ ") {
            (cat, voice, eng) -> "ÙØ¦Ø© $cat Ø§Ù„ØµÙˆØª $voice Ù„Ø§ ÙŠØªØ¨Ø¹ Ø§Ù„Ù…Ø­Ø±Ùƒ $eng"
        }
        A11ySpeechCoordinator.speakAnnouncement(
            requireContext(),
            announcement
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_mismatch_title)
            .setMessage(msgList)
            .setPositiveButton(R.string.voice_mismatch_fix) { _, _ ->
                for ((cat, _, _) in mismatched) {
                    settings.setPreferredVoiceIdForCategory(cat, "")
                }
                Toast.makeText(
                    requireContext(),
                    R.string.voice_mismatch_fixed_toast,
                    Toast.LENGTH_LONG
                ).show()
                refreshVoiceCatalogDiscovery()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun refreshVoiceCatalogDiscovery(
        onComplete: (() -> Unit)? = null
    ) {
        catalogDiscoveryJob?.cancel()
        catalogDiscoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val active = buildActiveEnginesSet()
            val discovery = withContext(AppDispatchers.io) {
                try {
                    VoiceCatalog.discoverAllLanguagesAcrossEngines(
                        requireContext(),
                        active
                    )
                } catch (t: Throwable) {
                    Log.w(
                        "VoiceSelectionFragment",
                        "Voice catalog discovery failed: ${t.message}",
                        t
                    )
                    DiscoveryResult(
                        languages = emptyMap<String, List<EngineWithVoices>>(),
                        unavailableEngines = listOf(
                            UnavailableEngine(
                                enginePackage = "all",
                                engineLabel = "Discovery Error",
                                message = t.localizedMessage ?: t.message
                            )
                        )
                    )
                }
            }
            val unavailMap = discovery.unavailableEngines.associate { row ->
                row.enginePackage to (row.message ?: row.code ?: "ØºÙŠØ± Ù…ØªØ§Ø­")
            }
            val rows = discovery.languages.mapValues { (_, engines) ->
                engines.map { row ->
                    EngineVoicesRow(
                        row.enginePackage,
                        row.engineLabel,
                        row.voices.map { voice ->
                            VoiceOption(
                                voice.name,
                                voice.name,
                                row.enginePackage
                            )
                        }
                    )
                }
            }
            engineCatalog.update(rows, unavailMap)
            if (!isAdded) return@launch
            if (::categoryVoicePanel.isInitialized) {
                categoryVoicePanel.refreshCategoryEngines()
                categoryVoicePanel.refreshLanguages()
            }
            if (::smsSection.isInitialized) smsSection.refreshSmsVoices()
            if (::callerSection.isInitialized) {
                callerSection.refreshCallerEngines()
                callerSection.refreshCallerVoices()
            }
            checkAndPromptVoiceMismatchFix()
            onComplete?.invoke()
        }
    }

    internal fun refreshCategoryEngines() {
        if (::categoryVoicePanel.isInitialized) {
            categoryVoicePanel.refreshCategoryEngines()
        }
    }

    internal fun ensureVoiceCatalogDiscovered() {
        if (!::engineCatalog.isInitialized ||
            !engineCatalog.hasDiscoveredData()
        ) {
            refreshVoiceCatalogDiscovery()
        }
    }

    /** ÙŠØ¹Ø§Ù„Ø¬ Ø£Ø­Ø¯Ø§Ø« Ø§Ù„Ø¹Ù…Ù„ÙŠØ§Øª ØºÙŠØ± Ø§Ù„Ù…ØªØ²Ø§Ù…Ù†Ø© Ø§Ù„Ù‚Ø§Ø¯Ù…Ø© Ù…Ù† Ø§Ù„ÙÙŠ Ø¥Ù… (ØªØ¬Ø±ÙŠ ÙƒÙ„Ù‡Ø§ Ø¹Ù„Ù‰
     *  Ø®ÙŠØ· IO ÙÙŠ Ø§Ù„ÙÙŠ Ø¥Ù…): ÙŠØ¹Ø±Ø¶ Ø§Ù„Ù†ØªÙŠØ¬Ø© ÙˆÙŠØ¹ÙŠØ¯ Ø±Ø³ÙƒÙ„Ø© Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ Ø¹Ù†Ø¯ Ø§Ù„Ø­Ø§Ø¬Ø©. */
    private fun handleOperationEvent(event: SettingsOperation) {
        val stats = when (event) {
            is SettingsOperation.DictImported -> {
                if (event.ok) refreshDictAdapter()
                if (event.ok) R.string.dict_imported_ok
                else R.string.dict_import_failed
            }
            is SettingsOperation.DictExported -> if (event.ok)
                R.string.dict_exported_ok else R.string.dict_export_failed
            is SettingsOperation.BackedUp -> if (event.ok)
                R.string.backup_saved else R.string.backup_failed
            is SettingsOperation.Restored -> {
                if (event.ok) {
                    // Ø¥Ø¹Ø§Ø¯Ø© Ø¹Ø±Ø¶ Ø§Ù„Ø£Ù‚Ø³Ø§Ù… ØªØªÙ… ØªÙ„Ù‚Ø§Ø¦ÙŠØ§Ù‹
                    // Ø¹Ø¨Ø± Ù…Ø±Ø§Ø¬Ø¹Ø© settingsRevision
                    AnnouncementSchedulerService.requestStart(requireContext())
                    if (event.callersOnly) R.string.restore_done_callers_only
                    else R.string.restore_done
                } else {
                    R.string.restore_failed
                }
            }
        }
        val duration = if (event is SettingsOperation.Restored)
            Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ToastDebouncer.show(requireContext(), stats, duration)
        view?.announceCompat(getString(stats))
    }

    // ===== Ø§Ø®ØªÙŠØ§Ø± Ø§Ù„Ù…Ø­Ø±Ùƒ ÙˆØ­ÙˆØ§Ø± Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ: Ø§Ù†ØªÙ‚Ù„Ø§ Ø¥Ù„Ù‰
    // EngineSectionController =====
    /** Ù†Ø³Ø®Ø© Ø­ØµØ±ÙŠØ© Ù„Ù„ÙØµÙŠÙ„ Ù…Ù† Ù…Ø­Ù„Ù‘Ù„ Ø§Ù„Ù€ Spinner Ø§Ù„Ø£Ø³Ø§Ø³ÙŠ (Ø§Ù„Ù…Ù†ÙÙŽÙ‘Ø° ÙÙŠ
     *  EngineSectionController). */
    internal fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        simpleAdapter(requireContext(), items)

    /** Ù…Ø­ÙˆÙ‘Ù„ Ù‚Ø§Ø¦Ù…Ø© Ø¥Ø¯Ø®Ø§Ù„Ø§Øª Ø§Ù„Ù‚Ø§Ù…ÙˆØ³ â€” ÙŠÙØ¹Ø§Ø¯ Ø¥Ù†Ø´Ø§Ø¤Ù‡ Ø¹Ù†Ø¯ ÙƒÙ„ ØªØ­Ø¯ÙŠØ«
     *  (Ø¥Ø¶Ø§ÙØ©/ØªØ¹Ø¯ÙŠÙ„/Ø­Ø°Ù/Ø§Ø³ØªÙŠØ±Ø§Ø¯) â€” ÙŠØªØ¬Ù†Ø¨ Ø®Ø·Ø£ submitList Ø¹Ù„Ù‰ Ù†ÙØ³ Ù…Ø±Ø¬Ø¹ Ø§Ù„Ù‚Ø§Ø¦Ù…Ø©. */
    internal var pronunciationDictAdapter: PronunciationDictAdapter? = null

    internal fun openDeveloperSupport() {
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(DEVELOPER_SUPPORT_URL)
                )
            )
        }.onFailure {
            Toast.makeText(
                requireContext(),
                R.string.contact_developer_no_handler,
                Toast.LENGTH_SHORT
            ).show()
            view?.announceCompat(
                getString(R.string.contact_developer_no_handler)
            )
        }
    }

    // ===== Ø¢Ø®Ø± Ø§Ù„ØªØ­Ø¯ÙŠØ«Ø§Øª / Ø¯Ù„ÙŠÙ„ Ø§Ù„ÙˆØµÙˆÙ„ÙŠØ© (Ù…ÙÙˆÙ‘Ø¶) =====
    internal val infoDialogs by lazy { SettingsInfoDialogsController(this) }

    // ===== اقرأ لاحقاً =====
    fun openReadLater() {
        accordion.openGroupByIndex(2)
        showReadLaterDialog()
    }

    private fun showReadLaterDialog() {
        val items = runCatching { settings.getReadLaterItems() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) {
            val emptyDialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext()
                )
                    .setTitle(R.string.read_later_title)
                    .setMessage(R.string.read_later_empty)
                    .setPositiveButton(R.string.read_later_back, null)
                    .setOnDismissListener {
                        view?.findViewById<View>(R.id.btn_read_later_open)
                            ?.requestFocus()
                    }
                    .create()
            trackDialog(emptyDialog)
            emptyDialog.show()
            return
        }
        val labels = items.map { item ->
            val preview = item.text.replace("\n", " ").trim()
            if (preview.length > 80) {
                preview.take(80) + "…"
            } else {
                preview
            }
        }.toTypedArray()

        val listDialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext()
            )
                .setTitle(R.string.read_later_title)
                .setItems(labels) { _, which ->
                    val item = items.getOrNull(which) ?: return@setItems
                    showReadLaterItemActions(item)
                }
                .setNeutralButton(R.string.read_later_clear_all) { _, _ ->
                    runCatching { settings.clearReadLater() }
                    showReadLaterDialog()
                }
                .setNegativeButton(R.string.read_later_back, null)
                .setOnDismissListener {
                    view?.findViewById<View>(R.id.btn_read_later_open)
                        ?.requestFocus()
                }
                .create()
        trackDialog(listDialog)
        listDialog.show()
    }

    private fun showReadLaterItemActions(
        item: com.aymankhattab.nateq.core.data.SettingsRepository
            .ReadLaterItem
    ) {
        val options = arrayOf(
            getString(R.string.read_later_play),
            getString(R.string.read_later_stop),
            getString(R.string.read_later_delete)
        )
        val actionsDialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext()
            )
                .setTitle(R.string.read_later_item_actions_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            A11ySpeechCoordinator.speakAnnouncement(
                                context = requireContext(),
                                text = item.text,
                                isInterruptAllowed = true,
                                source = com.aymankhattab.nateq.core.audio
                                    .engine.SpeechEntryPoints.Source
                                    .SCREEN_READER
                            )
                        }
                        1 -> {
                            runCatching {
                                com.aymankhattab.nateq.core.audio
                                    .announcement.AnnouncementSpeaker
                                    .getInstance(requireContext())
                                    .stop()
                            }
                        }
                        2 -> {
                            runCatching {
                                settings.removeReadLaterItem(item.id)
                            }
                            showReadLaterDialog()
                        }
                    }
                }
                .setNegativeButton(R.string.read_later_back) { _, _ ->
                    showReadLaterDialog()
                }
                .create()
        trackDialog(actionsDialog)
        actionsDialog.show()
    }

    internal fun showChangelogDialog() = infoDialogs.showChangelogDialog()

    internal fun showA11yGuideDialog() = infoDialogs.showA11yGuideDialog()




    // Ù…Ø³Ø§Ø¹Ø¯Ø§Øª Ø§Ù„ØªØ´Ø®ÙŠØµ (dirSize/formatSize/maskRegistration ÙˆÙˆØµÙ Ø§Ù„Ù…Ø­Ø±Ùƒ)
    // ÙÙØµÙ„Øª Ø¥Ù„Ù‰ VoiceSelectionDiagnostics.kt ÙÙŠ Ø§Ù„Ø­Ø²Ù…Ø© Ù†ÙØ³Ù‡Ø§ØŒ ÙØªØ¨Ù‚Ù‰
    // Ø§Ù„Ø§Ø³ØªØ¯Ø¹Ø§Ø¡Ø§Øª Ø§Ù„Ù‚Ø¯ÙŠÙ…Ø© Ù‡Ù†Ø§ ØªØ¹Ù…Ù„ Ø¨Ù„Ø§ ØªØºÙŠÙŠØ± Ø³Ù„ÙˆÙƒ.
}

/**
 * [Ø¨Ù†Ø§Ø¡ 10728] Ø¨Ù†Ø§Ø¡ Ù…Ø¬Ù…ÙˆØ¹Ø© Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù†Ø´Ø·Ø© Ù„Ù„Ø§ÙƒØªØ´Ø§Ù: ÙƒÙ„ Ù…Ø­Ø±Ùƒ (Ù…Ø³Ù…ÙˆØ­ Ø£Ùˆ
 * Ù…Ø±Ø´ÙŽÙ‘Ø­ Ù…Ù† ØªÙØ¶ÙŠÙ„Ø§Øª Ø§Ù„Ù„ØºØ©/Ø§Ù„ÙØ¦Ø©) ÙŠÙ…Ø±Ù‘ Ø¹Ù„Ù‰ [isVisible]ØŒ ÙÙ„Ø§ ÙŠØ¹ÙˆØ¯ Ù…Ø­Ø±Ùƒ
 * Ø£Ø®ÙØ§Ù‡ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… Ø¥Ù„Ù‰ Ø§Ù„Ø§ÙƒØªØ´Ø§Ù. Ø§Ù„Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„ÙØ§Ø±ØºØ© ØªØ¹ÙˆØ¯ `null` (Ø¨Ù„Ø§ ØªÙ‚ÙŠÙŠØ¯)
 * Ø­Ù…Ø§ÙŠØ©Ù‹ Ù…Ù† Ø¥ÙØ±Ø§Øº Ù‚ÙˆØ§Ø¦Ù… Ø§Ù„Ø£ØµÙˆØ§Øª Ø¥Ø°Ø§ ÙƒØ§Ù† Ø§Ù„ÙÙ„ØªØ± Ù…ÙØ¹Ù‘Ù„Ø§Ù‹ Ø¨Ù„Ø§ Ø§Ø®ØªÙŠØ§Ø±.
 */
internal fun activeEnginesOrNull(
    allowed: Iterable<String>,
    candidates: Iterable<String?>,
    isVisible: (String) -> Boolean
): Set<String>? {
    val set = mutableSetOf<String>()
    allowed.forEach { engine ->
        if (engine.isNotBlank() && isVisible(engine)) set.add(engine)
    }
    candidates.forEach { engine ->
        if (!engine.isNullOrBlank() && isVisible(engine)) set.add(engine)
    }
    return set.ifEmpty { null }
}
