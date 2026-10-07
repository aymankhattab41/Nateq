package com.acapelagroup.android.tts;

import android.speech.tts.SynthesisCallback;
import android.util.Log;

/**
 * محرك Acapela TTS الأصلي المدمج محلياً عبر JNI.
 */
public class AcattsandroidService {
    private static final String TAG = "AcattsandroidService";

    static {
        try {
            System.loadLibrary("ttsacattsandroidextended");
            Log.i(TAG, "libttsacattsandroidextended.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libttsacattsandroidextended.so: " +
                e.getMessage());
        }
    }

    public native int nInit(String path);
    public native int nShutdown();
    public native int nIsLanguageAvailable(
        String lang,
        String country,
        String variant
    );
    public native int nloadLanguage(
        String lang,
        String country,
        String variant
    );
    public native int nSetLanguage(
        String lang,
        String country,
        String variant
    );
    public native int nSetCurrentVoice(String voiceName);
    public native int nSetPreferedVoice(
        String lang,
        String country,
        String voiceName
    );
    public native String nGetClosestVoice(
        String lang,
        String country,
        String variant
    );
    public native String[] nGetLanguage();
    public native int nSetProperty(String key, String value);
    public native int nSetVoiceMode(String mode);
    public native int nSpeak(String text, SynthesisCallback callback);
    public native int nStop();
    public native int nStopSync();
    public native int nUtf8Mode(String p1, String p2);
    public native int nRefrehEnumeration();
    public native String nGetPhonetic(String text);
}
