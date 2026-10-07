package com.nuance.android.vocalizer;

import java.io.Serializable;

/**
 * يمثل صوتاً معرفاً داخل محرك Vocalizer.
 */
public class VocalizerVoice implements Serializable {
    private static final long serialVersionUID = 1L;

    private int mLangId;
    private String mLanguage;
    private String mLanguageTLW;
    private String mLanguageVersion;
    private int mSampleRate;
    private String mVoiceAge;
    private String mVoiceName;
    private String mVoiceOperatingPoint;
    private String mVoiceType;
    private String mVoiceVersion;

    public VocalizerVoice() {
        this.mLangId = 0;
        this.mLanguage = "";
        this.mLanguageTLW = "";
        this.mLanguageVersion = "";
        this.mSampleRate = 22050;
        this.mVoiceAge = "";
        this.mVoiceName = "";
        this.mVoiceOperatingPoint = "";
        this.mVoiceType = "";
        this.mVoiceVersion = "";
    }

    public int getLangId() {
        return mLangId;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public String getLanguageTLW() {
        return mLanguageTLW;
    }

    public String getLanguageVersion() {
        return mLanguageVersion;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public String getVoiceAge() {
        return mVoiceAge;
    }

    public String getVoiceName() {
        return mVoiceName;
    }

    public String getVoiceOperatingPoint() {
        return mVoiceOperatingPoint;
    }

    public String getVoiceType() {
        return mVoiceType;
    }

    public String getVoiceVersion() {
        return mVoiceVersion;
    }

    public boolean equals(VocalizerVoice other) {
        if (other == null) return false;
        if (this == other) return true;
        return (mVoiceName != null &&
            mVoiceName.equals(other.mVoiceName)) &&
            (mLanguage != null &&
            mLanguage.equals(other.mLanguage));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof VocalizerVoice) {
            return equals((VocalizerVoice) obj);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (mVoiceName != null ? mVoiceName.hashCode() : 0) ^
            (mLanguage != null ? mLanguage.hashCode() : 0);
    }

    @Override
    public String toString() {
        return mVoiceName + " (" + mLanguage + ", " + mSampleRate + "Hz)";
    }
}
