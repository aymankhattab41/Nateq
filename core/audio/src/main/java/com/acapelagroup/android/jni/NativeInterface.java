package com.acapelagroup.android.jni;

/**
 * واجهة JNI لمكتبة Acapela NativeInterface.
 */
public class NativeInterface {
    public static native String nGetPhonetic(String text);
    public static native int nSetInfo(String key, String value);
}
