package com.nuance.android.vocalizer.internal;

/**
 * معلومات مورد محرك Vocalizer.
 */
public class VocalizerResourceInfo {
    public long mHandle;
    public long mId;
    public String mMimeType;
    public byte[] mResourceData;

    public VocalizerResourceInfo() {
        this.mHandle = 0L;
        this.mId = 0L;
        this.mMimeType = null;
        this.mResourceData = null;
    }
}
