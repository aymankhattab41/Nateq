package com.acapelagroup.android.tts;

import androidx.annotation.Keep;

/**
 * JNI Bridge to Acapela TTS native library (libacattsandroid.so).
 * Class name and package must exactly match the JNI symbols exported
 * by the native library.
 */
@Keep
public class acattsandroid {

    public interface AudioListener {
        void onAudioAvailable(short[] samples, int length);
        long onSpeechEvent(
            long eventId,
            long p1,
            long p2,
            long p3,
            long p4
        );
    }

    private AudioListener listener;

    public acattsandroid() {}

    public acattsandroid(AudioListener listener) {
        this.listener = listener;
    }

    public void setAudioListener(AudioListener listener) {
        this.listener = listener;
    }

    /**
     * Native callback invoked by libacattsandroid.so for PCM audio samples.
     * Signature: ([SJ)V
     */
    @Keep
    public void samplesCallback(short[] samples, long len) {
        AudioListener l = this.listener;
        if (l != null && samples != null) {
            l.onAudioAvailable(samples, (int) len);
        }
    }

    /**
     * Native callback invoked by libacattsandroid.so for speech events.
     * Signature: (JJJJJ)J
     */
    @Keep
    public long eventsCallback(
        long eventId,
        long p1,
        long p2,
        long p3,
        long p4
    ) {
        AudioListener l = this.listener;
        if (l != null) {
            return l.onSpeechEvent(eventId, p1, p2, p3, p4);
        }
        return 0L;
    }

    // Native JNI functions:
    public native int nInitCallbacks();
    public native int nSetLicense(String license);
    public native void nSetLog(boolean enable);
    public native int nGetVoiceList(String[] paths);
    public native String nGetVoiceInfo(String path, String key);
    public native int nLoadVoice(String voicePathOrName);
    public native int nSetShape(float shape);
    public native int nGetShape();
    public native int nSetSpeechRate(float rate);
    public native int nGetSpeechRate();
    public native int nSetTTSSetting(int setting, int value);
    public native String nGetLanguage();
    public native int nSpeak(String text);
    public native int nSpeakToFile(String text, String path);
    public native int nStop();
    public native int nPause();
    public native int nResume();
    public native int nIsSpeaking();
    public native int nIsPaused();
    public native int nShutdown();
    public native String nGetVersion();
    public native int nGetLastError();
}
