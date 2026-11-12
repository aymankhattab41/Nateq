package com.acapelagroup.android.tts;

import android.speech.tts.SynthesisCallback;
import androidx.annotation.Keep;

/**
 * JNI Bridge to Acapela extended TTS native library
 * (libttsacattsandroidextended.so).
 */
@Keep
public final class AcattsandroidService {
    private final Audio audio;
    private final String registration;

    public interface Audio {
        void samples(byte[] bArr, int i);
    }

    public native int nInit(String str);
    public native int nSetCurrentVoice(String str);
    public native int nSetLanguage(
        String str,
        String str2,
        String str3
    );
    public native int nSetProperty(String str, String str2);
    public native int nSetVoiceMode(String str);
    public native int nShutdown();
    public native int nSpeak(
        String str,
        SynthesisCallback synthesisCallback
    );
    public native int nStop();
    public native int nUtf8Mode(String str, String str2);

    public AcattsandroidService(Audio audio, String str) {
        this.audio = audio;
        this.registration = str;
    }

    public String idfunction() {
        return this.registration;
    }

    @Keep
    private void samplesCallback(byte[] bArr, long j) {
        if (this.audio != null) {
            int len = (j < 0 || j > 2147483647L) ? -1 : (int) j;
            this.audio.samples(bArr, len);
        }
    }

    @Keep
    private long eventsCallback(
        long j,
        long j2,
        long j3,
        long j4
    ) {
        return 0L;
    }

    @Keep
    private void exceptionCallback() {
        if (this.audio != null) {
            this.audio.samples(null, -1);
        }
    }
}
