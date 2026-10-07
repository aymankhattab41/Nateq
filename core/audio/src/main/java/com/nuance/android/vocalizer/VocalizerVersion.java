package com.nuance.android.vocalizer;

/**
 * إصدار محرك Vocalizer.
 */
public class VocalizerVersion {
    public int mMajor;
    public int mMinor;
    public int mBuild;
    public int mBuildYear;
    public int mBuildMonth;
    public int mBuildDay;
    public String mBuildInfo;

    public VocalizerVersion() {
        this.mMajor = 0;
        this.mMinor = 0;
        this.mBuild = 0;
        this.mBuildYear = 0;
        this.mBuildMonth = 0;
        this.mBuildDay = 0;
        this.mBuildInfo = "";
    }

    @Override
    public String toString() {
        return mMajor + "." + mMinor + "." + mBuild +
            " (" + mBuildYear + "-" + mBuildMonth + "-" + mBuildDay +
            " " + mBuildInfo + ")";
    }
}
