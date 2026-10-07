package com.nuance.android.vocalizer.internal;

/**
 * معلومات ملف الأصول في محرك Vocalizer.
 */
public class VocalizerFileInfo {
    public int descriptor;
    public long startOffset;
    public long length;

    public VocalizerFileInfo() {
        this.descriptor = -1;
        this.startOffset = 0L;
        this.length = 0L;
    }
}
