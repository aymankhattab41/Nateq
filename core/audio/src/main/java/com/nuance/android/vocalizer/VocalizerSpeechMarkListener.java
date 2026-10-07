package com.nuance.android.vocalizer;

/**
 * مستمع علامات النطق في محرك Vocalizer.
 */
public interface VocalizerSpeechMarkListener {
    void onSpeechMarkReceived(VocalizerSpeechMark mark);
}
