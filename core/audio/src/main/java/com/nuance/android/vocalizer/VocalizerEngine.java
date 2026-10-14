package com.nuance.android.vocalizer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.aymankhattab.nateq.core.audio.enginehost.HostLog;
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol;
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerDataProvider;
import com.nuance.android.vocalizer.internal.VocalizerFileInfo;
import com.nuance.android.vocalizer.internal.VocalizerResourceInfo;
import com.nuance.android.vocalizer.internal.VocalizerStatusInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * المحرك الأصلي لـ Nuance Vocalizer Expressive 2 المدمج محلياً.
 */
public class VocalizerEngine {
    private static final String TAG = "VocalizerEngine";

    /**
     * معرّف المحرك في سجل المضيف (:engine_host) الموحّد.
     */
    private static final String ENGINE_ID =
        HostProtocol.ENGINE_VOCALIZER;

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

    private Throwable mCreateError = null;
    private Throwable mInitError = null;
    private String mLastError = null;

    /**
     * ترويسات خط المعالجة المجمّعة من ملفات .hdr، تُمرَّر
     * حرفياً إلى initializeNative (وليس مسار مجلد).
     */
    private String mPipelineHeaders = null;

    /**
     * عنوان مؤشر المحرك الأصلي (CVocalizerEngine*) الذي يُنشئه
     * createNativeEngine ويخزّنه عبر SetLongField.
     *
     * <p>الحقل مطلوب حرفياً في عقد JNI: يُقرأ اسمه وتوقيعه
     * "mNativeEnginePtr" و "J" من سجل libNuanceVocalizer.so
     * (الدالة Java_..._createNativeEngine تُنادي SetLongField عند
     * 0x370 بمهلة الـ fieldID المخزّنة عالمياً). غيابه يجعل
     * GetFieldID يعيد null فترمي ART: "JNI DETECTED ERROR:
     * fid == null in call to SetLongField".
     *
     * <p>ممنوع تحويله إلى static أو final أو تغيير نوعه، وممنوع
     * إعادة تسميته (قواعد R8 في consumer-rules.pro).
     */
    private long mNativeEnginePtr = 0L;

    public VocalizerEngine(Context context) {
        this.mContext = context;
        try {
            createNativeEngine();
        } catch (Throwable t) {
            this.mCreateError = t;
            this.mLastError = t.getClass().getSimpleName() + ": " +
                t.getMessage();
            Log.e(TAG, "Error in createNativeEngine: " + t.getMessage(), t);
        }
    }

    public Throwable getCreateError() {
        return mCreateError;
    }

    public boolean isNativeCreated() {
        return mNativeEnginePtr != 0L;
    }

    public long getNativeEnginePtr() {
        return mNativeEnginePtr;
    }

    public Throwable getInitError() {
        return mInitError;
    }

    public String getLastError() {
        return mLastError;
    }

    /**
     * يهيّئ المحرك بترويسات خط المعالجة المجمّعة من ملفات .hdr.
     *
     * <p>عقد المكتبة الأصلية: initializeNative(String) ينتظر
     * <b>نصّ</b> الترويسات (تسلسل محتوى كل ملفات .hdr) لا مساراً.
     * تمرير مسار كان يجعل المحرك يحاول تحليله كـ XML فيفشل بـ
     * 0x80000004 ("The engine could not be initialized").
     *
     * @param pipelineHeaders نصّ الترويسات المجمّع.
     */
    public synchronized boolean initialize(String pipelineHeaders) {
        this.mPipelineHeaders = pipelineHeaders == null
            ? "" : pipelineHeaders;
        this.mInitError = null;
        this.mLastError = null;
        try {
            mIsInitialized =
                initializeNative(this.mPipelineHeaders);
            Log.i(TAG, "initializeNative result: " + mIsInitialized);
            if (!mIsInitialized && mLastError == null) {
                mLastError = "initializeNative returned false";
            }
            return mIsInitialized;
        } catch (Throwable t) {
            this.mInitError = t;
            this.mLastError = t.getClass().getSimpleName() + ": " +
                t.getMessage();
            Log.e(TAG, "Exception during initializeNative: " +
                t.getMessage(), t);
            return false;
        }
    }

    /**
     * ترويسات خط المعالجة الممرّرة للتهيئة.
     */
    public String getPipelineHeaders() {
        return mPipelineHeaders;
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
        closeAllOpenFiles();
        VocalizerDataProvider provider = mDataProvider;
        if (provider != null) {
            provider.closeAll();
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

    // ================= عقد JNI: فتح/غلق ملفات الأصول =================

    /**
     * وصفات ملفات أصول مفتوحة نحتفظ بها حتى يستدعي الكود الأصلي
     * closeAssetFile. المفتاح هو رقم الوصف (fd).
     */
    private final Map<Integer, ParcelFileDescriptor> mOpenFiles =
        new HashMap<>();

    /**
     * تسطيح اسم أصل إلى اسم ملف .xmf (عقد فتح الأصول).
     * مفوَّض إلى {@link VocalizerDataProvider#flattenAssetName}.
     */
    public static String flattenAssetName(String name) {
        return VocalizerDataProvider.flattenAssetName(name);
    }

    /**
     * مزوّد ملفات الصوت الخارجية .dat (يُحقن من جلسة المضيف).
     * إن كان null تُتخطّى ملفات .dat وتُفتح الأصول فقط.
     */
    private VocalizerDataProvider mDataProvider = null;

    /**
     * يضبط مزوّد ملفات .dat الخارجية قبل التهيئة.
     */
    public synchronized void setDataProvider(VocalizerDataProvider p) {
        this.mDataProvider = p;
    }

    /**
     * يفتح ملف بيانات أو أصل ويعيد معلوماته.
     *
     * <p>الترتيب مطابقٌ لمحرك Nuance الأصلي:
     * <ol>
     *   <li>ملفات الصوت الخارجية .dat عبر {@link VocalizerDataProvider}
     *       (المفتاح: الاسم مسطّحاً + .dat)؛</li>
     *   <li>أصول أساسية .xmf داخل assets/vocalizer/ بعد تسطيح
     *       الاسم، غير المضغوطة تُفتح بـ openFd والمضغوطة تُنسخ
     *       إلى cache.</li>
     * </ol>
     *
     * <p>عقد JNI: GetMethodID(VocalizerEngine, "openAssetFile",
     * "(Ljava/lang/String;)Lcom/nuance/android/vocalizer/internal/
     * VocalizerFileInfo;") يُنادى على الكائن نفسه.
     *
     * @return null إذا تعذّر الفتح.
     */
    public synchronized VocalizerFileInfo openAssetFile(String path) {
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "openAssetFile: path is empty");
            return null;
        }

        VocalizerDataProvider provider = mDataProvider;
        VocalizerFileInfo external = provider == null
            ? null : provider.openDataFile(path);
        if (external != null) {
            HostLog.w(ENGINE_ID,
                "openAssetFile path='" + path +
                "' source=external fd=" + external.descriptor +
                " off=" + external.startOffset +
                " len=" + external.length);
            return external;
        }

        String flat = VocalizerDataProvider.flattenAssetName(path);
        String assetPath = "vocalizer/" + flat;
        ParcelFileDescriptor pfd;
        long start = 0L;
        long length = 0L;
        try {
            AssetRef ref = openFromAssets(assetPath);
            if (ref != null) {
                pfd = ref.pfd;
                start = ref.startOffset;
                length = ref.length;
            } else {
                File cached = copyAssetToCache(assetPath);
                if (cached == null) {
                    HostLog.w(ENGINE_ID,
                        "openAssetFile [MISSING] path='" + path +
                        "' asset='" + assetPath +
                        "' flat='" + flat + "' source=null");
                    return null;
                }
                pfd = ParcelFileDescriptor.open(
                    cached, ParcelFileDescriptor.MODE_READ_ONLY);
                length = cached.length();
            }
        } catch (IOException e) {
            HostLog.w(ENGINE_ID,
                "openAssetFile [FAILED] path='" + path +
                "' asset='" + assetPath + "' flat='" + flat + "'",
                e);
            return null;
        }
        VocalizerFileInfo info = new VocalizerFileInfo();
        info.descriptor = pfd.getFd();
        info.startOffset = start;
        info.length = length;
        mOpenFiles.put(info.descriptor, pfd);
        HostLog.w(ENGINE_ID,
            "openAssetFile path='" + path + "' asset='" + assetPath +
            "' flat='" + flat + "' source=asset fd=" + info.descriptor +
            " off=" + start + " len=" + length);
        return info;
    }

    /**
     * يغلق وصف ملف فتحته openAssetFile.
     *
     * <p>يوّجه الإغلاق إلى المالك الصحيح: ملفات .dat الخارجية
     * يديرها {@link VocalizerDataProvider}، وملفات الأصول المحلية
     * يديرها هذا المحرك. لا نغلق وصفاً لا نملكه، لأن ذلك قد يقطع
     * مقبساً يخص النظام.
     *
     * @param descriptor رقم الوصف الذي أعطاه الكود الأصلي.
     */
    public synchronized void closeAssetFile(int descriptor) {
        VocalizerDataProvider provider = mDataProvider;
        if (provider != null && provider.hasOpenFile(descriptor)) {
            provider.closeAssetFile(descriptor);
            HostLog.w(ENGINE_ID,
                "closeAssetFile #" + descriptor + " source=external");
            return;
        }
        ParcelFileDescriptor pfd = mOpenFiles.remove(descriptor);
        if (pfd == null) {
            Log.w(TAG, "closeAssetFile: unknown fd " + descriptor);
            return;
        }
        try {
            pfd.close();
            HostLog.w(ENGINE_ID,
                "closeAssetFile #" + descriptor + " source=asset");
        } catch (IOException e) {
            Log.w(TAG, "closeAssetFile: " + descriptor + " - " + e);
        }
    }

    /**
     * نتيجة فتح أصق يفتحه `openAssetFile`.
     */
    private static final class AssetRef {
        final ParcelFileDescriptor pfd;
        final long startOffset;
        final long length;

        AssetRef(ParcelFileDescriptor pfd, long startOffset, long length) {
            this.pfd = pfd;
            this.startOffset = startOffset;
            this.length = length;
        }
    }

    /**
     * يفتح من الأصول غير المضغوطة فقط (openFd يرمي استثناءً
     * للملفات المضغوطة). يعيد null إن لم ينجح.
     */
    private AssetRef openFromAssets(String path) {
        try {
            AssetFileDescriptor afd =
                mContext.getAssets().openFd(path);
            ParcelFileDescriptor pfd =
                ParcelFileDescriptor.dup(afd.getFileDescriptor());
            AssetRef ref = new AssetRef(
                pfd, afd.getStartOffset(), afd.getLength());
            afd.close();
            return ref;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * ينسخ أصلاً مضغوطاً إلى ذاكرة التخزين المؤقتة ثم يعيده.
     * يعيد null إن لم يوجد الأصل.
     */
    private File copyAssetToCache(String path) {
        File out = new File(mContext.getCacheDir(),
            "voc_" + Integer.toHexString(path.hashCode()));
        if (out.isFile() && out.length() > 0L) return out;
        try (InputStream in = mContext.getAssets().open(path);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            return out;
        } catch (IOException e) {
            Log.w(TAG, "copyAssetToCache: " + path + " - " + e);
            return null;
        }
    }

    /**
     * يغلق كل الأوصاف المفتوحة (يستدعى عند تحرير المحرك).
     */
    private synchronized void closeAllOpenFiles() {
        for (Map.Entry<Integer, ParcelFileDescriptor> e
                : mOpenFiles.entrySet()) {
            try {
                e.getValue().close();
            } catch (IOException ignored) {
                // الوصف مغلق أصلاً أو فسد — لا شيء نفعله هنا.
            }
        }
        mOpenFiles.clear();
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
