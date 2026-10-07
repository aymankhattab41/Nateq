package com.nuance.android.vocalizer.internal;

/**
 * حالة ومؤشرات موارد محرك Vocalizer.
 */
public class VocalizerStatusInfo {
    public int mAssetFileHandles;
    public int mHeapBlocks;
    public int mSystemFileHandles;

    public VocalizerStatusInfo() {
        this.mAssetFileHandles = 0;
        this.mHeapBlocks = 0;
        this.mSystemFileHandles = 0;
    }

    public boolean hasLeaks() {
        return mAssetFileHandles > 0 ||
            mHeapBlocks > 0 ||
            mSystemFileHandles > 0;
    }
}
