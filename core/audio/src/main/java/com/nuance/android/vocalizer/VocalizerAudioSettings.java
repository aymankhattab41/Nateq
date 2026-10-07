package com.nuance.android.vocalizer;

/**
 * إعدادات الصوت ومعدل العينات وقنوات الستيريو لمحرك Vocalizer.
 */
public class VocalizerAudioSettings {
    public static final int MONO = 1;
    public static final int STEREO = 2;

    public int mRate;
    public int mChannels;
    public int mAudioBufferSize;

    public VocalizerAudioSettings() {
        this.mRate = 22050;
        this.mChannels = MONO;
        this.mAudioBufferSize = 4096;
    }

    public VocalizerAudioSettings(int rate, int channels) {
        this.mRate = rate;
        this.mChannels = channels;
        this.mAudioBufferSize = 4096;
    }

    public boolean isStereo() {
        return mChannels == STEREO;
    }

    public int convertToAudioStreamChannel() {
        return mChannels == STEREO ? 12 : 4;
    }

    @Override
    public String toString() {
        return "VocalizerAudioSettings[rate=" + mRate +
            ", channels=" + mChannels + "]";
    }
}
