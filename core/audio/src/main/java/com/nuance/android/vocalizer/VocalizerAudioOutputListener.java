package com.nuance.android.vocalizer;

/**
 * مستمع استقبال بيانات الصوت PCM الخام الصادرة من محرك Vocalizer.
 */
public interface VocalizerAudioOutputListener {
    void onAudioData(VocalizerAudioSettings settings, short[] audioData);
}
