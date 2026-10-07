package com.nuance.android.vocalizer;

import android.content.Context;
import android.util.Log;
import com.nuance.android.vocalizer.internal.VocalizerResourceInfo;
import com.nuance.android.vocalizer.internal.VocalizerStatusInfo;
import java.io.File;

/**
 * المحرك الأصلي لـ Nuance Vocalizer Expressive 2 المدمج محلياً.
 */
public class VocalizerEngine {
    private static final String TAG = "VocalizerEngine";

    public static final int DEFAULT_RATE = 100;
    public static final int DEFAULT_PITCH = 100;
    public static final int DEFAULT_VOLUME = 80;
    public static final int DEFAULT_READMODE = 1;
    public static final int DEFAULT_TEXTMODE = 1;
    public static final int DEFAULT_TEXTFORMAT = 0;
    public static final int DEFAULT_WAIT_FACTOR = 1;

    static {
        try {
            System.loadLibrary("NuanceVocalizer");
            Log.i(TAG, "libNuanceVocalizer.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libNuanceVocalizer.so: " +
                e.getMessage());
        }
    }

    private final Context mContext;
    private boolean mIsInitialized = false;
    private VocalizerVoice mCurrentVoice = null;
    private VocalizerAudioOutputListener mAudioOutputListener = null;
    private VocalizerEngineListener mEngineListener = null;
    private VocalizerSpeechMarkListener mSpeechMarkListener = null;
    private VocalizerAudioSettings mAudioSettings =
        new VocalizerAudioSettings(22050, VocalizerAudioSettings.MONO);

    private String mDataPath = null;

    public VocalizerEngine(Context context) {
        this.mContext = context;
        try {
            createNativeEngine();
        } catch (Throwable t) {
            Log.e(TAG, "Error in createNativeEngine: " + t.getMessage());
        }
    }

    public synchronized boolean initialize(String dataDirectory) {
        this.mDataPath = dataDirectory;
        try {
            File dir = new File(dataDirectory);
            if (!dir.exists()) {
                Log.w(TAG, "Vocalizer data directory does not exist: " +
                    dataDirectory);
            }
            mIsInitialized = initializeNative(dataDirectory);
            Log.i(TAG, "initializeNative result: " + mIsInitialized);
            return mIsInitialized;
        } catch (Throwable t) {
            Log.e(TAG, "Exception during initializeNative: " + t.getMessage());
            return false;
        }
    }

    public boolean isInitialized() {
        return mIsInitialized;
    }

    public VocalizerVoice[] getVoiceList() {
        if (!mIsInitialized) return new VocalizerVoice[0];
        try {
            VocalizerVoice[] list = getAvailableVoiceListNative();
            return list != null ? list : new VocalizerVoice[0];
        } catch (Throwable t) {
            Log.e(TAG, "Error getAvailableVoiceListNative: " + t.getMessage());
            return new VocalizerVoice[0];
        }
    }

    public synchronized boolean loadVoice(VocalizerVoice voice) {
        if (!mIsInitialized || voice == null) return false;
        try {
            boolean success = loadVoiceNative(voice);
            if (success) {
                mCurrentVoice = voice;
                mAudioSettings = new VocalizerAudioSettings(
                    voice.getSampleRate(),
                    VocalizerAudioSettings.MONO
                );
            }
            return success;
        } catch (Throwable t) {
            Log.e(TAG, "Error loadVoiceNative: " + t.getMessage());
            return false;
        }
    }

    public VocalizerVoice getCurrentVoice() {
        return mCurrentVoice;
    }

    public void setAudioOutputListener(
        VocalizerAudioOutputListener listener,
        boolean bypassPlayback
    ) {
        this.mAudioOutputListener = listener;
    }

    public void setListener(VocalizerEngineListener listener) {
        this.mEngineListener = listener;
    }

    public void setSpeechMarkListener(VocalizerSpeechMarkListener listener) {
        this.mSpeechMarkListener = listener;
    }

    public synchronized void speak(String text) {
        if (!mIsInitialized || text == null || text.isEmpty()) return;
        try {
            speakNative(text);
        } catch (Throwable t) {
            Log.e(TAG, "Error in speakNative: " + t.getMessage());
        }
    }

    public synchronized void stop() {
        if (!mIsInitialized) return;
        try {
            stopSpeechNative();
        } catch (Throwable t) {
            Log.e(TAG, "Error in stopSpeechNative: " + t.getMessage());
        }
    }

    public void setSpeechRate(int rate) {
        if (mIsInitialized) setRate(rate);
    }

    public void setSpeechPitch(int pitch) {
        if (mIsInitialized) setPitch(pitch);
    }

    public void setSpeechVolume(int volume) {
        if (mIsInitialized) setVolume(volume);
    }

    public synchronized void release() {
        if (mIsInitialized) {
            try {
                releaseNative();
                destroyNativeEngine();
            } catch (Throwable t) {
                Log.e(TAG, "Error releasing Vocalizer engine: " +
                    t.getMessage());
            }
            mIsInitialized = false;
        }
    }

    /**
     * استدعاء رد اتصال من C++ عند توفر عينات PCM الصوتية.
     */
    private boolean audioSamplesReceived(short[] audioBuffer) {
        VocalizerAudioOutputListener listener = mAudioOutputListener;
        if (listener != null && audioBuffer != null) {
            listener.onAudioData(mAudioSettings, audioBuffer);
            return true;
        }
        return false;
    }

    /**
     * استدعاء رد اتصال من C++ عند توفر علامات النطق.
     */
    private void speechMarksReceived(VocalizerSpeechMark[] marks) {
        VocalizerSpeechMarkListener listener = mSpeechMarkListener;
        if (listener != null && marks != null) {
            for (VocalizerSpeechMark mark : marks) {
                if (mark != null) listener.onSpeechMarkReceived(mark);
            }
        }
    }

    // ================= Native Methods =================

    private native void createNativeEngine();
    private native void destroyNativeEngine();
    private native boolean initializeNative(String path);
    private native boolean loadVoiceNative(VocalizerVoice voice);
    private native VocalizerVoice[] getAvailableVoiceListNative();
    private native VocalizerVersion getEngineVersion();
    private native void setPitch(int pitch);
    private native int getPitch();
    private native void setRate(int rate);
    private native int getRate();
    private native void setVolume(int volume);
    private native int getVolume();
    private native void setReadMode(int readMode);
    private native int getReadMode();
    private native void setTextMode(int textMode);
    private native int getTextMode();
    private native void setTextFormat(int textFormat);
    private native int getTextFormat();
    private native void setWaitFactor(int waitFactor);
    private native int getWaitFactor();
    private native boolean setAudioBufferSize(int size);
    private native void setEnableMarks(boolean enable);
    private native VocalizerResourceInfo nativeLoadResource(
        byte[] data,
        String mime
    );
    private native void nativeUnloadResource(VocalizerResourceInfo info);
    private native void speakNative(String text);
    private native void stopSpeechNative();
    private native void releaseNative();
    private native VocalizerStatusInfo getStatInfo();
}
