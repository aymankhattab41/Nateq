package com.nuance.android.vocalizer;

/**
 * علامة نطق في محرك Vocalizer.
 */
public class VocalizerSpeechMark {
    public int mMarkType;
    public int mSrcPos;
    public int mSrcTextLen;
    public int mDestPos;
    public int mDestLen;
    public int mPhonemeId;
    public int mMarkId;
    public int mJawOpen;
    public int mTeethUpVisible;
    public int mTeethLoVisible;
    public int mMouthHeight;
    public int mMouthWidth;
    public int mMouthUpturn;
    public int mLipTension;
    public int mTonguePos;

    /**
     * صوت الحرف التالي/السابق (Next/Previous phoneme) بالنص.
     *
     * <p>حقل مطلوب حرفياً في عقد JNI: اسمه "mLHPhoneme" وتوقيعه
     * "Ljava/lang/String;" مأخوذان من سجل libNuanceVocalizer.so.
     * غيابه يجعل GetFieldID يعيد null.
     */
    public String mLHPhoneme;

    public short[] mAudioData;

    public VocalizerSpeechMark() {
    }

    public int getType() {
        return mMarkType;
    }

    public int getSrcPos() {
        return mSrcPos;
    }

    public int getSrcTextLen() {
        return mSrcTextLen;
    }

    public int getDestPos() {
        return mDestPos;
    }

    public int getDestLen() {
        return mDestLen;
    }

    public void setAudioData(short[] audioData) {
        this.mAudioData = audioData;
    }
}
