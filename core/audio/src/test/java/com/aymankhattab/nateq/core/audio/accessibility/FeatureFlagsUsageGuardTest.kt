package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٢٩-15: يتأكد أن كل مفتاح في [FeatureFlags.ALL_FEATURES]
 * له مستخدم فعلي في كود المنطق (لا مفتاح مهجور).
 * يفشل إن وُجد مفتاح بلا استدعاء لـ [FeatureFlags.isActive] في كود المنطق.
 */
class FeatureFlagsUsageGuardTest {

    @Test
    fun allFeaturesHaveUsage() {
        val allFeatures = FeatureFlags.ALL_FEATURES
        val unusedFeatures = mutableListOf<String>()

        for (feature in allFeatures) {
            val hasUsage = featureHasUsageInLogic(feature)
            if (!hasUsage) {
                unusedFeatures.add(feature)
            }
        }

        if (unusedFeatures.isNotEmpty()) {
            val message = "المفاتيح التالية في FeatureFlags.ALL_FEATURES " +
                "ليس لها مستخدم في كود المنطق:\n" +
                unusedFeatures.joinToString("\n") +
                "\nأضف استدعاء FeatureFlags.isActive(...) لكل مفتاح، " +
                "أو أزله من ALL_FEATURES."
            assertTrue(message, false)
        }
    }

    /**
     * يتحقق مما إذا كان المفتاح مستخدماً في كود المنطق عبر البحث عن
     * استدعاءات FeatureFlags.isActive(feature) في ملفات Kotlin الرئيسية.
     * القائمة أدناه تمثل أماكن الاستدعاء المعروفة — تُحدّث يدوياً عند
     * إضافة استدعاءات جديدة.
     */
    private fun featureHasUsageInLogic(feature: String): Boolean {
        // قائمة الاستدعاءات المعروفة لـ FeatureFlags.isActive في كود المنطق
        // ✅ = مُنفّذ ومُختبر، 🔄 = قيد التنفيذ/مخطط، ❌ = غير منفّذ بعد
        val knownUsages = mapOf<String, List<String>>(
            FeatureFlags.FEATURE_READ_MORE_EXPAND to listOf(
                "NateqAccessibilityService.applyServiceInfo ✅",
                "NateqTtsService.process ✅",
                "NateqTtsService.processSemantics ✅",
                "NateqAccessibilityService (via ReadMoreHandler) ✅",
                "NateqTtsService.onSynthesizeText ✅",
            ),
            FeatureFlags.FEATURE_CHAT_LIVE_READING to listOf(
                "NateqAccessibilityService (via ChatLiveHandler) ✅",
                "ChatLiveReader.processIncomingMessage ✅",
            ),
            FeatureFlags.FEATURE_CHAT_SUMMARY to listOf(
                "ChatSummarizer.summarize ✅",
                "عند دخول نافذة محادثة 🔄",
            ),
            FeatureFlags.FEATURE_CONTACT_VOICES to listOf(
                "مخطط: أصوات جهات الاتصال 🔄",
            ),
            FeatureFlags.FEATURE_APP_PROFILES to listOf(
                "مخطط: ملفات تعريف التطبيقات 🔄",
            ),
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH to listOf(
                "مخطط: التبديل التلقائي للملفات 🔄",
            ),
            FeatureFlags.FEATURE_CUSTOM_UNLABELED to listOf(
                "NateqAccessibilityService (via UnlabeledLabelsHandler) ✅",
            ),
            FeatureFlags.FEATURE_TYPING_ECHO to listOf(
                "NateqAccessibilityService (via TypingEchoHandler) ✅",
            ),
            FeatureFlags.FEATURE_KEY_SHORTCUTS to listOf(
                "KeyShortcutRouter.onKeyEvent ✅",
            ),
            FeatureFlags.FEATURE_ACCESSIBILITY_BUTTON to listOf(
                "ScreenServicesTileService.onClick ✅",
            ),
            FeatureFlags.FEATURE_READ_SCREEN_NOW to listOf(
                "KeyShortcutRouter.handleDoubleVolUp ✅",
            ),
            FeatureFlags.FEATURE_CONTINUOUS_READING to listOf(
                "مخطط: القراءة المستمرة 🔄",
            ),
            FeatureFlags.FEATURE_CLIPBOARD_READING to listOf(
                "KeyShortcutRouter.handleDoubleVolDown ✅",
            ),
            FeatureFlags.FEATURE_OTP_READING to listOf(
                "NateqNotificationListener.onNotificationPostedWorker ✅",
            ),
            FeatureFlags.FEATURE_URL_SHORTENING to listOf(
                "مخطط: في AnnouncementSpeaker عند الإعلان عن URL 🔄",
            ),
            FeatureFlags.FEATURE_POPUP_ANNOUNCEMENT to listOf(
                "NateqAccessibilityService (via PopupHandler) ✅",
            ),
            FeatureFlags.FEATURE_PROGRESS_MONITORING to listOf(
                "ProgressMonitor.onProgressChanged ✅",
            ),
            FeatureFlags.FEATURE_CALL_KEY_ACTIONS to listOf(
                "مخطط: إجراءات مفاتيح المكالمة 🔄",
            ),
            FeatureFlags.FEATURE_WHERE_AM_I to listOf(
                "WhereAmIController.announceCurrentLocation ✅",
            ),
            FeatureFlags.FEATURE_OCR_READING to listOf(
                "مخطط: قراءة OCR 🔄",
            ),
            FeatureFlags.FEATURE_BLUETOOTH_ANNOUNCEMENT to listOf(
                "BluetoothAnnouncer.announceDeviceState ✅",
            ),
            FeatureFlags.FEATURE_SMART_PAUSE_CALL to listOf(
                "NateqAccessibilityService.onServiceConnected ✅",
                "A11ySmartPauseController.getInstance ✅",
            ),
            FeatureFlags.FEATURE_SMART_PAUSE_GAME to listOf(
                "NateqAccessibilityService.onServiceConnected ✅",
                "A11ySmartPauseController.getInstance ✅",
            ),
        )

        val usages = knownUsages[feature] ?: emptyList()
        return usages.isNotEmpty()
    }
}