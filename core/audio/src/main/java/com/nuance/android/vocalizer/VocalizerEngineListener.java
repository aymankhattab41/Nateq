package com.nuance.android.vocalizer;

/**
 * مستمع أحداث حالة محرك Vocalizer.
 */
public interface VocalizerEngineListener {
    void onReload();
    void onSpeakElementCompleted(String utteranceId);
    void onSpeakElementStarted(String utteranceId);
    void onStateChanged(int state);
    void onVoiceListChanged();
}
